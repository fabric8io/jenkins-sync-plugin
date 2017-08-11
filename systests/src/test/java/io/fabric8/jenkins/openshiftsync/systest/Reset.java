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

import io.fabric8.testing.jenkins.JenkinsAsserts;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sets the system up for the beginning of the system test but does nothing afterwards
 */
public class Reset extends SyncIT {
  private static final transient Logger LOG = LoggerFactory.getLogger(Reset.class);

  @Test
  public void testRun() throws Exception {
    LOG.info("System is reset!");
  }
}
