'use client'

import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { cn } from '@/lib/cn'
import { useSidebar } from './sidebar-context'
import {
  LayoutDashboard,
  MessageSquare,
  Settings,
  ChevronLeft,
  ChevronRight,
  GitBranch,
  BarChart3,
  GitPullRequest,
  AlertTriangle,
  ListTodo,
} from 'lucide-react'
import { TymexLogo } from '@/components/ui/tymex-logo'

interface NavItem {
  href: string
  label: string
  icon: React.ElementType
  badge?: number
}

interface NavSection {
  label?: string
  items: NavItem[]
}

// Structured sidebar: Overview → Pipeline work → Operations → Admin
const navSections: NavSection[] = [
  {
    items: [
      { href: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
      { href: '/dashboard/insights', label: 'Insights', icon: BarChart3 },
    ],
  },
  {
    label: 'Pipeline',
    items: [
      { href: '/dashboard/timeline', label: 'Timeline', icon: GitBranch },
      { href: '/dashboard/tasks', label: 'Tasks', icon: ListTodo },
      {
        href: '/dashboard/reviews',
        label: 'Reviews',
        icon: GitPullRequest,
      },
      {
        href: '/dashboard/incidents',
        label: 'Incidents',
        icon: AlertTriangle,
        badge: 2,
      },
    ],
  },
  {
    label: 'Workspace',
    items: [
      { href: '/chat', label: 'Chat', icon: MessageSquare },
      { href: '/admin', label: 'Admin', icon: Settings },
    ],
  },
]

export function Sidebar() {
  const { collapsed, toggle } = useSidebar()
  const pathname = usePathname()

  const isActive = (href: string) => {
    // Exact match for /dashboard, prefix match for everything else
    if (href === '/dashboard') return pathname === '/dashboard'
    return pathname === href || pathname.startsWith(href + '/')
  }

  return (
    <nav
      className={cn(
        'flex flex-col flex-shrink-0 bg-surface-base border-r border-slate-800/60 sidebar-transition h-full',
        collapsed ? 'w-16' : 'w-64',
      )}
      aria-label="Main navigation"
    >
      {/* Logo */}
      <div className="flex items-center h-16 px-4 border-b border-slate-800/60">
        <TymexLogo
          size={collapsed ? 24 : 28}
          showText={!collapsed}
          className="text-brand-light"
        />
      </div>

      {/* Nav sections */}
      <div className="flex flex-col gap-5 p-3 flex-1 overflow-y-auto">
        {navSections.map((section, si) => (
          <div key={si}>
            {section.label && !collapsed && (
              <p className="text-[10px] font-semibold text-slate-600 uppercase tracking-wider px-3 mb-1.5">
                {section.label}
              </p>
            )}
            <ul className="flex flex-col gap-0.5" role="list">
              {section.items.map((item) => {
                const active = isActive(item.href)
                const Icon = item.icon
                return (
                  <li key={item.href}>
                    <Link
                      href={item.href}
                      className={cn(
                        'flex items-center gap-3 rounded-lg text-sm font-medium transition-colors min-h-[40px]',
                        collapsed ? 'justify-center px-2' : 'px-3',
                        active
                          ? 'bg-brand/10 text-brand-light border-l-2 border-brand'
                          : 'text-slate-400 hover:text-slate-200 hover:bg-surface-elevated',
                      )}
                      title={collapsed ? item.label : undefined}
                    >
                      <Icon className="h-5 w-5 flex-shrink-0" />
                      {!collapsed && (
                        <>
                          <span className="flex-1">{item.label}</span>
                          {item.badge != null && item.badge > 0 && (
                            <span className="inline-flex items-center justify-center min-w-[20px] h-5 px-1.5 rounded-full bg-red-500/20 text-red-400 text-[10px] font-semibold">
                              {item.badge}
                            </span>
                          )}
                        </>
                      )}
                      {collapsed && item.badge != null && item.badge > 0 && (
                        <span className="absolute -mt-4 ml-4 w-2 h-2 rounded-full bg-red-500" />
                      )}
                    </Link>
                  </li>
                )
              })}
            </ul>
          </div>
        ))}
      </div>

      {/* Footer */}
      <div className="border-t border-slate-800/60">
        {!collapsed && (
          <div className="px-4 py-3">
            <p className="text-[10px] text-brand/60 uppercase tracking-wider">
              TymeX Engineering
            </p>
            <p className="text-xs text-slate-500 mt-0.5">v0.1.0</p>
          </div>
        )}
        <button
          onClick={toggle}
          className="flex items-center justify-center w-full h-10 text-slate-500 hover:text-slate-300 hover:bg-surface-elevated transition-colors"
          aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        >
          {collapsed ? (
            <ChevronRight className="h-4 w-4" />
          ) : (
            <ChevronLeft className="h-4 w-4" />
          )}
        </button>
      </div>
    </nav>
  )
}
