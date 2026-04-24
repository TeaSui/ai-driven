// Fire each agent type via API, wait for the full workflow to complete,
// then verify the UI task-detail page renders the full event stream.

import { chromium } from 'playwright';
import { writeFileSync } from 'fs';

const OUT = '/tmp/ui-walk';
const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });

async function submit(agentType, message) {
  const page = await ctx.newPage();
  await page.goto('http://127.0.0.1:3000/dashboard', { waitUntil: 'domcontentloaded' });
  const resp = await page.evaluate(async ({ t, m }) => {
    const r = await fetch('/api/chat/send', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: m, agent_type: t, context: {} }),
    });
    return await r.json();
  }, { t: agentType, m: message });
  await page.close();
  return resp;
}

async function probe(taskId, agentType, label) {
  const page = await ctx.newPage();
  const errs = [];
  page.on('pageerror', e => errs.push(e.message.slice(0, 200)));
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text().slice(0, 200)); });

  await page.goto(`http://127.0.0.1:3000/dashboard/tasks/${taskId}`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(7000);  // let workflow run + events flow in
  await page.screenshot({ path: `${OUT}/e2e-${label}.png`, fullPage: true });

  const events = await page.evaluate(async (id) => (await fetch(`/api/admin/tasks/${id}/events`)).json(), taskId);
  const task = await page.evaluate(async (id) => (await fetch(`/api/admin/tasks/${id}`)).json(), taskId);
  const evals = await page.evaluate(async (id) => (await fetch(`/api/admin/tasks/${id}/evaluations`)).json(), taskId);

  await page.close();
  const evList = Array.isArray(events) ? events : (events.events || []);
  const hist = {};
  evList.forEach(e => { hist[e.type] = (hist[e.type] || 0) + 1; });

  return {
    label, agentType, taskId,
    status: task.status,
    prUrl: task.prUrl || task.pr_url,
    jiraUrl: task.jiraUrl || task.jira_url,
    tokens: task.tokenUsage || task.total_tokens,
    cost: task.costUsd || task.total_cost_usd,
    iterations: task.iterations,
    event_count: evList.length,
    histogram: hist,
    evaluations: (evals.evaluations || evals || []).length,
    errors: errs.slice(0, 5),
  };
}

const results = [];
for (const spec of [
  ['coding',   'Fix the race condition in balance cache',   'coding'],
  ['review',   'Review PR #42',                              'review'],
  ['devops',   'Investigate high error rate on user-api',    'devops'],
  ['workflow', 'Run full pipeline for PROJ-456',             'workflow'],
]) {
  const r = await submit(spec[0], spec[1]);
  if (!r.taskId) {
    console.log(`✗ ${spec[2]}: submit failed — ${JSON.stringify(r).slice(0, 200)}`);
    continue;
  }
  console.log(`▶ ${spec[2]}  task=${r.taskId}`);
  const p = await probe(r.taskId, spec[0], spec[2]);
  results.push(p);
  console.log(`  status=${p.status}  iters=${p.iterations}  events=${p.event_count}  evals=${p.evaluations}`);
  console.log(`  hist: ${JSON.stringify(p.histogram)}`);
  if (p.prUrl) console.log(`  PR:  ${p.prUrl}`);
  if (p.jiraUrl) console.log(`  Jira: ${p.jiraUrl}`);
  if (p.errors.length) console.log(`  !!   ${p.errors[0]}`);
}

writeFileSync(`${OUT}/e2e-full.json`, JSON.stringify(results, null, 2));

console.log('\n═══ SUMMARY ═══');
const done = results.filter(r => r.status === 'completed').length;
const rich = results.filter(r => r.event_count >= 10).length;
const graded = results.filter(r => r.evaluations > 0).length;
console.log(`  workflows completed: ${done} / ${results.length}`);
console.log(`  workflows with ≥10 events: ${rich} / ${results.length}`);
console.log(`  workflows with evaluations: ${graded} / ${results.length}`);

await browser.close();
