pipeline {
  agent {
    dockerfile true
  }

  stages {
    stage('Build') {
      steps {
        sh '''
					mkdir -p build && cd $_
					cmake .. -G Ninja
					ninja
        '''
      }
    }

    stage('Upload Artifact') {
      steps {
      }
    }

    stage('clear workspace') {
      steps {
        cleanWs(cleanWhenAborted: true, cleanWhenFailure: true, cleanWhenNotBuilt: true, cleanWhenSuccess: true, cleanWhenUnstable: true, cleanupMatrixParent: true, deleteDirs: true, disableDeferredWipeout: true, notFailBuild: true)
      }
    }

  }
}
