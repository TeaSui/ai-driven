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
 * Backend endpoint is stubbed — returns optimistic success until implemented.
 */
export async function approveReview(id: string): Promise<ReviewActionResult> {
  const res = await authFetch(`/api/admin/reviews/${id}/approve`, { method: 'POST' })
  if (res.status === 404 || res.status === 405) {
    // Stub: endpoint not yet implemented — return optimistic result
    return { id, status: 'approved' }
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err?.detail ?? `Approve failed: ${res.status}`)
  }
  const json = await res.json().catch(() => ({}))
  return json ?? { id, status: 'approved' }
}

/**
 * Request changes on a review.
 * Calls POST /api/admin/reviews/{id}/request-changes with optional comment.
 * Backend endpoint is stubbed — returns optimistic success until implemented.
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
  if (res.status === 404 || res.status === 405) {
    // Stub: endpoint not yet implemented — return optimistic result
    return { id, status: 'changes_requested' }
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({}))
    throw new Error(err?.detail ?? `Request changes failed: ${res.status}`)
  }
  const json = await res.json().catch(() => ({}))
  return json ?? { id, status: 'changes_requested' }
}

export async function getInsights(range: string): Promise<InsightsData> {
  return f(`/api/admin/insights?range=${range}`)
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
}

export async function createRepo(req: CreateRepoRequest): Promise<Repo> {
  return authFetch('/api/admin/repos', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  }).then(async r => {
    if (!r.ok) {
      const err = await r.json().catch(() => ({}))
      throw new Error(err?.error?.message ?? `Request failed: ${r.status}`)
    }
    const json = await r.json()
    return json.data ?? json
  })
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
  return r.evaluations ?? []
}
