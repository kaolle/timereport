import { test, expect, chromium } from '@playwright/test';

const code = { code: 'FAIR1234', active: true, version: 1 };
async function mockApi(context, entries = []) {
    await context.route('**/api/customer-codes', route => route.fulfill({ json: [code] }));
    await context.route('**/api/time-entries?*', route => route.fulfill({ json: entries }));
}
async function open(page) {
    await page.goto('/');
    await expect(page.locator('#offline-readiness')).toContainText('Redo utan nät');
    await expect(page.locator('#customer-code')).toHaveValue('FAIR1234');
}
async function save(page, comment = 'Utveckling av kundportal') {
    await page.getByLabel('Vad arbetade du med?').fill(comment);
    await page.getByRole('button', { name: 'Spara på enheten' }).click();
    await expect(page.locator('.entry-card')).toHaveCount(1);
}

test('mobile flow saves offline, survives reload and reopening, edits and deletes locally', async ({ page, context }) => {
    await mockApi(context);
    await open(page);
    await context.setOffline(true);
    await save(page);
    await expect(page.locator('.entry-duration')).toHaveText('8 h 30 min');
    await expect(page.locator('.entry-status')).toHaveText('Sparat på enheten');
    await page.reload();
    await expect(page.locator('.entry-comment')).toHaveText('Utveckling av kundportal');
    await page.getByRole('button', { name: 'Ändra 08:00–17:00' }).click();
    await page.getByLabel('Tid att räkna bort').fill('15');
    await page.getByRole('button', { name: 'Spara ändringen' }).click();
    await expect(page.locator('.entry-duration')).toHaveText('8 h 45 min');
    const second = await context.newPage();
    await page.close();
    await second.goto('/');
    await expect(second.locator('.entry-duration')).toHaveText('8 h 45 min');
    await second.getByRole('button', { name: 'Ta bort 08:00–17:00' }).click();
    await second.locator('#confirm-delete').click();
    await expect(second.locator('.entry-card')).toHaveCount(0);
    await expect(second.locator('#pending-count')).toHaveText('Inga lokala ändringar');
    await second.reload();
    await expect(second.locator('.entry-card')).toHaveCount(0);
});

test('local entries and pending writes survive a complete browser restart', async ({}, testInfo) => {
    const directory = testInfo.outputPath('browser-profile');
    let context = await chromium.launchPersistentContext(directory, { headless: true, viewport: { width: 390, height: 844 } });
    try {
        await mockApi(context);
        const page = await context.newPage();
        await page.goto('http://127.0.0.1:4173');
        await expect(page.locator('#offline-readiness')).toContainText('Redo utan nät');
        await expect(page.locator('#customer-code')).toHaveValue('FAIR1234');
        await context.setOffline(true);
        await save(page, 'Sparad före omstart');
        await context.close();
        context = await chromium.launchPersistentContext(directory, { headless: true, viewport: { width: 390, height: 844 }, offline: true });
        const reopened = await context.newPage();
        await reopened.goto('http://127.0.0.1:4173');
        await expect(reopened.locator('.entry-comment')).toHaveText('Sparad före omstart');
        await expect(reopened.locator('#pending-count')).toHaveText('1 ändring väntar på överföring');
    } finally { await context.close(); }
});

test('never uploads time entries and refresh preserves pending edits and deletion tombstones', async ({ page, context }) => {
    let writes = 0;
    context.on('request', request => { if (request.url().includes('/api/time-entries') && request.method() !== 'GET') writes++; });
    await mockApi(context);
    await open(page);
    const date = await page.locator('#day').inputValue();
    const remote = { id: 'fd4c7a76-2b47-45e6-bcc0-0fa7cf690ecc', date, start: '08:00:00', end: '17:00:00',
        deductedMinutes: 30, customerCode: 'FAIR1234', comment: 'Från servern', version: 3 };
    await context.route('**/api/time-entries?*', route => route.fulfill({ json: [remote] }));
    await page.locator('#refresh').click();
    await expect(page.locator('.entry-status')).toHaveText('Hämtad från servern');
    await page.getByRole('button', { name: 'Ändra 08:00–17:00' }).click();
    await page.locator('#comment').fill('Lokal ändring');
    await page.getByRole('button', { name: 'Spara ändringen' }).click();
    await page.locator('#refresh').click();
    await expect(page.locator('.entry-comment')).toHaveText('Lokal ändring');
    await page.getByRole('button', { name: 'Ta bort 08:00–17:00' }).click();
    await page.locator('#confirm-delete').click();
    await page.locator('#refresh').click();
    await expect(page.locator('.entry-card')).toHaveCount(0);
    await expect(page.locator('#pending-count')).toHaveText('1 ändring väntar på överföring');
    const outbox = await page.evaluate(async () => (await (await import('/js/storage.js')).snapshot()).outbox);
    expect(outbox[0]).toMatchObject({ operation: 'delete', expectedVersion: 3 });
    expect(writes).toBe(0);
});

