CREATE TABLE IF NOT EXISTS workflow_definitions (
    id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(36)  NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    version         INTEGER      NOT NULL DEFAULT 1,
    status          VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    steps           JSONB,
    trigger_type    VARCHAR(100),
    trigger_config  JSONB,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    created_by      VARCHAR(36),
    UNIQUE (tenant_id, name, version)
);

CREATE TABLE IF NOT EXISTS workflow_executions (
    id                      VARCHAR(36)  NOT NULL PRIMARY KEY,
    workflow_definition_id  VARCHAR(36)  NOT NULL REFERENCES workflow_definitions(id),
    tenant_id               VARCHAR(36)  NOT NULL,
    status                  VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    current_step_id         VARCHAR(36),
    context                 JSONB,
    error_message           TEXT,
    started_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at            TIMESTAMP WITH TIME ZONE,
    triggered_by            VARCHAR(36)
);

CREATE INDEX idx_workflow_def_tenant ON workflow_definitions(tenant_id);
CREATE INDEX idx_workflow_def_status ON workflow_definitions(tenant_id, status);
CREATE INDEX idx_workflow_exec_tenant ON workflow_executions(tenant_id);
CREATE INDEX idx_workflow_exec_definition ON workflow_executions(workflow_definition_id);
CREATE INDEX idx_workflow_exec_status ON workflow_executions(tenant_id, status);
