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
        sh 'tools/validate/format.sh'
        sh 'tools/validate/includeGuard.sh'
      }
    }

		stage('generate install package'){
			steps{
				sh'''
					cd build
					mkdir -p package
					mkdir -p package/DEBIAN
					mkdir -p package/usr
					mkdir -p package/usr/bin
					cp ./CocoaDiskInfo package/usr/bin
					cp ../env/DEBIAN package/DEBIAN/control
					fakeroot dpkg-deb --build package .
				'''
			}
		}

    stage('Upload Artifact') {
      steps {
				archiveArtifacts allowEmptyArchive: true, artifacts: 'build/cocoadiskinfo_*.deb'
      }
    }

    stage('clear workspace') {
      steps {
        cleanWs(cleanWhenAborted: true, cleanWhenFailure: true, cleanWhenNotBuilt: true, cleanWhenSuccess: true, cleanWhenUnstable: true, cleanupMatrixParent: true, deleteDirs: true, disableDeferredWipeout: true, notFailBuild: true)
      }
    }

  }
}
