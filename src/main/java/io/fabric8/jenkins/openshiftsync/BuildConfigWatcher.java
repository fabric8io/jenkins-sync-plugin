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
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.triggers.SafeTimerTask;
import hudson.util.DescribableList;
import hudson.util.XStream2;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigList;
import io.fabric8.openshift.api.model.BuildConfigSpec;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMNavigatorDescriptor;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;
import org.apache.commons.beanutils.BeanUtilsBean;
import org.apache.tools.ant.filters.StringInputStream;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.w3c.dom.Document;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.Annotations.DISABLE_SYNC_CREATE_ON;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.initializeBuildConfigToJobMap;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.putJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.removeJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.removeJobWithBuildConfigUid;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.mapBuildConfigToFlow;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL_LATEST_ONLY;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.maybeScheduleNext;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getAnnotation;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getFullNameParent;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getNamespace;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isJenkinsBuildConfig;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobFullName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.jenkinsJobName;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.parseResourceVersion;
import static java.net.HttpURLConnection.HTTP_GONE;
import static java.util.logging.Level.SEVERE;

/**
 * Watches {@link BuildConfig} objects in OpenShift and for WorkflowJobs we ensure there is a
 * suitable Jenkins Job object defined with the correct configuration
 */
public class BuildConfigWatcher implements Watcher<BuildConfig> {
  private final Logger logger = Logger.getLogger(getClass().getName());
  private final String namespace;
  private Watch buildConfigWatch;
  private ScheduledFuture relister;

  public BuildConfigWatcher(String namespace) {
    this.namespace = namespace;
  }

  public synchronized void start() {
    initializeBuildConfigToJobMap();

    // lets process the initial state
    logger.info("Now handling startup build configs!!");
    // lets do this in a background thread to avoid errors like:
    //  Tried proxying io.fabric8.jenkins.openshiftsync.GlobalPluginConfiguration to support a circular dependency, but it is not an interface.
    Runnable task = new SafeTimerTask() {
      @Override
      public void doRun() {
        try {
          logger.fine("listing BuildConfigs resources");
          final BuildConfigList buildConfigs = getOpenShiftClient().buildConfigs().inNamespace(namespace).list();
          removeJobsForMissingBuildConfigs(buildConfigs);
          onInitialBuildConfigs(buildConfigs);
          logger.fine("handled BuildConfigs resources");
          if (buildConfigWatch == null) {
            buildConfigWatch = getOpenShiftClient().buildConfigs().inNamespace(namespace).withResourceVersion(buildConfigs.getMetadata().getResourceVersion()).watch(BuildConfigWatcher.this);
          }
        } catch (Exception e) {
          logger.log(SEVERE, "Failed to load BuildConfigs: " + e, e);
        }
      }
    };
    relister = Timer.get().scheduleAtFixedRate(task, 100, 10 * 1000, TimeUnit.MILLISECONDS);
  }

