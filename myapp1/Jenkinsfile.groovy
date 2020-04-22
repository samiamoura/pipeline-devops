// Define your secret project token here
def project_token = 'abcdefghijklmnopqrstuvwxyz0123456789ABCDEF'
def buildNum = env.BUILD_NUMBER
def branchName = env.BRANCH_NAME



// Reference the GitLab connection name from your Jenkins Global configuration (https://JENKINS_URL/configure, GitLab section)
properties([
    gitLabConnection('your-gitlab-connection-name'),
    pipelineTriggers([
        [
            $class: 'GitLabPushTrigger',
            branchFilterType: 'All',
            triggerOnPush: true,
            triggerOnMergeRequest: true,
            triggerOpenMergeRequestOnPush: "never",
            triggerOnNoteRequest: true,
            noteRegex: "Jenkins please retry a build",
            skipWorkInProgressMergeRequest: true,
            secretToken: project_token,
            ciSkip: false,
            setBuildDescription: true,
            addNoteOnMergeRequest: true,
            addCiMessage: true,
            addVoteOnMergeRequest: true,
            acceptMergeRequestOnSuccess: true,
            branchFilterType: "NameBasedFilter",
            includeBranchesSpec: "${branchName}",
            excludeBranchesSpec: "",
        ]
    ])
])

node(){
  try{

    print buildNum
    print branchName

    stage('Env - clone generator'){
      git "http://gitlab.example.com/mypipeline/generator.git"
    }

    stage('Env - run postgres'){
      sh "./generator.sh --postgres pipeline${buildNum}${branchName}"
      sh "docker ps -a"
    }
    
    def ipPostgres = sh returnStdout: true, script: "./generator.sh -i | grep postgrespipeline${buildNum}${branchName} | awk '{print \$1}' | tr -d '\n'"

    
    /* Get repo with branch*/
    stage('Service - Git checkout'){
      git branch: branchName, url: "http://gitlab.example.com/mypipeline/myapp1.git"
    }

    if (branchName == "dev" ){
      extension = "-SNAPSHOT"
    }
    if (branchName == "stage" ){
      extension = "-RC"
    }
    if (branchName == "master" ){
      extension = ""
    }   

 
    /* Get long commitID */
    /* returnStdout: true --> allow to put the command result in var commitIdLong */ 
    def commitIdLong = sh returnStdout: true, script: 'git rev-parse HEAD' 
    
    /* Get short commitID */
    def commitId = commitIdLong.take(7)  

 
    /* Modify version in pom.xml */
    sh "sed -i s/'-XXX'/${extension}/g pom.xml" 
    
    /* Ajout du nom du conteneur dans application properties */
    sh "sed -i s/'XXX'/${ipPostgres}/g src/main/resources/application.properties"


    /* Get version of package */
    def version = sh returnStdout: true, script: "cat pom.xml | grep -A1 '<artifactId>myapp1' | tail -1 |perl -nle 'm{.*<version>(.*)</version>.*};print \$1' | tr -d '\n'" 
       

    print """ 
    #################################################
    BanchName: $branchName
    CommitID: $commitId
    AppVersion: $version
    JobNumber: $buildNum
    #################################################
    """

    stage('SERVICE -  Maven:Test unitaire'){
      sh 'docker run --rm --name maven-${commitIdLong} -v /var/lib/maven/:/root/.m2 -v "$(pwd)":/usr/src/mymaven --network generator -w /usr/src/mymaven maven:3.3-jdk-8 mvn -B clean test'
    }
  
     stage('SERVICE -  Maven:Jar '){
      sh 'docker run --rm --name maven-${commitIdLong} -v /var/lib/maven/:/root/.m2 -v "$(pwd)":/usr/src/mymaven --network generator -w /usr/src/mymaven maven:3.3-jdk-8 mvn -B clean install'
    }
 
    def imageName='192.168.20.215:5000/myapp'


    stage('DOCKER - Build/Push registry'){
      print "$imageName:${version}-${commitId}"
      docker.withRegistry('http://192.168.20.215:5000', 'myregistry_login') {
        def customImage = docker.build("$imageName:${version}-${commitId}")
        customImage.push()
      }
      sh "docker rmi $imageName:${version}-${commitId}"
    }

    stage('DOCKER - check registry'){
      withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'myregistry_login',usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
        sh 'curl -sk --user $USERNAME:$PASSWORD https://192.168.20.215:5000/v2/myapp/tags/list'
      }
    }

 
    stage('ANSIBLE - Deploy'){
        git branch: 'master', url: 'http://gitlab.example.com/mypipeline/deploy-ansible.git'
        sh "mkdir -p roles"
        sh "ansible-galaxy install --roles-path roles -r requirements.yml"
        ansiblePlaybook (
              colorized: true,
              playbook: "install-myapp1.yml",
              hostKeyChecking: false,
              inventory: "env/${branchName}/hosts",
              extras: "-u sami -e 'image=$imageName:${version}-${commitId}' -e 'version=${version}'"
              )
    }
    
    if (branchName == "dev" ){

      stage('JMETER - test'){
          sh '/usr/bin/jmeter/apache-jmeter-5.2.1/bin/jmeter -n -t test/MyApp2.jmx -l results.jtl'
          sh 'cat results.jtl'
          //perfReport 'results.jtl'
          perfReport errorFailedThreshold: 0, errorUnstableThreshold: 0, filterRegex: '', sourceDataFiles: 'results.jtl'
       }
    }
  

    git "http://gitlab.example.com/mypipeline/generator.git"
                sh "./generator.sh --clean postgrespipeline${buildNum}${branchName}"




  } finally {
      cleanWs()
  }
}
