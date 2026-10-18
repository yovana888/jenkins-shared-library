# 🚀 CI/CD Pipeline for Angular with Jenkins, Docker, and AWS ECR

This repository contains the CI/CD pipeline configuration for the Angular application (`dmc-zoom-app`). The pipeline automates code checkout, unit testing, application building, Docker containerization, and pushing image tags to **Amazon Elastic Container Registry (ECR)** using a reusable **Jenkins Shared Library**.

---

## 🏗️ Architecture Overview

```text
[ Developer Push ]
       │
       ▼
 [ GitHub Repository ] ──── (Webhook) ───► [ AWS EC2: Jenkins Server ]
                                                   │
                                                   ├── 1. Checkout Code
                                                   ├── 2. Run Tests & Build (Node Container)
                                                   ├── 3. Docker Build & Tag
                                                   └── 4. Push Image to AWS ECR
```

### Key Components

- **Source Code Management**: GitHub (`dmc-zoom-app`)
- **CI/CD Server**: Jenkins running inside Docker on an **AWS EC2 (t3.small)** instance.
- **Shared Pipeline**: `jenkins-shared-library` for modular and reusable pipeline code.
- **Container Registry**: AWS ECR (`webapp-angular`)
- **Build Environment**: Dynamic Docker-in-Docker Execution (`node:20-alpine` for builds and tests).

---

## 🛠️ Prerequisites & Configuration

### 1. AWS Credentials in Jenkins
Jenkins requires AWS Access Credentials stored in its Credentials Manager:

- `aws-access-key` (Secret Text)
- `aws-secret-key` (Secret Text)

### 2. Jenkins Global Shared Library
Configured under **Manage Jenkins** ➔ **System** ➔ **Global Pipeline Libraries**:

- **Name**: `jenkins-shared-library`
- **Default Version**: `main`
- **Repository URL**: `https://github.com/yovana888/jenkins-shared-library.git`

---

## 📜 Jenkins Pipeline Structure

The pipeline script (`devops/jenkins/jenkins-delivery-dev-pipeline.groovy`) calls the custom library function `buildAngularPipeline`:

```groovy
@Library('jenkins-shared-library@main') _

buildAngularPipeline(
    deploymentEnvironment: 'dev',
    imageRepo: 'webapp-angular',
    awsRegion: 'us-east-1'
)
```

### Shared Library Logic (`vars/buildAngularPipeline.groovy`)

The reusable pipeline handles the execution lifecycle across three distinct stages:

```groovy
def call(Map config = [:]) {
    pipeline {
        agent any

        environment {
            DEPLOY_ENV = "${config.deploymentEnvironment ?: 'dev'}"
            IMAGE_REPO = "${config.imageRepo ?: 'webapp-angular'}"
            AWS_REGION = "${config.awsRegion ?: 'us-east-1'}"
        }

        stages {
            stage('Preparation') {
                steps {
                    script {
                        echo "Starting deployment for environment: ${env.DEPLOY_ENV}"
                        checkout scm
                    }
                }
            }

            stage('Build & Test') {
                agent {
                    docker {
                        image 'node:20-alpine'
                        args '-v /tmp:/tmp'
                    }
                }
                steps {
                    sh 'npm install'
                    sh 'npm run test -- --coverage --ci --maxWorkers=2'
                    sh 'npm run build'
                }
            }

            stage('Deploy to ECR') {
                steps {
                    script {
                        // Dynamically retrieve AWS Account ID
                        def AWS_ACCOUNT_ID = sh(
                            script: 'aws sts get-caller-identity --query Account --output text',
                            returnStdout: true
                        ).trim()

                        def REGISTRY_URI = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
                        
                        // Extract version from package.json
                        def APP_VERSION = sh(
                            script: "node -p \"require('./package.json').version\"",
                            returnStdout: true
                        ).trim()

                        def FULL_TAG = "${DEPLOY_ENV}-${APP_VERSION}-${BUILD_NUMBER}"

                        echo "Target ECR Registry: ${REGISTRY_URI}"
                        echo "Building Docker image: ${IMAGE_REPO}:${FULL_TAG}"

                        sh "docker build --build-arg BUILD_ENV=${DEPLOY_ENV} -t ${IMAGE_REPO}:${FULL_TAG} ."

                        withCredentials([
                            string(credentialsId: 'aws-access-key', variable: 'AWS_ACCESS_KEY_ID'),
                            string(credentialsId: 'aws-secret-key', variable: 'AWS_SECRET_ACCESS_KEY')
                        ]) {
                            // Authenticate Docker with ECR
                            sh "aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${REGISTRY_URI}"
                            
                            // Tag images
                            sh "docker tag ${IMAGE_REPO}:${FULL_TAG} ${REGISTRY_URI}/${IMAGE_REPO}:${FULL_TAG}"
                            sh "docker tag ${IMAGE_REPO}:${FULL_TAG} ${REGISTRY_URI}/${IMAGE_REPO}:${DEPLOY_ENV}-latest"

                            // Push to Amazon ECR
                            sh "docker push ${REGISTRY_URI}/${IMAGE_REPO}:${FULL_TAG}"
                            sh "docker push ${REGISTRY_URI}/${IMAGE_REPO}:${DEPLOY_ENV}-latest"
                        }
                    }
                }
            }
        }
    }
}
```

---

## 🧪 Testing Integration

Unit tests are enforced during the **Build & Test** stage before any container image is pushed:

- **Command**: `npm run test -- --coverage --ci --maxWorkers=2`
- **Execution**: Run inside an isolated `node:20-alpine` container.
- **Fail-Safe**: If any unit test fails, the pipeline aborts immediately, preventing faulty code from being deployed to AWS ECR.

---

## ⚡ Webhook Automation

Automatic pipeline execution on code push is handled via GitHub Webhooks:

1. Go to Repository **Settings** ➔ **Webhooks**.
2. **Payload URL**: `http://<EC2-PUBLIC-IP>:8080/github-webhook/`
3. **Content type**: `application/json`
4. **Events**: `Push events`
