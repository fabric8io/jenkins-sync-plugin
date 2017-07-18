/**
<<<<<<< HEAD
 * Copyright (C) 2017 Red Hat, Inc.
=======
 * Copyright (C) 2016 Red Hat, Inc.
>>>>>>> 129b1fd... support syncing ConfigMap's with config.xml files <-> Jenkins Jobs
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
import com.thoughtworks.xstream.XStreamException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.BulkChange;
import hudson.XmlFile;
import hudson.model.*;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import hudson.util.DescribableList;
import hudson.util.XStream2;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.openshift.api.model.ImageStreamTag;
import jenkins.branch.OrganizationFolder;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMNavigatorDescriptor;
import jenkins.security.NotReallyRoleSensitiveCallable;
import org.apache.commons.beanutils.BeanUtilsBean;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.ConfigMapKeys.*;
import static io.fabric8.jenkins.openshiftsync.ConfigMapToJobMap.*;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.*;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;


public class ConfigMapWatcher extends BaseWatcher implements Watcher<ConfigMap> {
    private Map<String, List<PodTemplate>> trackedConfigMaps;
    private final static Logger logger = Logger.getLogger(ConfigMapWatcher.class.getName());
    private static final String SPECIAL_IST_PREFIX = "imagestreamtag:";
    private static final int SPECIAL_IST_PREFIX_IDX = SPECIAL_IST_PREFIX.length();


  @SuppressFBWarnings("EI_EXPOSE_REP2")
    public ConfigMapWatcher(String[] namespaces) {
        super(namespaces);
        this.trackedConfigMaps = new ConcurrentHashMap<>();
    }

    public Runnable getStartTimerTask() {
        return new SafeTimerTask() {
            @Override
            public void doRun() {
                if (!CredentialsUtils.hasCredentials()) {
                    logger.fine("No Openshift Token credential defined.");
                    return;
                }
              initializeConfigMapToJobMap();

              for (String namespace : namespaces) {
                    ConfigMapList configMaps = null;
                    try {
                        logger.fine("listing ConfigMap resources");
                        configMaps = getAuthenticatedOpenShiftClient()
                                .configMaps().inNamespace(namespace).list();
                        onInitialConfigMaps(configMaps);
                        logger.fine("handled ConfigMap resources");
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
                    }
                    try {
                        String resourceVersion = "0";
                        if (configMaps == null) {
                            logger.warning("Unable to get config map list; impacts resource version used for watch");
                        } else {
                            resourceVersion = configMaps.getMetadata()
                                    .getResourceVersion();
                        }
                        if (watches.get(namespace) == null) {
                            logger.info("creating ConfigMap watch for namespace "
                                    + namespace
                                    + " and resource version "
                                    + resourceVersion);
                            watches.put(
                                    namespace,
                                    getAuthenticatedOpenShiftClient()
                                            .configMaps()
                                            .inNamespace(namespace)
                                            .withResourceVersion(
                                                    resourceVersion)
                                            .watch(ConfigMapWatcher.this));
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Failed to load ConfigMaps: " + e, e);
                    }
                }
            }
        };
    }

    public synchronized void start() {
        super.start();
        // lets process the initial state
        logger.info("Now handling startup config maps!!");
    }

    @Override
    public void eventReceived(Action action, ConfigMap configMap) {
        try {
            switch (action) {
            case ADDED:
                if (containsSlave(configMap)) {
                    List<PodTemplate> templates = podTemplatesFromConfigMap(configMap);
                    trackedConfigMaps.put(configMap.getMetadata().getUid(),
                            templates);
                    for (PodTemplate podTemplate : templates) {
                        JenkinsUtils.addPodTemplate(podTemplate);
                    }
                }
                upsertJob(configMap);
                break;

            case MODIFIED:
                boolean alreadyTracked = trackedConfigMaps
                        .containsKey(configMap.getMetadata().getUid());

                if (alreadyTracked) {
                    if (containsSlave(configMap)) {
                        // Since the user could have change the immutable image
                        // that a PodTemplate uses, we just
                        // recreate the PodTemplate altogether. This makes it so
                        // that any changes from within
                        // Jenkins is undone.
                        for (PodTemplate podTemplate : trackedConfigMaps
                                .get(configMap.getMetadata().getUid())) {
                            JenkinsUtils.removePodTemplate(podTemplate);
                        }

                        for (PodTemplate podTemplate : podTemplatesFromConfigMap(configMap)) {
                            JenkinsUtils.addPodTemplate(podTemplate);
                        }
                    } else {
                        // The user modified the configMap to no longer be a
                        // jenkins-slave.
                        for (PodTemplate podTemplate : trackedConfigMaps
                                .get(configMap.getMetadata().getUid())) {
                            JenkinsUtils.removePodTemplate(podTemplate);
                        }

                        trackedConfigMaps.remove(configMap.getMetadata()
                                .getUid());
                    }
                } else {
                    if (containsSlave(configMap)) {
                        // The user modified the configMap to be a jenkins-slave

                        List<PodTemplate> templates = podTemplatesFromConfigMap(configMap);
                        trackedConfigMaps.put(configMap.getMetadata().getUid(),
                                templates);
                        for (PodTemplate podTemplate : templates) {
                            JenkinsUtils.addPodTemplate(podTemplate);
                        }
                    }
                }
                modifyJob(configMap);
                break;

            case DELETED:
                if (trackedConfigMaps.containsKey(configMap.getMetadata()
                        .getUid())) {
                    for (PodTemplate podTemplate : trackedConfigMaps
                            .get(configMap.getMetadata().getUid())) {
                        JenkinsUtils.removePodTemplate(podTemplate);
                    }
                    trackedConfigMaps.remove(configMap.getMetadata().getUid());
                }
                deleteJob(configMap);
                break;
            // pedantic mvn:findbugs complaint
            default:
                break;

            }
        } catch (Exception e) {
            logger.log(WARNING, "Caught: " + e, e);
        }
    }

    private synchronized void onInitialConfigMaps(ConfigMapList configMaps) {
        if (configMaps == null)
            return;
        if (trackedConfigMaps == null) {
            trackedConfigMaps = new ConcurrentHashMap<>(configMaps.getItems()
                    .size());
        }
        List<ConfigMap> items = configMaps.getItems();
        if (items != null) {
            for (ConfigMap configMap : items) {
                try {
                    if (containsSlave(configMap)
                            && !trackedConfigMaps.containsKey(configMap
                                    .getMetadata().getUid())) {

                      List<PodTemplate> templates = podTemplatesFromConfigMap(configMap);
                        trackedConfigMaps.put(configMap.getMetadata().getUid(),
                                templates);
                        for (PodTemplate podTemplate : templates) {
                            JenkinsUtils.addPodTemplate(podTemplate);
                        }

                      upsertJob(configMap);
                    }
                } catch (Exception e) {
                    logger.log(SEVERE,
                            "Failed to update ConfigMap PodTemplates", e);
                }
            }
        }
    }

    private boolean containsSlave(ConfigMap configMap) {
        return hasSlaveLabelOrAnnotation(configMap.getMetadata().getLabels());
    }

    private boolean hasOneAndOnlyOneWithSomethingAfter(String str, String substr) {
        return str.contains(substr)
                && str.indexOf(substr) == str.lastIndexOf(substr)
                && str.indexOf(substr) < str.length();
    }

    // podTemplatesFromConfigMap takes every key from a ConfigMap and tries to
    // create a PodTemplate from the contained
    // XML.
    public List<PodTemplate> podTemplatesFromConfigMap(ConfigMap configMap) {
        List<PodTemplate> results = new ArrayList<>();
        Map<String, String> data = configMap.getData();

        XStream2 xStream2 = new XStream2();

        for (Entry<String, String> entry : data.entrySet()) {
            Object podTemplate;
            try {
                podTemplate = xStream2.fromXML(entry.getValue());

                String warningPrefix = "Content of key '" + entry.getKey()
                        + "' in ConfigMap '"
                        + configMap.getMetadata().getName();
                if (podTemplate instanceof PodTemplate) {
                    PodTemplate pt = (PodTemplate) podTemplate;

                    String image = pt.getImage();
                    try {
                        // if requested via special prefix, convert this images
                        // entry field, if not already fully qualified, as if
                        // it were an IST
                        // IST of form [optional_namespace]/imagestreamname:tag
                        // checks based on ParseImageStreamTagName in
                        // https://github.com/openshift/origin/blob/master/pkg/image/apis/image/helper.go
                        if (image.startsWith(SPECIAL_IST_PREFIX)) {
                            image = image.substring(SPECIAL_IST_PREFIX_IDX);
                            if (image.contains("@")) {
                                logger.warning(warningPrefix
                                        + " the presence of @ implies an image stream image, not an image stream tag, "
                                        + " so no ImageStreamTag to Docker image reference translation was performed.");
                            } else {
                                boolean hasNamespace = hasOneAndOnlyOneWithSomethingAfter(
                                        image, "/");
                                boolean hasTag = hasOneAndOnlyOneWithSomethingAfter(
                                        image, ":");
                                String namespace = getAuthenticatedOpenShiftClient()
                                        .getNamespace();
                                String isName = image;
                                String newImage = null;
                                if (hasNamespace) {
                                    String[] parts = image.split("/");
                                    namespace = parts[0];
                                    isName = parts[1];
                                }
                                if (hasTag) {
                                    ImageStreamTag ist = getAuthenticatedOpenShiftClient()
                                            .imageStreamTags()
                                            .inNamespace(namespace)
                                            .withName(isName).get();
                                    if (ist != null
                                            && ist.getImage() != null
                                            && ist.getImage()
                                                    .getDockerImageReference() != null
                                            && ist.getImage()
                                                    .getDockerImageReference()
                                                    .length() > 0) {
                                        newImage = ist.getImage()
                                                .getDockerImageReference();
                                        logger.fine(String
                                                .format("Converting image ref %s as an imagestreamtag %s to fully qualified image %s",
                                                        image, isName, newImage));
                                    } else {
                                        logger.warning(warningPrefix
                                                + " used the 'imagestreamtag:' prefix in the image field, but the subsequent value, while a valid ImageStreamTag reference,"
                                                + " produced no valid ImageStreaTag upon lookup,"
                                                + " so no ImageStreamTag to Docker image reference translation was performed.");
                                    }
                                } else {
                                    logger.warning(warningPrefix
                                            + " used the 'imagestreamtag:' prefix in the image field, but the subsequent value had no tag indicator,"
                                            + " so no ImageStreamTag to Docker image reference translation was performed.");
                                }

                                if (newImage != null) {
                                    logger.fine("translated IST ref " + image
                                            + " to docker image ref "
                                            + newImage);
                                    pt.getContainers().get(0)
                                            .setImage(newImage);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        if (logger.isLoggable(Level.FINE))
                            logger.log(Level.FINE, "podTemplateFromConfigMap",
                                    t);
                    }
                    results.add((PodTemplate) podTemplate);
                } else {
                    logger.warning(warningPrefix + "' is not a PodTemplate");
                }
            } catch (XStreamException xse) {
                logger.warning(new IOException("Unable to read key '"
                        + entry.getKey() + "' from ConfigMap '"
                        + configMap.getMetadata().getName() + "'", xse)
                        .getMessage());
            } catch (Error e) {
                logger.warning(new IOException("Unable to read key '"
                        + entry.getKey() + "' from ConfigMap '"
                        + configMap.getMetadata().getName() + "'", e)
                        .getMessage());
            }
        }

        return results;
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
            Job job = getJobFromConfigMap(configMap);
            Jenkins activeInstance = Jenkins.getActiveInstance();
            ItemGroup parent = activeInstance;
            if (job == null) {
              job = (Job) activeInstance.getItemByFullName(jobFullName);
            }
            boolean newJob = job == null;
            boolean updated = true;
            if (newJob) {
              if (!rootJob) {
                parent = getFullNameParent(activeInstance, jobFullName, getNamespace(configMap));
              }
              job = new FreeStyleProject(parent, jobName);
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

            BulkChange bk = new BulkChange(job);

            BuildConfigProjectProperty buildConfigProjectProperty = BuildConfigProjectProperty.getProperty(job);
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

            } else {
              buildConfigProjectProperty = new BuildConfigProjectProperty(configMap);
              ConfigMapToJobMap.putBuildConfigProjectProperty(buildConfigProjectProperty);
            }

            InputStream jobStream = new ByteArrayInputStream(configXml.getBytes());

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
            job.addProperty(
              buildConfigProjectProperty
            );

            if (updated) {
              maybeScheduleConfigMapJob(job, configMap);
            }
            bk.commit();
            String fullName = job.getFullName();
            Job currentJob = activeInstance.getItemByFullName(fullName, Job.class);
            if (currentJob == null && parent instanceof Folder && job instanceof TopLevelItem) {
              // we should never need this but just in case there's an odd timing issue or something...
              TopLevelItem top = (TopLevelItem) job;
              Folder folder = (Folder) parent;
              folder.add(top, jobName);
              currentJob = activeInstance.getItemByFullName(fullName, Job.class);
            }
            if (currentJob == null) {
              logger.warning("Could not find created job " + fullName + " for ConfigMap: " + getNamespace(configMap) + "/" + getName(configMap));
            } else {
              //logger.info((newJob ? "created" : "updated" ) + " job " + fullName + " with path " + jobFullName + " from ConfigMap: " + getNamespace(configMap) + "/" + getName(configMap));
              putJobWithConfigMap(currentJob, configMap);
            }
            return null;
          }
        });
      }
    }
  }

  private static void maybeScheduleConfigMapJob(Job job, ConfigMap configMap) {
    if (OpenShiftUtils.isConfigMapFlagEnabled(configMap, TRIGGER_ON_CHANGE)) {
      if (job.isBuilding() || job.isInQueue()) {
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

  private void modifyJob(ConfigMap configMap) throws Exception {
    if (isJenkinsConfigMap(configMap)) {
      upsertJob(configMap);
      return;
    }

    // no longer a Jenkins build so lets delete it if it exists
    deleteJob(configMap);
  }

  private void deleteJob(final ConfigMap configMap) throws Exception {
    final Job job = getJobFromConfigMap(configMap);
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

  private void removeConfigMapJobFromFolderPluginJob(Item parent, ConfigMap configMap, Job job) {
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
