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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import hudson.BulkChange;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.util.XStream2;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerStatus;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceSpec;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigSpec;
import io.fabric8.openshift.api.model.BuildSource;
import io.fabric8.openshift.api.model.BuildStatus;
import io.fabric8.openshift.api.model.GitBuildSource;
import io.fabric8.openshift.api.model.BuildConfigList;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.api.model.RouteSpec;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftAPIGroups;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.filters.StringInputStream;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildPhases.NEW;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.PENDING;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.RUNNING;
import static io.fabric8.jenkins.openshiftsync.ConfigMapKeys.CONFIG_XML;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_DEFAULT_NAMESPACE;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_BUILD_CONFIG_GIT_REPOSITORY_NAME;
import static java.util.logging.Level.FINE;

/**
 */
public class OpenShiftUtils {

  private final static Logger logger = Logger.getLogger(OpenShiftUtils.class.getName());

  private static OpenShiftClient openShiftClient;

  private static final DateTimeFormatter dateFormatter = ISODateTimeFormat.dateTimeNoMillis();

  /**
   * Initializes an {@link OpenShiftClient}
   *
   * @param serverUrl the optional URL of where the OpenShift cluster API server is running
   */
  public synchronized static void initializeOpenShiftClient(String serverUrl) {
    OpenShiftConfigBuilder configBuilder = new OpenShiftConfigBuilder();
    if (serverUrl != null && !serverUrl.isEmpty()) {
      configBuilder.withMasterUrl(serverUrl);
    }
    Config config = configBuilder.build();
    openShiftClient = new DefaultOpenShiftClient(config);
  }

  public synchronized static OpenShiftClient getOpenShiftClient() {
    return openShiftClient;
  }

  public synchronized static void shutdownOpenShiftClient() {
    if (openShiftClient != null) {
      openShiftClient.close();
      openShiftClient = null;
    }
  }

  /**
   * This will return the buildConfig whose name is equal to buildConfigName
   * or the buildConfig with a label of the buildconfigName
   */
  public static BuildConfig findBCbyNameOrLabel(OpenShiftClient client,String namespace, String buildConfigname) {

    logger.info("Finding BuildConfig for namespace: " + namespace + " name: " + buildConfigname);
    BuildConfig jobBuildConfig = client.buildConfigs().
      inNamespace(namespace).withName(buildConfigname).get();

    if (jobBuildConfig == null){
      logger.info("Not able to find BuildConfig for namespace: " + namespace + " name: " + buildConfigname);
      logger.info("Finding BuildConfig for namespace: " + namespace + " with label gitRepository: " + buildConfigname);

      /* BuildConfig and Repo Name are not same, lets find the buildConfig by label*/
      BuildConfigList jobBuildConfigs = client.buildConfigs().inNamespace(namespace)
        .withLabels(Collections.singletonMap(OPENSHIFT_LABELS_BUILD_CONFIG_GIT_REPOSITORY_NAME, buildConfigname)).list();

      /*Always choose the first one because launcher(https://github.com/fabric8-launcher/launcher-backend) will create
       a single pipeline for a git repo and this will always return one if exists and nothing if does not exist*/
      if (!jobBuildConfigs.getItems().isEmpty()){
        jobBuildConfig = jobBuildConfigs.getItems().get(0);
        logger.info("Able to find BuildConfig for namespace: " + namespace + " with label gitRepository: " + buildConfigname);
      } else {
        logger.info("Not able to find BuildConfig for namespace: " + namespace +
          " with label gitRepository: " + buildConfigname);
      }
    } else {
      logger.info("Able to find BuildConfig for namespace: " + namespace + " name: " + buildConfigname);
    }
    return jobBuildConfig;
  }

