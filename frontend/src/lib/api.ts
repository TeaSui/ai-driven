// Typed API client for GoTyme-X AI backend

export interface Task {
  id: string
  title: string
  status: string
  agentType: string
  jiraKey?: string
  durationMs?: number
  tokenUsage?: number
  costUsd?: number
  iterations?: number
  prUrl?: string
  jiraUrl?: string
  createdAt: string
  startedAt?: string
  completedAt?: string
}

export interface DashboardSummary {
  tasksToday: number
  tasksYesterday: number
  successRate: number
  costToday: number
  dailyBudget: number
  budgetUsagePercent: number
  totalTokens: { input: number; output: number }
  activeAgents: Record<string, number>
}

export interface AgentEvent {
  type: string
  data: Record<string, unknown>
  timestamp?: string
}

export interface PipelineRun {
  id: string
  title: string
  jiraKey?: string
  repo?: string
  branch?: string
  prNumber?: number
  prUrl?: string
  stage: string
  stageStatus: string
  durationMs: number
  agent: string
  assignee?: string
  filesChanged?: number
  additions?: number
  deletions?: number
}

export type IncidentSeverity = 'P1' | 'P2' | 'P3' | 'P4'

export interface Incident {
  id: string
  title: string
  severity: IncidentSeverity
  status: string
  service: string
  metric: string
  currentValue: string
  threshold: string
  startedAt: string
  rca?: {
    rootCause: string
    confidence: string
    evidence: string[]
    suggestedFix: string
    relatedPR?: { number: number; url: string }
  }
}

export interface ReviewDetail {
  id: string
  repo: string
  prNumber: number
  branch: string
  title: string
  summary?: string
  author: string
  prUrl: string
  jiraKey?: string
  /** Status from backend: pending | in_progress | approved | changes_requested | merged */
  status: string
  createdAt?: string
  /** Duration of the review task, in milliseconds (backend derives from task timing). */
  durationMs?: number
  files: ReviewFile[]
}

export interface ReviewFile {
  path: string
  additions: number
  deletions: number
  comments: ReviewComment[]
}

export interface ReviewComment {
  id: string
  line: number
  severity: string
  category: string
  confidence: string
  body: string
  resolved?: boolean
  suggestion?: { before: string; after: string }
}

export interface InsightsData {
  summary: {
    hoursSaved: number
    hoursSavedDelta: number
    prsMerged: number
    prsMergedDelta: number
    avgCycleTimeHours: number
    avgCycleTimeDelta: number
    successRate: number
    successRateDelta: number
  }
  hoursByWeek: { week: string; human: number; agent: number }[]
  prSplit: { type: string; value: number }[]
  heatmap: number[][]
  topRepos: { repo: string; prs: number; hours: number; success: number }[]
}

import { authFetch } from './auth'

const f = (url: string) => authFetch(url).then(r => r.json())

export async function getDashboardSummary(): Promise<DashboardSummary> {
  return f('/api/admin/dashboard')
}

export async function getTasks(): Promise<Task[]> {
  const r = await f('/api/admin/tasks')
  return r.tasks ?? []
}

export async function getTask(id: string): Promise<Task> {
  return f(`/api/admin/tasks/${id}`)
}

export async function getTaskEvents(id: string): Promise<AgentEvent[]> {
  return f(`/api/admin/tasks/${id}/events`)
}

export interface TaskFile {
  path: string
  content: string
  size: number
  repo: string
  ref: string
  provider?: 'github' | 'bitbucket'
}

/**
 * Fetch the live contents of a file the agent touched, read straight from
 * GitHub. Use this instead of the `content` field from the event stream —
 * that field was truncated to 2 KB on older tasks and will show a partial
 * file. This endpoint always returns the real thing.
 */
export async function getTaskFile(taskId: string, path: string): Promise<TaskFile> {
  return f(`/api/admin/tasks/${taskId}/file?path=${encodeURIComponent(path)}`)
}

