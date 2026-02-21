-- Workflows table
CREATE TABLE IF NOT EXISTS workflows (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    trigger_type VARCHAR(50) NOT NULL,
    trigger_config JSONB,
    created_by VARCHAR(36) NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Workflow steps table
CREATE TABLE IF NOT EXISTS workflow_steps (
    id VARCHAR(36) PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    step_order INTEGER NOT NULL,
    step_type VARCHAR(50) NOT NULL,
    config JSONB,
    retry_count INTEGER NOT NULL DEFAULT 0,
    timeout_seconds INTEGER NOT NULL DEFAULT 300,
    on_failure VARCHAR(50) NOT NULL DEFAULT 'STOP'
);

-- Workflow executions table
CREATE TABLE IF NOT EXISTS workflow_executions (
    id VARCHAR(36) PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(36) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    triggered_by VARCHAR(36),
    current_step_id VARCHAR(36),
    context JSONB,
    error_message VARCHAR(2000),
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_workflows_tenant_id ON workflows(tenant_id);
CREATE INDEX IF NOT EXISTS idx_workflows_status ON workflows(status);
CREATE INDEX IF NOT EXISTS idx_workflow_steps_workflow_id ON workflow_steps(workflow_id);
CREATE INDEX IF NOT EXISTS idx_executions_workflow_id ON workflow_executions(workflow_id);
CREATE INDEX IF NOT EXISTS idx_executions_tenant_id ON workflow_executions(tenant_id);
CREATE INDEX IF NOT EXISTS idx_executions_status ON workflow_executions(status);
