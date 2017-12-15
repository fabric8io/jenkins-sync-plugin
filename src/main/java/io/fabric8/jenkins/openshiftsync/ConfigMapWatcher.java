/**
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.google.common.base.Strings;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.BulkChange;
import hudson.XmlFile;
import hudson.model.AbstractItem;
import hudson.model.BuildableItem;
import hudson.model.Cause;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import hudson.util.DescribableList;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMNavigatorDescriptor;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;
import org.apache.commons.beanutils.BeanUtilsBean;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.ConfigMapKeys.CONFIG_XML;
import static io.fabric8.jenkins.openshiftsync.ConfigMapKeys.ROOT_JOB;
import static io.fabric8.jenkins.openshiftsync.ConfigMapKeys.TRIGGER_ON_CHANGE;
import static io.fabric8.jenkins.openshiftsync.ConfigMapToJobMap.getJobFromConfigMap;
import static io.fabric8.jenkins.openshiftsync.ConfigMapToJobMap.initializeConfigMapToJobMap;
import static io.fabric8.jenkins.openshiftsync.ConfigMapToJobMap.putJobWithConfigMap;
import static io.fabric8.jenkins.openshiftsync.ConfigMapToJobMap.removeJobWithConfigMap;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getFullNameParent;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getNamespace;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isJenkinsConfigMap;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobFullName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.parseResourceVersion;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.util.logging.Level.SEVERE;

/**
 * Watches {@link ConfigMap} objects to sync arbitrary jenkins jobs
 * from the <code>config.xml</code> entry
 */
public class ConfigMapWatcher implements Watcher<ConfigMap> {
  private final static Logger logger = Logger.getLogger(ConfigMapWatcher.class.getName());
  private final String namespace;
  private Watch configMapWatch;
  private ScheduledFuture relister;

  public ConfigMapWatcher(String namespace) {
    this.namespace = namespace;
  }

  public synchronized void start() {
    initializeConfigMapToJobMap();

    // lets process the initial state
    logger.info("Now handling startup ConfigMaps!!");
    // lets do this in a background thread to avoid errors like:
    //  Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support a circular dependency, but it is not an interface.
    Runnable task = new SafeTimerTask() {
      @Override
      public void doRun() {
        try {
          logger.fine("listing ConfigMaps resources");
          final ConfigMapList configMaps = getOpenShiftClient().configMaps().inNamespace(namespace).list();
          onInitialConfigMaps(configMaps);
          logger.fine("handled ConfigMaps resources");
          if (configMapWatch == null) {
            logger.info("watching the ConfigMapList");
            configMapWatch = getOpenShiftClient().configMaps().inNamespace(namespace).withResourceVersion(configMaps.getMetadata().getResourceVersion()).watch(ConfigMapWatcher.this);
          }
        } catch (Exception e) {
          logger.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
        }
      }
    };
    relister = Timer.get().scheduleAtFixedRate(task, 100,5 * 60 * 1000, TimeUnit.MILLISECONDS); // interval 5 minutes
  }

  public synchronized void stop() {
    if (relister != null && !relister.isDone()) {
      relister.cancel(true);
      relister = null;
    }
    if (configMapWatch != null) {
      configMapWatch.close();
      configMapWatch = null;
    }
  }

  @Override
  public synchronized void onClose(KubernetesClientException e) {
    if (e != null) {
      logger.warning(e.toString());

      if (e.getStatus() != null && e.getStatus().getCode() == HTTP_GONE) {
        stop();
        start();
      }
    }
  }

  private synchronized void onInitialConfigMaps(ConfigMapList configMaps) {
    List<ConfigMap> items = configMaps.getItems();
    if (items != null) {
      for (ConfigMap configMap : items) {
        try {
          upsertJob(configMap);
        } catch (Exception e) {
          logger.log(SEVERE, "Failed to update job", e);
        }
      }
    }
  }

