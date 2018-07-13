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
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildRequestBuilder;
import io.fabric8.openshift.client.OpenShiftAPIGroups;
import io.fabric8.openshift.client.OpenShiftClient;
import jenkins.branch.BranchEventCause;
import jenkins.branch.BranchIndexingCause;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

      // if this is a branch indexing scan don't trigger
      if (isJobBranchIndexing(wj, actions)){
        return false;
      }

      boolean triggerOpenShiftBuild = !isOpenShiftBuildCause(actions);
      OpenShiftClient openShiftClient = getOpenShiftClient();
      if (triggerOpenShiftBuild) {
        // if on kubernetes let's always trigger
        if (openShiftClient != null && !openShiftClient.supportsOpenShiftAPIGroup(OpenShiftAPIGroups.IMAGE)) {
          return true;
        }
        if (isBranchEventCausePush(wj, actions)) {
          if (isJobInQueue(wj)) {
            // lets only trigger an OpenShift build for this git push event if we don't have a build already queued
            return false;
          }
        } else {
          if (isBranchCause(actions)) {
            boolean isPRBuild = false;
            if (wj.getName() != null && wj.getName().startsWith("PR-")){
              isPRBuild = true;
            }
            if ((wj.getFirstBuild() != null && !isPRBuild)|| wj.isBuilding() || wj.isBuildBlocked() || isJobInQueue(wj)) {
              // lets only trigger an OpenShift build if the build index cause
              // happens on projects not built yet - if its already been built or is building lets ignore

              // TODO we could filter the WorkflowJob to find only OpenShift enabled jobs?
              // or maybe use an annotation to enable triggering of jobs when the organisation rescans?
              return false;
            }
          }
        }
      }
      if (triggerOpenShiftBuild) {
        String namespace = new GlobalPluginConfiguration().getNamespace();
        String jobName = JenkinsUtils.getBuildConfigName(wj);
        String buildConfigname = OpenShiftUtils.convertNameToValidResourceName(jobName);
        String jenkinsNS = namespace;
        if (jenkinsNamespace != null && !jenkinsNamespace.isEmpty()) {
          jenkinsNS = jenkinsNamespace;
        }

        // if we get this far and the sync plugin is disabled then trigger build
        if (openShiftClient == null){
          return true;
        }
        String jobURL = joinPaths(getJenkinsURL(openShiftClient, jenkinsNS), wj.getUrl());

        // if we have the build.openshift.io API Group but don't have S2I then we don't have the
        // OpenShift build subsystem so lets just default to regular Jenkins jobs
        if (!openShiftClient.supportsOpenShiftAPIGroup(OpenShiftAPIGroups.IMAGE)) {
          logger.info("Triggering Jenkins build on WorkflowJob " + wj.getFullName() + " due to running on kubernetes due to " + causeDescription(actions));
          return true;
        }
        logger.info("Triggering OpenShift Build for WorkflowJob " + wj.getFullName() + " due to " + causeDescription(actions));
        BuildConfig foundBuildConfig = OpenShiftUtils.findBCbyNameOrLabel(getOpenShiftClient(), namespace, buildConfigname);
        if (foundBuildConfig != null) {
          buildConfigname = foundBuildConfig.getMetadata().getName();
          triggerBuildRequest(buildConfigname, namespace, jobURL);
          return false;
        }
      }
    }
    return true;
  }

  private void triggerBuildRequest(String buildConfigname, String namespace, String jobURL) {
    getOpenShiftClient().buildConfigs()
      .inNamespace(namespace).withName(buildConfigname)
      .instantiate(
        new BuildRequestBuilder()
          .withNewMetadata().withName(buildConfigname).and()
          .addNewTriggeredBy().withMessage("Triggered by Jenkins job at " + jobURL).and()
          .build()
      );
  }

  private boolean isJobInQueue(WorkflowJob wj) {
    if (wj.isInQueue()) {
      return true;
    }
    Queue queue = Jenkins.getInstance().getQueue();
    if (queue.contains(wj)) {
      return true;
    }
    logger.info("Push request on Pipeline job " + wj.getFullName() + " and it is not in the build queue: " + queueSummary(queue));
    return false;
  }

  private String queueSummary(Queue queue) {
    try {
      List<String> names = new ArrayList<>();
      Queue.Item[] items = queue.getItems();
      if (items != null) {
        for (Queue.Item item : items) {
          String name = item.getDisplayName();
          Queue.Task task = item.task;
          if (task != null) {
            name += " " + task.getFullDisplayName();
          }
          names.add(name);
        }
      }
      return String.join(",", names);
    } catch (Exception e) {
      return queue.toString() + ". Caught: " + e;
    }
  }

  private void addBuildItemNames(Set<String> names, List<Queue.BuildableItem> items) {
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
   * Returns true if this is a organisation scan
   */
  private boolean isJobBranchIndexing(WorkflowJob wj, List<Action> actions) {
    for (Action action : actions) {
      if (action instanceof CauseAction) {
        CauseAction causeAction = (CauseAction) action;
        List<Cause> causes = causeAction.getCauses();
        for (Cause cause : causes) {
          if (cause instanceof BranchIndexingCause) {
            return true;
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
