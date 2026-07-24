import { chromium } from '@playwright/test';
const b = await chromium.launch();
const p = await b.newPage();

// 截模板编辑器
await p.goto('http://localhost:5173/templates/tpl_test/edit', { waitUntil: 'networkidle' });
await p.waitForTimeout(3000);
await p.screenshot({ path: '/tmp/cmp-template.png', fullPage: true });
console.log('=== template editor body text ===\n', (await p.evaluate(() => document.body.innerText)).slice(0,800));

// 取 Body Skeleton card 的实际 bounding box
const card = p.locator('.ant-card').filter({ hasText: 'Body Skeleton' }).first();
const box = await card.boundingBox().catch(() => null);
console.log('Body Skeleton Card box:', JSON.stringify(box));

// 取 react-flow 容器实际尺寸
const rf = p.locator('.react-flow').first();
const rfBox = await rf.boundingBox().catch(() => null);
console.log('React-flow box:', JSON.stringify(rfBox));

// 看内部有没有实际节点 HTML
const rfInner = await p.locator('.react-flow__nodes').first().innerHTML().catch(() => '(not found)');
console.log('react-flow__nodes innerHTML length:', rfInner.length);
console.log('nodes HTML 前 200:', rfInner.slice(0, 200));

await b.close();
