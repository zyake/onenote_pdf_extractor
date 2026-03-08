#!/usr/bin/env python3
"""CDK app entry point for the OneNote PDF Extractor pipeline."""

import aws_cdk as cdk

from pipeline_stack import PipelineStack

app = cdk.App()

env_name = app.node.try_get_context("env") or "dev"

PipelineStack(
    app,
    f"OneNotePipeline-{env_name}",
    env_name=env_name,
)

app.synth()
