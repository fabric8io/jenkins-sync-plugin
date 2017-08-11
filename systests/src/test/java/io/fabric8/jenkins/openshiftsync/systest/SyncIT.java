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

import com.offbytwo.jenkins.model.Build;
import com.offbytwo.jenkins.model.BuildWithDetails;
import com.offbytwo.jenkins.model.JobWithDetails;
import com.offbytwo.jenkins.model.QueueReference;
import io.fabric8.jenkins.openshiftsync.systest.support.JenkinsConfigXmlAssertions;
import io.fabric8.jenkins.openshiftsync.systest.support.JobDeleteKind;
import io.fabric8.jenkins.openshiftsync.systest.support.OpenShiftTestUtils;
import io.fabric8.jenkins.openshiftsync.systest.support.SystemProperties;
import io.fabric8.testing.jenkins.JenkinsAsserts;
import io.fabric8.utils.DomHelper;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import javax.xml.transform.TransformerException;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.util.Set;

import static io.fabric8.jenkins.openshiftsync.systest.support.JenkinsConfigXmlAssertions.assertHasGithubNavigatorPattern;
import static io.fabric8.testing.jenkins.JenkinsAsserts.assertTriggerJobPath;
import static io.fabric8.testing.jenkins.JenkinsAsserts.assertWaitForNoRunningBuilds;
import static net.sf.ezmorph.test.ArrayAssertions.assertEquals;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.Assert.assertTrue;

/**
 * A system test to run on the current available openshift/kubernetes cluster
 * against the jenkins service pointed to via the kubernetes style environment variables.
 * <br>
 * Uses <code>KUBERNETES_NAMESPACE</code> as the namespace to use and <code></code>
 */
public class SyncIT extends Clear {
  private static final transient Logger LOG = LoggerFactory.getLogger(SyncIT.class);

  protected String githubOrgJobName = "jstrachan";
  protected String bcName1 = "cheese140";
  protected String bcName2 = "cheese142";
  protected String bcName3 = "cheese149";
  protected String branchName = "master";

  protected long waitForBuildTimeout = 100000L;
  protected long waitForBuildConfigDeleteTimeout = 20000L;

  public static void assertJobNotInOrganisationPattern(Document doc, String orgJob, String jobName, String description) {
    String pattern = assertHasGithubNavigatorPattern(doc, description);
    LOG.info("Found github organisation pattern: " + pattern);

    Set<String> patterns = JenkinsConfigXmlAssertions.patternSet(pattern);
    assertTrue("Should not contain " + jobName + " in the github org pattern set " + patterns, !patterns.contains(jobName));
  }

  public static void assertJobsInOrganisationPattern(Document doc, String[] jobNames, String description) {
    String pattern = assertHasGithubNavigatorPattern(doc, description);
    LOG.info("Found github organisation pattern: " + pattern);

    Set<String> patterns = JenkinsConfigXmlAssertions.patternSet(pattern);
    for (String jobName : jobNames) {
      assertTrue("Should contain " + jobName + " in the github org pattern set " + patterns, patterns.contains(jobName));
    }
  }

  @Before
  public void init() throws Exception {
    super.init();

    String basedir = System.getProperty("basedir", ".");
    File sysTestResourceDir = new File(basedir, "src/test/resources/systest");
    assertTrue("Folder " + sysTestResourceDir + " is not a directory!", sysTestResourceDir.exists() && sysTestResourceDir.isDirectory());


    LOG.info("Applying resource to namespace " + namespace);
    int resourceCount = OpenShiftTestUtils.applyResources(openShiftClient, namespace, sysTestResourceDir);
    assertTrue("Should have found at least one resource", resourceCount > 0);

    JenkinsAsserts.assertWaitForJobPathExists(jenkins, waitForBuildTimeout, githubOrgJobName, bcName1, branchName);
    JenkinsAsserts.assertWaitForJobPathExists(jenkins, waitForBuildTimeout, githubOrgJobName, bcName2, branchName);
    JenkinsAsserts.assertWaitForJobPathExists(jenkins, waitForBuildTimeout, githubOrgJobName, bcName3, branchName);

    assertJobsInOrganisationPattern(githubOrgJobName, bcName1, bcName2, bcName3);

    displayJobs();
    displayBuildConfigs();
  }

  @Test
  public void testRun() throws Exception {
    assertWaitForNoRunningBuilds(jenkins, waitForBuildTimeout);
    waitForTestToStart();

    LOG.info("Lets delete BuildConfigs while jenkins is up");

    String job1lastBuildID = assertGetLastBuildID(bcName1);
    String job3lastBuildID = assertGetLastBuildID(bcName3);

    String bcName = bcName2;
    deleteBuildConfig(bcName);

    assertBuildNotExist(bcName);
    assertBuildsExist(bcName1, bcName3);
    displayBuildConfigs();
    displayJobs();

    assertLastBuildID(bcName1, job1lastBuildID);
    assertLastBuildID(bcName3, job3lastBuildID);

    LOG.info("Lets trigger an Organisation Scan");
    QueueReference queueReference = assertTriggerJobPath(jenkins, githubOrgJobName);
    assertWaitForNoRunningBuilds(jenkins, waitForBuildTimeout);

    assertLastBuildID(bcName1, job1lastBuildID);
    assertLastBuildID(bcName3, job3lastBuildID);


    bcName = bcName1;
    deletePatternFromOrganisationJob(bcName);

    assertBuildNotExist(bcName);
    assertBuildsExist(bcName3);
    displayBuildConfigs();
    displayJobs();

    assertLastBuildID(bcName3, job3lastBuildID);

    bcName = bcName3;
    deletePatternFromOrganisationJob(bcName);

    assertBuildNotExist(bcName);
    displayBuildConfigs();
    displayJobs();

  }

