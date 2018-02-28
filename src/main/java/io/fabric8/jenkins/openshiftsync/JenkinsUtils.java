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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Action;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Queue;
import hudson.model.StringParameterDefinition;
import hudson.model.StringParameterValue;
import hudson.model.TopLevelItem;
import hudson.plugins.git.RevisionParameterAction;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildBuilder;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.api.model.GitBuildSource;
import io.fabric8.openshift.api.model.GitSourceRevision;
import io.fabric8.openshift.api.model.JenkinsPipelineBuildStrategy;
import io.fabric8.openshift.api.model.SourceRevision;
import io.fabric8.openshift.client.OpenShiftAPIGroups;
import io.fabric8.openshift.client.OpenShiftClient;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;

import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.BuildConfigToJobMap.getJobFromBuildConfig;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.CANCELLED;
import static io.fabric8.jenkins.openshiftsync.BuildPhases.PENDING;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL;
import static io.fabric8.jenkins.openshiftsync.BuildRunPolicy.SERIAL_LATEST_ONLY;
import static io.fabric8.jenkins.openshiftsync.BuildWatcher.buildAdded;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_ANNOTATIONS_BUILD_NUMBER;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_BUILD_STATUS_FIELD;
import static io.fabric8.jenkins.openshiftsync.Constants.OPENSHIFT_LABELS_BUILD_CONFIG_NAME;
import static io.fabric8.jenkins.openshiftsync.CredentialsUtils.updateSourceCredentials;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.isCancelled;
import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.updateOpenShiftBuildPhase;
import static java.util.Collections.sort;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static org.apache.commons.lang.StringUtils.isBlank;

/**
 */
public class JenkinsUtils {

  private static final Logger LOGGER = Logger.getLogger(JenkinsUtils.class.getName());

  public static Job getJob(String job) {
    TopLevelItem item = Jenkins.getActiveInstance().getItem(job);
    if (item instanceof Job) {
      return (Job) item;
    }
    return null;
  }

  public static String getRootUrl() {
    // TODO is there a better place to find this?
    String root = Jenkins.getActiveInstance().getRootUrl();
    if (root == null || root.length() == 0) {
      root = "http://localhost:8080/";
    }
    return root;
  }
  
  public static void addJobParamForBuildEnvs(WorkflowJob job, JenkinsPipelineBuildStrategy strat, boolean replaceExisting) throws IOException {
      List<EnvVar> envs = strat.getEnv();
      if (envs.size() > 0) {
          // get existing property defs, including any manually added from the jenkins console independent of BC
          ParametersDefinitionProperty params = job.removeProperty(ParametersDefinitionProperty.class);
          Map<String, ParameterDefinition> paramMap = new HashMap<String, ParameterDefinition>();
          // store any existing parameters in map for easy key lookup
          if (params != null) {
              List<ParameterDefinition> existingParamList = params.getParameterDefinitions();
              for (ParameterDefinition param : existingParamList) {
                  paramMap.put(param.getName(), param);
              }
          }
          for (EnvVar env : envs) {
              if (replaceExisting) {
                  StringParameterDefinition envVar = new StringParameterDefinition(env.getName(), env.getValue());
                  paramMap.put(env.getName(), envVar);
              } else if (!paramMap.containsKey(env.getName())) {
                  // if list from BC did not have this parameter, it was added via `oc start-build -e` ... in this 
                  // case, we have chosen to make the default value an empty string
                  StringParameterDefinition envVar = new StringParameterDefinition(env.getName(), "");
                  paramMap.put(env.getName(), envVar);
              }
          }
          List<ParameterDefinition> newParamList = new ArrayList<ParameterDefinition>(paramMap.values());
          job.addProperty(new ParametersDefinitionProperty(newParamList));
      }
  }
  
  public static List<Action> setJobRunParamsFromEnv(JenkinsPipelineBuildStrategy strat, List<Action> buildActions) {
      List<EnvVar> envs = strat.getEnv();
      if (envs.size() > 0) {
          List<ParameterValue> envVarList = new ArrayList<ParameterValue>();
          for (EnvVar env : envs) {
              StringParameterValue envVar = new StringParameterValue(env.getName(),env.getValue());
              envVarList.add(envVar);
          }
          buildActions.add(new ParametersAction(envVarList));
      }

      return buildActions;
  }

