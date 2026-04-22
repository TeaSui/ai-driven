import type { Metadata } from 'next'
import './globals.css'
import '@/styles/a11y.css'
import { SidebarProvider } from '@/components/layout/sidebar-context'

export const metadata: Metadata = {
  title: 'GoTyme·X AI',
  description: 'AI-driven engineering orchestration platform',
}

// Blocking script: runs synchronously before first paint to prevent FOUC.
// Reads localStorage preference, falls back to OS preference, defaults to light.
const themeScript = `(function(){
  try {
    var stored = localStorage.getItem('gtx-theme');
    var theme = (stored === 'dark' || stored === 'light')
      ? stored
      : (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    var html = document.documentElement;
    if (theme === 'dark') {
      html.classList.remove('gtx-light');
      html.classList.add('gtx-dark');
    } else {
      html.classList.remove('gtx-dark');
      html.classList.add('gtx-light');
    }
  } catch(e) {}
})();`

export default function RootLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <html lang="en" className="gtx-app gtx-light gtx-density-balanced" style={{ height: '100%' }}>
      <head>
        {/* Blocking theme script — must execute before any paint to prevent flash of wrong theme */}
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
      </head>
      <body style={{ height: '100%', margin: 0, display: 'flex', flexDirection: 'column' }}>
        {/* Skip-to-content — WCAG 2.4.1 Bypass Blocks */}
        <a href="#main-content" className="gtx-skip-link">
          Skip to content
        </a>
        <SidebarProvider>
          <div style={{ display: 'flex', height: '100%', width: '100%', overflow: 'hidden' }}>
            {children}
          </div>
        </SidebarProvider>
      </body>
    </html>
  )
}
