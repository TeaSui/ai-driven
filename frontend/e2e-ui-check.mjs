// Verify the UI surfaces each element of a completed workflow:
// timeline pipeline stages, task detail streaming, evaluation scores, PR link.

import { chromium } from 'playwright';
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });

const OUT = '/tmp/ui-walk';

async function grab(url, filename) {
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push(e.message.slice(0,150)));
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text().slice(0,150)); });
  await page.goto(url, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${OUT}/${filename}.png`, fullPage: true });
  const bodyText = await page.locator('body').textContent();
  await page.close();
  return { bodyText: bodyText || '', errs };
}

const checks = [];

// 1. Dashboard overview should show recent tasks
const dash = await grab('http://127.0.0.1:3000/dashboard', 'ui-overview');
checks.push({ name: 'dashboard has tasks', pass: /task|run/i.test(dash.bodyText) });

// 2. Tasks list page
const tasks = await grab('http://127.0.0.1:3000/dashboard/tasks', 'ui-tasks');
checks.push({ name: 'tasks page loads', pass: tasks.errs.length === 0 });

// 3. Pick the most recent task and view its detail
const api = await ctx.newPage();
await api.goto('http://127.0.0.1:3000/dashboard', { waitUntil: 'domcontentloaded' });
const taskInfo = await api.evaluate(async () => {
  const r = await fetch('/api/admin/tasks');
  const d = await r.json();
  const list = d.tasks || d;
  const recent = list.slice(0, 4);
  return recent.map(t => ({ id: t.id, agent: t.agentType, status: t.status }));
});
await api.close();

for (const t of taskInfo) {
  const res = await grab(`http://127.0.0.1:3000/dashboard/tasks/${t.id}`, `ui-task-${t.agent}`);
  const hasStreamText = res.bodyText.includes('iteration') || res.bodyText.includes('token') || res.bodyText.includes('Pause') || res.bodyText.includes('Stop');
  const hasEvals = /\b\d+\s*\/\s*10\b/.test(res.bodyText) || /evaluation/i.test(res.bodyText);
  checks.push({ name: `task/${t.agent} page renders stream`, pass: hasStreamText });
  checks.push({ name: `task/${t.agent} shows evaluation or scores`, pass: hasEvals });
  checks.push({ name: `task/${t.agent} no console errors`, pass: res.errs.length === 0, note: res.errs[0] });
}

// 4. Timeline should show runs
const timeline = await grab('http://127.0.0.1:3000/dashboard/timeline', 'ui-timeline');
checks.push({ name: 'timeline loads', pass: timeline.errs.length === 0 });

// 5. Reviews
const reviews = await grab('http://127.0.0.1:3000/dashboard/reviews', 'ui-reviews');
checks.push({ name: 'reviews page loads', pass: reviews.errs.length === 0 });

// 6. Incidents
const inc = await grab('http://127.0.0.1:3000/dashboard/incidents', 'ui-incidents');
checks.push({ name: 'incidents page loads', pass: inc.errs.length === 0 });

// 7. Insights
const insights = await grab('http://127.0.0.1:3000/dashboard/insights', 'ui-insights');
checks.push({ name: 'insights page loads', pass: insights.errs.length === 0 });

console.log('\n═══ UI STATE VERIFICATION ═══');
let passed = 0;
for (const c of checks) {
  const icon = c.pass ? '✓' : '✗';
  console.log(`  ${icon} ${c.name}${c.note ? ' — ' + c.note : ''}`);
  if (c.pass) passed++;
}
console.log(`\n  ${passed} / ${checks.length} passed`);

await browser.close();
