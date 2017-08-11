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
public class SystemProperties {
  public static final String WAIT_FOR_USER_PROMPT = "waitForUserPrompt";
  public static final String DELETE_KIND = "deleteKind";

  public static boolean isPropertyFlag(String systemPropertyName) {
    return isPropertyFlag(systemPropertyName, false);
  }

  public static boolean isPropertyFlag(String systemPropertyName, boolean defaultValue) {
    String value = System.getProperty(systemPropertyName);
    if (value != null) {
      return value.equalsIgnoreCase("true");
    }
    return defaultValue;
  }


  public static <T extends Enum> T enumPropertyFlag(String systemPropertyName, Class<T> clazz, T defaultValue) {
    String value = System.getProperty(systemPropertyName);
    if (value != null && !value.isEmpty()) {
      return (T) Enum.valueOf(clazz, value);
    }
    return defaultValue;
  }
}