  @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
  @Override
  public synchronized void eventReceived(Action action, ConfigMap configMap) {
    try {
      switch (action) {
        case ADDED:
          upsertJob(configMap);
          break;
        case DELETED:
          deleteJob(configMap);
          break;
        case MODIFIED:
          modifyJob(configMap);
          break;
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Caught: " + e, e);
    }
  }

  private void upsertJob(final ConfigMap configMap) throws Exception {
    if (isJenkinsConfigMap(configMap)) {
      Map<String, String> configData = configMap.getData();
      final String configXml = configData.get(CONFIG_XML);
      if (configXml != null) {

        ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
          @Override
          public Void call() throws Exception {
            String jobName = jenkinsJobName(configMap);
            String jobFullName = jenkinsJobFullName(configMap);
            boolean rootJob = OpenShiftUtils.isConfigMapFlagEnabled(configMap, ROOT_JOB);
            if (rootJob) {
              jobFullName = getName(configMap);
            }
            AbstractItem job = getJobFromConfigMap(configMap);
            Jenkins activeInstance = Jenkins.getActiveInstance();
            ItemGroup parent = activeInstance;
            if (job == null) {
              Item item = activeInstance.getItemByFullName(jobFullName);
              if (item instanceof AbstractItem) {
                job = (AbstractItem) item;
              }
            }
            boolean newJob = job == null;
            boolean updated = true;
            if (newJob) {
              if (!rootJob) {
                parent = getFullNameParent(activeInstance, jobFullName, getNamespace(configMap));
              }
            } else {
              XmlFile configFile = job.getConfigFile();
              if (configFile != null) {
                String oldConfigXml = configFile.asString();
                if (oldConfigXml != null) {
                  updated = OpenShiftUtils.configXmlUpdated(configXml, oldConfigXml);
                }
              }
            }
            if (!updated) {
              return null;
            }


            BuildConfigProjectProperty buildConfigProjectProperty = null;
            if (job != null) {
              buildConfigProjectProperty = ConfigMapToJobMap.getOrFindProperty(job);
              if (buildConfigProjectProperty != null) {
                long updatedBCResourceVersion = parseResourceVersion(configMap);
                long oldBCResourceVersion = parseResourceVersion(buildConfigProjectProperty.getResourceVersion());
                BuildConfigProjectProperty newProperty = new BuildConfigProjectProperty(configMap);
                if (updatedBCResourceVersion <= oldBCResourceVersion &&
                  newProperty.getUid().equals(buildConfigProjectProperty.getUid()) &&
                  newProperty.getNamespace().equals(buildConfigProjectProperty.getNamespace()) &&
                  newProperty.getName().equals(buildConfigProjectProperty.getName())) {
                  return null;
                }
                buildConfigProjectProperty.setUid(newProperty.getUid());
                buildConfigProjectProperty.setNamespace(newProperty.getNamespace());
                buildConfigProjectProperty.setName(newProperty.getName());
                buildConfigProjectProperty.setResourceVersion(newProperty.getResourceVersion());
                buildConfigProjectProperty.setBuildRunPolicy(newProperty.getBuildRunPolicy());
              }
            }
            if (buildConfigProjectProperty == null) {
              buildConfigProjectProperty = new BuildConfigProjectProperty(configMap);
            }
            BulkChange bk = null;
            if (job != null) {
              bk = new BulkChange(job);
            }

            InputStream jobStream = new ByteArrayInputStream(configXml.getBytes(StandardCharsets.UTF_8));

            if (newJob) {
              if (parent instanceof Folder) {
                Folder folder = (Folder) parent;
                folder.createProjectFromXML(
                        jobName,
                        jobStream
                ).save();
              } else {
                activeInstance.createProjectFromXML(
                        jobName,
                        jobStream
                ).save();
              }

              logger.info("Created job " + jobName + " from ConfigMap " + NamespaceName.create(configMap) + " with revision: " + configMap.getMetadata().getResourceVersion());
            } else {
              Source source = new StreamSource(jobStream);
              job.updateByXml(source);
              job.save();
              logger.info("Updated job " + jobName + " from ConfigMap " + NamespaceName.create(configMap) + " with revision: " + configMap.getMetadata().getResourceVersion());

            }

            if (job == null) {
              Item item = activeInstance.getItemByFullName(jobFullName);
              if (item instanceof AbstractItem) {
                job = (AbstractItem) item;
              }
            }
            if (job == null) {
              logger.warning("Could not find new job " + jobFullName);
              return null;
            }
            ConfigMapToJobMap.putBuildConfigProjectProperty(buildConfigProjectProperty, job);
            BuildConfigProjectProperty.setProperty(job, buildConfigProjectProperty);

            if (updated) {
              maybeScheduleConfigMapJob(job, configMap);
            }
            if (bk != null) {
              bk.commit();
            }
            String fullName = job.getFullName();
            if (parent instanceof Folder && job instanceof TopLevelItem) {
              // we should never need this but just in case there's an odd timing issue or something...
              TopLevelItem top = (TopLevelItem) job;
              Folder folder = (Folder) parent;
              folder.add(top, jobName);
            }
            //logger.info((newJob ? "created" : "updated" ) + " job " + fullName + " with path " + jobFullName + " from ConfigMap: " + getNamespace(configMap) + "/" + getName(configMap));
            putJobWithConfigMap(job, configMap, buildConfigProjectProperty);
            return null;
          }
        });
      }
    }
  }

  private static void maybeScheduleConfigMapJob(Item job, ConfigMap configMap) {
    if (OpenShiftUtils.isConfigMapFlagEnabled(configMap, TRIGGER_ON_CHANGE)) {
      //if () {
      if (isBuildingOrInQueue(job)) {
        return;
      }
      if (job instanceof BuildableItem) {
        BuildableItem buildableItem = (BuildableItem) job;
        Cause.RemoteCause cause = new Cause.RemoteCause("kubernetes", "Triggered by the change to the ConfigMap");
        logger.info("triggered build!");
        buildableItem.scheduleBuild(cause);
      } else {
        logger.warning("Job is not a BuildableItem for " + job.getClass().getName() + " " + job);
      }
    }
  }

  private static boolean isBuildingOrInQueue(Item item) {
    if (item instanceof Job) {
      Job job = (Job) item;
      return job.isBuilding() || job.isInQueue();
    }
    return false;
  }

  private void modifyJob(ConfigMap configMap) throws Exception {
    if (isJenkinsConfigMap(configMap)) {
      upsertJob(configMap);
      return;
    }

    // no longer a Jenkins build so lets delete it if it exists
    deleteJob(configMap);
  }

  private void deleteJob(final ConfigMap configMap) throws Exception {
    final AbstractItem job = getJobFromConfigMap(configMap);
    if (job != null) {
      ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
        @Override
        public Void call() throws Exception {
          ItemGroup parent = job.getParent();
          try {
            job.delete();
          } finally {
            removeJobWithConfigMap(configMap);
            Jenkins.getActiveInstance().rebuildDependencyGraphAsync();
          }
          if (parent instanceof Item) {
            removeConfigMapJobFromFolderPluginJob((Item) parent, configMap, job);
          }
          return null;
        }
      });
    }
  }

  private void removeConfigMapJobFromFolderPluginJob(Item parent, ConfigMap configMap, AbstractItem job) {
    if (parent instanceof OrganizationFolder) {
      OrganizationFolder organizationFolder = (OrganizationFolder) parent;
      DescribableList<SCMNavigator, SCMNavigatorDescriptor> navigators = organizationFolder.getNavigators();
      if (navigators != null) {
        for (SCMNavigator navigator : navigators) {
          if (navigator.getClass().getName().equals("org.jenkinsci.plugins.github_branch_source.GitHubSCMNavigator")) {
            // lets try get the pattern property
            BeanUtilsBean converter = new BeanUtilsBean();
            String pattern = null;
            try {
              pattern = converter.getProperty(navigator, "pattern");
            } catch (Exception e) {
              logger.warning("Could not get pattern of navigator " + navigator + " due to: " + e);
            }
            ObjectMeta metadata = configMap.getMetadata();
            if (!Strings.isNullOrEmpty(pattern) && metadata != null) {
              String name = metadata.getName();
              String newPattern = JenkinsUtils.removePattern(pattern, name);
              if (newPattern != null) {
                try {
                  converter.setProperty(navigator, "pattern", newPattern);
                } catch (Exception e) {
                  logger.warning("Could not update pattern of navigator " + navigator + " to " + pattern + " due to: " + e);
                }
              }
            }
          }
        }
      }
    } else if (parent != null) {
      ItemGroup<? extends Item> grandParent = parent.getParent();
      if (grandParent instanceof Item) {
        removeConfigMapJobFromFolderPluginJob((Item) grandParent, configMap, job);
      }
    }
  }
}
