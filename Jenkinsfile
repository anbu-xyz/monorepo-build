pipeline {
    agent any

    environment {
        GITHUB_CREDENTIALS_ID = 'github-PAT-Aug-16'
        GITHUB_ACCOUNT = 'anbu-xyz'
        GITHUB_REPO = 'monorepo-build'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        stage('Build') {
            steps {
                script {
                    updateGithubCommitStatus('pending', 'Building...')

                    sh 'mvn --version && mvn clean install && (cd sample-project && mvn package -X)'
                }
            }
        }
    }

    post {
        success {
            script {
                updateGithubCommitStatus('success', 'Build succeeded')
            }
        }
        failure {
            script {
                updateGithubCommitStatus('failure', 'Build failed')
            }
        }
    }
}

def getRepoURL() {
  sh "git config --get remote.origin.url > .git/remote-url"
  return readFile(".git/remote-url").trim()
}

def getCommitSha() {
  sh "git rev-parse HEAD > .git/current-commit"
  return readFile(".git/current-commit").trim()
}

def updateGithubCommitStatus(state, description) {
  repoUrl = getRepoURL()
  commitSha = getCommitSha()
  payload =  """{"state": "${state}", "target_url": "https://jenkins.anbu.io/monorepo-build", "description": "${description}", "context": "continuous-integration/jenkins"}"""

  withCredentials([usernamePassword(credentialsId: env.GITHUB_CREDENTIALS_ID, passwordVariable: 'PASSWORD_VAR', usernameVariable: 'USERNAME')]) {
     sh """curl -L \
              -X POST \
              -H "Accept: application/vnd.github+json" \
              -H "Authorization: Bearer ${PASSWORD_VAR}" \
              -H "X-GitHub-Api-Version: 2022-11-28" \
              https://api.github.com/repos/${env.GITHUB_ACCOUNT}/${env.GITHUB_REPO}/statuses/${commitSha} \
              -d '${payload}'
              """
  }
}
