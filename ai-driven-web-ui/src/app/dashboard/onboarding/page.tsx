'use client'

import React, { Suspense, useState, useEffect, useCallback, useId, useRef } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { Code2, GitPullRequest, AlertTriangle, GitCommit, Check, Loader2, ArrowLeft, ArrowRight, X } from 'lucide-react'
import { Btn } from '@/components/ui/btn'
import { createRepo, sendMessage } from '@/lib/api'
import { setOnboardedFlag } from '@/hooks/use-onboarding'

// ── Types ─────────────────────────────────────────────────────────────────────

type AgentType = 'coding' | 'review' | 'workflow' | 'devops'

type ScmType = 'github' | 'bitbucket'

const TOTAL_STEPS = 4

// ── Agent definitions ─────────────────────────────────────────────────────────

interface AgentDef {
  id: AgentType
  label: string
  icon: React.ReactNode
  description: string
}

const AGENTS: AgentDef[] = [
  {
    id: 'coding',
    label: 'Coding Agent',
    icon: <Code2 size={16} />,
    description: 'Implements features and bug fixes directly from Jira tickets into pull requests.',
  },
  {
    id: 'review',
    label: 'Code Review',
    icon: <GitPullRequest size={16} />,
    description: 'Reviews open PRs for security issues, bugs, and style — posts inline comments.',
  },
  {
    id: 'workflow',
    label: 'Workflow Agent',
    icon: <GitCommit size={16} />,
    description: 'Orchestrates multi-step pipelines across agents for end-to-end automation.',
  },
  {
    id: 'devops',
    label: 'DevOps Agent',
    icon: <AlertTriangle size={16} />,
    description: 'Investigates alerts and incidents, produces root-cause analysis and remediation plans.',
  },
]

// ── Input style (shared) ──────────────────────────────────────────────────────

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '9px 12px',
  fontSize: 13.5,
  fontFamily: 'inherit',
  border: '1px solid var(--border)',
  borderRadius: 8,
  background: 'var(--bg)',
  color: 'var(--text)',
  outline: 'none',
  boxSizing: 'border-box',
  transition: 'border-color .15s',
}

// ── Step indicator ────────────────────────────────────────────────────────────

function StepIndicator({ current, total }: { current: number; total: number }) {
  return (
    <div
      role="list"
      aria-label="Onboarding steps"
      style={{ display: 'flex', alignItems: 'center', gap: 0 }}
    >
      {Array.from({ length: total }, (_, i) => {
        const stepNum = i + 1
        const done = stepNum < current
        const active = stepNum === current

        return (
          <React.Fragment key={stepNum}>
            <div
              role="listitem"
              aria-label={`Step ${stepNum}${done ? ' (completed)' : active ? ' (current)' : ''}`}
              aria-current={active ? 'step' : undefined}
              style={{
                width: 28,
                height: 28,
                borderRadius: '50%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 12,
                fontWeight: 600,
                flexShrink: 0,
                transition: 'background .2s, border-color .2s, color .2s',
                background: done
                  ? 'var(--accent-500)'
                  : active
                    ? 'var(--accent-500)'
                    : 'var(--bg-muted)',
                color: done || active ? '#fff' : 'var(--text-faint)',
                border: `2px solid ${done || active ? 'var(--accent-500)' : 'var(--border)'}`,
              }}
            >
              {done ? <Check size={13} aria-hidden="true" /> : stepNum}
            </div>
            {i < total - 1 && (
              <div
                aria-hidden="true"
                style={{
                  flex: 1,
                  height: 2,
                  minWidth: 32,
                  background: done ? 'var(--accent-500)' : 'var(--border)',
                  transition: 'background .2s',
                  margin: '0 2px',
                }}
              />
            )}
          </React.Fragment>
        )
      })}
    </div>
  )
}

// ── Step 1: Welcome ───────────────────────────────────────────────────────────

