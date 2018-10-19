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

import com.google.inject.Guice;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.fabric8.openshift.api.model.Build;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.maybeScheduleNext;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.util.logging.Level.WARNING;

/**
 * Listens to Jenkins Job build lifecycle like start, stop, abort, delete then ensure there's a suitable {@link Build} object in
 * OpenShift that's updated correctly with the Job {@link Run} status
 */

@Extension
public class BuildLifeCycleListener extends RunListener<Run> {
  private static final Logger logger = Logger.getLogger(BuildLifeCycleListener.class.getName());

  private transient BuildStateUpdater stateUpdater;
  private String namespace;

  public BuildLifeCycleListener() {
    this.stateUpdater = BuildStateUpdater.getInstance();
    this.namespace = OpenShiftUtils.getNamespaceOrUseDefault(namespace, getOpenShiftClient());
  }

  @Override
  public synchronized void onStarted(Run run, TaskListener listener) {
    if (isOpenshiftBuild(run)) {
      setBuildDescription(run);
      stateUpdater.update(run, namespace);
    }
    super.onStarted(run, listener);
  }

  private void setBuildDescription(Run run) {
    try {
      BuildCause cause = (BuildCause) run.getCause(BuildCause.class);
      if (cause != null) {
        run.setDescription(cause.getShortDescription());
      }
    } catch (IOException e) {
      logger.log(WARNING, "Cannot set build description: " + e);
    }
  }

  @Override
  public synchronized void onCompleted(Run run, @Nonnull TaskListener listener) {
    if (isOpenshiftBuild(run)) {
      stateUpdater.update(run, namespace);
      logger.info("onCompleted " + run.getUrl());
      maybeScheduleNext(((WorkflowRun) run).getParent());
    }
    super.onCompleted(run, listener);
  }

  @Override
  public synchronized void onDeleted(Run run) {
    if (isOpenshiftBuild(run)) {
      stateUpdater.update(run, namespace);
      logger.info("onDeleted " + run.getUrl());
      maybeScheduleNext(((WorkflowRun) run).getParent());
    }
    super.onDeleted(run);
  }

  @Override
  public synchronized void onFinalized(Run run) {
    if (isOpenshiftBuild(run)) {
      stateUpdater.update(run, namespace);
      logger.info("onFinalized " + run.getUrl());
    }
    super.onFinalized(run);
  }

  /**
   * Returns true if we should poll the status of this run
   *
   * @param run the Run to test against
   * @return true if the should poll the status of this build run
   */
  protected boolean isOpenshiftBuild(Run run) {
    return run instanceof WorkflowRun && run.getCause(BuildCause.class) != null;
  }
}
