#!/usr/bin/env node
import * as cdk from "aws-cdk-lib";
import { PipelineStack } from "../lib/pipeline-stack";

const app = new cdk.App();
const envName = app.node.tryGetContext("env") ?? "dev";

new PipelineStack(app, `OneNotePipeline-${envName}`, { envName });

app.synth();
