import { Construct } from "constructs";
import * as cdk from "aws-cdk-lib";
import * as dynamodb from "aws-cdk-lib/aws-dynamodb";
import * as events from "aws-cdk-lib/aws-events";
import * as targets from "aws-cdk-lib/aws-events-targets";
import * as iam from "aws-cdk-lib/aws-iam";
import * as lambda from "aws-cdk-lib/aws-lambda";
import * as cloudwatch from "aws-cdk-lib/aws-cloudwatch";
import * as s3 from "aws-cdk-lib/aws-s3";
import * as ssm from "aws-cdk-lib/aws-ssm";

interface EnvConfig {
  schedule: string;
  lambdaMemory: number;
  lambdaTimeoutMinutes: number;
  removalPolicy: cdk.RemovalPolicy;
  cwNamespace: string;
}

const ENV_CONFIG: Record<string, EnvConfig> = {
  dev: {
    schedule: "rate(1 day)",
    lambdaMemory: 1024,
    lambdaTimeoutMinutes: 10,
    removalPolicy: cdk.RemovalPolicy.DESTROY,
    cwNamespace: "OneNotePipeline/Dev",
  },
  prod: {
    schedule: "rate(6 hours)",
    lambdaMemory: 2048,
    lambdaTimeoutMinutes: 15,
    removalPolicy: cdk.RemovalPolicy.RETAIN,
    cwNamespace: "OneNotePipeline/Prod",
  },
};

interface PipelineStackProps extends cdk.StackProps {
  envName: string;
}

export class PipelineStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: PipelineStackProps) {
    super(scope, id, props);

    const config = ENV_CONFIG[props.envName] ?? ENV_CONFIG["dev"];
    const ssmPrefix = `/onenote-pipeline/${props.envName}`;

    // --- S3 bucket for PDF storage ---
    const pdfBucket = new s3.Bucket(this, "PdfStore", {
      bucketName: `onenote-pdf-store-${props.envName}-${this.account}`,
      versioned: true,
      removalPolicy: config.removalPolicy,
      autoDeleteObjects: config.removalPolicy === cdk.RemovalPolicy.DESTROY,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
    });

    // --- DynamoDB table for deduplication ---
    const exportTracker = new dynamodb.Table(this, "ExportTracker", {
      tableName: `ExportTracker-${props.envName}`,
      partitionKey: { name: "pageId", type: dynamodb.AttributeType.STRING },
      billingMode: dynamodb.BillingMode.PAY_PER_REQUEST,
      removalPolicy: config.removalPolicy,
    });

    // --- SSM parameters (placeholders — actual values set manually) ---
    const secureParams = [
      "ms-client-id",
      "ms-client-secret",
      "ms-tenant-id",
      "google-service-account-json",
      "notebooklm-project-id",
    ];
    for (const paramName of secureParams) {
      new ssm.StringParameter(this, `Param-${paramName}`, {
        parameterName: `${ssmPrefix}/${paramName}`,
        stringValue: "PLACEHOLDER",
        description: `Placeholder for ${paramName} — replace with actual value via console or CLI`,
        tier: ssm.ParameterTier.STANDARD,
      });
    }

    new ssm.StringParameter(this, "Param-section-id", {
      parameterName: `${ssmPrefix}/section-id`,
      stringValue: "PLACEHOLDER",
      description: "OneNote section ID to export — replace with actual value",
      tier: ssm.ParameterTier.STANDARD,
    });

    // --- Lambda function (Java 21 managed runtime, fat JAR) ---
    const pipelineFunction = new lambda.Function(this, "PipelineFunction", {
      functionName: `onenote-pipeline-${props.envName}`,
      runtime: lambda.Runtime.JAVA_21,
      handler: "com.extractor.pipeline.PipelineHandler::handleRequest",
      code: lambda.Code.fromAsset("../target/onenote-pdf-extractor-1.0-SNAPSHOT.jar"),
      memorySize: config.lambdaMemory,
      timeout: cdk.Duration.minutes(config.lambdaTimeoutMinutes),
      reservedConcurrentExecutions: 1,
      environment: {
        SSM_PREFIX: ssmPrefix,
        S3_BUCKET: pdfBucket.bucketName,
        DYNAMO_TABLE: exportTracker.tableName,
        CW_NAMESPACE: config.cwNamespace,
        CONCURRENCY_LIMIT: "5",
      },
      description: "OneNote PDF Extractor automated pipeline",
    });

    // --- IAM: least-privilege permissions ---
    exportTracker.grantReadWriteData(pipelineFunction);
    pdfBucket.grantPut(pipelineFunction);

    pipelineFunction.addToRolePolicy(
      new iam.PolicyStatement({
        effect: iam.Effect.ALLOW,
        actions: ["ssm:GetParameter"],
        resources: [
          `arn:aws:ssm:${this.region}:${this.account}:parameter${ssmPrefix}/*`,
        ],
      })
    );

    pipelineFunction.addToRolePolicy(
      new iam.PolicyStatement({
        effect: iam.Effect.ALLOW,
        actions: ["cloudwatch:PutMetricData"],
        resources: ["*"],
        conditions: {
          StringEquals: { "cloudwatch:namespace": config.cwNamespace },
        },
      })
    );

    // --- EventBridge scheduled rule ---
    const rule = new events.Rule(this, "PipelineSchedule", {
      ruleName: `onenote-pipeline-schedule-${props.envName}`,
      schedule: events.Schedule.expression(config.schedule),
      description: `Triggers OneNote pipeline (${props.envName})`,
    });
    rule.addTarget(new targets.LambdaFunction(pipelineFunction));

    // --- CloudWatch alarm on Lambda errors ---
    const errorMetric = pipelineFunction.metricErrors({
      period: cdk.Duration.minutes(5),
      statistic: "Sum",
    });

    new cloudwatch.Alarm(this, "PipelineErrorAlarm", {
      alarmName: `onenote-pipeline-errors-${props.envName}`,
      metric: errorMetric,
      threshold: 1,
      evaluationPeriods: 1,
      comparisonOperator:
        cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
      alarmDescription: `Alarm when OneNote pipeline Lambda errors >= 1 (${props.envName})`,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });

    // --- Outputs ---
    new cdk.CfnOutput(this, "PdfBucketName", { value: pdfBucket.bucketName });
    new cdk.CfnOutput(this, "ExportTrackerTableName", { value: exportTracker.tableName });
    new cdk.CfnOutput(this, "LambdaFunctionName", { value: pipelineFunction.functionName });
    new cdk.CfnOutput(this, "ScheduleRuleName", { value: rule.ruleName });
    new cdk.CfnOutput(this, "ErrorAlarmName", { value: `onenote-pipeline-errors-${props.envName}` });
  }
}
