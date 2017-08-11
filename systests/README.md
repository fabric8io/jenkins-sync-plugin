## System Tests

This module implements a suite of system tests that checks the behavior of the jenkins-sync plugin on an OpenShift namespace against some Jenkins Server.

You can use this test suite to test any combination of Jenkins server and OpenShift/Kubernetes cluster.

### Environment variables

* `KUBERNETES_NAMESPACE` the default namespace to use for BuildConfig / ConfigMaps which is typically the main users tenant namespace
* `JENKINS_URL` the URL used to talk to Jenkins. If none is specified it assumes you're testing Jenkins locally at `http://localhost:8080` via something like `mvn hpi:run`
* `JENKINS_USER` the username to talk to jenkins. Defaults to `admin`
* `JENKINS_PASSWORD` the password or token used to talk to jenkins. Defaults to `admin`


