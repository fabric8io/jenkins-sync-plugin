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

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Queue;
import io.fabric8.openshift.api.model.BuildRequestBuilder;
import io.fabric8.openshift.client.OpenShiftAPIGroups;
import io.fabric8.openshift.client.OpenShiftClient;
import jenkins.branch.BranchIndexingCause;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.List;

import static io.fabric8.jenkins.openshiftsync.BuildSyncRunListener.joinPaths;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getJenkinsURL;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;

@Extension
public class BuildDecisionHandler extends Queue.QueueDecisionHandler {

  @Override
  public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
    if (p instanceof WorkflowJob) {
      WorkflowJob wj = (WorkflowJob) p;

      boolean triggerOpenShiftBuild = !isOpenShiftBuildCause(actions);
      if (triggerOpenShiftBuild && isBranchIndexingCause(actions)) {
        if (wj.getFirstBuild() != null) {
          // lets only trigger an OpenShift build if the build index cause
          // happens on projects not built yet - if its already been built lets ignore
          triggerOpenShiftBuild = false;
        }
      }
      if (triggerOpenShiftBuild) {
        String namespace = new GlobalPluginConfiguration().getNamespace();
        String buildConfigName = JenkinsUtils.getBuildConfigName(wj);
        String jobName = OpenShiftUtils.convertNameToValidResourceName(buildConfigName);
        String jobURL = joinPaths(getJenkinsURL(getOpenShiftClient(), namespace), wj.getUrl());

        OpenShiftClient openShiftClient = getOpenShiftClient();
        // if we have the build.openshift.io API Group but don't have S2I then we don't have the
        // OpenShift build subsystem so lets just default to regular Jenkins jobs
        if (!openShiftClient.supportsOpenShiftAPIGroup(OpenShiftAPIGroups.IMAGE)) {
          return true;
        }
        openShiftClient.buildConfigs()
          .inNamespace(namespace).withName(jobName)
          .instantiate(
            new BuildRequestBuilder()
              .withNewMetadata().withName(jobName).and()
              .addNewTriggeredBy().withMessage("Triggered by Jenkins job at " + jobURL).and()
              .build()
          );
        return false;
      }
    }
    return true;
  }

  private boolean isOpenShiftBuildCause(List<Action> actions) {
    for (Action action : actions) {
      if (action instanceof CauseAction) {
        CauseAction causeAction = (CauseAction) action;
        for (Cause cause : causeAction.getCauses()) {
          if (cause instanceof BuildCause) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Returns true if this is the af branch indexing cause
   */
  private boolean isBranchIndexingCause(List<Action> actions) {
    for (Action action : actions) {
      if (action instanceof CauseAction) {
        CauseAction causeAction = (CauseAction) action;
        for (Cause cause : causeAction.getCauses()) {
          if (cause instanceof BranchIndexingCause) {
            return true;
          }
        }
      }
    }
    return false;
  }

}
