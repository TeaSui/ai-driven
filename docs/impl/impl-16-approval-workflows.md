# impl-16: Approval Workflows
**Status**: DONE

## Context & Motivation

The current agent architecture automatically commits code and creates Pull Requests (via both Pipeline Mode and Agent Mode using MCP tools). While this enables high automation, it poses significant risk for destructive actions, production deployments, security-sensitive changes, or any operation requiring human judgment — violating the core "Security First, AI Second" principle.

A reliable human-in-the-loop approval mechanism is essential before executing high-risk actions, ensuring developers retain final control while the agent handles routine work.