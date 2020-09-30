pipeline {
  agent {
    dockerfile true
  }

  stages {
    stage('Build') {
      steps {
        sh '''
					mkdir -p build 
					cd build
					cmake .. -G Ninja
					ninja
        '''
      }
    }

    stage('Static Validation') {
      steps {
        sh 'tools/isTopLevel.sh'
      }
    }

//    stage('Upload Artifact') {
//      steps {
//      }
//    }

    stage('clear workspace') {
      steps {
        cleanWs(cleanWhenAborted: true, cleanWhenFailure: true, cleanWhenNotBuilt: true, cleanWhenSuccess: true, cleanWhenUnstable: true, cleanupMatrixParent: true, deleteDirs: true, disableDeferredWipeout: true, notFailBuild: true)
      }
    }

  }
}