export interface TaskDiff {
  path: string
  status: string          // "added" | "modified" | "removed" | ...
  additions: number
  deletions: number
  patch: string           // unified-diff format (may be empty for binary files)
  repo: string
  prNumber: number
  provider?: 'github' | 'bitbucket'
}

/**
 * Fetch the unified diff patch for a file in this task's PR, so the UI can
 * render GitHub-style red/green line coloring instead of just the final file.
 */
export async function getTaskDiff(taskId: string, path: string): Promise<TaskDiff> {
  return f(`/api/admin/tasks/${taskId}/diff?path=${encodeURIComponent(path)}`)
}

export async function getUsageHistory(days: number) {
  return f(`/api/admin/usage/history?days=${days}`)
}

export async function getUsageByAgent() {
  return f('/api/admin/usage/by-agent')
}

export async function getPipelineRuns(): Promise<PipelineRun[]> {
  return f('/api/admin/pipeline/runs')
}

export async function getIncidents(): Promise<Incident[]> {
  return f('/api/admin/incidents')
}

export async function getReviews(): Promise<ReviewDetail[]> {
  return f('/api/admin/reviews')
}

// ── Review Action API (stubs — backend endpoints not yet implemented) ─────────

export interface ReviewActionResult {
  id: string
  status: string
}

/**
 * Approve a review.
 * Calls POST /api/admin/reviews/{id}/approve.
 * Submits an APPROVE review to the underlying PR via the SCM client and
 * marks the Review row ``approved`` in the DB. Errors propagate so the
 * optimistic UI update can roll back.
 */
export async function approveReview(
  id: string,
  comment: string = '',
): Promise<ReviewActionResult> {
  const res = await authFetch(`/api/admin/reviews/${id}/approve`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ comment }),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err?.detail ?? err?.error?.message ?? `Approve failed: ${res.status}`)
  }
  return (await res.json()) as ReviewActionResult
}

/**
 * Request changes on a review.
 * Submits a REQUEST_CHANGES review to the underlying PR via the SCM client
 * and marks the Review row ``changes_requested``. Errors propagate for
 * optimistic rollback.
 */
