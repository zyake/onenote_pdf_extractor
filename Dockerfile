# Lambda container image for OneNote PDF Extractor pipeline
# Uses AWS Lambda Java 21 base image with JDK 25 preview features enabled at runtime.
# The fat JAR is pre-compiled with JDK 25 + --enable-preview by Maven Shade plugin.
FROM public.ecr.aws/lambda/java:21

# Enable JDK 25 preview features at runtime
ENV JAVA_TOOL_OPTIONS="--enable-preview"

# Copy the fat JAR produced by Maven Shade plugin
COPY target/onenote-pdf-extractor-1.0-SNAPSHOT.jar ${LAMBDA_TASK_ROOT}/lib/

# Set the Lambda handler
CMD ["com.extractor.pipeline.PipelineHandler::handleRequest"]