  public static boolean triggerJob(WorkflowJob job, Build build) throws IOException {
    if (isAlreadyTriggered(job, build)) {
      return false;
    }

    String buildConfigName = build.getStatus().getConfig().getName();
    if (isBlank(buildConfigName)) {
      return false;
    }

    BuildConfigProjectProperty bcProp = job.getProperty(BuildConfigProjectProperty.class);
    if (bcProp == null) {
      return false;
    }

    switch (bcProp.getBuildRunPolicy()) {
      case SERIAL_LATEST_ONLY:
        cancelQueuedBuilds(job, bcProp.getUid());
        if (job.isBuilding()) {
          return false;
        }
        break;
      case SERIAL:
        if (job.isInQueue() || job.isBuilding()) {
            return false;
        }
        break;
      default:
    }

    ObjectMeta meta = build.getMetadata();
    String namespace = meta.getNamespace();
    BuildConfig buildConfig = getOpenShiftClient().buildConfigs().inNamespace(namespace).withName(buildConfigName).get();
    if (buildConfig == null) {
      return false;
    }

    updateSourceCredentials(buildConfig);

    List<Action> buildActions = new ArrayList<Action>();
    buildActions.add(new CauseAction(new BuildCause(build, bcProp.getUid())));

    GitBuildSource gitBuildSource = build.getSpec().getSource().getGit();
    SourceRevision sourceRevision = build.getSpec().getRevision();

    if (gitBuildSource != null && sourceRevision != null) {
      GitSourceRevision gitSourceRevision = sourceRevision.getGit();
      if (gitSourceRevision != null) {
        try {
          URIish repoURL = new URIish(gitBuildSource.getUri());
          buildActions.add(new RevisionParameterAction(gitSourceRevision.getCommit(), repoURL));
        } catch (URISyntaxException e) {
          LOGGER.log(SEVERE, "Failed to parse git repo URL" + gitBuildSource.getUri(), e);
        }
      }
    }
    
    // grab envs from actual build in case user overrode default values via `oc start-build -e`
    JenkinsPipelineBuildStrategy strat = build.getSpec().getStrategy().getJenkinsPipelineStrategy();
    // only add new param defs for build envs which are not in build config envs
    addJobParamForBuildEnvs(job, strat, false);
    // now add the actual param values stemming from openshift build env vars for this specific job
    buildActions = setJobRunParamsFromEnv(strat, buildActions);

    if (job.scheduleBuild2(0, buildActions.toArray(new Action[buildActions.size()])) != null) {
      updateOpenShiftBuildPhase(build, PENDING);
      // If builds are queued too quickly, Jenkins can add the cause to the previous queued build so let's add a tiny
      // sleep.
      try {
        Thread.sleep(50l);
      } catch (InterruptedException e) {
        // Ignore
      }
      return true;
    }

    return false;
  }

  private static boolean isAlreadyTriggered(WorkflowJob job, Build build) {
    return getRun(job, build) != null;
  }

  public synchronized static void cancelBuild(WorkflowJob job, Build build) {
    if (!cancelQueuedBuild(job, build)) {
      cancelRunningBuild(job, build);
    }
    try {
      updateOpenShiftBuildPhase(build, CANCELLED);
    } catch (Exception e) {
      throw e;
    }
  }

  private static WorkflowRun getRun(WorkflowJob job, Build build) {
    if (build != null && build.getMetadata() != null) {
      return getRun(job, build.getMetadata().getUid());
    }
    return null;
  }

  private static WorkflowRun getRun(WorkflowJob job, String buildUid) {
    for (WorkflowRun run : job.getBuilds()) {
      BuildCause cause = run.getCause(BuildCause.class);
      if (cause != null && cause.getUid().equals(buildUid)) {
        return run;
      }
    }
    return null;
  }

  private static boolean cancelRunningBuild(WorkflowJob job, Build build) {
    String buildUid = build.getMetadata().getUid();
    WorkflowRun run = getRun(job, buildUid);
    if (run != null && run.isBuilding()) {
      terminateRun(run);
      return true;
    }
    return false;
  }

  private static boolean cancelNotYetStartedBuild(WorkflowJob job, Build build) {
    String buildUid = build.getMetadata().getUid();
    WorkflowRun run = getRun(job, buildUid);
    if (run != null && run.hasntStartedYet()) {
      terminateRun(run);
      return true;
    }
    return false;
  }

  private static void cancelNotYetStartedBuilds(WorkflowJob job, String bcUid) {
    cancelQueuedBuilds(job, bcUid);
    for (WorkflowRun run : job.getBuilds()) {
      if (run != null && run.hasntStartedYet()) {
        BuildCause cause = run.getCause(BuildCause.class);
        if (cause != null && cause.getBuildConfigUid().equals(bcUid)) {
          terminateRun(run);
        }
      }
    }
  }