export async function requestChanges(
  id: string,
  comment: string = '',
): Promise<ReviewActionResult> {
  const res = await authFetch(`/api/admin/reviews/${id}/request-changes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ comment }),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err?.detail ?? err?.error?.message ?? `Request changes failed: ${res.status}`)
  }
  return (await res.json()) as ReviewActionResult
}

export async function getInsights(range: string): Promise<InsightsData> {
  return f(`/api/admin/insights?range=${range}`)
}

// ── Review sidebar: reviewers + checks (live from SCM) ────────────────────────

export interface PRReviewer {
  username: string
  displayName: string
  avatarUrl: string | null
  isBot: boolean
  /** "pending" | "approved" | "changes_requested" | "commented" | "dismissed" */
  state: string
  commentCount: number
}

export interface PRCheck {
  name: string
  /** "queued" | "in_progress" | "completed" */
  status: string
  /** "success" | "failure" | "neutral" | "cancelled" | "skipped" | "timed_out" | null */
  conclusion: string | null
  durationSeconds: number | null
  detailsUrl: string | null
}

export interface ReviewReviewersResponse {
  reviewers: PRReviewer[]
  scmError?: string
}

export interface ReviewChecksResponse {
  checks: PRCheck[]
  scmError?: string
}

export async function getReviewReviewers(reviewId: string): Promise<ReviewReviewersResponse> {
  return f(`/api/admin/reviews/${reviewId}/reviewers`)
}

export async function getReviewChecks(reviewId: string): Promise<ReviewChecksResponse> {
  return f(`/api/admin/reviews/${reviewId}/checks`)
}

export interface PRCommit {
  sha: string
  shortSha: string
  message: string
  author: string
  timestamp: string | null
  url: string | null
}

export interface ReviewCommitsResponse {
  commits: PRCommit[]
  scmError?: string
}

export async function getReviewCommits(reviewId: string): Promise<ReviewCommitsResponse> {
  return f(`/api/admin/reviews/${reviewId}/commits`)
}

// ── Chat API ──────────────────────────────────────────────────────────────────

export interface Repo {
  id: string
  fullName: string
  name: string
  scmType?: string
  defaultBranch?: string
  isActive?: boolean
  status?: 'active' | 'inactive'
  lastSynced?: string
}

export interface SendMessageRequest {
  message: string
  agentType: string
  context: Record<string, string>
}

export interface ChatResponse {
  taskId: string
  sessionId: string
  status: string
  message: string
}

export async function getRepos(): Promise<Repo[]> {
  const r = await f('/api/admin/repos')
  const list: Repo[] = Array.isArray(r) ? r : r.data ?? r.repos ?? []
  return list.map(repo => ({ ...repo, name: repo.name || repo.fullName }))
}

export interface CreateRepoRequest {
  fullName: string
  scmType: 'github' | 'bitbucket'
  defaultBranch?: string
}

export async function createRepo(req: CreateRepoRequest): Promise<Repo> {
  return authFetch('/api/admin/repos', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }).then(async r => {
    if (!r.ok) {
      const err = await r.json().catch(() => ({}))
      throw new Error(
        err?.error?.message ?? err?.detail ?? `Request failed: ${r.status}`,
      )
    }
    const json = await r.json()
    return json.data ?? json
  })
}

/**
 * Soft-delete a repository (sets is_active=false, preserves task history).
 * Pass `hard: true` to permanently remove the row and detach linked tasks.
 */
export async function deleteRepo(id: string, opts?: { hard?: boolean }): Promise<void> {
  const qs = opts?.hard ? '?hard=true' : ''
  const r = await authFetch(`/api/admin/repos/${id}${qs}`, { method: 'DELETE' })
  if (!r.ok && r.status !== 204) {
    const err = await r.json().catch(() => ({}))
    throw new Error(err?.error?.message ?? err?.detail ?? `Request failed: ${r.status}`)
  }
}

/** Reactivate a soft-deleted repository. */
export async function reactivateRepo(id: string): Promise<Repo> {
  const r = await authFetch(`/api/admin/repos/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ isActive: true }),
  })
  if (!r.ok) {
    const err = await r.json().catch(() => ({}))
    throw new Error(err?.error?.message ?? err?.detail ?? `Request failed: ${r.status}`)
  }
  const json = await r.json()
  return json.data ?? json
}

// ── Team / API-key management (Sprint 9 Phase 5) ─────────────────────────────

export interface MeResponse {
  teamId: string
  teamName: string
}

export async function getMe(): Promise<MeResponse> {
  const r = await f('/api/admin/me')
  return r.data ?? r
}

export interface KeyRotateResponse {
  /** The freshly-minted raw key — only shown once; store it securely. */
  apiKey: string
  /** ISO timestamp when the old key's 48h grace period ends. */
  oldKeyExpiresAt: string
  teamId: string
}

export async function rotateApiKey(teamId: string): Promise<KeyRotateResponse> {
  const r = await authFetch(`/api/admin/teams/${teamId}/rotate-key`, {
    method: 'POST',
  })
  if (!r.ok) {
    const err = await r.json().catch(() => ({}))
    throw new Error(
      err?.error?.message ?? err?.detail ?? `Request failed: ${r.status}`,
    )
  }
  return r.json()
}

export async function sendMessage(req: SendMessageRequest): Promise<ChatResponse> {
  return authFetch('/api/chat/send', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }).then(r => r.json())
}

// ── Task Control API ─────────────────────────────────────────────────────────

export async function pauseTask(taskId: string): Promise<void> {
  await authFetch(`/api/chat/tasks/${taskId}/pause`, { method: 'POST' })
}

export async function resumeTask(taskId: string): Promise<void> {
  await authFetch(`/api/chat/tasks/${taskId}/resume`, { method: 'POST' })
}

