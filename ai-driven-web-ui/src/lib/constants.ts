// Agent type display names
export const AGENT_TYPE_LABELS: Record<string, string> = {
  coding:   'Coding Agent',
  review:   'Code Review',
  devops:   'DevOps',
  workflow: 'Workflow',
  slack:    'Slack',
}

// Chart colors — teal palette consistent with design tokens
export const CHART_COLORS = {
  primary:   '#5fa0a8',  // brand-teal-500
  secondary: '#86b8be',  // brand-teal-400
  success:   '#2f9e6a',
  warn:      '#c28a2a',
  danger:    '#c94a4a',
  muted:     '#a5a8aa',
  series: [
    '#5fa0a8', // teal-500
    '#86b8be', // teal-400
    '#9cc7cd', // teal-300
    '#386a71', // teal-700
    '#c28a2a', // warn
    '#2f9e6a', // success
  ],
}

// Status → pill tone mapping
export const STATUS_TONE: Record<string, 'success' | 'warn' | 'danger' | 'info' | 'default'> = {
  completed:    'success',
  succeeded:    'success',
  merged:       'success',
  running:      'info',
  in_progress:  'info',
  pending:      'default',
  queued:       'default',
  failed:       'danger',
  error:        'danger',
  investigating:'warn',
  warning:      'warn',
  open:         'warn',
}