  private static void terminateRun(final WorkflowRun run) {
    ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, RuntimeException>() {
      @Override
      public Void call() throws RuntimeException {
        run.doTerm();
        Timer.get().schedule(new SafeTimerTask() {
          @Override
          public void doRun() {
            ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, RuntimeException>() {
              @Override
              public Void call() throws RuntimeException {
                run.doKill();
                return null;
              }
            });
          }
        }, 5, TimeUnit.SECONDS);
        return null;
      }
    });
  }

  @SuppressFBWarnings("SE_BAD_FIELD")
  public static boolean cancelQueuedBuild(WorkflowJob job, Build build) {
    String buildUid = build.getMetadata().getUid();
    final Queue buildQueue = Jenkins.getActiveInstance().getQueue();
    for (final Queue.Item item : buildQueue.getItems()) {
      for (Cause cause : item.getCauses()) {
        if (cause instanceof BuildCause && ((BuildCause) cause).getUid().equals(buildUid)) {
          return ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Boolean, RuntimeException>() {
            @Override
            public Boolean call() throws RuntimeException {
              buildQueue.cancel(item);
              return true;
            }
          });
        }
      }
    }
    return cancelNotYetStartedBuild(job, build);
  }

  public static void cancelQueuedBuilds(WorkflowJob job, String bcUid) {
    Queue buildQueue = Jenkins.getActiveInstance().getQueue();
    for (Queue.Item item : buildQueue.getItems()) {
      for (Cause cause : item.getCauses()) {
        if (cause instanceof BuildCause) {
          BuildCause buildCause = (BuildCause) cause;
          if (buildCause.getBuildConfigUid().equals(bcUid)) {
            Build build = new BuildBuilder()
              .withNewMetadata()
              .withNamespace(buildCause.getNamespace())
              .withName(buildCause.getName())
              .and().build();
            cancelQueuedBuild(job, build);
          }
        }
      }
    }
  }

  public static WorkflowJob getJobFromBuild(Build build) {
    String buildConfigName = build.getStatus().getConfig().getName();
    if (StringUtils.isEmpty(buildConfigName)) {
      return null;
    }
    BuildConfig buildConfig = getOpenShiftClient().buildConfigs().inNamespace(build.getMetadata().getNamespace()).withName(buildConfigName).get();
    if (buildConfig == null) {
      return null;
    }
    return getJobFromBuildConfig(buildConfig);
  }

  public static void maybeScheduleNext(WorkflowJob job) {
    BuildConfigProjectProperty bcp = job.getProperty(BuildConfigProjectProperty.class);
    if (bcp == null) {
      return;
    }
    OpenShiftClient openShiftClient = getOpenShiftClient();
    if (openShiftClient != null){
      FilterWatchListDeletable<Build, BuildList, Boolean, Watch, Watcher<Build>> resource = openShiftClient.builds().
      inNamespace(bcp.getNamespace()).withLabel(OPENSHIFT_LABELS_BUILD_CONFIG_NAME, bcp.getName());
      // we can't use field filters on the new API Groups API
      if (openShiftClient.supportsApiPath("/oapi") && !openShiftClient.supportsOpenShiftAPIGroup(OpenShiftAPIGroups.IMAGE)) {
      resource = resource.withField(OPENSHIFT_BUILD_STATUS_FIELD, BuildPhases.NEW);
      }
      List<Build> builds = resource.list().getItems();
      handleBuildList(job, builds, bcp);
    }
  }

  public static void handleBuildList(WorkflowJob job, List<Build> builds, BuildConfigProjectProperty buildConfigProjectProperty) {
    if (builds.isEmpty()) {
      return;
    }
    boolean isSerialLatestOnly = SERIAL_LATEST_ONLY.equals(buildConfigProjectProperty.getBuildRunPolicy());
    if (isSerialLatestOnly) {
      // Try to cancel any builds that haven't actually started, waiting for executor perhaps.
      cancelNotYetStartedBuilds(job, buildConfigProjectProperty.getUid());
    }
    sort(builds, new Comparator<Build>() {
      @Override
      public int compare(Build b1, Build b2) {
        // Order so cancellations are first in list so we can stop processing build list when build run policy is
        // SerialLatestOnly and job is currently building.
        Boolean b1Cancelled = b1.getStatus() != null && b1.getStatus().getCancelled() != null ?
          b1.getStatus().getCancelled() : false;
        Boolean b2Cancelled = b2.getStatus() != null && b2.getStatus().getCancelled() != null ?
          b2.getStatus().getCancelled() : false;
        // Inverse comparison as boolean comparison would put false before true. Could have inverted both cancellation
        // states but this removes that step.
        int cancellationCompare = b2Cancelled.compareTo(b1Cancelled);
        if (cancellationCompare != 0) {
          return cancellationCompare;
        }

        return Long.compare(
          Long.parseLong(b1.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER)),
          Long.parseLong(b2.getMetadata().getAnnotations().get(OPENSHIFT_ANNOTATIONS_BUILD_NUMBER))
        );
      }
    });
    boolean isSerial = SERIAL.equals(buildConfigProjectProperty.getBuildRunPolicy());
    boolean jobIsBuilding = job.isBuilding();
    for (int i = 0; i < builds.size(); i++) {
      Build b = builds.get(i);
      // For SerialLatestOnly we should try to cancel all builds before the latest one requested.
      if (isSerialLatestOnly) {
        // If the job is currently building, then let's return on the first non-cancellation request so we do not try to
        // queue a new build.
        if (jobIsBuilding && !isCancelled(b.getStatus())) {
          return;
        }

        if (i < builds.size() - 1) {
          cancelQueuedBuild(job, b);
          updateOpenShiftBuildPhase(b, CANCELLED);
          continue;
        }
      }
      boolean buildAdded = false;
      try {
        buildAdded = buildAdded(b);
      } catch (IOException e) {
        ObjectMeta meta = b.getMetadata();
        LOGGER.log(WARNING, "Failed to add new build " + meta.getNamespace() + "/" + meta.getName(), e);
      }
      // If it's a serial build then we only need to schedule the first build request.
      if (isSerial && buildAdded) {
        return;
      }
    }
  }

  public static String getFullJobName(WorkflowJob job) {
    return job.getRelativeNameFrom(Jenkins.getInstance());
  }

  public static String getBuildConfigName(WorkflowJob job) {
    String name = getFullJobName(job);
    GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
    String[] paths = name.split("/");
    if (paths.length > 1) {
      String orgName = paths[0];
      if (StringUtils.isNotBlank(orgName)) {
        if (config != null) {
          String skipOrganisationPrefix = config.getSkipOrganisationPrefix();
          if (StringUtils.isEmpty(skipOrganisationPrefix)) {
            config.setSkipOrganisationPrefix(orgName);
            skipOrganisationPrefix = config.getSkipOrganisationPrefix();
          }

          // if the default organisation lets strip the organisation name from the prefix
          int prefixLength = orgName.length() + 1;
          if (orgName.equals(skipOrganisationPrefix) && name.length() > prefixLength) {
            name = name.substring(prefixLength);
          }
        }
      }
    }

      // lets avoid the .master postfixes as we treat master as the default branch name
    String masterSuffix = "/master";
    if (config != null) {
      String skipBranchSuffix = config.getSkipBranchSuffix();
      if (StringUtils.isEmpty(skipBranchSuffix)) {
        config.setSkipBranchSuffix("master");
        skipBranchSuffix = config.getSkipBranchSuffix();
      }
      masterSuffix = "/" + skipBranchSuffix;
    }
    if (name.endsWith(masterSuffix) && name.length() > masterSuffix.length()) {
      name = name.substring(0, name.length() - masterSuffix.length());
    }

    return name;
  }

  /**
   * Removes the given text value from a pattern separated by <code>|</code> symbols when using something like
   * the pattern expressions on the github organsiation plugin
   *
   * @param pattern the pattern expression which could be a single value or multiple values separated by <code>|</code>
   * @param name the name of a project to be removed if its contained in the pattern
   * @return the new pattern or null if it could not be found
   */
  public static String removePattern(String pattern, String name) {
    if (pattern.equals(name)) {
      pattern = "";
    } else {
      String prefix = name + "|";
      String suffix = "|" + name;
      String middle = "|" + name + "|";
      if (pattern.startsWith(prefix)) {
        pattern = pattern.substring(prefix.length());
      } else if (pattern.endsWith(suffix)) {
        pattern = pattern.substring(0, pattern.length() - suffix.length());
      } else {
        int idx = pattern.indexOf(middle);
        if (idx < 0) {
          return null;
        }
        String separator = idx > 0 ? "|" : "";
        pattern = pattern.substring(0, idx) + separator + pattern.substring(idx + middle.length());
      }
    }
    return pattern;
  }
}