export async function stopTask(taskId: string): Promise<void> {
  await authFetch(`/api/chat/tasks/${taskId}/stop`, { method: 'POST' })
}

// ── Human-in-the-loop clarification ──────────────────────────────────────────

export async function respondToTask(taskId: string, answer: string): Promise<void> {
  await authFetch(`/api/chat/tasks/${taskId}/respond`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ answer }),
  })
}

export interface PendingQuestion {
  question: string
  context?: string
  options?: string[]
  timeout?: number
}

/** Extract the latest unanswered question from the event stream. */
export function extractPendingQuestion(
  events: AgentEvent[],
  taskStatus?: string,
): PendingQuestion | null {
  if (taskStatus && !['awaiting_input', 'running', 'coding', 'planning'].includes(taskStatus)) {
    return null
  }
  // Walk events backwards. A `human_response` after a `human_input_required`
  // means the question was already answered.
  let pending: PendingQuestion | null = null
  for (const e of events) {
    if (e.type === 'human_input_required') {
      const d = e.data as Record<string, unknown>
      pending = {
        question: (d.question as string) || '',
        context: d.context as string | undefined,
        options: (d.options as string[] | undefined) ?? [],
        timeout: d.timeout as number | undefined,
      }
    } else if (e.type === 'human_response') {
      pending = null
    }
  }
  return pending
}

// ── Evaluation API ───────────────────────────────────────────────────────────

export interface TaskEvaluation {
  id: string
  dimension: string
  score: number
  reasoning: string
  strengths: string[]
  weaknesses: string[]
  graderModel: string
  createdAt: string
}

export async function getTaskEvaluations(taskId: string): Promise<TaskEvaluation[]> {
  const r = await f(`/api/admin/tasks/${taskId}/evaluations`)
  // Accept both shapes: backend returns a raw array today; older docs said
  // `{evaluations: [...]}`. Either form must render.
  if (Array.isArray(r)) return r
  return r.evaluations ?? []
}

// ── T25: Specialists tab — artifact envelope + override hint ─────────────────

export type ArtifactVerdict =
  | 'pass'
  | 'pass_with_notes'
  | 'block'
  | 'error'
  | 'not_implemented'
  | string

export interface Artifact {
  id: string
  role: string
  verdict: ArtifactVerdict
  reasoning: string | null
  payload: Record<string, unknown>
  modelUsed: string | null
  inputTokens: number
  outputTokens: number
  /** Serialized as a string to preserve NUMERIC(10,6) precision across JSON.
   * Parse to float only for display — keep the raw string for exact math. */
  costUsd: string
  createdAt: string | null
}

export interface QAFailure {
  kind?: 'ac_unsatisfied' | 'security_rule_unfollowed' | string
  criterion?: string
  rule?: string
  reason?: string
  evidence?: string
  severity?: 'critical' | 'high' | 'medium' | 'low' | string
}

export type OverrideAction =
  | 'rerun_coding'
  | 'rerun_qa'
  | 'force_pass'
  | 'reap'

export interface TaskArtifactsResponse {
  taskId: string
  classifierTier: string | null
  artifacts: Artifact[]
  qaVerdict: 'pass' | 'block' | null
  qaFailures: QAFailure[] | null
  canOverride: boolean
  overrideActions: OverrideAction[]
}

/**
 * Fetch all specialist artifacts + QA outcome + override hints for a task.
 * Backend: T25's `GET /api/admin/tasks/{id}/artifacts`.
 *
 * Unwraps the `{data, meta}` envelope. Callers use the returned object
 * directly.
 */
export async function getTaskArtifacts(
  taskId: string,
): Promise<TaskArtifactsResponse> {
  const r = await f(`/api/admin/tasks/${taskId}/artifacts`)
  // Endpoint returns `{ data: {...}, meta: {...} }`. Tolerate both the
  // wrapped and bare shape so a backend shim change doesn't break the UI.
  return (r?.data ?? r) as TaskArtifactsResponse
}

