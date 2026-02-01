#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { AiDrivenStack } from '../lib/ai-driven-stack';

const app = new cdk.App();

new AiDrivenStack(app, 'AiDrivenStack', {
    env: {
        account: process.env.CDK_DEFAULT_ACCOUNT,
        region: process.env.CDK_DEFAULT_REGION || 'us-east-1',
    },
    description: 'AI-Driven Development System - Jira to PR Automation',
});
