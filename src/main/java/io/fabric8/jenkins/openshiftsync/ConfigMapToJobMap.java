package io.fabric8.jenkins.openshiftsync;

import hudson.model.Job;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import jenkins.model.Jenkins;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class ConfigMapToJobMap {

  private static Map<String, Job> configMapToJobMap;
  private static Map<String, BuildConfigProjectProperty> uuidToProperty;

  private ConfigMapToJobMap() {
  }

  static synchronized void initializeConfigMapToJobMap() {
    if (configMapToJobMap == null) {
      List<Job> jobs = Jenkins.getActiveInstance().getAllItems(Job.class);
      int size = jobs.size();
      configMapToJobMap = new ConcurrentHashMap<>(size);
      uuidToProperty = new ConcurrentHashMap<>(size);
      for (Job job : jobs) {
        BuildConfigProjectProperty configMapProjectProperty = BuildConfigProjectProperty.getProperty(job);
        if (configMapProjectProperty == null) {
          continue;
        }
        String bcUid = configMapProjectProperty.getUid();
        if (isNotBlank(bcUid)) {
          configMapToJobMap.put(bcUid, job);
          uuidToProperty.put(bcUid, configMapProjectProperty);
        }
      }
    }
  }

  static synchronized Job getJobFromConfigMap(ConfigMap configMap) {
    ObjectMeta meta = configMap.getMetadata();
    if (meta == null) {
      return null;
    }
    return getJobFromConfigMapUid(meta.getUid());
  }

  static synchronized Job getJobFromConfigMapUid(String uid) {
    if (isBlank(uid)) {
      return null;
    }
    return configMapToJobMap.get(uid);
  }

  static synchronized void putJobWithConfigMap(Job job, ConfigMap configMap) {
    if (configMap == null) {
      throw new IllegalArgumentException("ConfigMap cannot be null");
    }
    if (job == null) {
      throw new IllegalArgumentException("Job cannot be null");
    }
    ObjectMeta meta = configMap.getMetadata();
    if (meta == null) {
      throw new IllegalArgumentException("ConfigMap must contain valid metadata");
    }
    putJobWithConfigMapUid(job, meta.getUid());
  }

  static synchronized void putJobWithConfigMapUid(Job job, String uid) {
    if (isBlank(uid)) {
      throw new IllegalArgumentException("ConfigMap uid must not be blank");
    }
    configMapToJobMap.put(uid, job);
  }

  static synchronized void removeJobWithConfigMap(ConfigMap configMap) {
    if (configMap == null) {
      throw new IllegalArgumentException("ConfigMap cannot be null");
    }
    ObjectMeta meta = configMap.getMetadata();
    if (meta == null) {
      throw new IllegalArgumentException("ConfigMap must contain valid metadata");
    }
    removeJobWithConfigMapUid(meta.getUid());
  }

  static synchronized void removeJobWithConfigMapUid(String uid) {
    if (isBlank(uid)) {
      throw new IllegalArgumentException("ConfigMap uid must not be blank");
    }
    configMapToJobMap.remove(uid);
    uuidToProperty.remove(uid);
  }

  /**
   * Returns the property for the given job using the old cached Job if there is no longer a property
   * on the Job (such as if the Job has been editted in the Jenkins UI)
   */
  public static BuildConfigProjectProperty getOrFindProperty(Job job) {
    BuildConfigProjectProperty answer = BuildConfigProjectProperty.getProperty(job);
    if (answer == null) {
      /// we must have just edited the Job via the Jenkins UI so lets try find the old Job to find the old proeprty
      String fullName = job.getFullName();
      Set<Map.Entry<String, Job>> entries = configMapToJobMap.entrySet();
      for (Map.Entry<String, Job> entry : entries) {
        String uid = entry.getKey();
        Job item = entry.getValue();
        if (fullName.equals(item.getFullName())) {
          answer = BuildConfigProjectProperty.getProperty(item);
          if (answer == null) {
            answer = uuidToProperty.get(uid);
          }
          break;
        }
      }
    }
    return answer;
  }

  public static void putBuildConfigProjectProperty(BuildConfigProjectProperty property) {
    String uid = property.getUid();
    if (uid != null) {
      uuidToProperty.put(uid, property);
    }
  }
}