function StepWelcome() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
      <div>
        <div
          aria-hidden="true"
          style={{
            width: 56,
            height: 56,
            borderRadius: 14,
            background: 'linear-gradient(135deg, var(--accent-500), var(--brand-teal-700))',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            marginBottom: 20,
          }}
        >
          <svg viewBox="0 0 32 32" width={32} height={32} fill="none" aria-hidden="true">
            <path
              d="M8 8L16 16L24 8 M8 24L16 16L24 24 M16 16L16 24"
              stroke="#fff"
              strokeWidth="2.5"
              strokeLinecap="square"
            />
          </svg>
        </div>

        <h2
          className="gtx-serif"
          style={{
            margin: 0,
            fontSize: 28,
            fontWeight: 400,
            letterSpacing: -0.6,
            color: 'var(--text)',
            lineHeight: 1.15,
          }}
        >
          Welcome to{' '}
          <em style={{ color: 'var(--accent-600)', fontStyle: 'italic' }}>GoTymeX AI</em>
        </h2>

        <p style={{ margin: '10px 0 0', fontSize: 14, color: 'var(--text-muted)', lineHeight: 1.6 }}>
          Your engineering org, augmented. Let us walk you through the basics in three quick steps.
        </p>
      </div>

      <ul
        aria-label="Platform highlights"
        style={{
          margin: 0,
          padding: 0,
          listStyle: 'none',
          display: 'flex',
          flexDirection: 'column',
          gap: 12,
        }}
      >
        {[
          {
            icon: <Code2 size={15} />,
            title: 'Automated code changes',
            body: 'Agents read Jira tickets and open pull requests — fully automated, fully attributable.',
          },
          {
            icon: <GitPullRequest size={15} />,
            title: 'Inline code review',
            body: 'Every PR gets an AI review with line-level comments before your team even looks at it.',
          },
          {
            icon: <AlertTriangle size={15} />,
            title: 'Incident triage while you sleep',
            body: 'DevOps agent correlates alerts, finds root causes, and drafts remediation before you wake up.',
          },
        ].map(({ icon, title, body }) => (
          <li
            key={title}
            style={{
              display: 'flex',
              gap: 14,
              padding: '14px 16px',
              background: 'var(--bg-muted)',
              borderRadius: 10,
              border: '1px solid var(--border)',
            }}
          >
            <span
              aria-hidden="true"
              style={{
                width: 32,
                height: 32,
                borderRadius: 8,
                background: 'var(--accent-soft)',
                color: 'var(--accent-600)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0,
              }}
            >
              {icon}
            </span>
            <div>
              <div style={{ fontSize: 13.5, fontWeight: 500, color: 'var(--text)' }}>{title}</div>
              <div style={{ fontSize: 12.5, color: 'var(--text-muted)', marginTop: 2, lineHeight: 1.5 }}>{body}</div>
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}

// ── Step 2: Add Repository ────────────────────────────────────────────────────

interface StepAddRepoProps {
  onRepoAdded: (repoFullName: string) => void
}

function StepAddRepo({ onRepoAdded }: StepAddRepoProps) {
  const fullNameId = useId()
  const scmId = useId()
  const formRef = useRef<HTMLFormElement>(null)
  const [fullName, setFullName] = useState('')
  const [scmType, setScmType] = useState<ScmType>('github')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [fieldErrors, setFieldErrors] = useState<{ fullName?: string }>({})

  const validate = useCallback(() => {
    const errs: { fullName?: string } = {}
    if (!fullName.trim()) {
      errs.fullName = 'Repository name is required'
    } else if (!/^[^/]+\/[^/]+$/.test(fullName.trim())) {
      errs.fullName = 'Use "owner/repo" format, e.g. acme-corp/backend'
    }
    setFieldErrors(errs)
    return Object.keys(errs).length === 0
  }, [fullName])

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault()
    if (!validate()) return

    setError('')
    setSubmitting(true)
    try {
      await createRepo({ fullName: fullName.trim(), scmType })
      onRepoAdded(fullName.trim())
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to add repository. Please try again.')
      setSubmitting(false)
    }
  }, [fullName, scmType, validate, onRepoAdded])

  const handleAddClick = useCallback(() => {
    formRef.current?.requestSubmit()
  }, [])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div>
        <h2 style={{ margin: 0, fontSize: 18, fontWeight: 600, color: 'var(--text)', letterSpacing: -0.3 }}>
          Add your first repository
        </h2>
        <p style={{ margin: '6px 0 0', fontSize: 13.5, color: 'var(--text-muted)', lineHeight: 1.5 }}>
          Connect a repository so agents know where to open pull requests and read code.
        </p>
      </div>

      <form
        ref={formRef}
        onSubmit={handleSubmit}
        noValidate
        style={{ display: 'flex', flexDirection: 'column', gap: 16 }}
        aria-label="Add repository form"
      >
        <div>
          <label
            htmlFor={fullNameId}
            style={{ display: 'block', fontSize: 12, fontWeight: 500, color: 'var(--text-subtle)', marginBottom: 6 }}
          >
            Repository name <span aria-label="required">*</span>
          </label>
          <input
            id={fullNameId}
            type="text"
            placeholder="owner/repo-name"
            value={fullName}
            onChange={e => {
              setFullName(e.target.value)
              if (fieldErrors.fullName) setFieldErrors({})
            }}
            aria-required="true"
            aria-invalid={!!fieldErrors.fullName}
            aria-describedby={fieldErrors.fullName ? `${fullNameId}-error` : undefined}
            style={{
              ...inputStyle,
              borderColor: fieldErrors.fullName ? 'var(--status-danger)' : undefined,
            }}
          />
          {fieldErrors.fullName && (
            <div
              id={`${fullNameId}-error`}
              role="alert"
              style={{ fontSize: 12, color: 'var(--status-danger)', marginTop: 4 }}
            >
              {fieldErrors.fullName}
            </div>
          )}
        </div>

        <div>
          <label
            htmlFor={scmId}
            style={{ display: 'block', fontSize: 12, fontWeight: 500, color: 'var(--text-subtle)', marginBottom: 6 }}
          >
            Source control
          </label>
          <select
            id={scmId}
            value={scmType}
            onChange={e => setScmType(e.target.value as ScmType)}
            style={inputStyle}
          >
            <option value="github">GitHub</option>
            <option value="bitbucket">Bitbucket</option>
          </select>
        </div>

        {error && (
          <div role="alert" style={{ fontSize: 12.5, color: 'var(--status-danger)', padding: '8px 12px', background: 'var(--status-danger-bg)', borderRadius: 8 }}>
            {error}
          </div>
        )}

      </form>

      <Btn
        tone="brand"
        size="md"
        onClick={handleAddClick}
        disabled={submitting}
        icon={submitting ? <Loader2 size={14} style={{ animation: 'spin 1s linear infinite' }} /> : undefined}
        style={{ alignSelf: 'flex-start', minWidth: 120 }}
      >
        {submitting ? 'Adding...' : 'Add repository'}
      </Btn>
    </div>
  )
}

