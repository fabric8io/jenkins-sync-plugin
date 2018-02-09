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

import com.cloudbees.workflow.rest.external.AtomFlowNodeExt;
import com.cloudbees.workflow.rest.external.FlowNodeExt;
import com.cloudbees.workflow.rest.external.PendingInputActionsExt;
import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StageNodeExt;
import com.cloudbees.workflow.rest.external.StatusExt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildBuilder;
import io.fabric8.openshift.api.model.BuildStatus;
import io.fabric8.openshift.api.model.DoneableBuild;
import io.fabric8.openshift.client.OpenShiftAPIGroups;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.dsl.BuildResource;
import jenkins.util.Timer;
import org.apache.commons.httpclient.HttpStatus;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_BUILD_CONFIG_NAME;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_BUILD_NUMBER;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_BUILD_URI;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_LOG_URL;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_NAMESPACE;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_PENDING_INPUT_ACTION_JSON;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_JENKINS_STATUS_JSON;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.getBuildConfigName;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.maybeScheduleNext;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.formatTimestamp;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/**
 * Listens to Jenkins Job build {@link Run} start and stop then ensure there's a suitable {@link Build} object in
 * OpenShift thats updated correctly with the current status, logsURL and metrics
 */
@Extension
public class BuildSyncRunListener extends RunListener<Run> {
  private static final Logger logger = Logger.getLogger(BuildSyncRunListener.class.getName());
  public static final String JENKINS_ROOT_URL_ENV_VAR = "JENKINS_ROOT_URL";

  private long pollPeriodDelayMs = 1000;
  private long pollPeriodMs = 1000*10; // default polling frequency is 10 seconds, initially it was 1 second
  private String namespace;

  private transient Set<Run> runsToPoll = new CopyOnWriteArraySet<>();

  private transient AtomicBoolean timerStarted = new AtomicBoolean(false);
  private Boolean kubernetesCluster;

  public BuildSyncRunListener() {
    init();
  }

  @DataBoundConstructor
  public BuildSyncRunListener(long pollPeriodMs) {
    this.pollPeriodMs = pollPeriodMs;
    init();
  }

