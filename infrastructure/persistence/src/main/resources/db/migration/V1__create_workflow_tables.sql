CREATE TABLE IF NOT EXISTS workflow_definitions (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    trigger_type VARCHAR(50) NOT NULL,
    trigger_config TEXT,
    steps_config TEXT NOT NULL,
    version INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_workflow_definitions_tenant_id ON workflow_definitions(tenant_id);
CREATE INDEX idx_workflow_definitions_tenant_enabled ON workflow_definitions(tenant_id, enabled);

CREATE TABLE IF NOT EXISTS workflow_executions (
    id VARCHAR(36) PRIMARY KEY,
    workflow_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    context_data TEXT,
    step_results TEXT DEFAULT '[]',
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    error_message VARCHAR(2000),
    CONSTRAINT fk_execution_workflow FOREIGN KEY (workflow_id) REFERENCES workflow_definitions(id)
);

CREATE INDEX idx_workflow_executions_workflow_id ON workflow_executions(workflow_id);
CREATE INDEX idx_workflow_executions_tenant_id ON workflow_executions(tenant_id);
CREATE INDEX idx_workflow_executions_status ON workflow_executions(status);
CREATE INDEX idx_workflow_executions_tenant_status ON workflow_executions(tenant_id, status);

CREATE TABLE IF NOT EXISTS tenant_configurations (
    tenant_id VARCHAR(100) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    plan VARCHAR(50) NOT NULL,
    enabled_plugins TEXT NOT NULL DEFAULT '[]',
    feature_flags TEXT DEFAULT '{}',
    limits_config TEXT,
    integrations_config TEXT DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
