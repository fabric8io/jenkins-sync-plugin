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

import io.fabric8.arquillian.kubernetes.Session;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.assertj.core.api.Condition;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.junit.Ignore;
import org.junit.runner.RunWith;

import static io.fabric8.kubernetes.assertions.Assertions.assertThat;

@RunWith(Arquillian.class)
public class JenkinsOpenShiftKT {

  @ArquillianResource
  KubernetesClient client;

  @ArquillianResource
  Session session;

  @Ignore
  public void testAppProvisionsRunningPods() throws Exception {

    installInitialBuildConfigs();

    assertThat(client).pods()
        .runningStatus()
        .filterNamespace(session.getNamespace())
        .haveAtLeast(1, new Condition<Pod>() {
          @Override
          public boolean matches(Pod podSchema) {
            return true;
          }
        });

    System.out.println("Now asserting that we have Jenkins Jobs!");
  }

  protected void installInitialBuildConfigs() {
    System.out.println("Starting BuildConfigs");
  }
}
