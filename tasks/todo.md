# Priority 8 — Q4 2026 Cross-Agent Swarm

## [/] Phase 2: Researcher Extraction

### Core Engine
- [x] Create `WorkerAgent.java` interface
- [x] Create `CoderAgent.java` (wraps existing `AgentOrchestrator` logic)
- [x] Create `ResearcherAgent.java` (Haiku-based read-only agent)
- [x] Create `SwarmOrchestrator.java` (routes intent to Coder or Researcher)
- [x] Extract `IntentClassifier` or add intent routing logic to SwarmOrchestrator

### Lambdas & Infra
- [x] Update `ServiceFactory.java` to instantiate Swarm agents
- [x] Update `AgentProcessorHandler.java` to use `SwarmOrchestrator`
- [x] Add `CLAUDE_RESEARCHER_MODEL` env var to `ai-driven-stack.ts`

### Verification
- [ ] Run unit tests
- [ ] Test Q&A routing locally or via deployment
- [ ] Update walkthrough.md

## [REVIEW] Results
*To be populated upon completion.*
