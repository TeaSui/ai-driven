rootProject.name = "workflow-automation"

include(
    "core",
    "core:workflow-api",
    "core:workflow-engine",
    "core:tenant",
    "plugins",
    "plugins:plugin-api",
    "plugins:email-plugin",
    "plugins:webhook-plugin",
    "plugins:slack-plugin",
    "infrastructure",
    "infrastructure:persistence",
    "infrastructure:messaging",
    "app"
)
