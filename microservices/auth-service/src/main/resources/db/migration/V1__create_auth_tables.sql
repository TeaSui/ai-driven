-- Tenants table
CREATE TABLE IF NOT EXISTS tenants (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    slug VARCHAR(100) NOT NULL UNIQUE,
    contact_email VARCHAR(255) NOT NULL,
    plan VARCHAR(50) NOT NULL DEFAULT 'STARTER',
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    max_users INTEGER NOT NULL DEFAULT 5,
    max_workflows INTEGER NOT NULL DEFAULT 10,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(36) NOT NULL REFERENCES tenants(id),
    username VARCHAR(50) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts INTEGER NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(username, tenant_id),
    UNIQUE(email, tenant_id)
);

-- User roles table
CREATE TABLE IF NOT EXISTS user_roles (
    user_id VARCHAR(36) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role VARCHAR(100) NOT NULL,
    PRIMARY KEY (user_id, role)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_users_tenant_id ON users(tenant_id);
CREATE INDEX IF NOT EXISTS idx_users_username_tenant ON users(username, tenant_id);
CREATE INDEX IF NOT EXISTS idx_users_email_tenant ON users(email, tenant_id);

-- Default demo tenant
INSERT INTO tenants (id, name, slug, contact_email, plan, status, max_users, max_workflows)
VALUES ('demo-tenant-001', 'Demo Company', 'demo', 'admin@demo.com', 'ENTERPRISE', 'ACTIVE', 100, 100)
ON CONFLICT DO NOTHING;