  /**
   * Checks if a {@link BuildConfig} relates to a Jenkins build
   *
   * @param bc the BuildConfig
   * @return true if this is an OpenShift BuildConfig which should be mirrored to
   * a Jenkins Job
   */
  public static boolean isJenkinsBuildConfig(BuildConfig bc) {
    if (BuildConfigToJobMapper.JENKINS_PIPELINE_BUILD_STRATEGY.equalsIgnoreCase(bc.getSpec().getStrategy().getType()) &&
      bc.getSpec().getStrategy().getJenkinsPipelineStrategy() != null) {
      return true;
    }

    ObjectMeta metadata = bc.getMetadata();
    if (metadata != null) {
      Map<String, String> annotations = metadata.getAnnotations();
      if (annotations != null) {
        if (annotations.get("fabric8.link.jenkins.job/label") != null) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Checks if a {@link ConfigMap} should be mapped to a non-Pipeline Jenkins Job
   *
   * @param cm the ConfigMap
   * @return true if this ConfigMap should be mapped to a Jenkins Job
   */
  public static boolean isJenkinsConfigMap(ConfigMap cm) {
    ObjectMeta metadata = cm.getMetadata();
    Map<String, String> data = cm.getData();
    if (metadata == null || data == null) {
      return false;
    }
    Map<String, String> labels = metadata.getLabels();
    if (labels == null) {
      return false;
    }
    String jenkinsJobLabel = labels.get("openshift.io/jenkins");
    String configMap = data.get(CONFIG_XML);
    return configMap != null && configMap.length() > 0 && Objects.equal("job", jenkinsJobLabel);
  }

  /**
   * Finds the Jenkins job name for the given {@link BuildConfig}.
   *
   * @param bc the BuildConfig
   * @return the jenkins job name for the given BuildConfig
   */
  public static String jenkinsJobName(HasMetadata bc) {
    return getName(bc);
  }

  /**
   * Finds the full jenkins job path including folders for the given {@link BuildConfig}.
   *
   * @param bc the BuildConfig
   * @return the jenkins job name for the given BuildConfig
   */
  public static String jenkinsJobFullName(HasMetadata bc) {
    String jobName = getAnnotation(bc, Annotations.JENKINS_JOB_PATH);
    if (StringUtils.isNotBlank(jobName)) {
      return jobName;
    }
    return getNamespace(bc) + "/" + getName(bc);
  }

  /**
   * Returns the parent for the given item full name or default to the active jenkins if it does not exist
   */
  public static ItemGroup getFullNameParent(Jenkins activeJenkins, String fullName, String namespace) {
    int idx = fullName.lastIndexOf('/');
    if (idx > 0) {
      String parentFullName = fullName.substring(0, idx);
      Item parent = activeJenkins.getItemByFullName(parentFullName);
      if (parent instanceof ItemGroup) {
        return (ItemGroup) parent;
      } else if (parentFullName.equals(namespace)) {

        // lets lazily create a new folder for this namespace parent
        Folder folder = new Folder(activeJenkins, namespace);
        try {
          folder.setDescription("Folder for the OpenShift project: " + namespace);
        } catch (IOException e) {
          // ignore
        }
        BulkChange bk = new BulkChange(folder);
        InputStream jobStream = new StringInputStream(new XStream2().toXML(folder));
        try {
          activeJenkins.createProjectFromXML(
            namespace,
            jobStream
          ).save();
        } catch (IOException e) {
          logger.warning("Failed to create the Folder: " + namespace);
        }
        try {
          bk.commit();
        } catch (IOException e) {
          logger.warning("Failed to commit toe BulkChange for the Folder: " + namespace);
        }
        // lets look it up again to be sure
        parent = activeJenkins.getItemByFullName(namespace);
        if (parent instanceof ItemGroup) {
          return (ItemGroup) parent;
        }
      }
    }
    return activeJenkins;
  }

  /**
   * Finds the Jenkins job display name for the given {@link BuildConfig}.
   *
   * @param bc the BuildConfig
   * @return the jenkins job display name for the given BuildConfig
   */
  public static String jenkinsJobDisplayName(BuildConfig bc) {
    String namespace = bc.getMetadata().getNamespace();
    String name = bc.getMetadata().getName();
    return jenkinsJobDisplayName(namespace, name);
  }

  /**
   * Creates the Jenkins Job display name for the given buildConfigName
   *
   * @param namespace the namespace of the build
   * @param buildConfigName the name of the {@link BuildConfig} in in the namespace
   * @return the jenkins job display name for the given namespace and name
   */
  public static String jenkinsJobDisplayName(String namespace, String buildConfigName) {
    return namespace + "/" + buildConfigName;
  }

  /**
   * Gets the current namespace running Jenkins inside or returns a reasonable default
   *
   * @param configuredNamespace the optional configured namespace
   * @param client the OpenShift client
   * @return the default namespace using either the configuration value, the default namespace on the client or "default"
   */
  public static String getNamespaceOrUseDefault(String configuredNamespace, OpenShiftClient client) {
    String namespace = configuredNamespace;
    if (namespace != null && namespace.startsWith("${") && namespace.endsWith("}")) {
      String envVar = namespace.substring(2, namespace.length() - 1);
      namespace = System.getenv(envVar);
      if (StringUtils.isBlank(namespace)) {
        logger.warning("No value defined for namespace environment variable `" + envVar +"`");
      }
    }
    if (StringUtils.isBlank(namespace)) {
      if (client != null) {
        namespace = client.getNamespace();
      }
      if (StringUtils.isBlank(namespace)) {
        namespace = OPENSHIFT_DEFAULT_NAMESPACE;
      }
    }
    return namespace;
  }

  /**
   * Returns the public URL of the given service
   *
   * @param openShiftClient the OpenShiftClient to use
   * @param defaultProtocolText the protocol text part of a URL such as <code>http://</code>
   * @param namespace the Kubernetes namespace
   * @param serviceName the service name
   * @return the external URL of the service
   */
  public static String getExternalServiceUrl(OpenShiftClient openShiftClient, String defaultProtocolText, String namespace, String serviceName) {
    if (openShiftClient != null && openShiftClient.supportsOpenShiftAPIGroup(OpenShiftAPIGroups.ROUTE)) {
      try {
        RouteList routes = openShiftClient.routes().inNamespace(namespace).list();
        for (Route route : routes.getItems()) {
          RouteSpec spec = route.getSpec();
          if (spec != null && spec.getTo() != null && "Service".equalsIgnoreCase(spec.getTo().getKind()) && serviceName.equalsIgnoreCase(spec.getTo().getName())) {
            String host = spec.getHost();
            if (host != null && host.length() > 0) {
              if (spec.getTls() != null) {
                return "https://" + host;
              }
              return "http://" + host;
            }
          }
        }
      } catch (Exception e) {
        logger.log(Level.WARNING, "Could not find Route for service " + namespace + "/" + serviceName + ". " + e, e);
      }
    }
    // lets try the portalIP instead
    try {
      if (openShiftClient != null) {
        Service service = openShiftClient.services().inNamespace(namespace).withName(serviceName).get();
        if (service != null) {
          // if exposecontroller is being used
          // see: https://github.com/fabric8io/exposecontroller/blob/master/README.md
          String answer = getAnnotation(service, Annotations.FABRIC8_EXPOSE_URL);
          if (answer != null) {
            return answer;
          }
          ServiceSpec spec = service.getSpec();
          if (spec != null) {
            List<String> externalIPs = spec.getExternalIPs();
            if (externalIPs != null) {
              for (String externalIP : externalIPs) {
                if (!Strings.isNullOrEmpty(externalIP)) {
                  return defaultProtocolText + externalIP;
                }
              }
            }
          }
        }
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Could not find Route for service " + namespace + "/" + serviceName + ". " + e, e);
    }

    // lets default to the service DNS name
    return defaultProtocolText + serviceName;
  }

  /**
   * Calculates the external URL to access Jenkins
   *
   * @param namespace the namespace Jenkins is runing inside
   * @param openShiftClient              the OpenShift client
   * @return the external URL to access Jenkins
   */
  public static String getJenkinsURL(OpenShiftClient openShiftClient, String namespace) {
    return getExternalServiceUrl(openShiftClient, "http://", namespace ,"jenkins");
  }

  /**
   * Lazily creates the GitSource if need be then updates the git URL
   *  @param buildConfig the BuildConfig to update
   * @param gitUrl the URL to the git repo
   * @param ref
   */
  public static void updateGitSourceUrl(BuildConfig buildConfig, String gitUrl, String ref) {
    BuildConfigSpec spec = buildConfig.getSpec();
    if (spec == null) {
      spec = new BuildConfigSpec();
      buildConfig.setSpec(spec);
    }
    BuildSource source = spec.getSource();
    if (source == null) {
      source = new BuildSource();
      spec.setSource(source);
    }
    source.setType("Git");
    GitBuildSource gitSource = source.getGit();
    if (gitSource == null) {
      gitSource = new GitBuildSource();
      source.setGit(gitSource);
    }
    gitSource.setUri(gitUrl);
    gitSource.setRef(ref);
  }

  public static void updateOpenShiftBuildPhase(Build build, String phase) {
    logger.log(FINE, "setting build to {0} in namespace {1}/{2}", new Object[]{phase, build.getMetadata().getNamespace(), build.getMetadata().getName()});
    getOpenShiftClient().builds().inNamespace(build.getMetadata().getNamespace()).withName(build.getMetadata().getName())
      .edit()
      .editStatus().withPhase(phase).endStatus()
      .done();
  }

  /**
   * Maps a Jenkins Job name to an ObjectShift BuildConfig name
   *
   * @return the namespaced name for the BuildConfig
   * @param jobName the job to associate to a BuildConfig name
   * @param namespace the default namespace that Jenkins is running inside
   */
  public static NamespaceName buildConfigNameFromJenkinsJobName(String jobName, String namespace) {
    // TODO lets detect the namespace separator in the jobName for cases where a jenkins is used for
    // BuildConfigs in multiple namespaces?
    return new NamespaceName(namespace, jobName);
  }

  public static long parseResourceVersion(HasMetadata obj) {
    return parseResourceVersion(obj.getMetadata().getResourceVersion());
  }

  public static long parseResourceVersion(String resourceVersion) {
    try {
      return Long.parseLong(resourceVersion);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  public static String formatTimestamp(long timestamp) {
    return dateFormatter.print(new DateTime(timestamp));
  }

  public static long parseTimestamp(String timestamp) {
    return dateFormatter.parseMillis(timestamp);
  }

  public static boolean isResourceWithoutStateEqual(HasMetadata oldObj, HasMetadata newObj) {
    try {
      byte[] oldDigest = MessageDigest.getInstance("MD5").digest(dumpWithoutRuntimeStateAsYaml(oldObj).getBytes(StandardCharsets.UTF_8));
      byte[] newDigest = MessageDigest.getInstance("MD5").digest(dumpWithoutRuntimeStateAsYaml(newObj).getBytes(StandardCharsets.UTF_8));
      return Arrays.equals(oldDigest, newDigest);
    } catch (NoSuchAlgorithmException | JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  public static String dumpWithoutRuntimeStateAsYaml(HasMetadata obj) throws JsonProcessingException {
    ObjectMapper statelessMapper = new ObjectMapper(new YAMLFactory());
    statelessMapper.addMixInAnnotations(ObjectMeta.class, ObjectMetaMixIn.class);
    statelessMapper.addMixInAnnotations(ReplicationController.class, StatelessReplicationControllerMixIn.class);
    return statelessMapper.writeValueAsString(obj);
  }

  public static boolean isCancellable(BuildStatus buildStatus) {
    String phase = buildStatus.getPhase();
    return phase.equals(NEW) || phase.equals(PENDING) || phase.equals(RUNNING);
  }

  public static boolean isNew(BuildStatus buildStatus) {
    return buildStatus.getPhase().equals(NEW);
  }

  public static boolean isCancelled(BuildStatus status) {
    return status != null && status.getCancelled() != null && Boolean.TRUE.equals(status.getCancelled());
  }

  /**
   * Lets convert the string to btw a valid kubernetes resource name
   */
  static String convertNameToValidResourceName(String text) {
    String lower = text.toLowerCase();
    StringBuilder builder = new StringBuilder();
    boolean started = false;
    char lastCh = ' ';
    for (int i = 0, last = lower.length() - 1; i <= last; i++) {
      char ch = lower.charAt(i);
      if (!(ch >= 'a' && ch <= 'z') && !(ch >= '0' && ch <= '9')) {
        if (ch == '/') {
          ch = '.';
        } else if (ch != '.' && ch != '-') {
          ch = '-';
        }
        if (!started || lastCh == '-' || lastCh == '.' || i == last) {
          continue;
        }
      }
      builder.append(ch);
      started = true;
      lastCh = ch;
    }
    return builder.toString();
  }

  public static String getAnnotation(HasMetadata resource, String name) {
    ObjectMeta metadata = resource.getMetadata();
    if (metadata != null) {
      Map<String, String> annotations = metadata.getAnnotations();
      if (annotations != null) {
        return annotations.get(name);
      }
    }
    return null;
  }


  public static void addAnnotation(HasMetadata resource, String name, String value) {
    ObjectMeta metadata = resource.getMetadata();
    if (metadata == null) {
      metadata = new ObjectMeta();
      resource.setMetadata(metadata);
    }
    Map<String, String> annotations = metadata.getAnnotations();
    if (annotations == null) {
      annotations = new HashMap<>();
      metadata.setAnnotations(annotations);
    }
    annotations.put(name, value);
  }

  public static void removeAnnotation(HasMetadata resource, String name) {
    ObjectMeta metadata = resource.getMetadata();
    if (metadata != null) {
      metadata = new ObjectMeta();
      Map<String, String> annotations = metadata.getAnnotations();
      if (annotations != null) {
        annotations.remove(name);
      }
    }
  }

  public static String getNamespace(HasMetadata resource) {
    ObjectMeta metadata = resource.getMetadata();
    if (metadata != null) {
      return metadata.getNamespace();
    }
    return null;
  }

  public static String getName(HasMetadata resource) {
    ObjectMeta metadata = resource.getMetadata();
    if (metadata != null) {
      return metadata.getName();
    }
    return null;
  }

  /**
   * Returns true if the given flagName is true in the ConfigMap data map
   */
  public static boolean isConfigMapFlagEnabled(ConfigMap configMap, String flagName) {
    Map<String, String> data = configMap.getData();
    boolean answer = false;
    if (data != null) {
      String flag = data.get(flagName);
      answer = flag != null && flag.equalsIgnoreCase("true");
    }
    return answer;
  }

  /**
   * Removes the BuildConfigProperty element from the given XML document
   */
  public static String removePropertiesFromXml(String xml) throws TransformerException, IOException, SAXException, ParserConfigurationException {
    Document doc = XmlUtils.parseDoc(xml);
    NodeList list = doc.getElementsByTagName("io.fabric8.jenkins.openshiftsync.BuildConfigProjectProperty");
    for (int i = 0, size = list.getLength(); i < size; i++) {
      Node item = list.item(i);
      XmlUtils.detach(item);
    }
    return XmlUtils.toXml(doc);
  }

  /**
   * Returns true if the config XML has changed ignoring the properties elements
   */
  public static boolean configXmlUpdated(String configXml, String oldConfigXml) {
    try {
      String xml1 = OpenShiftUtils.removePropertiesFromXml(configXml);
      String xml2 = OpenShiftUtils.removePropertiesFromXml(oldConfigXml);
      return !java.util.Objects.equals(xml1, xml2);
    } catch (Exception e) {
      logger.log(Level.WARNING, "Failed to remove the BuildConfigProperty from xml " + e, e);
      return true;
    }

  }


  abstract class StatelessReplicationControllerMixIn extends ReplicationController {
    @JsonIgnore
    private ReplicationControllerStatus status;

    StatelessReplicationControllerMixIn() {
    }

    @JsonIgnore
    public abstract ReplicationControllerStatus getStatus();
  }

  abstract class ObjectMetaMixIn extends ObjectMeta {
    @JsonIgnore
    private String creationTimestamp;
    @JsonIgnore
    private String deletionTimestamp;
    @JsonIgnore
    private Long generation;
    @JsonIgnore
    private String resourceVersion;
    @JsonIgnore
    private String selfLink;
    @JsonIgnore
    private String uid;

    ObjectMetaMixIn() {
    }

    @JsonIgnore
    public abstract String getCreationTimestamp();

    @JsonIgnore
    public abstract String getDeletionTimestamp();

    @JsonIgnore
    public abstract Long getGeneration();

    @JsonIgnore
    public abstract String getResourceVersion();

    @JsonIgnore
    public abstract String getSelfLink();

    @JsonIgnore
    public abstract String getUid();
  }
}
