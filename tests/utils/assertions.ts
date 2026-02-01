/**
 * Custom assertions for workflow testing
 */

export function expectValidTicketStructure(ticket: any): void {
    expect(ticket).toBeDefined();
    expect(ticket).toHaveProperty('ticketKey');
    expect(ticket).toHaveProperty('summary');
    expect(ticket).toHaveProperty('description');
    expect(ticket).toHaveProperty('status');
    expect(ticket).toHaveProperty('labels');

    expect(typeof ticket.ticketKey).toBe('string');
    expect(typeof ticket.summary).toBe('string');
    expect(Array.isArray(ticket.labels)).toBe(true);
}

export function expectValidPRStructure(pr: any): void {
    expect(pr).toBeDefined();
    expect(pr).toHaveProperty('id');
    expect(pr).toHaveProperty('url');
    expect(pr).toHaveProperty('title');
    expect(pr).toHaveProperty('state');

    expect(typeof pr.id).toBe('string');
    expect(typeof pr.url).toBe('string');
    expect(pr.url).toMatch(/^https?:\/\//);
}

export function expectValidExecutionStructure(execution: any): void {
    expect(execution).toBeDefined();
    expect(execution).toHaveProperty('executionArn');
    expect(execution).toHaveProperty('status');

    expect(typeof execution.executionArn).toBe('string');
    expect(execution.executionArn).toMatch(/^arn:aws:states:/);
    expect(['RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'ABORTED']).toContain(execution.status);
}

export function expectValidClaudeResponse(response: any): void {
    expect(response).toBeDefined();
    expect(typeof response).toBe('string');
    expect(response.length).toBeGreaterThan(0);
}

export function expectValidFileChanges(changes: any): void {
    expect(changes).toBeDefined();
    expect(typeof changes).toBe('object');

    // Verify at least one file change
    const fileCount = Object.keys(changes).length;
    expect(fileCount).toBeGreaterThan(0);

    // Verify each file has content
    Object.entries(changes).forEach(([path, content]) => {
        expect(typeof path).toBe('string');
        expect(path.length).toBeGreaterThan(0);
        expect(typeof content).toBe('string');
    });
}

export function expectTicketKeyFormat(ticketKey: string): void {
    expect(ticketKey).toMatch(/^[A-Z]+-\d+$/);
}

export function expectBranchNameFormat(branchName: string, ticketKey: string): void {
    expect(branchName).toBe(`ai - ticket - ${ticketKey} `);
}

export function expectPRTitleFormat(title: string, ticketKey: string): void {
    expect(title).toContain(ticketKey);
    expect(title).toMatch(/\[AI\]|AI-generated|Generated/i);
}

export function expectHttpError(error: any, expectedStatus: number): void {
    expect(error).toBeDefined();
    expect(error.response).toBeDefined();
    expect(error.response.status).toBe(expectedStatus);
}

export function expectValidDryRunOutput(output: any): void {
    expect(output).toBeDefined();
    expect(output).toHaveProperty('dryRun');
    expect(output.dryRun).toBe(true);
    expect(output).toHaveProperty('status');
    expect(output.status).toContain('TEST_COMPLETED');
}
