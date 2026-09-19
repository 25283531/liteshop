/* Optional browser validation: see docs/LOCAL_WEB_UI.md. Uses temporary test data. */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawn} = require('node:child_process');
const modules = process.env.LITESHOP_TEST_NODE_MODULES || path.join(os.tmpdir(), 'liteshop-validation', 'node_modules');
const {chromium} = require(path.join(modules, 'playwright'));
const acorn = require(path.join(modules, 'acorn'));
acorn.parse(fs.readFileSync('apps/web/public/app.js', 'utf8'), {ecmaVersion: 5});
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'liteshop-browser-'));
const token = 'browser-test-terminal-token-123456789';
const server = spawn('python', ['-u', '-m', 'apps.web.server', '--port', '0', '--db', path.join(dir, 'test.sqlite'), '--shop-name', '青禾生活 · 演示门店'], {
    env: {...process.env, LITESHOP_TOKEN: token}, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
});
let browser;
const errors = [];
async function main() {
    const url = await new Promise((resolve, reject) => {
        let log = '';
        const timer = setTimeout(() => reject(new Error('Server did not start: ' + log)), 15000);
        server.stdout.on('data', data => { log += data; const m = log.match(/http:\/\/127\.0\.0\.1:\d+/); if (m) { clearTimeout(timer); resolve(m[0]); } });
        server.stderr.on('data', data => { log += data; });
        server.on('exit', code => { clearTimeout(timer); reject(new Error('Server exited ' + code + ' ' + log)); });
    });
    const executablePath = process.env.LITESHOP_BROWSER || (process.platform === 'win32' ? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' : undefined);
    browser = await chromium.launch({headless: true, executablePath});
    const page = await browser.newPage({viewport: {width: 1366, height: 1000}});
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(url);
    await page.locator('#token').fill(token);
    await page.locator('#login-form button').click();
    await page.waitForFunction(() => document.querySelector('#connection').textContent.indexOf('已连接') >= 0);
    await page.locator('#new-member').click();
    await page.locator('#f-name').fill('张三 <b>演示会员</b>');
    await page.locator('#f-phone').fill('13800000000');
    await page.locator('#submit').click();
    await page.waitForSelector('[data-action="open"]');
    assert.equal(await page.locator('.member-title b').count(), 0, 'User text must not become HTML');
    await page.locator('[data-action="open"]').click();
    await page.locator('#f-type').selectOption({label: '储值卡'});
    await page.locator('#submit').click();
    await page.waitForSelector('[data-action="RECHARGE"]');
    async function action(kind, value, remark) {
        await page.locator('[data-action="' + kind + '"]').first().click();
        await page.locator('#f-quantity').fill(value);
        if (remark) { await page.locator('#f-remark').fill(remark); }
        await page.locator('#submit').click();
    }
    async function balance(text) {
        await page.waitForFunction(t => document.querySelector('.card strong') && document.querySelector('.card strong').textContent === t, '¥ ' + text);
    }
    await action('RECHARGE', '100.25'); await balance('100.25');
    await action('CONSUME', '20.05'); await balance('80.20');
    await action('refund', '5.05'); await balance('85.25');
    await action('points', '120', '到店奖励');
    await page.waitForFunction(() => document.querySelector('[data-action="points"]').textContent === '积分 · 120');
    await page.route('**/api/v1/commands/transact', async route => {
        await route.fetch();  // Server commits, but the client never receives the response.
        await route.abort();
    }, {times: 1});
    await action('RECHARGE', '1.00');
    await page.waitForFunction(() => document.querySelector('#message').textContent.indexOf('连接中断') >= 0);
    const original = await page.evaluate(() => JSON.parse(localStorage.getItem('liteshop.pending')).payload.request_id);
    await page.reload();
    await page.waitForSelector('#pending:not([hidden])');
    await page.evaluate(() => sessionStorage.setItem('liteshop.token', 'wrong-token'));
    await page.locator('#retry').click();
    await page.waitForSelector('#login:not([hidden])');
    assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('liteshop.pending')).payload.request_id), original);
    await page.locator('#token').fill(token);
    await page.locator('#login-form button').click();
    await page.waitForFunction(() => document.querySelector('#connection').textContent.indexOf('已连接') >= 0);
    await page.locator('#retry').click();
    await page.waitForFunction(() => !localStorage.getItem('liteshop.pending'));
    await balance('86.25');
    assert.equal(await page.locator('.table-wrap').first().locator('tbody tr').count(), 4);
    await page.locator('[data-action="edit"]').click();
    await page.locator('#f-name').fill('张三 · 演示会员');
    await page.locator('#submit').click();
    await page.waitForFunction(() => document.querySelector('.member-title').textContent.indexOf('<b>') < 0);
    fs.mkdirSync('docs/screenshots', {recursive: true});
    await page.screenshot({path: 'docs/screenshots/web-workspace.png', fullPage: true});
    await page.setViewportSize({width: 800, height: 600});
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);

    const native = await browser.newPage();
    native.on('pageerror', e => errors.push(e.message));
    await native.addInitScript(secret => {
        window.LiteShopTerminal = {
            getTerminalInfo: function () { return '{"androidVersion":"4.4 (bridge simulation)","model":"test"}'; },
            reportReady: function () {},
            request: function (id, method, endpoint, body) {
                var xhr = new XMLHttpRequest();
                xhr.open(method, '/api/v1/' + endpoint);
                xhr.setRequestHeader('Authorization', 'Bearer ' + secret);
                if (body) { xhr.setRequestHeader('Content-Type', 'application/json'); }
                xhr.onload = function () { window.LiteShopReceive(id, xhr.status, xhr.responseText); };
                xhr.send(body || null);
            }
        };
    }, token);
    await native.goto(url);
    await native.waitForFunction(() => document.querySelector('#connection').textContent.indexOf('已连接') >= 0);
    await native.locator('#member-list button').first().click();
    await native.waitForSelector('[data-action="points"]');
    await native.locator('[data-action="points"]').click();
    await native.locator('#f-quantity').fill('-20');
    await native.locator('#f-remark').fill('兑换');
    await native.locator('#submit').click();
    await native.waitForFunction(() => document.querySelector('[data-action="points"]').textContent === '积分 · 100');
    assert.deepEqual(errors, []);
    console.log('PASS: ES5 parse; desktop workflow; HTML escaping; lost-response/auth retry; persistence; 800px layout; simulated Android bridge.');
}
main().catch(e => { console.error(e); process.exitCode = 1; }).finally(async () => {
    if (browser) { await browser.close(); }
    server.kill();
    await new Promise(resolve => { if (server.exitCode !== null) { resolve(); } else { server.once('exit', resolve); } });
    fs.rmSync(dir, {recursive: true, force: true}); // Exact unique temporary directory created above.
});
