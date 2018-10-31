package io.fabric8.jenkins.openshiftsync;

import com.cloudbees.workflow.rest.external.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.Result;
import hudson.model.Run;
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
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.Constants.*;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.getHostName;
import static io.fabric8.jenkins.openshiftsync.JenkinsUtils.joinPaths;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.formatTimestamp;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.SEVERE;

/**
 * updates the build resources status in openshift for a given
 * jenkins run resource.
 */

public class BuildStateUpdater {

  private static BuildStateUpdater instance;
  private final transient ExecutorService threadExecutor;

  private BuildStateUpdater() {
    this.threadExecutor = Executors.newSingleThreadExecutor();
  }

  public static BuildStateUpdater getInstance() {
    if(instance == null) {
      createInstance();
    }

    return instance;
  }

  private static void createInstance() {
    synchronized (BuildStateUpdater.class) {
      if(instance == null) {
        instance = new BuildStateUpdater();
      }
    }
  }

  public void update(Run run, String namespace) {
    threadExecutor.submit(new BuildState(namespace, run));
  }

  class BuildState implements Callable<Boolean> {

    private final Logger logger = Logger.getLogger(BuildState.class.getName());

    private final String buildNamespace;
    private final Run workflowRun;
    private final OpenShiftClient openShiftClient;

    public BuildState(final String namespace, final Run run) {
      this.buildNamespace = namespace;
      this.workflowRun = run;
      this.openShiftClient = getOpenShiftClient();
    }

    @Override
    public Boolean call() throws Exception {
      upsertBuild(workflowRun, RunExt.create((WorkflowRun) workflowRun));
      return true;
    }

    private void upsertBuild(Run run, RunExt wfRunExt) {
      if (run == null) {
        return;
      }

      BuildCause cause = (BuildCause) run.getCause(BuildCause.class);
      if (cause == null) {
        return;
      }

      String buildConfigName = null;
      String rootUrl = getHostName(openShiftClient, buildNamespace);
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
              config.setNamespace(buildNamespace);
            }
            resource.createOrReplace(build);
          }
        } catch (KubernetesClientException e) {
          if (HTTP_NOT_FOUND == e.getCode()) {
            logger.warning("build resource not found : " + e.getMessage());
          } else {
            throw e;
          }
        }
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

  }
}
