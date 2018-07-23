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

import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;
import io.fabric8.openshift.api.model.BuildConfigListBuilder;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.server.mock.OpenShiftServer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_BUILD_CONFIG_GIT_REPOSITORY_NAME;

public class OpenShiftUtilsTest {

  @Rule
  public OpenShiftServer server = new OpenShiftServer();

  @Before
  public void init() {

    BuildConfig buildConfig1 = new BuildConfigBuilder()
      .withNewMetadata()
      .withName("test1")
      .withNamespace("test")
      .addToAnnotations(Annotations.GENERATED_BY, "jenkins")
      .addToLabels(OPENSHIFT_LABELS_BUILD_CONFIG_GIT_REPOSITORY_NAME,"testRepo")
      .endMetadata()
      .withNewSpec()
      .withNewStrategy()
      .withType("JenkinsPipeline")
      .withNewJenkinsPipelineStrategy()
      .endJenkinsPipelineStrategy()
      .endStrategy()
      .endSpec()
      .build();

    BuildConfig buildConfig2 = new BuildConfigBuilder()
      .withNewMetadata()
      .withName("test2")
      .withNamespace("test")
      .addToAnnotations(Annotations.GENERATED_BY, "jenkins")
      .addToLabels(OPENSHIFT_LABELS_BUILD_CONFIG_GIT_REPOSITORY_NAME,"test2")
      .endMetadata()
      .withNewSpec()
      .withNewStrategy()
      .withType("JenkinsPipeline")
      .withNewJenkinsPipelineStrategy()
      .endJenkinsPipelineStrategy()
      .endStrategy()
      .endSpec()
      .build();

    server.expect().withPath("/oapi/v1/namespaces/test/buildconfigs/test2").andReturn(200, buildConfig2).once();
    server.expect().withPath("/oapi/v1/namespaces/test/buildconfigs/testRepo").andReturn(200, null).once();
    server.expect().withPath("/oapi/v1/namespaces/test/buildconfigs/test3").andReturn(200, null).once();
    server.expect().withPath("/oapi/v1/namespaces/test/buildconfigs?labelSelector="
      + OPENSHIFT_LABELS_BUILD_CONFIG_GIT_REPOSITORY_NAME + "%3DtestRepo")
      .andReturn( 200, new BuildConfigListBuilder().addToItems(0, buildConfig1).build()).once();

    server.expect().withPath("/oapi/v1/namespaces/test/buildconfigs?labelSelector="
      + OPENSHIFT_LABELS_BUILD_CONFIG_GIT_REPOSITORY_NAME + "%3Dtest3")
      .andReturn( 200, new BuildConfigListBuilder().build()).once();

  }

  @Test
  public void getBuildConfig() throws Exception {

    OpenShiftClient client = server.getOpenshiftClient();

    BuildConfig buildConfig = OpenShiftUtils.findBCbyNameOrLabel(client,"test","testRepo");
    Assert.assertEquals("test1",buildConfig.getMetadata().getName());

    buildConfig = OpenShiftUtils.findBCbyNameOrLabel(client,"test","test2");
    Assert.assertEquals("test2",buildConfig.getMetadata().getName());

    buildConfig = OpenShiftUtils.findBCbyNameOrLabel(client,"test","test3");
    Assert.assertNull(buildConfig);
  }

}