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

import io.fabric8.kubernetes.api.model.RootPaths;
import io.fabric8.openshift.api.model.RouteBuilder;
import io.fabric8.openshift.api.model.RouteList;
import io.fabric8.openshift.api.model.RouteListBuilder;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.server.mock.OpenShiftMockServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.EnvironmentVariables;

import java.util.Arrays;

import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.joinPaths;
import static org.junit.Assert.assertEquals;

public class JenkinsUtilsTest {

  @Rule
  public final EnvironmentVariables environmentVariables = new EnvironmentVariables();

  @Test
  public void testRemoverPattern() throws Exception {
    assertRemovePattern("cheese", "cheese", "");
    assertRemovePattern("foo|cheese", "cheese", "foo");
    assertRemovePattern("cheese|foo", "cheese", "foo");
    assertRemovePattern("foo|cheese|bar", "cheese", "foo|bar");
  }

  private void assertRemovePattern(String pattern, String name, String expected) {
    String actual = JenkinsUtils.removePattern(pattern, name);
    assertEquals("remove pattern from `" + pattern + "` with name `" + name + "`", expected, actual);
  }

  @Test
  public void testJoinPaths() throws Exception {
    assertJoinPaths("http://localhost:8080/job/cheese/12/wfapi/describe", "http://localhost:8080/", "job/cheese/12/", "/wfapi/describe");
    assertJoinPaths("http://localhost:8080/job/cheese/12/wfapi/describe", "http://localhost:8080/", "/job/cheese/12/", "/wfapi/describe");
    assertJoinPaths("http://localhost:8080/job/cheese/12/wfapi/describe", "http://localhost:8080", "job/cheese/12/", "wfapi/describe");
    assertJoinPaths("http://localhost:8080/job/cheese/12/wfapi/describe", "http://localhost:8080", "job/cheese/12/", "wfapi/describe");
    assertJoinPaths("http://localhost:8080/job/cheese/12/wfapi/describe", "http://localhost:8080", "//job/cheese/12/", "wfapi/describe");
  }

  private void assertJoinPaths(String expected, String... strings) {
    String actual = joinPaths(strings);
    assertEquals("Join strings: " + Arrays.asList(strings), expected, actual);
  }

  @Test
  public void should_get_hostname_from_env_var() {
    // given
    OpenShiftClient openShiftClient = new DefaultOpenShiftClient();
    environmentVariables.set(JenkinsUtils.JENKINS_ROOT_URL_ENV_VAR, "http://proxy.openshift.io");

    // when
    String url = JenkinsUtils.getHostName(openShiftClient, "default");

    //then
    assertEquals("http://proxy.openshift.io", url);
  }

  @Test
  public void should_get_hostname_from_openshift_service() {
    // given
    RouteList routeList = new RouteListBuilder()
      .addToItems(
        new RouteBuilder()
          .withNewSpec()
          .withNewTo()
          .withKind("Service")
          .withName("jenkins")
          .endTo()
          .withHost("jenkins.openshift.io")
          .endSpec()
          .build())
      .build();
    OpenShiftMockServer openShiftServer = mockOpenshiftJenkinsRoutes(routeList);

    // when
    String url = JenkinsUtils.getHostName(openShiftServer.createOpenShiftClient(), "default");

    // then
    assertEquals("http://jenkins.openshift.io", url);
  }

  private OpenShiftMockServer mockOpenshiftJenkinsRoutes(RouteList routeList) {
    OpenShiftMockServer openShiftServer = new OpenShiftMockServer(false);
    RootPaths rootPaths = new RootPaths();
    rootPaths.getPaths().add("/oapi");
    openShiftServer.expect().get().withPath("/").andReturn(200, rootPaths).always();
    openShiftServer.expect().get().withPath("/oapi/v1/namespaces/default/routes").andReturn(200, routeList).always();
    return openShiftServer;
  }

}
