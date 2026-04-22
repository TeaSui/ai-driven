import type { Config } from 'tailwindcss'

const config: Config = {
  content: [
    './src/**/*.{ts,tsx}',
  ],
  theme: {
    container: {
      center: true,
    },
    extend: {
      colors: {
        brand: {
          teal: {
            50:  'var(--brand-teal-50)',
            100: 'var(--brand-teal-100)',
            200: 'var(--brand-teal-200)',
            300: 'var(--brand-teal-300)',
            400: 'var(--brand-teal-400)',
            500: 'var(--brand-teal-500)',
            600: 'var(--brand-teal-600)',
            700: 'var(--brand-teal-700)',
            800: 'var(--brand-teal-800)',
            900: 'var(--brand-teal-900)',
          },
          ink: 'var(--brand-ink)',
        },
        surface: {
          bg:        'var(--bg)',
          'bg-elev': 'var(--bg-elev)',
          'bg-muted': 'var(--bg-muted)',
          'bg-subtle': 'var(--bg-subtle)',
          'bg-hover': 'var(--bg-hover)',
          panel:     'var(--panel)',
          'panel-border': 'var(--panel-border)',
          border:    'var(--border)',
          'border-strong': 'var(--border-strong)',
          divider:   'var(--divider)',
        },
        text: {
          DEFAULT: 'var(--text)',
          muted:   'var(--text-muted)',
          subtle:  'var(--text-subtle)',
          faint:   'var(--text-faint)',
          inverse: 'var(--text-inverse)',
        },
        status: {
          success:    'var(--status-success)',
          'success-bg': 'var(--status-success-bg)',
          warn:       'var(--status-warn)',
          'warn-bg':  'var(--status-warn-bg)',
          danger:     'var(--status-danger)',
          'danger-bg': 'var(--status-danger-bg)',
          info:       'var(--status-info)',
          'info-bg':  'var(--status-info-bg)',
        },
        accent: {
          400: 'var(--accent-400)',
          500: 'var(--accent-500)',
          600: 'var(--accent-600)',
          soft: 'var(--accent-soft)',
          contrast: 'var(--accent-contrast)',
        },
      },
      fontFamily: {
        sans:  ['Geist', '-apple-system', 'BlinkMacSystemFont', 'system-ui', 'sans-serif'],
        mono:  ['Geist Mono', 'ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
        serif: ['Instrument Serif', 'Georgia', 'serif'],
      },
      keyframes: {
        'gtx-pulse': {
          '0%, 100%': { opacity: '1' },
          '50%':      { opacity: '0.35' },
        },
        'gtx-blink': {
          '0%, 100%': { opacity: '1' },
          '50%':      { opacity: '0' },
        },
        'gtx-shimmer': {
          '0%':   { transform: 'translateX(-100%)' },
          '100%': { transform: 'translateX(100%)' },
        },
        'gtx-fadein': {
          from: { opacity: '0', transform: 'translateY(2px)' },
          to:   { opacity: '1', transform: 'none' },
        },
        'gtx-spin': {
          to: { transform: 'rotate(360deg)' },
        },
        'gtx-ring': {
          '0%':   { transform: 'scale(1)', opacity: '0.6' },
          '100%': { transform: 'scale(2.4)', opacity: '0' },
        },
        'gtx-dash': {
          to: { strokeDashoffset: '-24' },
        },
      },
      animation: {
        'gtx-pulse':   'gtx-pulse 2s ease-in-out infinite',
        'gtx-blink':   'gtx-blink 1s step-end infinite',
        'gtx-shimmer': 'gtx-shimmer 1.6s linear infinite',
        'gtx-fadein':  'gtx-fadein 0.2s ease-out',
        'gtx-spin':    'gtx-spin 0.9s linear infinite',
        'gtx-ring':    'gtx-ring 1.6s ease-out infinite',
      },
    },
  },
  plugins: [],
}

export default config
