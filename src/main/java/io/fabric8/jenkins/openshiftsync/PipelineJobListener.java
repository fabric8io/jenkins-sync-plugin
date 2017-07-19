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

import com.google.common.base.Objects;
import hudson.Extension;
import hudson.XmlFile;
import hudson.model.AbstractItem;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.listeners.ItemListener;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.DoneableConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;
import io.fabric8.openshift.api.model.BuildConfigSpec;
import io.fabric8.openshift.api.model.BuildSource;
import io.fabric8.openshift.api.model.BuildStrategy;
import io.fabric8.openshift.api.model.GitBuildSource;
import io.fabric8.openshift.api.model.JenkinsPipelineBuildStrategy;
import io.fabric8.openshift.client.OpenShiftClient;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.removeJobWithBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMapper.updateBuildConfigFromJob;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

/**
 * Listens to {@link WorkflowJob} objects being updated via the web console or Jenkins REST API and replicating
 * the changes back to the OpenShift {@link BuildConfig} for the case where folks edit inline Jenkinsfile flows
 * inside the Jenkins UI
 */
@Extension
public class PipelineJobListener extends ItemListener {
  private static final Logger logger = Logger.getLogger(PipelineJobListener.class.getName());

  private String server;
  private String namespace;
  private String jobNamePattern;

  public PipelineJobListener() {
    init();
  }

  @DataBoundConstructor
  public PipelineJobListener(String server, String namespace, String jobNamePattern) {
    this.server = server;
    this.namespace = namespace;
    this.jobNamePattern = jobNamePattern;
    init();
  }

  @Override
  public String toString() {
    return "PipelineJobListener{" +
            "server='" + server + '\'' +
            ", namespace='" + namespace + '\'' +
            ", jobNamePattern='" + jobNamePattern + '\'' +
            '}';
  }

  private void init() {
    namespace = OpenShiftUtils.getNamespaceOrUseDefault(namespace, getOpenShiftClient());
  }

  @Override
  public void onCreated(Item item) {
    reconfigure();
    super.onCreated(item);
    upsertItem(item);
  }

  @Override
  public void onUpdated(Item item) {
    reconfigure();
    super.onUpdated(item);
    upsertItem(item);
  }