  public void deleteJobWithKind(String bcName) {
    JobDeleteKind deleteKind = JobDeleteKind.getValue();
    LOG.info("Deleting job " + bcName + " using strategy: " + deleteKind);

    switch (deleteKind) {
      case DeleteBuildConfig:
        deleteBuildConfig(bcName);
        return;
      case DeletePatternFromConfigMapJob:
        deletePatternFromConfigMapJob(bcName);
        return;
      case DeletePatternFromOrganisationJob:
        deletePatternFromOrganisationJob(bcName);
        return;
      default:
        fail("Unknown Delete kind: " + deleteKind);
    }
  }

  protected void assertLastBuildID(String buildName, String expectedBuildID) throws IOException {
    String id = assertGetLastBuildID(buildName);
    assertEquals("Job " + buildName + " last build ID", expectedBuildID, id);
  }

  public String assertGetLastBuildID(String buildName) throws IOException {
    JobWithDetails jobDetails = JenkinsAsserts.assertJobPathExists(jenkins, githubOrgJobName, buildName, branchName);
    Build lastBuild = jobDetails.getLastBuild();
    assertTrue("Job " + buildName + " should have a last build!", lastBuild != null);
    BuildWithDetails details = lastBuild.details();
    assertTrue("Job " + buildName + " last build should have have details!", details != null);
    return details.getId();
  }

  protected void waitForTestToStart() {
    if (SystemProperties.isPropertyFlag(SystemProperties.WAIT_FOR_USER_PROMPT)) {
      System.out.println();
      System.out.println("Press [ENTER] to continue the test:");
      Console c = System.console();
      c.readLine();
    } else {
      doSleep(2000);
    }
  }

  public void assertBuildNotExist(String bcName) throws Exception {
    JenkinsAsserts.assertWaitForJobPathNotExist(jenkins, waitForBuildTimeout, githubOrgJobName, bcName, "master");
    assertJobNotInOrganisationPattern(githubOrgJobName, bcName);
    JenkinsAsserts.assertWaitForJobPathNotExist(jenkins, waitForBuildTimeout, githubOrgJobName, bcName);
    OpenShiftTestUtils.assertWaitForBuildConfigNotExist(openShiftClient, namespace, bcName, waitForBuildConfigDeleteTimeout);
  }

  protected void assertBuildsExist(String... buildNames) throws IOException {
    for (String buildName : buildNames) {
      JenkinsAsserts.assertJobPathExists(jenkins, githubOrgJobName, buildName, branchName);
      OpenShiftTestUtils.assertBuildConfigExists(openShiftClient, namespace, buildName);
    }
  }

  protected void deletePatternFromOrganisationJob(String bcName) {
    LOG.info("Deleting Job " + bcName + " by updating the pattern in Organisation Job " + githubOrgJobName);
    Document doc = JenkinsAsserts.assertJobXmlDocument(jenkins, githubOrgJobName);
    JenkinsConfigXmlAssertions.assertRemovePattern(doc, bcName, "Org Job config.xml for " + githubOrgJobName);
    assertUpdateJob(bcName, doc);

  }

  protected void deletePatternFromConfigMapJob(String bcName) {
    LOG.info("Deleting Job " + bcName + " by updating the pattern in the ConfigMap Organisation Job " + githubOrgJobName);
    Document doc = OpenShiftTestUtils.assertConfigXmlFromConfigMapJob(openShiftClient, namespace, githubOrgJobName);
    JenkinsConfigXmlAssertions.assertRemovePattern(doc, bcName, "ConfigMap " + githubOrgJobName + " config.xml");
    String xml = assertToXml(doc, "config.xml for job " + bcName);
    OpenShiftTestUtils.assertUpdateConfigMapDataKey(openShiftClient, namespace, githubOrgJobName, "config.xml", xml);
  }

  protected void assertUpdateJob(String jobName, Document doc) {
    String xml = assertToXml(doc, "config.xml for job " + jobName);
    try {
      jenkins.updateJob(githubOrgJobName, xml);
    } catch (IOException e) {
      throw new AssertionError("Failed to update XML of job " + jobName + " due to " + e, e);
    }
  }

  private String assertToXml(Document doc, String description) {
    try {
      return DomHelper.toXml(doc);
    } catch (TransformerException e) {
      throw new AssertionError("Failed to convert " + description + " to XML due to " + e, e);
    }
  }

  public void assertJobsInOrganisationPattern(String orgJob, String... jobNames) {
    Document doc = JenkinsAsserts.assertJobXmlDocument(jenkins, orgJob);
    assertJobsInOrganisationPattern(doc, jobNames, "job XML for " + orgJob);

    doc = OpenShiftTestUtils.assertConfigXmlFromConfigMapJob(openShiftClient, namespace, orgJob);
    assertJobsInOrganisationPattern(doc, jobNames, "config.xml from ConfigMap job " + orgJob + " in namespace " + namespace);
  }

  public void assertJobNotInOrganisationPattern(String orgJob, String jobName) {
    Document doc = JenkinsAsserts.assertJobXmlDocument(jenkins, orgJob);
    assertJobNotInOrganisationPattern(doc, orgJob, jobName, "job XML for " + orgJob);

    doc = OpenShiftTestUtils.assertConfigXmlFromConfigMapJob(openShiftClient, namespace, orgJob);
    assertJobNotInOrganisationPattern(doc, orgJob, jobName, "config.xml from ConfigMap job " + orgJob + " in namespace " + namespace);
  }


}
