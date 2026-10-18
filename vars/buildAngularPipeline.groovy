def call(Map config) {
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
                        echo "Iniciando despliegue para el entorno: ${env.DEPLOY_ENV}"
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
                    sh "npm run build:${env.DEPLOY_ENV}"
                }
            }

            stage('Deploy to ECR') {
                steps {
                    script {
                        // Obtiene el Account ID dinámicamente sin quemar 847547470661 en código
                        def AWS_ACCOUNT_ID = sh(
                            script: 'aws sts get-caller-identity --query Account --output text',
                            returnStdout: true
                        ).trim()

                        def REGISTRY_URI = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
                        
                        // Obtiene versión desde el package.json
                        def APP_VERSION = sh(
                            script: "node -p \"require('./package.json').version\"",
                            returnStdout: true
                        ).trim()

                        def FULL_TAG = "${DEPLOY_ENV}-${APP_VERSION}-${BUILD_NUMBER}"

                        echo "AWS Account detectado automáticamente: ${AWS_ACCOUNT_ID}"
                        echo "Construyendo imagen Docker: ${IMAGE_REPO}:${FULL_TAG}"

                        sh "docker build --build-arg BUILD_ENV=${DEPLOY_ENV} -t ${IMAGE_REPO}:${FULL_TAG} ."

                        // Lee las credenciales del gestor local de Jenkins
                        withCredentials([
                            string(credentialsId: 'aws-access-key', variable: 'AWS_ACCESS_KEY_ID'),
                            string(credentialsId: 'aws-secret-key', variable: 'AWS_SECRET_ACCESS_KEY')
                        ]) {
                            sh "aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${REGISTRY_URI}"
                            
                            sh "docker tag ${IMAGE_REPO}:${FULL_TAG} ${REGISTRY_URI}/${IMAGE_REPO}:${FULL_TAG}"
                            sh "docker tag ${IMAGE_REPO}:${FULL_TAG} ${REGISTRY_URI}/${IMAGE_REPO}:${DEPLOY_ENV}-latest"

                            sh "docker push ${REGISTRY_URI}/${IMAGE_REPO}:${FULL_TAG}"
                            sh "docker push ${REGISTRY_URI}/${IMAGE_REPO}:${DEPLOY_ENV}-latest"
                        }
                    }
                }
            }
        }
    }
}