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
package io.fabric8.jenkins.openshiftsync.systest;

import com.offbytwo.jenkins.JenkinsServer;
import io.fabric8.jenkins.openshiftsync.systest.support.Environment;
import io.fabric8.jenkins.openshiftsync.systest.support.OpenShiftTestUtils;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigList;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.testing.jenkins.JenkinsAsserts;
import io.fabric8.utils.Strings;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import static io.fabric8.kubernetes.api.KubernetesHelper.getName;
import static io.fabric8.testing.jenkins.JenkinsAsserts.deleteAllCurrentJobs;
import static io.fabric8.testing.jenkins.JenkinsAsserts.numberOfJobs;
import static org.junit.Assert.assertEquals;

/**
 * Simple base system test which clears down the system
 */
public class Clear {
  private static final transient Logger LOG = LoggerFactory.getLogger(Clear.class);
  public static final String DEFAULT_JENKINS_URL = "http://localhost:8080/";
  public static final String DEFAULT_JENKINS_USER = "admin";
  public static final String DEFAULT_JENKINS_PASSWORD = "admin";

  protected OpenShiftClient openShiftClient;
  protected String namespace;
  protected JenkinsServer jenkins;
  protected String jenkinsURL;
  protected String jenkinsUsername;
  protected String jenkinsPasswordOrToken;

  @Before
  public void init() throws Exception {
    openShiftClient = new DefaultOpenShiftClient();
    namespace = Environment.getValue(Environment.KUBERNETES_NAMESPACE, openShiftClient.getNamespace());

    if (isClearOpenShiftOnInit()) {
      LOG.info("Clearing out namespace " + namespace);
      OpenShiftTestUtils.deleteBuildConfigsAndConfigMapJobs(openShiftClient, namespace);

      doSleep(2000);
    }

    jenkins = startJenkins();

    if (isClearJenkinsOnInit()) {
      LOG.info("Deleting all jenkins jobs");
      deleteAllCurrentJobs(jenkins);
      assertEquals("Number of Jenkins jobs", 0, numberOfJobs(jenkins));
    }
  }

  public void displayJobs() throws IOException {
    System.out.println();
    System.out.println("Jenkins jobs:");
    JenkinsAsserts.displayJobs(jenkins);
    System.out.println();
  }

  public void displayBuildConfigs() {
    System.out.println();
    System.out.println("BuildConfigs:");
    BuildConfigList list = openShiftClient.buildConfigs().inNamespace(namespace).list();
    List<BuildConfig> items = list.getItems();
    for (BuildConfig item : items) {
      System.out.println(JenkinsAsserts.INDENT + getName(item));
    }
    System.out.println();
  }

  public String getNamespace() {
    return namespace;
  }

  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  public JenkinsServer getJenkins() {
    return jenkins;
  }

  public void setJenkins(JenkinsServer jenkins) {
    this.jenkins = jenkins;
  }

  public String getJenkinsURL() {
    if (Strings.isNullOrBlank(jenkinsURL)) {
      jenkinsURL = Environment.getValue(Environment.JENKINS_URL, DEFAULT_JENKINS_URL);
    }
    return jenkinsURL;
  }

  public void setJenkinsURL(String jenkinsURL) {
    this.jenkinsURL = jenkinsURL;
  }

  public String getJenkinsUsername() {
    if (Strings.isNullOrBlank(jenkinsUsername)) {
      jenkinsUsername = Environment.getValue(Environment.JENKINS_USER, DEFAULT_JENKINS_USER);
    }
    return jenkinsUsername;
  }

  public void setJenkinsUsername(String jenkinsUsername) {
    this.jenkinsUsername = jenkinsUsername;
  }

  public String getJenkinsPasswordOrToken() {
    if (Strings.isNullOrBlank(jenkinsPasswordOrToken)) {
      jenkinsPasswordOrToken = Environment.getValue(Environment.JENKINS_PASSWORD, DEFAULT_JENKINS_PASSWORD);
    }
    return jenkinsPasswordOrToken;
  }

  public void setJenkinsPasswordOrToken(String jenkinsPasswordOrToken) {
    this.jenkinsPasswordOrToken = jenkinsPasswordOrToken;
  }

  protected boolean isClearJenkinsOnInit() {
    return true;
  }

  protected boolean isClearOpenShiftOnInit() {
    return true;
  }

  protected void doSleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      // ignore
    }
  }

  protected JenkinsServer startJenkins() throws URISyntaxException {
    String url = getJenkinsURL();
    String user = getJenkinsUsername();
    LOG.info("Using Jenkins at " + url + " with user " + user);
    return new JenkinsServer(new URI(url), user, getJenkinsPasswordOrToken());
  }

  @Test
  public void testRun() throws Exception {
    LOG.info("Cleared OpenShift and Jenkins!");
  }

  public void deleteBuildConfig(String bcName) {
    LOG.info("Deleting BuildConfig " + bcName + " in namespace " + namespace);
    openShiftClient.buildConfigs().inNamespace(namespace).withName(bcName).delete();
  }
}
