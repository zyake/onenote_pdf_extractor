"""CDK stack for the OneNote PDF Extractor automated pipeline."""

from constructs import Construct
import aws_cdk as cdk
from aws_cdk import (
    Stack,
    Duration,
    RemovalPolicy,
    aws_dynamodb as dynamodb,
    aws_events as events,
    aws_events_targets as targets,
    aws_iam as iam,
    aws_lambda as lambda_,
    aws_cloudwatch as cloudwatch,
    aws_s3 as s3,
    aws_ssm as ssm,
)


ENV_CONFIG = {
    "dev": {
        "schedule": "rate(1 day)",
        "lambda_memory": 1024,
        "lambda_timeout_minutes": 10,
        "removal_policy": RemovalPolicy.DESTROY,
        "cw_namespace": "OneNotePipeline/Dev",
    },
    "prod": {
        "schedule": "rate(6 hours)",
        "lambda_memory": 2048,
        "lambda_timeout_minutes": 15,
        "removal_policy": RemovalPolicy.RETAIN,
        "cw_namespace": "OneNotePipeline/Prod",
    },
}


class PipelineStack(Stack):
    """Stack defining all resources for the OneNote PDF Extractor pipeline."""

    def __init__(
        self, scope: Construct, construct_id: str, *, env_name: str = "dev", **kwargs
    ) -> None:
        super().__init__(scope, construct_id, **kwargs)

        config = ENV_CONFIG.get(env_name, ENV_CONFIG["dev"])
        ssm_prefix = f"/onenote-pipeline/{env_name}"

        # --- S3 bucket for PDF storage ---
        pdf_bucket = s3.Bucket(
            self,
            "PdfStore",
            bucket_name=f"onenote-pdf-store-{env_name}-{self.account}",
            versioned=True,
            removal_policy=config["removal_policy"],
            auto_delete_objects=config["removal_policy"] == RemovalPolicy.DESTROY,
            encryption=s3.BucketEncryption.S3_MANAGED,
            block_public_access=s3.BlockPublicAccess.BLOCK_ALL,
        )

        # --- DynamoDB table for deduplication ---
        export_tracker = dynamodb.Table(
            self,
            "ExportTracker",
            table_name=f"ExportTracker-{env_name}",
            partition_key=dynamodb.Attribute(
                name="pageId", type=dynamodb.AttributeType.STRING
            ),
            billing_mode=dynamodb.BillingMode.PAY_PER_REQUEST,
            removal_policy=config["removal_policy"],
        )

        # --- SSM parameters (placeholders — actual values set manually) ---
        ssm_params = {}
        secure_params = [
            "ms-client-id",
            "ms-client-secret",
            "ms-tenant-id",
            "google-service-account-json",
            "notebooklm-project-id",
        ]
        for param_name in secure_params:
            ssm_params[param_name] = ssm.StringParameter(
                self,
                f"Param-{param_name}",
                parameter_name=f"{ssm_prefix}/{param_name}",
                string_value="PLACEHOLDER",
                description=f"Placeholder for {param_name} — replace with actual value via console or CLI",
                tier=ssm.ParameterTier.STANDARD,
            )

        ssm_params["section-id"] = ssm.StringParameter(
            self,
            "Param-section-id",
            parameter_name=f"{ssm_prefix}/section-id",
            string_value="PLACEHOLDER",
            description="OneNote section ID to export — replace with actual value",
            tier=ssm.ParameterTier.STANDARD,
        )

        # --- Lambda function (Java 21 managed runtime, fat JAR) ---
        pipeline_function = lambda_.Function(
            self,
            "PipelineFunction",
            function_name=f"onenote-pipeline-{env_name}",
            runtime=lambda_.Runtime.JAVA_21,
            handler="com.extractor.pipeline.PipelineHandler::handleRequest",
            code=lambda_.Code.from_asset("../target/onenote-pdf-extractor-1.0-SNAPSHOT.jar"),
            memory_size=config["lambda_memory"],
            timeout=Duration.minutes(config["lambda_timeout_minutes"]),
            reserved_concurrent_executions=1,
            environment={
                "SSM_PREFIX": ssm_prefix,
                "S3_BUCKET": pdf_bucket.bucket_name,
                "DYNAMO_TABLE": export_tracker.table_name,
                "CW_NAMESPACE": config["cw_namespace"],
                "CONCURRENCY_LIMIT": "5",
            },
            description="OneNote PDF Extractor automated pipeline",
        )

        # --- IAM: least-privilege permissions ---
        # DynamoDB read/write
        export_tracker.grant_read_write_data(pipeline_function)

        # S3 put (write only — no read/delete needed)
        pdf_bucket.grant_put(pipeline_function)

        # SSM get-parameter for all pipeline parameters
        pipeline_function.add_to_role_policy(
            iam.PolicyStatement(
                effect=iam.Effect.ALLOW,
                actions=["ssm:GetParameter"],
                resources=[
                    f"arn:aws:ssm:{self.region}:{self.account}:parameter{ssm_prefix}/*"
                ],
            )
        )

        # CloudWatch put-metric-data
        pipeline_function.add_to_role_policy(
            iam.PolicyStatement(
                effect=iam.Effect.ALLOW,
                actions=["cloudwatch:PutMetricData"],
                resources=["*"],
                conditions={
                    "StringEquals": {
                        "cloudwatch:namespace": config["cw_namespace"]
                    }
                },
            )
        )

        # --- EventBridge scheduled rule ---
        schedule_expression = events.Schedule.expression(config["schedule"])

        rule = events.Rule(
            self,
            "PipelineSchedule",
            rule_name=f"onenote-pipeline-schedule-{env_name}",
            schedule=schedule_expression,
            description=f"Triggers OneNote pipeline ({env_name})",
        )
        rule.add_target(targets.LambdaFunction(pipeline_function))

        # --- CloudWatch alarm on Lambda errors ---
        error_metric = pipeline_function.metric_errors(
            period=Duration.minutes(5),
            statistic="Sum",
        )

        alarm = cloudwatch.Alarm(
            self,
            "PipelineErrorAlarm",
            alarm_name=f"onenote-pipeline-errors-{env_name}",
            metric=error_metric,
            threshold=1,
            evaluation_periods=1,
            comparison_operator=cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
            alarm_description=f"Alarm when OneNote pipeline Lambda errors >= 1 ({env_name})",
            treat_missing_data=cloudwatch.TreatMissingData.NOT_BREACHING,
        )

        # --- Outputs ---
        cdk.CfnOutput(self, "PdfBucketName", value=pdf_bucket.bucket_name)
        cdk.CfnOutput(self, "ExportTrackerTableName", value=export_tracker.table_name)
        cdk.CfnOutput(self, "LambdaFunctionName", value=pipeline_function.function_name)
        cdk.CfnOutput(self, "ScheduleRuleName", value=rule.rule_name)
        cdk.CfnOutput(self, "ErrorAlarmName", value=alarm.alarm_name)
