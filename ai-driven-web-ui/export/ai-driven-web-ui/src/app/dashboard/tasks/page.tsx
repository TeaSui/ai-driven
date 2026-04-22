'use client'

import { useState, useMemo } from 'react'
import useSWR from 'swr'
import Link from 'next/link'
import { Search, ArrowUpDown } from 'lucide-react'
import { getTasks, type Task } from '@/lib/api'
import { Card } from '@/components/ui/card'
import { StatusBadge, AgentBadge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { formatCost, formatTokens } from '@/lib/format'
import { cn } from '@/lib/cn'
import { AGENT_TYPES, type AgentType, AGENT_CONFIG } from '@/lib/constants'

type SortKey = 'createdAt' | 'durationMs' | 'costUsd' | 'tokenUsage'
type SortDir = 'asc' | 'desc'

export default function TasksPage() {
  const { data: tasks, isLoading } = useSWR('tasks', getTasks, {
    refreshInterval: 5000,
  })

  const [query, setQuery] = useState('')
  const [agentFilter, setAgentFilter] = useState<AgentType | 'all'>('all')
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [sortKey, setSortKey] = useState<SortKey>('createdAt')
  const [sortDir, setSortDir] = useState<SortDir>('desc')

  const filtered = useMemo(() => {
    if (!tasks) return []
    let out = tasks
    if (query.trim()) {
      const q = query.toLowerCase()
      out = out.filter(
        (t) =>
          t.title.toLowerCase().includes(q) ||
          t.id.toLowerCase().includes(q) ||
          (t.jiraKey?.toLowerCase().includes(q) ?? false),
      )
    }
    if (agentFilter !== 'all') {
      out = out.filter((t) => t.agentType === agentFilter)
    }
    if (statusFilter !== 'all') {
      out = out.filter((t) => t.status === statusFilter)
    }
    out = [...out].sort((a, b) => {
      const av = a[sortKey] ?? 0
      const bv = b[sortKey] ?? 0
      if (sortKey === 'createdAt') {
        const at = new Date(a.createdAt).getTime()
        const bt = new Date(b.createdAt).getTime()
        return sortDir === 'asc' ? at - bt : bt - at
      }
      return sortDir === 'asc'
        ? (av as number) - (bv as number)
        : (bv as number) - (av as number)
    })
    return out
  }, [tasks, query, agentFilter, statusFilter, sortKey, sortDir])

  function toggleSort(key: SortKey) {
    if (sortKey === key) {
      setSortDir(sortDir === 'asc' ? 'desc' : 'asc')
    } else {
      setSortKey(key)
      setSortDir('desc')
    }
  }

  const statusOptions = [
    'all',
    'queued',
    'running',
    'completed',
    'failed',
    'cancelled',
  ]

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-100">Tasks</h1>
          <p className="text-xs text-slate-500 mt-1">
            All agent tasks · {tasks?.length ?? 0} total
          </p>
        </div>
      </div>

      {/* Filter bar */}
      <Card className="p-4">
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative flex-1 min-w-[200px]">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-slate-500 pointer-events-none" />
            <input
              type="text"
              placeholder="Search title, ID, or Jira key…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              className="w-full rounded-lg border border-slate-700 bg-surface-base pl-9 pr-3 py-2 text-sm text-slate-100 placeholder:text-slate-500 focus:border-brand focus:ring-1 focus:ring-brand transition-colors"
            />
          </div>

          {/* Agent filter */}
          <div className="flex gap-1 rounded-lg bg-surface-base p-1 border border-slate-700/50">
            <button
              onClick={() => setAgentFilter('all')}
              className={cn(
                'px-2.5 py-1 text-xs rounded font-medium transition-colors',
                agentFilter === 'all'
                  ? 'bg-brand/20 text-brand-light'
                  : 'text-slate-500 hover:text-slate-300',
              )}
            >
              All agents
            </button>
            {AGENT_TYPES.map((type) => (
              <button
                key={type}
                onClick={() => setAgentFilter(type)}
                className={cn(
                  'px-2.5 py-1 text-xs rounded font-medium transition-colors',
                  agentFilter === type
                    ? 'bg-brand/20 text-brand-light'
                    : 'text-slate-500 hover:text-slate-300',
                )}
              >
                {AGENT_CONFIG[type].label}
              </button>
            ))}
          </div>

          {/* Status filter */}
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="rounded-lg border border-slate-700 bg-surface-base px-3 py-2 text-sm text-slate-100 focus:border-brand focus:ring-1 focus:ring-brand transition-colors"
          >
            {statusOptions.map((s) => (
              <option key={s} value={s}>
                {s === 'all' ? 'All statuses' : s}
              </option>
            ))}
          </select>
        </div>
      </Card>

      {/* Table */}
      <Card className="p-0 overflow-hidden">
        {isLoading ? (
          <div className="p-6 space-y-2">
            {Array.from({ length: 6 }).map((_, i) => (
              <Skeleton key={i} className="h-10" />
            ))}
          </div>
        ) : filtered.length === 0 ? (
          <div className="p-8">
            <EmptyState
              title="No tasks match"
              description={
                query || agentFilter !== 'all' || statusFilter !== 'all'
                  ? 'Try clearing filters.'
                  : 'No tasks yet.'
              }
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-700/50 text-[10px] text-slate-500 uppercase tracking-wider">
                  <th className="px-4 py-3 text-left font-medium">Status</th>
                  <th className="px-4 py-3 text-left font-medium">Title</th>
                  <th className="px-4 py-3 text-left font-medium">Agent</th>
                  <SortableTh
                    label="Duration"
                    active={sortKey === 'durationMs'}
                    dir={sortDir}
                    onClick={() => toggleSort('durationMs')}
                    align="right"
                  />
                  <SortableTh
                    label="Tokens"
                    active={sortKey === 'tokenUsage'}
                    dir={sortDir}
                    onClick={() => toggleSort('tokenUsage')}
                    align="right"
                  />
                  <SortableTh
                    label="Cost"
                    active={sortKey === 'costUsd'}
                    dir={sortDir}
                    onClick={() => toggleSort('costUsd')}
                    align="right"
                  />
                  <SortableTh
                    label="Created"
                    active={sortKey === 'createdAt'}
                    dir={sortDir}
                    onClick={() => toggleSort('createdAt')}
                    align="left"
                  />
                </tr>
              </thead>
              <tbody>
                {filtered.map((t) => (
                  <tr
                    key={t.id}
                    className="border-b border-slate-700/30 hover:bg-surface-base transition-colors"
                  >
                    <td className="px-4 py-3">
                      <StatusBadge status={t.status} />
                    </td>
                    <td className="px-4 py-3">
                      <Link
                        href={`/dashboard/tasks/${t.id}`}
                        className="text-slate-200 hover:text-brand-light truncate block max-w-md transition-colors"
                      >
                        {t.title}
                      </Link>
                      {t.jiraKey && (
                        <span className="text-[10px] text-slate-500 font-mono mt-0.5 block">
                          {t.jiraKey}
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      <AgentBadge type={t.agentType} />
                    </td>
                    <td className="px-4 py-3 text-right text-slate-400 tabular-nums">
                      {t.durationMs != null
                        ? `${(t.durationMs / 1000).toFixed(1)}s`
                        : '—'}
                    </td>
                    <td className="px-4 py-3 text-right text-slate-400 tabular-nums">
                      {t.tokenUsage != null ? formatTokens(t.tokenUsage) : '—'}
                    </td>
                    <td className="px-4 py-3 text-right text-slate-200 tabular-nums">
                      {t.costUsd != null ? formatCost(t.costUsd) : '—'}
                    </td>
                    <td className="px-4 py-3 text-slate-500 whitespace-nowrap">
                      {t.createdAt
                        ? new Date(t.createdAt).toLocaleString('en-GB', {
                            day: '2-digit',
                            month: 'short',
                            hour: '2-digit',
                            minute: '2-digit',
                          })
                        : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  )
}

function SortableTh({
  label,
  active,
  dir,
  onClick,
  align = 'left',
}: {
  label: string
  active: boolean
  dir: SortDir
  onClick: () => void
  align?: 'left' | 'right'
}) {
  return (
    <th className={cn('px-4 py-3 font-medium', align === 'right' ? 'text-right' : 'text-left')}>
      <button
        onClick={onClick}
        className={cn(
          'inline-flex items-center gap-1 hover:text-slate-300 transition-colors uppercase text-[10px] tracking-wider',
          active ? 'text-brand-light' : 'text-slate-500',
        )}
      >
        {label}
        <ArrowUpDown
          className={cn(
            'h-3 w-3 transition-transform',
            active && dir === 'asc' && 'rotate-180',
          )}
        />
      </button>
    </th>
  )
}
