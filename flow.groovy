def buildVersion = null
stage 'build'
node('docker') {
    docker.withServer('tcp://127.0.0.1:1234') {
        docker.image('kmadel/maven:3.3.3-jdk-8').inside('-v /data:/data') {
            sh 'rm -rf *'
            checkout([$class: 'GitSCM', branches: [[name: '*/master']], clean: true, doGenerateSubmoduleConfigurations: false, extensions: [], submoduleCfg: [], userRemoteConfigs: [[url: 'https://github.com/cloudbees/mobile-deposit-api.git']]])
            sh 'git checkout master'
            sh 'git config user.email "kmadel@mac.com"'
            sh 'git config user.name "kmadel"'
            sh 'git remote set-url origin git@github.com:cloudbees/mobile-deposit-api.git'
            sh 'mvn -s /data/mvn/settings.xml -Dmaven.repo.local=/data/mvn/repo clean package'

            stage 'sonar analysis'
            sh 'mvn -s /data/mvn/settings.xml -Dmaven.repo.local=/data/mvn/repo -Dsonar.scm.disabled=True sonar:sonar'

            stage 'integration-test'
            sh 'mvn -s /data/mvn/settings.xml -Dmaven.repo.local=/data/mvn/repo verify'
            //step([$class: 'JUnitResultArchiver', testResults: '**/target/surefire-reports/TEST-*.xml'])

            stage 'prepare release'
            def matcher = readFile('pom.xml') =~ '<version>(.+)</version>'
            if (matcher) {
                buildVersion = matcher[0][1]
                echo "Released version ${buildVersion}"
            }
            matcher = null
        }
    }

  docker.withServer('tcp://54.165.201.3:2376', 'slave-docker-us-east-1-tls'){

        stage 'build docker image'
        def mobileDepositApiImage
        dir('target') {
            mobileDepositApiImage = docker.build "kmadel/mobile-deposit-api:${buildVersion}"
        }

        stage 'deploy to production'
        try{
          sh "docker stop mobile-deposit-api"
          sh "docker rm mobile-deposit-api"
        } catch (Exception _) {
           echo "no container to stop"        
        }
        mobileDepositApiImage.run("--name mobile-deposit-api -p 8080:8080")

      stage 'publish docker image'
        sh 'curl -H "Content-Type: application/json" -X POST -d \'{"push_data": {"pushed_at": 1434386606, "images": null, "pusher": "kmadel"}, "callback_url": "https://registry.hub.docker.com/u/kmadel/mobile-bank-api/hook/21a0ic0dje2ff4hg3f3hbg23b5220454b/", "repository": {"status": "Active", "description": "", "is_trusted": false, "full_description": "", "repo_url": "https://registry.hub.docker.com/u/kmadel/mobile-bank-api/", "owner": "kmadel", "is_official": false, "is_private": false, "name": "mobile-bank-api", "namespace": "kmadel", "star_count": 0, "comment_count": 0, "date_created": 1434385021, "repo_name": "kmadel/mobile-bank-api"}}\' http://webhook:13461862c863d7df39e63435eb17deb9@jenkins-latest.beedemo.net/mobile-team/dockerhub-webhook/notify'
   }
}
