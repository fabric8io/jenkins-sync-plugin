/**
 * Copyright (C) 2016 Red Hat, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.jenkins.openshiftsync.systest.support;

import io.fabric8.kubernetes.api.KubernetesHelper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.utils.Asserts;
import io.fabric8.utils.Block;
import io.fabric8.utils.XmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;


/**
 */
public class OpenShiftTestUtils {
  private static final transient Logger LOG = LoggerFactory.getLogger(OpenShiftTestUtils.class);

  public static void main(String[] args) {
    OpenShiftClient openShiftClient = new DefaultOpenShiftClient();
    String namespace = openShiftClient.getNamespace();
    deleteBuildConfigsAndConfigMapJobs(openShiftClient, namespace);
  }

  public static void deleteBuildConfigsAndConfigMapJobs(OpenShiftClient openShiftClient, String namespace) {
    LOG.info("Deleting BuildConfigs in namespace " + namespace);
    openShiftClient.buildConfigs().inNamespace(namespace).delete();

    LOG.info("Deleting BuildConfigs in namespace " + namespace);
    openShiftClient.builds().inNamespace(namespace).delete();

    LOG.info("Deleting Jenkins Job ConfigMaps  in namespace " + namespace);
    openShiftClient.configMaps().inNamespace(namespace).withLabel("openshift.io/jenkins", "job").delete();
  }

  public static int applyResources(OpenShiftClient openShiftClient, String namespace, File dir) {
    int count = 0;
    File[] files = dir.listFiles();
    if (files != null) {
      for (File file : files) {
        String name = file.getName();
        String lower = name.toLowerCase();
        if (file.isFile() && lower.endsWith(".yml")) {
          FileInputStream is = null;
          try {
            is = new FileInputStream(file);
          } catch (FileNotFoundException e) {
            LOG.warn("Failed to open file " + file + ". " + e, e);
            continue;
          }
          List<HasMetadata> list = openShiftClient.load(is).createOrReplace();
          for (HasMetadata resource : list) {
            LOG.info("applied: " + resource.getClass().getSimpleName() + " " + KubernetesHelper.getName(resource));
          }
          count++;
        }
      }
    }
    return count;
  }

  public static void assertBuildConfigExists(OpenShiftClient openShiftClient, String namespace, String bcName) {
    BuildConfig buildConfig = openShiftClient.buildConfigs().inNamespace(namespace).withName(bcName).get();
    assertTrue("BuildConfig " + bcName + " should exist in namespace " + namespace, buildConfig != null);
  }

  public static void assertWaitForBuildConfigNotExist(OpenShiftClient openShiftClient, String namespace, String bcName, long timeMillis) throws Exception {
    LOG.info("Waiting for BuildConfig " + bcName + " to not not exist in namespace " + namespace);
    Asserts.assertWaitFor(timeMillis, new Block() {
      @Override
      public void invoke() throws Exception {
        assertBuildConfigNotExist(openShiftClient, namespace, bcName);
      }
    });
  }

  public static void assertBuildConfigNotExist(OpenShiftClient openShiftClient, String namespace, String bcName) {
    BuildConfig buildConfig = openShiftClient.buildConfigs().inNamespace(namespace).withName(bcName).get();
    assertTrue("BuildConfig " + bcName + " should not exist in namespace " + namespace, buildConfig == null);
  }

  public static Document assertConfigXmlFromConfigMapJob(OpenShiftClient openShiftClient, String namespace, String name) {
    String value = assertConfigMapDataEntry(openShiftClient, namespace, name, "config.xml");
    try {
      return XmlUtils.parseDoc(value);
    } catch (Exception e) {
      throw new AssertionError("Failed to parse config.xml key in ConfigMap " + name + " in namespace " + namespace);
    }
  }

  public static String assertConfigMapDataEntry(OpenShiftClient openShiftClient, String namespace, String name, String key) {
    ConfigMap configMap = assertConfigMapExists(openShiftClient, namespace, name);
    Map<String, String> data = configMap.getData();
    assertTrue("No data found for ConfigMap " + name + " namespace " + namespace, data != null);
    String value = data.get(key);
    assertTrue("No data for key " + key + " found in ConfigMap " + name + " namespace " + namespace, data != null);
    return value;
  }

  private static ConfigMap assertConfigMapExists(OpenShiftClient openShiftClient, String namespace, String name) {
    ConfigMap answer = openShiftClient.configMaps().inNamespace(namespace).withName(name).get();
    assertTrue("No ConfigMap " + name + " found in namespace " + namespace, answer != null);
    return answer;
  }

  public static void assertUpdateConfigMapDataKey(OpenShiftClient openShiftClient, String namespace, String name, String key, String value) {
    try {
      openShiftClient.configMaps().inNamespace(namespace).withName(name).edit().addToData(key, value).done();
    } catch (Exception e) {
      throw new AssertionError("Failed to update ConfigMap " + name + " in namespace " + namespace + " with key " + key + " value " + value + " due to " + e, e);
    }
  }
}