export interface OverrideResponseData {
  taskId: string
  fromStatus: string
  toStatus: string
  action: string
  auditId: string
}

/** Narrow error thrown by overrideTaskBlock so the UI can branch on status. */
export class OverrideError extends Error {
  status: number
  detail: string
  constructor(status: number, detail: string) {
    super(detail)
    this.status = status
    this.detail = detail
    this.name = 'OverrideError'
  }
}

/**
 * Apply an admin override to a blocked / qa_failed / stuck task.
 * Backend: T24's `POST /api/admin/tasks/{id}/override-block`.
 *
 * Throws `OverrideError` on non-2xx so callers can branch on status
 * (409 → task status changed; 403 → lost admin; 400 → action not legal).
 */
export async function overrideTaskBlock(
  taskId: string,
  action: OverrideAction,
  reason: string,
): Promise<OverrideResponseData> {
  const res = await authFetch(`/api/admin/tasks/${taskId}/override-block`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, reason }),
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    const detail =
      err?.detail ??
      err?.error?.message ??
      `Override failed: ${res.status}`
    throw new OverrideError(res.status, detail)
  }
  const json = await res.json()
  return (json?.data ?? json) as OverrideResponseData
}

// ── SCM Connections (PR 2 — Settings > Connections) ──────────────────────────

export type ScmProvider = 'github' | 'bitbucket'

/**
 * A caller's OAuth connection to a source-control provider. Returned by
 * `GET /api/admin/me/connections`, which on this router returns a BARE
 * list[ConnectionResponse] — not wrapped in `{data, meta}`. The DELETE and
 * `/test` endpoints on the same router DO use the envelope, so the helpers
 * below tolerate both shapes with `json?.data ?? json`.
 */
export interface Connection {
  id: string
  provider: ScmProvider
  scmUsername: string
  scmEmail: string | null
  /** Space-separated scope string, e.g. "public_repo read:user" or
   *  "repo read:user". Classify repo-access level via `includes("repo")`
   *  — the full-access `repo` scope is a superset of `public_repo`. */
  scope: string
  /** ISO timestamp; null when the token is non-expiring (GitHub OAuth App). */
  expiresAt: string | null
  connectedAt: string
  lastUsedAt: string | null
}

export interface ConnectionTestResult {
  ok: boolean
  scmUsername?: string
  scmEmail?: string
  reason?: string
}

/**
 * Fetch the caller's SCM connections. Backend returns a bare array, not
 * `{data, meta}` — see `src/ai_driven/api/admin_connections.py`.
 */
export async function getMyConnections(): Promise<Connection[]> {
  const res = await authFetch('/api/admin/me/connections')
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(
      err?.detail ?? err?.error?.message ?? `Request failed: ${res.status}`,
    )
  }
  const json = await res.json()
  // Defensive: accept both bare-array (current backend) and wrapped shapes.
  return (Array.isArray(json) ? json : json?.data ?? []) as Connection[]
}

/** Revoke the caller's connection for a given provider. */
export async function disconnectConnection(provider: ScmProvider): Promise<void> {
  const res = await authFetch(`/api/admin/me/connections/${provider}`, {
    method: 'DELETE',
  })
  if (!res.ok && res.status !== 204) {
    const err = await res.json().catch(() => ({}))
    throw new Error(
      err?.detail ?? err?.error?.message ?? `Request failed: ${res.status}`,
    )
  }
}

/**
 * Ping the provider with the caller's token to check whether it's still
 * valid. Unwraps the `{data, meta}` envelope but falls back to the bare
 * shape so a backend shim change won't break the UI.
 */
export async function testConnection(
  provider: ScmProvider,
): Promise<ConnectionTestResult> {
  const res = await authFetch(`/api/admin/me/connections/${provider}/test`, {
    method: 'POST',
  })
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(
      err?.detail ?? err?.error?.message ?? `Request failed: ${res.status}`,
    )
  }
  const json = await res.json()
  return (json?.data ?? json) as ConnectionTestResult
}