  public synchronized void stop() {
    if (relister != null && !relister.isDone()) {
      relister.cancel(true);
      relister = null;
    }
    if (buildConfigWatch != null) {
      buildConfigWatch.close();
      buildConfigWatch = null;
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

  private synchronized void removeJobsForMissingBuildConfigs(BuildConfigList buildConfigs) {
    Set<String> names = new HashSet<>();
    List<BuildConfig> items = buildConfigs.getItems();
    if (items != null) {
      for (BuildConfig buildConfig : items) {
        String name = getName(buildConfig);
        if (name != null) {
          names.add(name);
        }
      }
    }
    List<BuildConfigProjectProperty> propertiesToDelete = new ArrayList<>();
    deleteOldJobs(Jenkins.getActiveInstance(), namespace, names, propertiesToDelete);
    for (BuildConfigProjectProperty property : propertiesToDelete) {
      try {
        removeJobWithBuildConfigUid(property.getUid());
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to remove BuildConfigProjectProperty " + property + " due to: " + e, e);
      }
    }
  }


  private void deleteOldJobs(ItemGroup itemGroup, String ns, Set<String> buildConfigNames, List<BuildConfigProjectProperty> propertiesToDelete) {
    Collection items = itemGroup.getItems();
    if (items != null) {
      for (Object object : items) {
        if (object instanceof ItemGroup) {
          deleteOldJobs((ItemGroup) object, ns, buildConfigNames, propertiesToDelete);
        }
        if (object instanceof WorkflowJob) {
          WorkflowJob job = (WorkflowJob) object;
          BuildConfigProjectProperty property = BuildConfigProjectProperty.getProperty(job);
          if (property != null) {
            String name = property.getName();
            String namespace = property.getNamespace();
            if (ns.equals(namespace) && !buildConfigNames.contains(name)) {
              propertiesToDelete.add(property);
              deleteOldJob(job, property);
            }
          }
        }
      }
    }
  }

  private void deleteOldJob(final WorkflowJob job, final BuildConfigProjectProperty property) {
    removeBuildConfigNameFromConfigMapJobs(property.getNamespace(), property.getName());
    
    if (job != null) {
      logger.info("Deleting old job " + job.getFullName() + " due to the associated BuildConfig being deleted");
      try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
        ItemGroup parent = job.getParent();
        try {
          job.delete();
        } catch (Exception e) {
          logger.log(Level.WARNING, "Failed to delete job " + job.getFullName() + " due to: " + e, e);
        } finally {
          Jenkins.getInstance().rebuildDependencyGraphAsync();
        }
        if (parent instanceof Item) {
          removeBuildConfigJobFromFolderPluginJob((Item) parent, property.getName());
        }
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed deleting job " + job.getFullName() + " due to: " + e, e);
      }
    }
  }


  private synchronized void onInitialBuildConfigs(BuildConfigList buildConfigs) {
    List<BuildConfig> items = buildConfigs.getItems();
    if (items != null) {
      for (BuildConfig buildConfig : items) {
        try {
          upsertJob(buildConfig);
        } catch (Exception e) {
          logger.log(SEVERE, "Failed to update job", e);
        }
      }
    }
  }

  @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
  @Override
  public synchronized void eventReceived(Watcher.Action action, BuildConfig buildConfig) {
    try {
      switch (action) {
        case ADDED:
          upsertJob(buildConfig);
          break;
        case DELETED:
          deleteJob(buildConfig);
          break;
        case MODIFIED:
          modifyJob(buildConfig);
          break;
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Caught: " + e, e);
    }
  }

  private void upsertJob(final BuildConfig buildConfig) throws Exception {
    if (isJenkinsBuildConfig(buildConfig)) {
      ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
        @Override
        public Void call() throws Exception {
          String jobName = jenkinsJobName(buildConfig);
          String jobFullName = jenkinsJobFullName(buildConfig);
          WorkflowJob job = getJobFromBuildConfig(buildConfig);
          Jenkins activeInstance = Jenkins.getActiveInstance();
          ItemGroup parent = activeInstance;
          if (job == null) {
            job = (WorkflowJob) activeInstance.getItemByFullName(jobFullName);
          }
          boolean newJob = job == null;
          if (newJob) {
            String disableOn = getAnnotation(buildConfig, DISABLE_SYNC_CREATE_ON);
            if (disableOn != null && disableOn.equalsIgnoreCase("jenkins")) {
              logger.fine("Not creating missing jenkins job " + jobFullName + " due to annotation: " + DISABLE_SYNC_CREATE_ON);
              return null;
            }
            parent = getFullNameParent(activeInstance, jobFullName, getNamespace(buildConfig));
            job = new WorkflowJob(parent, jobName);
          }
          BulkChange bk = new BulkChange(job);

          FlowDefinition flowFromBuildConfig = mapBuildConfigToFlow(buildConfig);
          if (flowFromBuildConfig == null) {
            return null;
          }

          job.setDefinition(flowFromBuildConfig);

          String existingBuildRunPolicy = null;

          BuildConfigProjectProperty buildConfigProjectProperty = job.getProperty(BuildConfigProjectProperty.class);
          if (buildConfigProjectProperty != null) {
            existingBuildRunPolicy = buildConfigProjectProperty.getBuildRunPolicy();
            long updatedBCResourceVersion = parseResourceVersion(buildConfig);
            long oldBCResourceVersion = parseResourceVersion(buildConfigProjectProperty.getResourceVersion());
            BuildConfigProjectProperty newProperty = new BuildConfigProjectProperty(buildConfig);
            if (updatedBCResourceVersion <= oldBCResourceVersion &&
              newProperty.getUid().equals(buildConfigProjectProperty.getUid()) &&
              newProperty.getNamespace().equals(buildConfigProjectProperty.getNamespace()) &&
              newProperty.getName().equals(buildConfigProjectProperty.getName()) &&
              Objects.equals(newProperty.getBuildRunPolicy(), buildConfigProjectProperty.getBuildRunPolicy())
              ) {
              return null;
            }
            buildConfigProjectProperty.setUid(newProperty.getUid());
            buildConfigProjectProperty.setNamespace(newProperty.getNamespace());
            buildConfigProjectProperty.setName(newProperty.getName());
            buildConfigProjectProperty.setResourceVersion(newProperty.getResourceVersion());
            buildConfigProjectProperty.setBuildRunPolicy(newProperty.getBuildRunPolicy());
          } else {
            job.addProperty(
              new BuildConfigProjectProperty(buildConfig)
            );
          }

          BuildConfigSpec spec = buildConfig.getSpec();
          if (spec != null) {
            String runPolicy = spec.getRunPolicy();
            if (runPolicy != null) {
              job.setConcurrentBuild(
                      !(runPolicy.equals(SERIAL) ||
                              runPolicy.equals(SERIAL_LATEST_ONLY))
              );
            }
          }

          InputStream jobStream = new StringInputStream(new XStream2().toXML(job));

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

            logger.info("Created job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig) + " with revision: " + buildConfig.getMetadata().getResourceVersion());
          } else {
            Source source = new StreamSource(jobStream);
            job.updateByXml(source);
            job.save();
            logger.info("Updated job " + jobName + " from BuildConfig " + NamespaceName.create(buildConfig) + " with revision: " + buildConfig.getMetadata().getResourceVersion());
            if (existingBuildRunPolicy != null && !existingBuildRunPolicy.equals(buildConfigProjectProperty.getBuildRunPolicy())) {
              maybeScheduleNext(job);
            }
          }
          bk.commit();
          String fullName = job.getFullName();
          WorkflowJob workflowJob = activeInstance.getItemByFullName(fullName, WorkflowJob.class);
          if (workflowJob == null && parent instanceof Folder) {
            // we should never need this but just in case there's an odd timing issue or something...
            Folder folder = (Folder) parent;
            folder.add(job, jobName);
            workflowJob = activeInstance.getItemByFullName(fullName, WorkflowJob.class);
          }
          if (workflowJob == null) {
            logger.warning("Could not find created job " + fullName + " for BuildConfig: " + getNamespace(buildConfig) + "/" + getName(buildConfig));
          } else {
            //logger.info((newJob ? "created" : "updated" ) + " job " + fullName + " with path " + jobFullName + " from BuildConfig: " + getNamespace(buildConfig) + "/" + getName(buildConfig));
            putJobWithBuildConfig(workflowJob, buildConfig);
          }
          return null;
        }
      });
    }
  }