// ── Step 3: Configure ─────────────────────────────────────────────────────────

interface StepConfigureProps {
  selectedAgent: AgentType
  onSelect: (agent: AgentType) => void
}

function StepConfigure({ selectedAgent, onSelect }: StepConfigureProps) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div>
        <h2 style={{ margin: 0, fontSize: 18, fontWeight: 600, color: 'var(--text)', letterSpacing: -0.3 }}>
          Choose your default agent
        </h2>
        <p style={{ margin: '6px 0 0', fontSize: 13.5, color: 'var(--text-muted)', lineHeight: 1.5 }}>
          This will pre-select the agent when you start a new run. You can always change it later.
        </p>
      </div>

      <fieldset style={{ border: 'none', padding: 0, margin: 0 }}>
        <legend className="sr-only">Select default agent type</legend>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {AGENTS.map(agent => {
            const isSelected = selectedAgent === agent.id
            return (
              <label
                key={agent.id}
                style={{
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: 14,
                  padding: '14px 16px',
                  borderRadius: 10,
                  border: `1.5px solid ${isSelected ? 'var(--accent-500)' : 'var(--border)'}`,
                  background: isSelected ? 'var(--accent-soft)' : 'var(--bg)',
                  cursor: 'pointer',
                  transition: 'border-color .15s, background .15s',
                }}
              >
                <input
                  type="radio"
                  name="defaultAgent"
                  value={agent.id}
                  checked={isSelected}
                  onChange={() => onSelect(agent.id)}
                  style={{ marginTop: 2, accentColor: 'var(--accent-500)', width: 16, height: 16, flexShrink: 0 }}
                  aria-label={`${agent.label}: ${agent.description}`}
                />
                <span
                  aria-hidden="true"
                  style={{
                    width: 34,
                    height: 34,
                    borderRadius: 8,
                    background: isSelected ? 'var(--accent-500)' : 'var(--bg-muted)',
                    color: isSelected ? '#fff' : 'var(--text-muted)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    flexShrink: 0,
                  }}
                >
                  {agent.icon}
                </span>
                <div>
                  <div style={{ fontSize: 13.5, fontWeight: 500, color: 'var(--text)' }}>{agent.label}</div>
                  <div style={{ fontSize: 12.5, color: 'var(--text-muted)', marginTop: 2, lineHeight: 1.5 }}>
                    {agent.description}
                  </div>
                </div>
              </label>
            )
          })}
        </div>
      </fieldset>
    </div>
  )
}

