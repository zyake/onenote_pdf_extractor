#!/usr/bin/env node
const cdk = require("aws-cdk-lib");
const { PipelineStack } = require("../lib/pipeline-stack");

const app = new cdk.App();
const envName = app.node.tryGetContext("env") ?? "dev";

new PipelineStack(app, `OneNotePipeline-${envName}`, { envName });

app.synth();
