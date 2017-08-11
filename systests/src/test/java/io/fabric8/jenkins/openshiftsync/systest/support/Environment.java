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

/**
 */
public class Environment {
  public static final String KUBERNETES_NAMESPACE = "KUBERNETES_NAMESPACE";
  public static final String JENKINS_URL = "JENKINS_URL";
  public static final String JENKINS_USER = "JENKINS_USER";
  public static final String JENKINS_PASSWORD = "JENKINS_PASSWORD";


  public static String getValue(String name, String defaultValue) {
    String answer = System.getenv(name);
    if (answer == null || answer.length() == 0) {
      answer = defaultValue;
    }
    return answer;
  }
}