// ── Step 4: First Task ────────────────────────────────────────────────────────

interface StepFirstTaskProps {
  repoFullName: string
  defaultAgent: AgentType
  onComplete: () => void
}

function StepFirstTask({ repoFullName, defaultAgent, onComplete }: StepFirstTaskProps) {
  const taskId = useId()
  const agentSelectId = useId()
  const router = useRouter()
  const [taskDescription, setTaskDescription] = useState('')
  const [agentType, setAgentType] = useState<AgentType>(defaultAgent)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [fieldError, setFieldError] = useState('')

  const handleSubmit = useCallback(async (e: React.FormEvent) => {
    e.preventDefault()
    if (!taskDescription.trim()) {
      setFieldError('Please describe what you want the agent to do.')
      return
    }
    setFieldError('')
    setError('')
    setSubmitting(true)

    try {
      const context: Record<string, string> = {}
      if (repoFullName) context.repo_full_name = repoFullName

      const res = await sendMessage({
        message: taskDescription.trim(),
        agentType,
        context,
      })
      setOnboardedFlag()
      onComplete()
      router.push(`/dashboard/tasks/${res.taskId}`)
    } catch {
      setError('Failed to start the task. Check that the backend is running.')
      setSubmitting(false)
    }
  }, [taskDescription, agentType, repoFullName, router, onComplete])

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div>
        <h2 style={{ margin: 0, fontSize: 18, fontWeight: 600, color: 'var(--text)', letterSpacing: -0.3 }}>
          Start your first task
        </h2>
        <p style={{ margin: '6px 0 0', fontSize: 13.5, color: 'var(--text-muted)', lineHeight: 1.5 }}>
          Describe what you want the agent to do. Be as specific as you like.
        </p>
      </div>

      <form
        onSubmit={handleSubmit}
        noValidate
        style={{ display: 'flex', flexDirection: 'column', gap: 16 }}
        aria-label="Start first task form"
      >
        <div>
          <label
            htmlFor={agentSelectId}
            style={{ display: 'block', fontSize: 12, fontWeight: 500, color: 'var(--text-subtle)', marginBottom: 6 }}
          >
            Agent
          </label>
          <select
            id={agentSelectId}
            value={agentType}
            onChange={e => setAgentType(e.target.value as AgentType)}
            style={inputStyle}
          >
            {AGENTS.map(a => (
              <option key={a.id} value={a.id}>{a.label}</option>
            ))}
          </select>
        </div>

        <div>
          <label
            htmlFor={taskId}
            style={{ display: 'block', fontSize: 12, fontWeight: 500, color: 'var(--text-subtle)', marginBottom: 6 }}
          >
            Task description <span aria-label="required">*</span>
          </label>
          <textarea
            id={taskId}
            placeholder={
              agentType === 'coding'
                ? 'Implement the login rate-limiting feature from PROJ-42, following the existing auth middleware pattern...'
                : agentType === 'review'
                  ? 'Review PR #88 for security issues and code quality in the payment module...'
                  : agentType === 'devops'
                    ? 'Investigate the high p99 latency alert on the orders service and suggest remediation...'
                    : 'Run the full CI/CD pipeline for the upcoming v2.0 release...'
            }
            value={taskDescription}
            onChange={e => {
              setTaskDescription(e.target.value)
              if (fieldError) setFieldError('')
            }}
            rows={5}
            aria-required="true"
            aria-invalid={!!fieldError}
            aria-describedby={fieldError ? `${taskId}-error` : undefined}
            style={{
              ...inputStyle,
              resize: 'vertical',
              minHeight: 110,
              borderColor: fieldError ? 'var(--status-danger)' : undefined,
            }}
          />
          {fieldError && (
            <div
              id={`${taskId}-error`}
              role="alert"
              style={{ fontSize: 12, color: 'var(--status-danger)', marginTop: 4 }}
            >
              {fieldError}
            </div>
          )}
        </div>

        {error && (
          <div role="alert" style={{ fontSize: 12.5, color: 'var(--status-danger)', padding: '8px 12px', background: 'var(--status-danger-bg)', borderRadius: 8 }}>
            {error}
          </div>
        )}

        <Btn
          type="submit"
          tone="brand"
          size="md"
          disabled={submitting}
          icon={submitting ? <Loader2 size={14} style={{ animation: 'spin 1s linear infinite' }} /> : undefined}
          style={{ alignSelf: 'flex-start', minWidth: 140 }}
        >
          {submitting ? 'Starting...' : 'Start task'}
        </Btn>
      </form>
    </div>
  )
}

