'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { useRouter } from 'next/navigation'
import { X, Code2, GitPullRequest, AlertTriangle, Loader2 } from 'lucide-react'
import { Btn } from '@/components/ui/btn'
import { sendMessage, getRepos, type Repo } from '@/lib/api'

type AgentType = 'coding' | 'review' | 'devops'

interface AgentOption {
  id: AgentType
  label: string
  icon: React.ReactNode
  description: string
  fields: ('repo' | 'jiraKey' | 'prNumber' | 'message')[]
}

const AGENTS: AgentOption[] = [
  {
    id: 'coding',
    label: 'Coding Agent',
    icon: <Code2 size={16} />,
    description: 'Implement features and fix bugs from Jira tickets',
    fields: ['repo', 'jiraKey', 'message'],
  },
  {
    id: 'review',
    label: 'Code Review',
    icon: <GitPullRequest size={16} />,
    description: 'Review pull requests and post inline comments',
    fields: ['repo', 'prNumber', 'message'],
  },
  {
    id: 'devops',
    label: 'DevOps Agent',
    icon: <AlertTriangle size={16} />,
    description: 'Investigate incidents, triage alerts, and suggest fixes',
    fields: ['message'],
  },
]

interface NewRunModalProps {
  open: boolean
  onClose: () => void
}

