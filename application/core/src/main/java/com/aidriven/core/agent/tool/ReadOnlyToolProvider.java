package com.aidriven.core.agent.tool;

import com.aidriven.spi.model.OperationContext;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.Set;

/**
 * A wrapper for ToolProvider that only exposes read-only tools.
 */
@RequiredArgsConstructor
public class ReadOnlyToolProvider implements ToolProvider {

    private static final Set<String> DENIED_KEYWORDS = Set.of(
            "create", "commit", "push", "delete", "update", "write", "post", "apply");

    private final ToolProvider delegate;

    @Override
    public String namespace() {
        return delegate.namespace();
    }

    @Override
    public List<Tool> toolDefinitions() {
        return delegate.toolDefinitions().stream()
                .filter(this::isReadOnly)
                .toList();
    }

    @Override
    public ToolResult execute(OperationContext context, ToolCall call) {
        if (!isReadOnly(call.name())) {
            return ToolResult.error(call.id(), "Tool '" + call.name() + "' is not allowed in read-only mode.");
        }
        return delegate.execute(context, call);
    }

    private boolean isReadOnly(Tool tool) {
        return isReadOnly(tool.name());
    }

    private boolean isReadOnly(String toolName) {
        String nameLower = toolName.toLowerCase();
        return DENIED_KEYWORDS.stream().noneMatch(nameLower::contains);
    }

    @Override
    public int maxOutputChars() {
        return delegate.maxOutputChars();
    }
}