  private void modifyJob(BuildConfig buildConfig) throws Exception {
    if (isJenkinsBuildConfig(buildConfig)) {
      upsertJob(buildConfig);
      return;
    }

    // no longer a Jenkins build so lets delete it if it exists
    deleteJob(buildConfig);
  }

  private void deleteJob(final BuildConfig buildConfig) throws Exception {
    final Job job = getJobFromBuildConfig(buildConfig);
    if (job != null) {
      ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
        @Override
        public Void call() throws Exception {
          ItemGroup parent = job.getParent();
          try {
            job.delete();
          } finally {
            removeJobWithBuildConfig(buildConfig);
            Jenkins.getActiveInstance().rebuildDependencyGraphAsync();
          }
          if (parent instanceof Item) {
            removeBuildConfigJobFromFolderPluginJob((Item) parent, buildConfig, job);
          }
          removeBuildConfigFromConfigMapJobs(buildConfig);
          return null;
        }
      });
    }
  }

  private void removeBuildConfigFromConfigMapJobs(BuildConfig buildConfig) {
    ObjectMeta metadata = buildConfig.getMetadata();
    if (metadata != null) {
      removeBuildConfigNameFromConfigMapJobs(metadata.getNamespace(), metadata.getName());
    }
  }

  private void removeBuildConfigNameFromConfigMapJobs(String ns, String buildConfigName) {
    if (!Strings.isNullOrEmpty(ns) && !Strings.isNullOrEmpty(buildConfigName)) {
      ConfigMapList cmList = null;
      try {
        cmList = getOpenShiftClient().configMaps().inNamespace(ns).withLabel("openshift.io/jenkins", "job").list();
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to find ConfigMaps in namespace " + ns + " due to " + e, e);
      }
      if (cmList != null) {
        List<ConfigMap> items = cmList.getItems();
        if (items != null) {
          for (ConfigMap configMap : items) {
            removeBuildConfigFromConfigMap(ns, buildConfigName, configMap);
          }
        }
      }
    }
  }

  private void removeBuildConfigFromConfigMap(String ns, String buildConfigName, ConfigMap configMap) {
    String configMapName = OpenShiftUtils.getName(configMap);
    Map<String, String> data = configMap.getData();
    if (data != null) {
      String configXml = data.get("config.xml");
      if (!Strings.isNullOrEmpty(configXml)) {
        Document doc;
        try {
          doc = XmlUtils.parseDoc(configXml);
        } catch (Exception e) {
          logger.log(Level.WARNING, "Failed to parse config.xml from ConfigMap " + configMapName + ": " + e, e);
          return;
        }
        boolean changed = ConfigXmlHelper.removeOrganisationPattern(doc, buildConfigName);
        if (changed) {
          try {
            configXml = XmlUtils.toXml(doc);
          } catch (TransformerException e) {
            logger.log(Level.WARNING, "Failed to convert updated config.xml Document to XML for ConfigMap " + configMapName + ": " + e, e);
            return;
          }
          data.put("config.xml", configXml);
          try {
            getOpenShiftClient().configMaps().inNamespace(ns).withName(configMapName).edit().addToData("config.xml", configXml).done();
          } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to update config.xml in ConfigMap " + configMapName + ": " + e, e);
          }
        }
      }
    }
  }



  private void removeBuildConfigJobFromFolderPluginJob(Item parent, String name) {
    if (parent instanceof OrganizationFolder) {
      OrganizationFolder organizationFolder = (OrganizationFolder) parent;
      DescribableList<SCMNavigator, SCMNavigatorDescriptor> navigators = organizationFolder.getNavigators();
      if (navigators != null) {
        for (SCMNavigator navigator : navigators) {
          String navigatorClassName = navigator.getClass().getName();
          if (navigatorClassName.equals("org.jenkinsci.plugins.github_branch_source.GitHubSCMNavigator")) {
            removeRepoPattern(navigator, "pattern", name);
          }
/*
          TODO for now the source filter seems to show up as a pattern property - but later on we may need to do something funkier:

          if (navigatorClassName.equals(REGEX_SCM_SOURCE_FILTER_TRAIT_ELEMENT)) {
            removeRepoPattern(navigator, "regex", name);
          }
*/
        }
      }
    } else if (parent != null) {
      ItemGroup<? extends Item> grandParent = parent.getParent();
      if (grandParent instanceof Item) {
        removeBuildConfigJobFromFolderPluginJob((Item) grandParent, name);
      }
    }
  }

  private void removeRepoPattern(SCMNavigator navigator, String propertyName, String name) {
    // lets try get the pattern property
    BeanUtilsBean converter = new BeanUtilsBean();
    String pattern = null;
    try {
      pattern = converter.getProperty(navigator, propertyName);
    } catch (Exception e) {
      logger.warning("Could not get pattern of navigator " + navigator + " due to: " + e);
    }
    if (!Strings.isNullOrEmpty(pattern)) {
      String newPattern = JenkinsUtils.removePattern(pattern, name);
      if (newPattern != null) {
        try {
          converter.setProperty(navigator, propertyName, newPattern);
        } catch (Exception e) {
          logger.warning("Could not update pattern of navigator " + navigator + " to " + pattern + " due to: " + e);
        }
      }
    }
  }

  private void removeBuildConfigJobFromFolderPluginJob(Item parent, BuildConfig buildConfig, Job job) {
    ObjectMeta metadata = buildConfig.getMetadata();
    if (metadata != null) {
      String name = metadata.getName();
      removeBuildConfigJobFromFolderPluginJob(parent, name);
    }
  }
}