test('validates overlap, zero lunch, daily defaults and comments as plain text', async ({ page, context }) => {
    await mockApi(context);
    await open(page);
    await save(page, '<img src=x onerror=alert(1)>');
    await expect(page.locator('.entry-comment')).toHaveText('<img src=x onerror=alert(1)>');
    await expect(page.locator('.entry-comment img')).toHaveCount(0);
    await expect(page.locator('#deduction')).toHaveValue('0');
    await page.getByRole('button', { name: 'Spara på enheten' }).click();
    await expect(page.locator('#form-error')).toContainText('överlappar');
    await expect(page.locator('.entry-card')).toHaveCount(1);
    await page.locator('#start').fill('17:00');
    await page.locator('#end').fill('18:00');
    await page.getByRole('button', { name: 'Spara på enheten' }).click();
    await expect(page.locator('.entry-card')).toHaveCount(2);
    await expect(page.locator('#day-total')).toHaveText('9 h 30 min');
    await page.locator('#next-day').click();
    await expect(page.locator('#deduction')).toHaveValue('30');
});

test('cross-tab edits cannot silently overwrite a newer local revision', async ({ page, context }) => {
    await mockApi(context);
    await open(page);
    await save(page);
    const second = await context.newPage();
    await open(second);
    await page.getByRole('button', { name: 'Ändra 08:00–17:00' }).click();
    await second.getByRole('button', { name: 'Ändra 08:00–17:00' }).click();
    await second.locator('#comment').fill('Nyare ändring');
    await second.getByRole('button', { name: 'Spara ändringen' }).click();
    await page.locator('#comment').fill('Äldre ändring');
    await page.getByRole('button', { name: 'Spara ändringen' }).click();
    await expect(page.locator('#form-error')).toContainText('annan flik');
    await expect(page.locator('.entry-comment')).toHaveText('Nyare ändring');
});

test('an open edit cannot recreate a server entry deleted during refresh', async ({ page, context }) => {
    await mockApi(context);
    await open(page);
    const date = await page.locator('#day').inputValue();
    await context.route('**/api/time-entries?*', route => route.fulfill({ json: [{
        id: '01aa9af4-753f-4ef3-aee5-a735ef08a513', date, start: '08:00', end: '17:00',
        deductedMinutes: 30, customerCode: 'FAIR1234', comment: 'Original', version: 1
    }] }));
    await page.locator('#refresh').click();
    await page.getByRole('button', { name: 'Ändra 08:00–17:00' }).click();
    await context.route('**/api/time-entries?*', route => route.fulfill({ json: [] }));
    await page.locator('#refresh').click();
    await expect(page.locator('.entry-card')).toHaveCount(0);
    await page.locator('#comment').fill('Inaktuell ändring');
    await page.getByRole('button', { name: 'Spara ändringen' }).click();
    await expect(page.locator('#form-error')).toContainText('togs bort');
    const data = await page.evaluate(async () => (await import('/js/storage.js')).snapshot());
    expect(data.entries).toHaveLength(0);
    expect(data.outbox).toHaveLength(0);
});

test('storage failure rolls back the write and never reports success', async ({ page, context }) => {
    await mockApi(context);
    await open(page);
    await page.evaluate(() => {
        const original = IDBObjectStore.prototype.put;
        IDBObjectStore.prototype.put = function (...args) {
            if (this.name === 'outbox') throw new DOMException('Full', 'QuotaExceededError');
            return original.apply(this, args);
        };
    });
    await page.locator('#comment').fill('Inte sparad');
    await page.getByRole('button', { name: 'Spara på enheten' }).click();
    await expect(page.locator('#form-error')).toContainText('lagring är full');
    await expect(page.locator('.entry-card')).toHaveCount(0);
    await expect(page.locator('#comment')).toHaveValue('Inte sparad');
    const data = await page.evaluate(async () => (await import('/js/storage.js')).snapshot());
    expect(data.entries).toHaveLength(0);
    expect(data.outbox).toHaveLength(0);
});

test('empty customer catalogue explains setup and allows creating an online code', async ({ page, context }) => {
    let codes = [];
    await context.route('**/api/customer-codes', route => route.fulfill({ json: codes }));
    await context.route('**/api/time-entries?*', route => route.fulfill({ json: [] }));
    await context.route('**/api/customer-codes/FAIR1234', route => {
        codes = [code];
        return route.fulfill({ json: code });
    });
    await page.goto('/');
    await expect(page.locator('#code-help')).toContainText('Inga kundkoder');
    await expect(page.locator('#save')).toBeDisabled();
    await page.locator('summary').click();
    await page.locator('#new-code').fill('FAIR1234');
    await page.locator('#add-code').click();
    await expect(page.locator('#customer-code')).toHaveValue('FAIR1234');
    await expect(page.locator('#save')).toBeEnabled();
});

test('mobile and desktop layouts fit the viewport and provide installation assets', async ({ page, context }, testInfo) => {
    await mockApi(context);
    await open(page);
    await save(page, 'Planering och utveckling');
    for (const [width, height] of [[320, 740], [390, 844], [1280, 960]]) {
        await page.setViewportSize({ width, height });
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.screenshot({ path: testInfo.outputPath(`layout-${width}.png`), fullPage: true });
    }
    const manifest = await (await page.request.get('/manifest.json')).json();
    expect(manifest.display).toBe('standalone');
    for (const icon of manifest.icons) {
        expect((await page.request.get(icon.src)).ok()).toBe(true);
    }
});