export function NewRunModal({ open, onClose }: NewRunModalProps) {
  const router = useRouter()
  const [agent, setAgent] = useState<AgentType>('coding')
  const [repo, setRepo] = useState('')
  const [jiraKey, setJiraKey] = useState('')
  const [prNumber, setPrNumber] = useState('')
  const [message, setMessage] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [repos, setRepos] = useState<Repo[]>([])
  const dialogRef = useRef<HTMLDivElement>(null)

  const selected = AGENTS.find(a => a.id === agent)!

  useEffect(() => {
    if (open) {
      getRepos().then(setRepos).catch(() => {})
    }
  }, [open])

  // Stable ref for onClose so effects don't re-run on every parent render
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose

  // Focus the first meaningful input once when modal opens
  useEffect(() => {
    if (!open) return
    const el = dialogRef.current?.querySelector<HTMLElement>(
      '[role="radiogroup"] button, select, input:not([disabled]), textarea:not([disabled])'
    )
    el?.focus()
  }, [open])

  // Keyboard: Escape to close + Tab focus trap
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCloseRef.current()
        return
      }
      if (e.key === 'Tab' && dialogRef.current) {
        // Exclude the close button from the focus-trap cycle
        const focusable = dialogRef.current.querySelectorAll<HTMLElement>(
          '[role="radio"], select:not([disabled]), input:not([disabled]), textarea:not([disabled]), button[type="button"]:not([aria-label="Close"]):not([disabled])'
        )
        if (!focusable.length) return
        const first = focusable[0]
        const last = focusable[focusable.length - 1]
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault()
          last.focus()
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault()
          first.focus()
        }
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open])

  const handleSubmit = useCallback(async () => {
    if (!message.trim()) {
      setError('Message is required')
      return
    }
    // Require repo whenever the selected agent lists it as a field.
    // Without a repo_full_name the backend can't instantiate an SCM
    // client, the CodingAgent hits "SCM client not configured" on
    // every repo.* tool call, and it either stalls or pauses on
    // ask_clarification asking which repo to use.
    if (selected.fields.includes('repo') && !repo) {
      setError('Repository is required for this agent')
      return
    }
    setError('')
    setSubmitting(true)
    try {
      const context: Record<string, string> = {}
      if (repo) context.repo_full_name = repo
      if (jiraKey) context.jira_key = jiraKey
      if (prNumber) context.pr_number = prNumber

      const res = await sendMessage({
        message: message.trim(),
        agentType: agent,
        context,
      })
      onClose()
      router.push(`/dashboard/tasks/${res.taskId}`)
    } catch (e) {
      setError('Failed to start run. Check that the backend is running.')
      setSubmitting(false)
    }
  }, [message, agent, repo, jiraKey, prNumber, onClose, router])

  if (!open) return null

  return (
    <>
      {/* Backdrop */}
      <div
        onClick={onClose}
        style={{
          position: 'fixed',
          inset: 0,
          background: 'rgba(0,0,0,0.45)',
          zIndex: 999,
          backdropFilter: 'blur(2px)',
        }}
      />
      {/* Modal */}
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-label="Start a new agent run"
        aria-describedby={error ? 'new-run-error' : undefined}
        style={{
          position: 'fixed',
          top: '50%',
          left: '50%',
          transform: 'translate(-50%, -50%)',
          zIndex: 1000,
          width: 520,
          maxHeight: '85vh',
          overflowY: 'auto',
          background: 'var(--panel)',
          border: '1px solid var(--panel-border)',
          borderRadius: 14,
          boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
        }}
      >
        {/* Header */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '16px 20px 12px', borderBottom: '1px solid var(--divider)',
        }}>
          <h2 style={{ margin: 0, fontSize: 15, fontWeight: 600, color: 'var(--text)' }}>
            New run
          </h2>
          <button
            onClick={onClose}
            aria-label="Close"
            style={{
              display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
              width: 28, height: 28, border: 'none', background: 'transparent',
              color: 'var(--text-muted)', cursor: 'pointer', borderRadius: 6,
            }}
          >
            <X size={16} />
          </button>
        </div>

        {/* Body */}
        <div style={{ padding: '16px 20px 20px', display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* Agent type selector */}
          <div>
            <div
              id="agent-type-label"
              style={{ fontSize: 12, fontWeight: 500, color: 'var(--text-subtle)', marginBottom: 6 }}
            >
              Agent
            </div>
            <div
              role="radiogroup"
              aria-labelledby="agent-type-label"
              style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 8 }}
            >
              {AGENTS.map(a => (
                <button
                  key={a.id}
                  role="radio"
                  aria-checked={agent === a.id}
                  onClick={() => setAgent(a.id)}
                  style={{
                    display: 'flex', alignItems: 'center', gap: 10,
                    padding: '10px 12px', borderRadius: 8, cursor: 'pointer',
                    border: `1.5px solid ${agent === a.id ? 'var(--accent-500)' : 'var(--border)'}`,
                    background: agent === a.id ? 'var(--accent-soft)' : 'var(--bg)',
                    color: agent === a.id ? 'var(--accent-600)' : 'var(--text-muted)',
                    fontFamily: 'inherit', textAlign: 'left',
                    transition: 'border-color .15s, background .15s',
                  }}
                >
                  <span style={{
                    width: 30, height: 30, borderRadius: 7,
                    background: agent === a.id ? 'var(--accent-500)' : 'var(--bg-muted)',
                    color: agent === a.id ? '#fff' : 'var(--text-muted)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
                  }}>
                    {a.icon}
                  </span>
                  <div>
                    <div style={{ fontSize: 12.5, fontWeight: 500, color: agent === a.id ? 'var(--text)' : 'var(--text-muted)' }}>
                      {a.label}
                    </div>
                    <div style={{ fontSize: 10.5, color: 'var(--text-faint)', marginTop: 1 }}>
                      {a.description}
                    </div>
                  </div>
                </button>
              ))}
            </div>
          </div>

          {/* Context fields — shown based on selected agent */}
          {selected.fields.includes('repo') && (
            <Field label="Repository" fieldId="new-run-repo">
              <select
                id="new-run-repo"
                value={repo}
                onChange={e => setRepo(e.target.value)}
                style={inputStyle}
              >
                <option value="">Select a repository...</option>
                {repos.map(r => (
                  <option key={r.id} value={r.fullName ?? r.name}>{r.fullName ?? r.name}</option>
                ))}
              </select>
            </Field>
          )}

          {selected.fields.includes('jiraKey') && (
            <Field label="Jira key" fieldId="new-run-jira-key">
              <input
                id="new-run-jira-key"
                type="text"
                placeholder="e.g. PROJ-123"
                value={jiraKey}
                onChange={e => setJiraKey(e.target.value)}
                style={inputStyle}
              />
            </Field>
          )}

          {selected.fields.includes('prNumber') && (
            <Field label="PR number" fieldId="new-run-pr-number">
              <input
                id="new-run-pr-number"
                type="text"
                placeholder="e.g. 42"
                value={prNumber}
                onChange={e => setPrNumber(e.target.value)}
                style={inputStyle}
              />
            </Field>
          )}

          {/* Message */}
          <Field label="Instructions" fieldId="new-run-instructions">
            <textarea
              id="new-run-instructions"
              aria-required="true"
              placeholder={
                agent === 'coding' ? 'Implement the Jira ticket, following existing patterns...'
                : agent === 'review' ? 'Review this PR for security, bugs, and code quality...'
                : 'Investigate the alert and suggest remediation...'
              }
              value={message}
              onChange={e => setMessage(e.target.value)}
              rows={3}
              style={{ ...inputStyle, resize: 'vertical', minHeight: 72 }}
            />
          </Field>

          {error && (
            <div
              id="new-run-error"
              role="alert"
              style={{ fontSize: 12, color: 'var(--status-danger)', padding: '4px 0' }}
            >
              {error}
            </div>
          )}
        </div>

        {/* Footer */}
        <div style={{
          display: 'flex', justifyContent: 'flex-end', gap: 8,
          padding: '12px 20px', borderTop: '1px solid var(--divider)',
        }}>
          <Btn size="sm" tone="ghost" onClick={onClose}>Cancel</Btn>
          <Btn
            size="sm"
            tone="brand"
            onClick={handleSubmit}
            disabled={submitting}
            icon={submitting ? <Loader2 size={12} style={{ animation: 'spin 1s linear infinite' }} /> : undefined}
          >
            {submitting ? 'Starting...' : 'Start run'}
          </Btn>
        </div>
      </div>
    </>
  )
}

// ── helpers ──────────────────────────────────────────────────────────────────

function Field({ label, fieldId, children }: { label: string; fieldId?: string; children: React.ReactNode }) {
  return (
    <div>
      <label
        htmlFor={fieldId}
        style={{ fontSize: 12, fontWeight: 500, color: 'var(--text-subtle)', marginBottom: 5, display: 'block' }}
      >
        {label}
      </label>
      {children}
    </div>
  )
}

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  fontSize: 13,
  fontFamily: 'inherit',
  border: '1px solid var(--border)',
  borderRadius: 8,
  background: 'var(--bg)',
  color: 'var(--text)',
  outline: 'none',
  boxSizing: 'border-box',
}
