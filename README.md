# openshift-jenkins-sync-plugin

This Jenkins plugin keeps OpenShift BuildConfig and Build objects in sync With Jenkins Jobs and Builds.

The synchronization works like this


* changes to OpenShift BuildConfig resources for Jenkins pipeline builds result in updates to the Jenkins Job of the same name
* creating a new OpenShift Build for a BuildConfig associated with a Jenkins Job results in the Jenkins Job being triggered
* changes in a Jenkins Build Run thats associated with a Jenkins Job gets replicated to an OpenShift Build object (which is created if necessary if the build was triggered via Jenkins)

Configuration
------------------------
Jenkins Build Log URL:
* This plugin adds an annotation to the OpenShift build configuration containing the Jenkins build log URL.
By default, the Jenkins base URL for the build log is determined via the OpenShift route of the Jenkins service. To override and configure this base URL, you can set the environment variable `JENKINS_ROOT_URL`.
This environment variable will get precedence than Jenkins service to determine base URL.
For fabric8/OpenShift.io tenant's Jenkins deployment, the log base URL is configured through [DeploymentConfig environment variable](https://github.com/fabric8-services/fabric8-tenant-jenkins/blob/master/apps/jenkins/src/main/fabric8/openshift-deployment.yml#L39)

Development Instructions
------------------------

* Build and run the unit tests
  Execute `mvn clean install`

* Install the plugin into a locally-running Jenkins
  Execute `mvn hpi:run`
  Navigate in brower to `http://localhost:8080/jenkins`

Synchronization Polling Frequencies
-----------------------------------

Build Sync: Default 5 seconds [BuildSyncRunListener](https://github.com/fabric8io/jenkins-sync-plugin/blob/master/src/main/java/io/fabric8/jenkins/openshiftsync/BuildSyncRunListener.java#L73)

Deploying the changes for OSIO
------------------------------

If you are making changes for OSIO, you need to do work on job-to-bc branch instead of master branch, after doing all your development work and testing it properly, you need follow the following steps:

Assuming you are in jenkins-sync-plugin folder.

* Generate hpi file
  Execute `mvn package`
  
This will generate openshift-sync.hpi in your target folder. After that you need to create jpi file from this hpi file and need to send a PR to [openshift-jenkins-s2i-config](https://github.com/fabric8io/openshift-jenkins-s2i-config) 

Assuming jenkins-sync-plugin and openshift-jenkins-s2i-config are cloned at same location.

* Generate jpi file and copy to openshift-s2i-config
  Execute `cp ../jenkins-sync-plugin/target/openshift-sync.hpi plugins/openshift-sync.jpi`
  
After you need to send a PR to both jenkins-sync plugin:job-to-bc and openshift-jenkins-s2i-config:master