// ── Main Onboarding Page ──────────────────────────────────────────────────────

const STEP_LABELS = ['Welcome', 'Repository', 'Configure', 'First task']

export default function OnboardingPage() {
  return (
    <Suspense fallback={null}>
      <OnboardingContent />
    </Suspense>
  )
}

function OnboardingContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const stepParam = parseInt(searchParams.get('step') ?? '1', 10)
  const currentStep = isNaN(stepParam) || stepParam < 1 || stepParam > TOTAL_STEPS ? 1 : stepParam

  const [repoFullName, setRepoFullName] = useState('')
  const [defaultAgent, setDefaultAgent] = useState<AgentType>('coding')
  // Track whether step 2 has been completed (repo added) within this session
  const [repoAdded, setRepoAdded] = useState(false)

  // Sync step to URL without pushing a new history entry for every change
  const goToStep = useCallback((step: number) => {
    const params = new URLSearchParams(searchParams.toString())
    params.set('step', String(step))
    router.replace(`/dashboard/onboarding?${params.toString()}`)
  }, [router, searchParams])

  const handleNext = useCallback(() => {
    if (currentStep < TOTAL_STEPS) goToStep(currentStep + 1)
  }, [currentStep, goToStep])

  const handleBack = useCallback(() => {
    if (currentStep > 1) goToStep(currentStep - 1)
  }, [currentStep, goToStep])

  const handleSkip = useCallback(() => {
    setOnboardedFlag()
    router.replace('/dashboard')
  }, [router])

  const handleRepoAdded = useCallback((name: string) => {
    setRepoFullName(name)
    setRepoAdded(true)
    goToStep(3)
  }, [goToStep])

  // Step 2 Next is only available after repo has been added
  const canAdvanceFromStep2 = repoAdded

  return (
    <main
      style={{
        flex: 1,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'flex-start',
        background: 'var(--bg)',
        minHeight: '100vh',
        padding: '40px 16px 80px',
        overflowY: 'auto',
      }}
    >
      {/* Card */}
      <div
        style={{
          width: '100%',
          maxWidth: 560,
          background: 'var(--panel)',
          border: '1px solid var(--panel-border)',
          borderRadius: 16,
          boxShadow: 'var(--shadow-lg)',
          overflow: 'hidden',
        }}
      >
        {/* Header */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '20px 24px 16px',
            borderBottom: '1px solid var(--divider)',
          }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <StepIndicator current={currentStep} total={TOTAL_STEPS} />
            <div style={{ fontSize: 11.5, color: 'var(--text-faint)', letterSpacing: 0.2 }}>
              Step {currentStep} of {TOTAL_STEPS} — {STEP_LABELS[currentStep - 1]}
            </div>
          </div>
          <button
            type="button"
            onClick={handleSkip}
            aria-label="Skip onboarding and go to dashboard"
            title="Skip onboarding"
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 32,
              height: 32,
              border: 'none',
              background: 'transparent',
              color: 'var(--text-faint)',
              cursor: 'pointer',
              borderRadius: 8,
              flexShrink: 0,
            }}
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>

        {/* Body */}
        <div
          data-step-container
          style={{ padding: '28px 28px 24px' }}
        >
          {currentStep === 1 && <StepWelcome />}
          {currentStep === 2 && (
            <StepAddRepo onRepoAdded={handleRepoAdded} />
          )}
          {currentStep === 3 && (
            <StepConfigure selectedAgent={defaultAgent} onSelect={setDefaultAgent} />
          )}
          {currentStep === 4 && (
            <StepFirstTask
              repoFullName={repoFullName}
              defaultAgent={defaultAgent}
              onComplete={() => setOnboardedFlag()}
            />
          )}
        </div>

        {/* Footer navigation */}
        {/* Step 4 manages its own submit button; step 2 manages its own CTA — navigation footer handles Back/Next/Skip */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '14px 24px 20px',
            borderTop: '1px solid var(--divider)',
            gap: 8,
          }}
        >
          <div style={{ display: 'flex', gap: 8 }}>
            {currentStep > 1 && (
              <Btn
                size="sm"
                tone="ghost"
                onClick={handleBack}
                icon={<ArrowLeft size={13} />}
              >
                Back
              </Btn>
            )}
          </div>

          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            {/* Skip button — available on steps 2-3 */}
            {(currentStep === 2 || currentStep === 3) && (
              <Btn size="sm" tone="bare" onClick={handleSkip}>
                Skip setup
              </Btn>
            )}

            {/* Next — steps 1 and 3; step 2 uses inline CTA, step 4 uses its own submit */}
            {currentStep === 1 && (
              <Btn
                size="sm"
                tone="brand"
                onClick={handleNext}
                iconRight={<ArrowRight size={13} />}
              >
                Get started
              </Btn>
            )}
            {currentStep === 2 && canAdvanceFromStep2 && (
              <Btn
                size="sm"
                tone="brand"
                onClick={handleNext}
                iconRight={<ArrowRight size={13} />}
              >
                Next
              </Btn>
            )}
            {currentStep === 3 && (
              <Btn
                size="sm"
                tone="brand"
                onClick={handleNext}
                iconRight={<ArrowRight size={13} />}
              >
                Next
              </Btn>
            )}
          </div>
        </div>
      </div>

      {/* Skip link at page bottom */}
      <button
        type="button"
        onClick={handleSkip}
        style={{
          marginTop: 20,
          background: 'none',
          border: 'none',
          fontSize: 12.5,
          color: 'var(--text-faint)',
          cursor: 'pointer',
          textDecoration: 'underline',
          textDecorationStyle: 'dotted',
          padding: '4px 8px',
          borderRadius: 4,
        }}
      >
        Skip and go to dashboard
      </button>
    </main>
  )
}