  @Override
  public void onDeleted(Item item) {
    reconfigure();
    super.onDeleted(item);
    if (item instanceof WorkflowJob) {
      WorkflowJob job = (WorkflowJob) item;
      BuildConfigProjectProperty property = buildConfigProjectForJob(job);
      if (property != null) {
        logger.info("Deleting BuildConfig " + property.getNamespace() + "/" + property.getName());

        String namespace = property.getNamespace();
        String buildConfigName = property.getName();
        BuildConfig buildConfig = getOpenShiftClient().buildConfigs().inNamespace(namespace).withName(buildConfigName).get();
        if (buildConfig != null) {
          try {
            getOpenShiftClient().buildConfigs().inNamespace(namespace).withName(buildConfigName).delete();
          } catch (KubernetesClientException e) {
            if (HTTP_NOT_FOUND != e.getCode()) {
              logger.log(Level.WARNING, "Failed to delete BuildConfig in namespace: " + namespace + " for name: " + buildConfigName, e);
            }
          } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to delete BuildConfig in namespace: " + namespace + " for name: " + buildConfigName, e);
          } finally {
            removeJobWithBuildConfig(buildConfig);
          }
        }
      }
    }
  }

  public void upsertItem(Item item) {
    if (item instanceof WorkflowJob) {
      upsertWorkflowJob((WorkflowJob) item);
    } else {
      if (item instanceof ItemGroup) {
        upsertChildren((ItemGroup) item);
      }
      if (item instanceof AbstractItem) {
        upsertJob((AbstractItem) item);
      }
    }
  }

  private void upsertJob(AbstractItem job) {
    BuildConfigProjectProperty property = ConfigMapToJobMap.getOrFindProperty(job);
    if (property != null) {
      String newConfigXml = null;
      XmlFile configFile = job.getConfigFile();
      if (configFile != null) {
        try {
          newConfigXml = configFile.asString();
        } catch (IOException e) {
          logger.log(Level.WARNING, "Failed to get Job " + job.getName() + " config.xml due to " + e, e);
          return;
        }
      }
      if (newConfigXml == null) {
        return;
      }
      // lets update the ConfigMap if the config.xml has changed
      String name = property.getName();
      String namespace = property.getNamespace();
      OpenShiftClient openShiftClient = getOpenShiftClient();
      ConfigMap configMap = null;
      Resource<ConfigMap, DoneableConfigMap> configMapResource = openShiftClient.configMaps().inNamespace(namespace).withName(name);
      try {
        configMap = configMapResource.get();
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to load ConfigMap " + namespace + "/" + name + " due to " + e, e);
        return;
      }
      if (configMap != null) {
        Map<String, String> data = configMap.getData();
        if (data != null) {
          String oldConfigXml = data.get(ConfigMapKeys.CONFIG_XML);
          if (oldConfigXml != null) {
            String xml1;
            String xml2;
            try {
              xml1 = OpenShiftUtils.removePropertiesFromXml(newConfigXml);
              xml2 = OpenShiftUtils.removePropertiesFromXml(oldConfigXml);
            } catch (Exception e) {
              logger.log(Level.WARNING, "Failed to parse config.xml due to " + e, e);
              return;
            }
            if (!java.util.Objects.equals(xml1, xml2)) {
              data.put(ConfigMapKeys.CONFIG_XML, xml1);
              try {
                configMapResource.edit().addToData(ConfigMapKeys.CONFIG_XML, xml1).done();
                logger.info("Updated ConfigMap " + namespace + "/" + name + " due to update to config.xml via Jenkins UI");
              } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to update ConfigMap " + namespace + "/" + name + " due to " + e, e);
              }
            }
          }
        }
      }
    }
  }

  private void upsertChildren(ItemGroup itemGroup) {
    Collection items = itemGroup.getItems();
    if (items != null) {
      for (Object child : items) {
        if (child instanceof Item) {
          upsertItem((Item) child);
        }
      }
    }
  }

  private void upsertWorkflowJob(WorkflowJob job) {
    BuildConfigProjectProperty property = buildConfigProjectForJob(job);
    if (property != null) {
      logger.info("Upsert WorkflowJob " + job.getName() + " to BuildConfig: "+ property.getNamespace() + "/" + property.getName() + " in OpenShift");
      upsertBuildConfigForJob(job, property);
    }
  }

  /**
   * Returns the mapping of the jenkins workflow job to a qualified namespace and BuildConfig name
   */
  private BuildConfigProjectProperty buildConfigProjectForJob(WorkflowJob job) {

    BuildConfigProjectProperty property = job.getProperty(BuildConfigProjectProperty.class);

    if (property != null) {
      if (StringUtils.isNotBlank(property.getNamespace()) && StringUtils.isNotBlank(property.getName())) {
        logger.info("Found BuildConfigProjectProperty for namespace: " + property.getNamespace() + " name: " + property.getName());
        return property;
      }
    }

    String patternRegex = this.jobNamePattern;
    String jobName = JenkinsUtils.getFullJobName(job);
    if (StringUtils.isNotEmpty(jobName) && StringUtils.isNotEmpty(patternRegex) && jobName.matches(patternRegex)) {
      String buildConfigName = OpenShiftUtils.convertNameToValidResourceName(JenkinsUtils.getBuildConfigName(job));

      // we will update the uuid when we create the BC
      String uuid = null;

      // TODO what to do for the resourceVersion?
      String resourceVersion = null;
      String buildRunPolicy = "Serial";
      logger.info("Creating BuildConfigProjectProperty for namespace: " + namespace + " name: " + buildConfigName);
      if (property != null) {
        property.setNamespace(namespace);
        property.setName(buildConfigName);
        if (!StringUtils.isNotBlank(property.getBuildRunPolicy())) {
          property.setBuildRunPolicy(buildRunPolicy);
        }
        return property;
      } else {
        return new BuildConfigProjectProperty(namespace, buildConfigName, uuid, resourceVersion, buildRunPolicy);
      }
    }
    return null;
  }

  private void upsertBuildConfigForJob(WorkflowJob job, BuildConfigProjectProperty buildConfigProjectProperty) {
    boolean create = false;
    BuildConfig jobBuildConfig = getOpenShiftClient().buildConfigs().
            inNamespace(buildConfigProjectProperty.getNamespace()).withName(buildConfigProjectProperty.getName()).get();
    if (jobBuildConfig == null) {
      create = true;
      jobBuildConfig = new BuildConfigBuilder().
              withNewMetadata().withName(buildConfigProjectProperty.getName()).
              withNamespace(buildConfigProjectProperty.getNamespace()).
              addToAnnotations(Annotations.GENERATED_BY, "jenkins").
              endMetadata().
              withNewSpec().
              withNewStrategy().withType("JenkinsPipeline").withNewJenkinsPipelineStrategy().endJenkinsPipelineStrategy().endStrategy().
              endSpec().build();
    } else {
      ObjectMeta metadata = jobBuildConfig.getMetadata();
      String uid = buildConfigProjectProperty.getUid();
      if (metadata != null && StringUtils.isEmpty(uid)) {
        buildConfigProjectProperty.setUid(metadata.getUid());
      } else if (metadata != null && !Objects.equal(uid, metadata.getUid())) {
        // the UUIDs are different so lets ignore this BC
        return;
      }
    }
    updateBuildConfigFromJob(job, jobBuildConfig);

    if (!hasEmbeddedPipelineOrValidSource(jobBuildConfig)) {
      // this pipeline has not yet been populated with the git source or an embedded pipeline so lets not create/update a BC yet
      return;
    }

    // lets annotate with the job name
    if (create) {
      OpenShiftUtils.addAnnotation(jobBuildConfig, Annotations.JENKINS_JOB_PATH, JenkinsUtils.getFullJobName(job));
    }
    
    if (create) {
      try {
        BuildConfig bc = getOpenShiftClient().buildConfigs().inNamespace(jobBuildConfig.getMetadata().getNamespace()).create(jobBuildConfig);
        String uid = bc.getMetadata().getUid();
        buildConfigProjectProperty.setUid(uid);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to create BuildConfig: " + NamespaceName.create(jobBuildConfig) + ". " + e, e);
      }
    } else {
      try {
        getOpenShiftClient().buildConfigs().inNamespace(jobBuildConfig.getMetadata().getNamespace()).withName(jobBuildConfig.getMetadata().getName()).cascading(false).replace(jobBuildConfig);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to update BuildConfig: " + NamespaceName.create(jobBuildConfig) + ". " + e, e);
      }
    }
  }

  private boolean hasEmbeddedPipelineOrValidSource(BuildConfig buildConfig) {
    BuildConfigSpec spec = buildConfig.getSpec();
    if (spec != null) {
      BuildStrategy strategy = spec.getStrategy();
      if (strategy != null) {
        JenkinsPipelineBuildStrategy jenkinsPipelineStrategy = strategy.getJenkinsPipelineStrategy();
        if (jenkinsPipelineStrategy != null) {
          if (StringUtils.isNotBlank(jenkinsPipelineStrategy.getJenkinsfile())) {
            return true;
          }
          if (StringUtils.isNotBlank(jenkinsPipelineStrategy.getJenkinsfilePath())) {
            BuildSource source = spec.getSource();
            if (source != null) {
              GitBuildSource git = source.getGit();
              if (git != null) {
                if (StringUtils.isNotBlank(git.getUri())) {
                  return true;
                }
              }
              // TODO support other SCMs
            }
          }
        }
      }
    }
    return false;
  }

  /**
   * TODO is there a cleaner way to get this class injected with any new configuration from GlobalPluginConfiguration?
   */
  private void reconfigure() {
    GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
    if (config != null) {
      this.jobNamePattern = config.getJobNamePattern();
      this.namespace = config.getNamespace();
      this.server = config.getServer();
    }
  }


}
