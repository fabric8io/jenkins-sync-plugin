package io.fabric8.jenkins.openshiftsync;

import com.cloudbees.workflow.flownode.FlowNodeUtil;
import hudson.Extension;
import hudson.model.Run;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.client.OpenShiftClient;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.flow.GraphListener;
import org.jenkinsci.plugins.workflow.graph.FlowNode;

import java.util.logging.Level;
import java.util.logging.Logger;

import static io.fabric8.jenkins.openshiftsync.OpenShiftUtils.getOpenShiftClient;

/**
 * Listens to Jenkins Job build Step then ensure there's a suitable {@link Build} object in
 * OpenShift that's updated correctly with the Job {@link Run} status
 */

@Extension
public class BuildSyncStageListener implements GraphListener {

  private static final Logger logger = Logger.getLogger(BuildSyncStageListener.class.getName());

  private String namespace;
  private transient BuildStateUpdater buildStateUpdater;
  private OpenShiftClient openShiftClient;

  public BuildSyncStageListener() {
    openShiftClient = getOpenShiftClient();
    namespace = OpenShiftUtils.getNamespaceOrUseDefault(namespace, openShiftClient);
    buildStateUpdater = BuildStateUpdater.getInstance();
  }

  public BuildSyncStageListener(OpenShiftClient ocClient, String ns) {
    openShiftClient = ocClient;
    namespace = OpenShiftUtils.getNamespaceOrUseDefault(ns, openShiftClient);
    buildStateUpdater = BuildStateUpdater.getInstance();
  }


  @Override
  public void onNewHead(FlowNode flowNode) {
    try {
      String stepName = flowNode.getDisplayName();
      if (!isUsefulEvent(stepName)) {
        logger.log(Level.FINE, "Ignoring node : " + stepName);
        return;
      }

      updateBuildState(flowNode);
    } catch(Exception e) {
      logger.warning("Error occurred while updating build state : " + e.getMessage());
    }
  }

  private boolean isUsefulEvent(String stepName) {
    return StringUtils.containsIgnoreCase(stepName, "start") ||
      StringUtils.containsIgnoreCase(stepName, "end" ) ||
      StringUtils.containsIgnoreCase(stepName, "wait");
  }

  private void updateBuildState(FlowNode step) {
    logger.log(Level.FINE, "Node : " +  step.getDisplayName());
    Run run = FlowNodeUtil.getWorkflowRunForExecution(step.getExecution());
    buildStateUpdater.update(run, namespace);
  }

}
