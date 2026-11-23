// 本地预览网页版：起一个模拟手机接口的小服务器，用无头浏览器截图
const http = require('http');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { chromium } = require('playwright-core');

const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/web/index.html'), 'utf8');
const script = html.split('<script>')[1].split('</script>')[0];
new vm.Script(script); // 语法检查
console.log('JS syntax OK');

const items = [
  { id: 'a1', name: '旅行vlog_4K.mp4', size: 2147483648, mime: 'video/mp4', from: 'Xiaomi 14' },
  { id: 'a2', name: '设计稿.png', size: 5242880, mime: 'image/png', from: 'Xiaomi 14' },
];
const server = http.createServer((req, res) => {
  if (req.url.startsWith('/api/web/poll')) {
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({ me: { name: 'Xiaomi 14' }, items }));
  } else if (req.url.startsWith('/api/download/')) {
    res.statusCode = 204; res.end();
  } else {
    res.setHeader('Content-Type', 'text/html; charset=utf-8');
    res.end(html);
  }
});
server.listen(47999, async () => {
  const browser = await chromium.launch({ executablePath: process.argv[2], chromiumSandbox: true });
  const ctx = await browser.newContext({ locale: 'en-US', timezoneId: 'America/Los_Angeles', viewport: { width: 1280, height: 820 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto('http://127.0.0.1:47999/');
  await page.waitForTimeout(2500);
  await page.screenshot({ path: path.join(__dirname, 'preview_web.png') });
  console.log('page errors:', errors.length ? errors : 'none');
  await browser.close();
  server.close();
});
