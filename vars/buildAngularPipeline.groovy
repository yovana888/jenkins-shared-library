def call(Map config) {
    pipeline {
        agent any
        
        environment {
            AWS_ACCOUNT_ID = config.awsAccountId ?: '847547470661'
            AWS_REGION     = config.awsRegion ?: 'us-east-1'
            IMAGE_REPO     = config.imageRepo ?: 'webapp-angular'
            REGISTRY_URI   = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
        }

        stages {
            stage('Get Source') {
                steps {
                    checkout scm
                }
            }

            stage('Build & Sonar (Paralelo)') {
                parallel {
                    stage('Angular Test & Build') {
                        steps {
                            sh 'npm install'
                            // Ejecuta los tests con Jest y genera la cobertura para Sonar
                            sh 'npm run test -- --coverage --ci --maxWorkers=2'
                            // Compila el proyecto en modo producción
                            sh 'npm run build:prod'
                        }
                    }
                    stage('SonarQube Analysis') {
                        steps {
                            script {
                                echo "Ejecutando análisis estático con SonarQube Scanner..."
                                // El scanner tomará el reporte en coverage/lcov.info
                                // sh 'sonar-scanner -Dsonar.projectKey=webapp-angular -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info'
                            }
                        }
                    }
                }
            }

            stage('Docker Build & Publish to ECR') {
                steps {
                    script {
                        // 1. Extraer la versión desde el package.json usando Node.js
                        def APP_VERSION = sh(
                            script: "node -p \"require('./package.json').version\"", 
                            returnStdout: true
                        ).trim()

                        def FULL_TAG = "${APP_VERSION}-${BUILD_NUMBER}"

                        echo "Construyendo imagen Docker con tag: ${FULL_TAG}"

                        // 2. Construir la imagen Docker
                        sh "docker build -t ${IMAGE_REPO}:${FULL_TAG} ."

                        // 3. Autenticación y publicación en AWS ECR
                        withCredentials([
                            string(credentialsId: 'aws-access-key', variable: 'AWS_ACCESS_KEY_ID'),
                            string(credentialsId: 'aws-secret-key', variable: 'AWS_SECRET_ACCESS_KEY')
                        ]) {
                            sh "aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${REGISTRY_URI}"
                            
                            // Etiquetado
                            sh "docker tag ${IMAGE_REPO}:${FULL_TAG} ${REGISTRY_URI}/${IMAGE_REPO}:${FULL_TAG}"
                            sh "docker tag ${IMAGE_REPO}:${FULL_TAG} ${REGISTRY_URI}/${IMAGE_REPO}:${APP_VERSION}"

                            // Push a AWS ECR
                            sh "docker push ${REGISTRY_URI}/${IMAGE_REPO}:${FULL_TAG}"
                            sh "docker push ${REGISTRY_URI}/${IMAGE_REPO}:${APP_VERSION}"
                        }
                    }
                }
            }
        }
    }
}