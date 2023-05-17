@Library('ni-utils') _

//service name is extrapolated from repository name.
def svcName= currentBuild.rawBuild.project.parent.displayName

//this defines the tag applied to the docker image
def tag = "${BRANCH_NAME}${BUILD_NUMBER}"

//get pod template definition
def pod = libraryResource 'com/naturalint/datainfra-agent.yaml'

// this class holds all the servicee-specific build steps (build, Deploy, etc...)
def d = new com.naturalint.datainfra()

 
// Build command override, if non empty
def buildCommand=""
service = true
def extraArgs = ["buildCommandOverride": "mvn test -Psource-config"]
// this will Call the pipeline defined in the shared library repo
// here: https://github.com/Natural-Intelligence/jenkins-shared-libraries

//TODO : we need to
// 1. either define DAG name here and pass it to the pipeline
// 2. or put it in the airflow.properties file and read it in the stage triggering Airflow
timestamps {
    dataInfraPipeline(d, pod, svcName, tag, buildCommand, service, extraArgs)
}