  /**
   * Joins all the given strings, ignoring nulls so that they form a URL with / between the paths without a // if the
   * previous path ends with / and the next path starts with / unless a path item is blank
   *
   * @param strings the sequence of strings to join
   * @return the strings concatenated together with / while avoiding a double // between non blank strings.
   */
  public static String joinPaths(String... strings) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < strings.length; i++) {
      sb.append(strings[i]);
      if (i < strings.length - 1) {
        sb.append("/");
      }
    }
    String joined = sb.toString();

    // And normalize it...
    return joined
      .replaceAll("/+", "/")
      .replaceAll("/\\?", "?")
      .replaceAll("/#", "#")
      .replaceAll(":/", "://");
  }

  private void init() {
    namespace = OpenShiftUtils.getNamespaceOrUseDefault(namespace, getOpenShiftClient());
  }

  @Override
  public synchronized void onStarted(Run run, TaskListener listener) {
    if (shouldPollRun(run)) {
      try {
        BuildCause cause = (BuildCause) run.getCause(BuildCause.class);
        if (cause != null) {
          // TODO This should be a link to the OpenShift console.
          run.setDescription(cause.getShortDescription());
        }
      } catch (IOException e) {
        logger.log(WARNING, "Cannot set build description: " + e);
      }
      if (runsToPoll.add(run)) {
        logger.info("starting polling build " + run.getUrl());
      }
      checkTimerStarted();
    } else {
      logger.fine("not polling polling build " + run.getUrl() + " as its not a WorkflowJob");
    }
    super.onStarted(run, listener);
  }

  protected void checkTimerStarted() {
    if (timerStarted.compareAndSet(false, true)) {
      Runnable task = new SafeTimerTask() {
        @Override
        protected void doRun() throws Exception {
          pollLoop();
        }
      };
      Timer.get().scheduleAtFixedRate(task, pollPeriodDelayMs, pollPeriodMs, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public synchronized void onCompleted(Run run, @Nonnull TaskListener listener) {
    if (shouldPollRun(run)) {
      runsToPoll.remove(run);
      pollRun(run);
      logger.info("onCompleted " + run.getUrl());
      maybeScheduleNext(((WorkflowRun) run).getParent());
    }
    super.onCompleted(run, listener);
  }

  @Override
  public synchronized void onDeleted(Run run) {
    if (shouldPollRun(run)) {
      runsToPoll.remove(run);
      pollRun(run);
      logger.info("onDeleted " + run.getUrl());
      maybeScheduleNext(((WorkflowRun) run).getParent());
    }
    super.onDeleted(run);
  }

  @Override
  public synchronized void onFinalized(Run run) {
    if (shouldPollRun(run)) {
      runsToPoll.remove(run);
      pollRun(run);
      logger.info("onFinalized " + run.getUrl());
    }
    super.onFinalized(run);
  }

  protected synchronized void pollLoop() {
    for (Run run : runsToPoll) {
      pollRun(run);
    }
  }

  protected synchronized void pollRun(Run run) {
    if (!(run instanceof WorkflowRun)) {
      throw new IllegalStateException("Cannot poll a non-workflow run");
    }

    RunExt wfRunExt = RunExt.create((WorkflowRun) run);

    try {
      upsertBuild(run, wfRunExt);
    } catch (KubernetesClientException e) {
      if (e.getCode() == HttpStatus.SC_UNPROCESSABLE_ENTITY) {
        runsToPoll.remove(run);
        logger.log(WARNING, "Cannot update status: {0}", e.getMessage());
        return;
      }
      throw e;
    }
  }

  private void upsertBuild(Run run, RunExt wfRunExt) {
    if (run == null) {
      return;
    }

    String buildConfigName = null;
    String buildConfigNamespace = this.namespace;
    BuildCause cause = (BuildCause) run.getCause(BuildCause.class);
    if (isKubernetesCluster()) {
      if (cause == null) {
        Job parent = run.getParent();
        if (parent instanceof WorkflowJob) {
          WorkflowJob workflowJob = (WorkflowJob) parent;
          String gitUrl = null;
          String commit = null;
          String buildConfigUid = null;
          String uid = null;
          BuildConfigProjectProperty property = BuildConfigProjectProperty.getProperty(workflowJob);
          if (property != null) {
            buildConfigName = property.getName();
            buildConfigNamespace = property.getNamespace();
          } else {
            buildConfigName = getBuildConfigName(workflowJob);
          }
          String buildName = buildConfigName + "-" + run.getId();
          cause = new BuildCause(uid, buildConfigNamespace, buildName, gitUrl, commit, buildConfigUid);
        } else {
          return;
        }
      }
    } else if (cause == null) {
      return;
    }

    OpenShiftClient openShiftClient = getOpenShiftClient();
    String rootUrl = getHostName(openShiftClient);
    String buildUrl = joinPaths(rootUrl, run.getUrl());
    String logsUrl = joinPaths(buildUrl, "/consoleText");

    boolean pendingInput = false;
    if (!wfRunExt.get_links().self.href.matches("^https?://.*$")) {
      wfRunExt.get_links().self.setHref(joinPaths(rootUrl, wfRunExt.get_links().self.href));
    }
    for (StageNodeExt stage : wfRunExt.getStages()) {
      FlowNodeExt.FlowNodeLinks links = stage.get_links();
      if (!links.self.href.matches("^https?://.*$")) {
        links.self.setHref(joinPaths(rootUrl, links.self.href));
      }
      if (links.getLog() != null && !links.getLog().href.matches("^https?://.*$")) {
        links.getLog().setHref(joinPaths(rootUrl, links.getLog().href));
      }
      for (AtomFlowNodeExt node : stage.getStageFlowNodes()) {
        FlowNodeExt.FlowNodeLinks nodeLinks = node.get_links();
        if (!nodeLinks.self.href.matches("^https?://.*$")) {
          nodeLinks.self.setHref(joinPaths(rootUrl, nodeLinks.self.href));
        }
        if (nodeLinks.getLog() != null && !nodeLinks.getLog().href.matches("^https?://.*$")) {
          nodeLinks.getLog().setHref(joinPaths(rootUrl, nodeLinks.getLog().href));
        }
      }
      StatusExt status = stage.getStatus();
      if (status != null && status.equals(StatusExt.PAUSED_PENDING_INPUT)) {
        pendingInput = true;
      }
    }

    String json;
    try {
      json = new ObjectMapper().writeValueAsString(wfRunExt);
    } catch (JsonProcessingException e) {
      logger.log(SEVERE, "Failed to serialize workflow run. " + e, e);
      return;
    }

    String pendingActionsJson = null;
    if (pendingInput && run instanceof WorkflowRun) {
      pendingActionsJson = getPendingActionsJson((WorkflowRun) run);
    }
    String phase = runToBuildPhase(run);

    long started = getStartTime(run);
    String startTime = null;
    String completionTime = null;
    if (started > 0) {
      startTime = formatTimestamp(started);

      long duration = getDuration(run);
      if (duration > 0) {
        completionTime = formatTimestamp(started + duration);
      }
    }

    // check for null as sync plugin may be disabled
    if (openShiftClient != null) {
      String name = cause.getName();
      logger.log(FINE, "Patching build {0}/{1}: setting phase to {2}", new Object[]{cause.getNamespace(), name, phase});
      try {
        boolean isS2ICluster = openShiftClient.supportsOpenShiftAPIGroup(OpenShiftAPIGroups.IMAGE);
        Map<String, String> addAnnotations = new HashMap<>();
        addAnnotations.put(OPENSHIFT_ANNOTATIONS_JENKINS_STATUS_JSON, json);
        addAnnotations.put(OPENSHIFT_ANNOTATIONS_JENKINS_BUILD_URI, buildUrl);
        addAnnotations.put(OPENSHIFT_ANNOTATIONS_JENKINS_LOG_URL, logsUrl);

        String jenkinsNamespace = System.getenv("KUBERNETES_NAMESPACE");
        if (jenkinsNamespace != null && !jenkinsNamespace.isEmpty()) {
          addAnnotations.put(OPENSHIFT_ANNOTATIONS_JENKINS_NAMESPACE, jenkinsNamespace);
        }
        if (pendingActionsJson != null && !pendingActionsJson.isEmpty()) {
          addAnnotations.put(OPENSHIFT_ANNOTATIONS_JENKINS_PENDING_INPUT_ACTION_JSON, pendingActionsJson);
        }

        BuildResource<Build, DoneableBuild, String, LogWatch> resource = openShiftClient.builds().inNamespace(cause.getNamespace()).withName(name);
        if (isS2ICluster) {
          resource.edit()
            .editMetadata().addToAnnotations(addAnnotations).endMetadata()
            .editOrNewStatus()
            .withPhase(phase)
            .withStartTimestamp(startTime)
            .withCompletionTimestamp(completionTime)
            .endStatus()
            .done();
        } else {
          Build build = resource.get();
          if (build == null) {
            build = new BuildBuilder().withNewMetadata().withName(name).endMetadata().build();
          }
          OpenShiftUtils.addAnnotation(build, OPENSHIFT_ANNOTATIONS_BUILD_NUMBER, "" + run.getNumber());
          ObjectMeta metadata = build.getMetadata();
          Map<String, String> annotations = metadata.getAnnotations();
          annotations.putAll(addAnnotations);

          Map<String, String> labels = metadata.getLabels();
          if (labels == null) {
            labels = new HashMap<>();
            metadata.setLabels(labels);
          }

          if (buildConfigName != null) {
            annotations.put(OPENSHIFT_ANNOTATIONS_BUILD_CONFIG_NAME, buildConfigName);
            labels.put(OPENSHIFT_ANNOTATIONS_BUILD_CONFIG_NAME, buildConfigName);
          } else {
            logger.warning("No BuildConfigName for build " + name);
          }

          BuildStatus status = build.getStatus();
          if (status == null) {
            status = new BuildStatus();
            build.setStatus(status);
          }
          status.setPhase(phase);
          status.setStartTimestamp(startTime);
          status.setCompletionTimestamp(completionTime);
          if (buildConfigName != null && !buildConfigName.isEmpty()) {
            ObjectReference config = status.getConfig();
            if (config == null) {
              config = new ObjectReference();
              status.setConfig(config);
            }
            config.setKind("BuildConfig");
            config.setName(buildConfigName);
            config.setNamespace(buildConfigNamespace);
          }
          resource.createOrReplace(build);
        }
      } catch (KubernetesClientException e) {
        if (HTTP_NOT_FOUND == e.getCode()) {
          runsToPoll.remove(run);
        } else {
          throw e;
        }
      }
    }
  }

  /*Fix for jenkins proxy/idler introduction to get the right URL for console*/
  private String getHostName(OpenShiftClient openShiftClient) {
    String rootUrlFromEnvVar = System.getenv(JENKINS_ROOT_URL_ENV_VAR);
    if(rootUrlFromEnvVar != null) {
      return rootUrlFromEnvVar.trim();
    } else {
      return OpenShiftUtils.getJenkinsURL(openShiftClient, namespace);
    }
  }

  private String getPendingActionsJson(WorkflowRun run) {
    List<PendingInputActionsExt> pendingInputActions = new ArrayList<PendingInputActionsExt>();
    InputAction inputAction = run.getAction(InputAction.class);

    if (inputAction != null) {
      List<InputStepExecution> executions = inputAction.getExecutions();
      if (executions != null && !executions.isEmpty()) {
        for (InputStepExecution inputStepExecution : executions) {
          pendingInputActions.add(PendingInputActionsExt.create(inputStepExecution, run));
        }
      }
    }
    try {
      return new ObjectMapper().writeValueAsString(pendingInputActions);
    } catch (JsonProcessingException e) {
      logger.log(SEVERE, "Failed to serialize pending actions. " + e, e);
      return null;
    }
  }

  private long getStartTime(Run run) {
    return run.getStartTimeInMillis();
  }

  private long getDuration(Run run) {
    return run.getDuration();
  }

  private String runToBuildPhase(Run run) {
    if (run != null && !run.hasntStartedYet()) {
      if (run.isBuilding()) {
        return BuildPhases.RUNNING;
      } else {
        Result result = run.getResult();
        if (result != null) {
          if (result.equals(Result.SUCCESS)) {
            return BuildPhases.COMPLETE;
          } else if (result.equals(Result.ABORTED)) {
            return BuildPhases.CANCELLED;
          } else if (result.equals(Result.FAILURE)) {
            return BuildPhases.FAILED;
          } else if (result.equals(Result.UNSTABLE)) {
            return BuildPhases.FAILED;
          } else {
            return BuildPhases.PENDING;
          }
        }
      }
    }
    return BuildPhases.NEW;
  }

  /**
   * Returns true if we should poll the status of this run
   *
   * @param run the Run to test against
   * @return true if the should poll the status of this build run
   */
  protected boolean shouldPollRun(Run run) {
    return run instanceof WorkflowRun && (run.getCause(BuildCause.class) != null || isKubernetesCluster());
  }

  private boolean isKubernetesCluster() {
    if (kubernetesCluster == null) {
      if (getOpenShiftClient() == null){
        kubernetesCluster = true;
      } else {
        kubernetesCluster = !getOpenShiftClient().supportsOpenShiftAPIGroup(OpenShiftAPIGroups.IMAGE);
      }
    }
    return kubernetesCluster;
  }
}
