import { chromium } from '@playwright/test';
const b = await chromium.launch();
const p = await b.newPage();
await p.goto('http://localhost:5173/templates/pw.diag/edit', { waitUntil: 'networkidle' });
await p.waitForTimeout(2500);
await p.screenshot({ path: '/tmp/pw-fix-1-loaded.png', fullPage: true });
// 点"添加第一个条件"
const addBtn = p.getByRole('button', { name: /添加第一个条件|add first/i }).first();
if (await addBtn.count()) {
  await addBtn.click();
  await p.waitForTimeout(1500);
  await p.screenshot({ path: '/tmp/pw-fix-2-afteradd.png', fullPage: true });
  // 取条件卡里的 combobox 选项数(有选项 = 修复成功)
  const combos = await p.getByRole('combobox').all();
  const results = [];
  for (const c of combos) {
    await c.click().catch(() => {});
    await p.waitForTimeout(300);
    const opts = await p.getByRole('option').allTextContents().catch(() => []);
    results.push({ placeholder: await c.getAttribute('placeholder') ?? '', opts: opts.slice(0,5) });
    await p.keyboard.press('Escape');
    await p.waitForTimeout(200);
  }
  console.log('=== combobox 选项 ===', JSON.stringify(results, null, 2));
} else {
  console.log('未找到 添加条件 按钮');
}
await b.close();
