# impl-05: AWS X-Ray Tracing

**Status:** ✅ Complete  
**Priority:** 1.5  
**Impact:** End-to-end distributed tracing across all Lambda functions and Step Functions

---

## Changes

- [x] Enable X-Ray on `JiraWebhookHandler`
- [x] Enable X-Ray on `FetchTicketHandler`
- [x] Enable X-Ray on `BitbucketFetchHandler`
- [x] Enable X-Ray on `ClaudeInvokeHandler`
- [x] Enable X-Ray on `PrCreatorHandler`
- [x] Enable X-Ray on `MergeWaitHandler`
- [x] Confirm Step Functions already has `tracingEnabled: true`

## What This Enables

- **Service Map:** visual graph of Lambda → DynamoDB → Secrets Manager → S3 call chains
- **Trace Drill-down:** latency breakdown per downstream SDK call
- **Error Correlation:** link Lambda errors to specific DynamoDB/API calls
- **Cold Start Analysis:** identify cold start frequency per handler

## Cost Note

~$5 per million traces. At typical usage levels (<1000 workflows/month), cost is negligible.

## Files Modified

- `infrastructure/lib/ai-driven-stack.ts` — added `tracing: lambda.Tracing.ACTIVE` to all 6 Lambda functions
