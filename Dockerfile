# Dockerfile for OneNote PDF Extractor Lambda container image
# Uses AWS Lambda custom runtime (provided:al2023) with JDK 25
# to support --enable-preview features (structured concurrency, etc.)

FROM public.ecr.aws/lambda/provided:al2023

# Install JDK 25 (Eclipse Temurin)
RUN dnf install -y tar gzip && \
    dnf clean all

ARG JDK_VERSION=25
ARG JDK_URL=https://api.adoptium.net/v3/binary/latest/${JDK_VERSION}/ga/linux/x64/jdk/hotspot/normal/eclipse

RUN curl -fsSL -o /tmp/jdk.tar.gz "${JDK_URL}" && \
    mkdir -p /opt/java && \
    tar -xzf /tmp/jdk.tar.gz -C /opt/java --strip-components=1 && \
    rm /tmp/jdk.tar.gz

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Enable preview features (structured concurrency, etc.)
ENV JAVA_TOOL_OPTIONS="--enable-preview"

# Copy the AWS Lambda Runtime Interface Client (RIC) bootstrap script
# The RIC is included in the fat JAR via aws-lambda-java-core;
# we provide a bootstrap script that invokes the Lambda RIC main class.
COPY bootstrap ${LAMBDA_RUNTIME_DIR}/bootstrap
RUN chmod +x ${LAMBDA_RUNTIME_DIR}/bootstrap

# Copy the pre-built fat JAR
COPY target/onenote-pdf-extractor-1.0-SNAPSHOT.jar ${LAMBDA_TASK_ROOT}/lib/app.jar

# Set the handler
CMD ["com.extractor.pipeline.PipelineHandler::handleRequest"]
