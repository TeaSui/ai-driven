import { chromium } from 'playwright';
import { mkdirSync } from 'fs';

const PAGES = [
  { name: '01-dashboard', path: '/dashboard' },
  { name: '01b-chat', path: '/dashboard/chat' },
  { name: '02-timeline', path: '/dashboard/timeline' },
  { name: '03-tasks', path: '/dashboard/tasks' },
  { name: '03b-task-detail', path: '/dashboard/tasks/GTX-4829' },
  { name: '04-reviews', path: '/dashboard/reviews' },
  { name: '05-incidents', path: '/dashboard/incidents' },
  { name: '06-insights', path: '/dashboard/insights' },
  { name: '06b-slack', path: '/dashboard/slack' },
  { name: '07-admin-repos', path: '/dashboard/admin/repos' },
  { name: '08-admin-rules', path: '/dashboard/admin/rules' },
  { name: '09-admin-runs', path: '/dashboard/admin/runs' },
];

const OUT = '/tmp/ui-screenshots';
mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });

for (const pg of PAGES) {
  const page = await ctx.newPage();
  try {
    await page.goto(`http://localhost:3000${pg.path}`, { waitUntil: 'networkidle', timeout: 15000 });
    await page.waitForTimeout(1000); // let animations settle
    await page.screenshot({ path: `${OUT}/${pg.name}.png`, fullPage: true });
    console.log(`✓ ${pg.name}`);
  } catch (err) {
    console.error(`✗ ${pg.name}: ${err.message}`);
  }
  await page.close();
}

await browser.close();
console.log(`\nScreenshots saved to ${OUT}`);
