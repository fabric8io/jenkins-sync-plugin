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
import jenkins.branch.BranchEventCause;
import jenkins.branch.BranchIndexingCause;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildSyncRunListener.joinPaths;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getJenkinsURL;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;

@Extension
public class BuildDecisionHandler extends Queue.QueueDecisionHandler {
  private final Logger logger = Logger.getLogger(getClass().getName());
  private String jenkinsNamespace = System.getenv("KUBERNETES_NAMESPACE");


  @Override
  public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
    if (p instanceof WorkflowJob) {
      WorkflowJob wj = (WorkflowJob) p;

      boolean triggerOpenShiftBuild = !isOpenShiftBuildCause(actions);
      if (triggerOpenShiftBuild && !isBranchEventCausePush(wj, actions) && isBranchCause(actions)) {
        if (wj.getFirstBuild() != null || wj.isBuilding() || wj.isInQueue() || wj.isBuildBlocked()) {
          // lets only trigger an OpenShift build if the build index cause
          // happens on projects not built yet - if its already been built or is building lets ignore

          // TODO we could filter the WorkflowJob to find only OpenShift enabled jobs?
          // or maybe use an annotation to enable triggering of jobs when the organisation rescans?
          return false;
        }
      }
      if (triggerOpenShiftBuild) {
        String namespace = new GlobalPluginConfiguration().getNamespace();
        String buildConfigName = JenkinsUtils.getBuildConfigName(wj);
        String jobName = OpenShiftUtils.convertNameToValidResourceName(buildConfigName);
        String jenkinsNS = namespace;
        if (jenkinsNamespace != null && !jenkinsNamespace.isEmpty()) {
          jenkinsNS = jenkinsNamespace;
        }
        String jobURL = joinPaths(getJenkinsURL(getOpenShiftClient(), jenkinsNS), wj.getUrl());

        OpenShiftClient openShiftClient = getOpenShiftClient();
        // if we have the build.openshift.io API Group but don't have S2I then we don't have the
        // OpenShift build subsystem so lets just default to regular Jenkins jobs
        if (!openShiftClient.supportsOpenShiftAPIGroup(OpenShiftAPIGroups.IMAGE)) {
          logger.info("Triggering Jenkins build on WorkflowJob " + wj.getFullName() + " due to running on kubernetes due to " + causeDescription(actions));
          return true;
        }
        logger.info("Triggering OpenShift Build for WorkflowJob " + wj.getFullName() + " due to " + causeDescription(actions));
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

  private String causeDescription(List<Action> actions) {
    List<String> causes = new ArrayList<>();
    for (Action action : actions) {
      if (action instanceof CauseAction) {
        CauseAction causeAction = (CauseAction) action;
        for (Cause cause : causeAction.getCauses()) {
          if (cause != null) {
            causes.add(cause.getClass().getName());
          }
        }
      }
    }
    return String.join(",", causes);
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
   * Returns true if this is a branch push event
   */
  private boolean isBranchEventCausePush(WorkflowJob wj, List<Action> actions) {
    for (Action action : actions) {
      if (action instanceof CauseAction) {
        CauseAction causeAction = (CauseAction) action;
        List<Cause> causes = causeAction.getCauses();
        for (Cause cause : causes) {
          if (cause instanceof BranchEventCause) {
            BranchEventCause branchEventCause = (BranchEventCause) cause;
            String description = branchEventCause.getShortDescription();
            logger.info("BranchEventCause on Pipeline " + wj.getFullName() + " for origin " + branchEventCause.getOrigin() +
              " timestamp: " + branchEventCause.getTimestamp() +
              " description: " + description);

            // for push events lets create a build
            if (description != null && description.toLowerCase().startsWith("push ")) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  /**
   * Returns true if this is the a branch indexing or random branch event cause
   */
  private boolean isBranchCause(List<Action> actions) {
    for (Action action : actions) {
      if (action instanceof CauseAction) {
        CauseAction causeAction = (CauseAction) action;
        List<Cause> causes = causeAction.getCauses();
        for (Cause cause : causes) {
          if (cause instanceof BranchEventCause || cause instanceof BranchIndexingCause) {
            return true;
          }
        }
      }
    }
    return false;
  }
}
