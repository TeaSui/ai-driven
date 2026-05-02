# AI-Driven Web UI — Design Port

Files here mirror `ai-driven/web_ui/src/` structure. Copy them across:

```bash
# from this project root
cp -r export/ai-driven-web-ui/src/* /path/to/ai-driven/web_ui/src/
```

## What's included

### Restyles (replace existing files)
- `src/app/dashboard/page.tsx` — uses brand/surface tokens + Card/StatusBadge/AgentBadge primitives
- `src/app/dashboard/tasks/page.tsx` — same restyle, uses real badge components
- `src/components/layout/sidebar.tsx` — adds new nav entries

### New pages (add alongside existing)
- `src/app/dashboard/timeline/page.tsx` — unified Jira → Code → Review → Deploy → Monitor pipeline
- `src/app/dashboard/insights/page.tsx` — hours saved, activity heatmap, agent vs human PRs
- `src/app/dashboard/reviews/page.tsx` — diff viewer with inline AI comments **(uses mock data — wire to real API)**
- `src/app/dashboard/incidents/page.tsx` — DevOps incident triage with agent RCA **(mock data)**

## Dependencies

Everything uses components already in your project:
- `@/components/ui/{button,card,badge,skeleton,empty-state,error-banner,input}`
- `@/lib/{api,cn,constants,format}`
- `lucide-react` icons (already installed)
- `swr` (already installed)

No new npm packages required.

## Design rationale

Your codebase already had the right DNA (teal brand tokens, surface palette, Inter + JetBrains). The gaps were:

1. **Dashboard + Tasks list** used generic `zinc-*` instead of your brand tokens — they looked like a different app
2. **No pipeline view** — tasks live in isolation with no visibility into the full SDLC flow
3. **No aggregate metrics** — success rate is one number; users want to see hours saved, trends, throughput
4. **No review/incident surfaces** — the backend agents exist but have no dedicated UI

Mock-data pages (`reviews`, `incidents`) are clearly marked `// MOCK` at the top. Replace those arrays with SWR calls to your backend once endpoints exist.
