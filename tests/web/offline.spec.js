import {chromium, expect, test} from '@playwright/test';

const origin = 'http://127.0.0.1:4173';
const code = { code: 'FAIR1234', active: true, version: 1 };
const account = {accountId: '33333333-3333-4333-8333-333333333333', email: 'offline@example.test'};
const snapshot = page => page.evaluate(async () => (await import('/js/storage.js')).snapshot());
const selectedDate = page => page.locator('.week-day.selected').getAttribute('data-date');

async function mockApi(context, entries = [], codes = [code]) {
    const state = {entries, codes, writes: []};
    await context.route('**/api/**', async route => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        if (path === '/api/auth/refresh') return route.fulfill({
            json: {
                accessToken: 'offline-test-access',
                expiresIn: 300,
                account
            }
        });
        if (path === '/api/customer-codes' && request.method() === 'GET') return route.fulfill({json: state.codes});
        if (path === '/api/vacation-days') return route.fulfill({json: []});
        if (path === '/api/time-entries' && request.method() === 'GET') return route.fulfill({json: state.entries});
        if (path === '/api/customer-codes/FAIR1234' && request.method() === 'PUT') {
            state.codes = [code];
            return route.fulfill({json: code});
        }
        if (path.startsWith('/api/time-entries/') && request.method() !== 'GET') {
            state.writes.push({method: request.method(), body: request.postData()});
        }
        // A server outage must leave the local queue intact.
        return route.fulfill({status: 503, json: {message: 'Testservern är otillgänglig.'}});
    });
    return state;
}

async function open(page) {
    await page.goto(origin);
    await expect(page.locator('#offline-readiness')).toContainText('Redo utan nät');
    await expect(page.locator('#entry-fields')).toBeEnabled();
    await expect(page.locator('#refresh')).toBeEnabled();
}

async function save(page, comment = 'Utveckling av kundportal', start = '08:00', end = '17:00') {
    await page.locator('#comment').fill(comment);
    await page.locator('#start').fill(start);
    await page.locator('#end').fill(end);
    await expect.poll(async () => (await snapshot(page)).entries.some(entry => !entry.deleted && entry.comment === comment)).toBe(true);
    await expect(page.locator('#autosave-status')).toContainText('Sparad lokalt');
}

async function refresh(page) {
    await page.locator('#refresh').click();
    await expect(page.locator('#refresh')).toBeEnabled();
}

async function remove(page) {
    await page.locator('#delete-current').click();
    await page.locator('#confirm-delete').click();
    await expect(page.locator('#delete-dialog')).toBeHidden();
}

test('mobile flow saves offline, survives reload and reopening, edits and deletes locally', async ({ page, context }) => {
    const state = await mockApi(context);
    await open(page);
    await context.setOffline(true);
    await save(page);
    await expect(page.locator('#preview')).toHaveText('8 h 30 min');
    await page.reload();
    await expect(page.locator('#comment')).toHaveValue('Utveckling av kundportal');
    await page.locator('#deduction').fill('15');
    await expect.poll(async () => (await snapshot(page)).entries[0].deductedMinutes).toBe(15);
    await expect(page.locator('#preview')).toHaveText('8 h 45 min');
    const second = await context.newPage();
    await page.close();
    await open(second);
    await expect(second.locator('#preview')).toHaveText('8 h 45 min');
    await remove(second);
    expect((await snapshot(second)).entries).toHaveLength(0);
    expect((await snapshot(second)).outbox).toHaveLength(0);
    await second.reload();
    await expect(second.locator('#entry-fields')).toBeEnabled();
    expect((await snapshot(second)).entries).toHaveLength(0);
    expect(state.writes).toHaveLength(0);
});

test('local entries and pending writes survive a complete browser restart', async ({}, testInfo) => {
    const directory = testInfo.outputPath('browser-profile');
    let context = await chromium.launchPersistentContext(directory, { headless: true, viewport: { width: 390, height: 844 } });
    try {
        await mockApi(context);
        const page = await context.newPage();
        await open(page);
        await context.setOffline(true);
        await save(page, 'Sparad före omstart');
        await context.close();
        context = await chromium.launchPersistentContext(directory, { headless: true, viewport: { width: 390, height: 844 }, offline: true });
        const reopened = await context.newPage();
        await open(reopened);
        await expect(reopened.locator('#comment')).toHaveValue('Sparad före omstart');
        const data = await snapshot(reopened);
        expect(data.entries).toHaveLength(1);
        expect(data.outbox).toHaveLength(1);
        expect(data.outbox[0]).toMatchObject({operation: 'put', expectedVersion: 0});
    } finally { await context.close(); }
});

test('failed synchronization and refresh preserve pending edits and deletion tombstones', async ({page, context}) => {
    const state = await mockApi(context);
    await open(page);
    const date = await selectedDate(page);
    const remote = { id: 'fd4c7a76-2b47-45e6-bcc0-0fa7cf690ecc', date, start: '08:00:00', end: '17:00:00',
        deductedMinutes: 30, customerCode: 'FAIR1234', comment: 'Från servern', version: 3 };
    state.entries = [remote];
    await refresh(page);
    await expect(page.locator('#comment')).toHaveValue('Från servern');
    await page.locator('#comment').fill('Lokal ändring');
    await expect.poll(async () => (await snapshot(page)).entries[0].comment).toBe('Lokal ändring');
    await page.locator('#sync').click();
    await expect(page.locator('#sync')).toBeEnabled();
    await expect.poll(() => state.writes.length).toBe(1);
    await refresh(page);
    expect((await snapshot(page)).entries[0].comment).toBe('Lokal ändring');
    await remove(page);
    await refresh(page);
    const data = await snapshot(page);
    expect(data.entries.filter(entry => !entry.deleted)).toHaveLength(0);
    expect(data.outbox[0]).toMatchObject({operation: 'delete', expectedVersion: 3});
});

test('validates overlap, zero lunch, daily defaults and comments as plain text', async ({ page, context }) => {
    await mockApi(context);
    await open(page);
    await context.setOffline(true);
    await save(page, '<img src=x onerror=alert(1)>');
    await expect(page.locator('#comment')).toHaveValue('<img src=x onerror=alert(1)>');
    await expect(page.locator('#entry-panel img')).toHaveCount(0);
    await page.locator('#new-entry').click();
    await expect(page.locator('#deduction')).toHaveValue('0');
    await page.locator('#start').fill('08:00');
    await page.locator('#end').fill('17:00');
    await expect(page.locator('#form-error')).toContainText('överlappar');
    expect((await snapshot(page)).entries).toHaveLength(1);
    await save(page, 'Nästa pass', '17:00', '18:00');
    expect((await snapshot(page)).entries).toHaveLength(2);
    await expect(page.locator('#week-total')).toHaveText('9 h 30 min');
    const previous = await page.evaluate(async () => {
        const domain = await import('/js/domain.js');
        return domain.shiftDate(domain.today(), -1);
    });
    await page.locator(`.week-day[data-date="${previous}"]`).click();
    await expect(page.locator('#deduction')).toHaveValue('30');
});

test('cross-tab edits cannot silently overwrite a newer local revision', async ({ page, context }) => {
    await mockApi(context);
    await open(page);
    await context.setOffline(true);
    await save(page);
    const second = await context.newPage();
    await open(second);
    // Keep the first tab dirty while a second tab commits a newer revision.
    await page.locator('#customer-code').fill('UNCONFIRMED');
    await second.evaluate(async () => {
        const storage = await import('/js/storage.js');
        const current = (await storage.snapshot()).entries[0];
        await storage.saveEntry({...current, comment: 'Nyare ändring'}, current.localRevision);
    });
    await page.locator('#comment').fill('Äldre ändring');
    await page.locator('#customer-code').fill('');
    await expect(page.locator('#form-error')).toContainText('annan flik');
    expect((await snapshot(page)).entries[0].comment).toBe('Nyare ändring');
});

test('an open edit cannot recreate a server entry deleted during refresh', async ({ page, context }) => {
    const state = await mockApi(context);
    await open(page);
    const date = await selectedDate(page);
    state.entries = [{
        id: '01aa9af4-753f-4ef3-aee5-a735ef08a513', date, start: '08:00', end: '17:00',
        deductedMinutes: 30, customerCode: 'FAIR1234', comment: 'Original', version: 1
    }];
    await refresh(page);
    await expect(page.locator('#comment')).toHaveValue('Original');
    await page.locator('#customer-code').fill('UNCONFIRMED');
    state.entries = [];
    await refresh(page);
    expect((await snapshot(page)).entries).toHaveLength(0);
    await page.locator('#comment').fill('Inaktuell ändring');
    await page.locator('#customer-code').fill('FAIR1234');
    await expect(page.locator('#form-error')).toContainText('togs bort');
    const data = await snapshot(page);
    expect(data.entries).toHaveLength(0);
    expect(data.outbox).toHaveLength(0);
});

test('storage failure rolls back the write and never reports success', async ({ page, context }) => {
    await mockApi(context);
    await open(page);
    await context.setOffline(true);
    await page.evaluate(() => {
        const original = IDBObjectStore.prototype.put;
        IDBObjectStore.prototype.put = function (...args) {
            if (this.name === 'outbox') throw new DOMException('Lagringen är full', 'QuotaExceededError');
            return original.apply(this, args);
        };
    });
    await page.locator('#comment').fill('Inte sparad');
    await page.locator('#start').fill('08:00');
    await page.locator('#end').fill('17:00');
    await expect(page.locator('#form-error')).toContainText('Lagringen är full');
    await expect(page.locator('#autosave-status')).not.toContainText('Sparad lokalt');
    await expect(page.locator('#comment')).toHaveValue('Inte sparad');
    const data = await snapshot(page);
    expect(data.entries).toHaveLength(0);
    expect(data.outbox).toHaveLength(0);
});

test('empty customer catalogue allows optional codes and adding an online code', async ({page, context}) => {
    await mockApi(context, [], []);
    await open(page);
    await expect(page.locator('#customer-code')).toBeEnabled();
    await expect(page.locator('#customer-codes')).toBeDisabled();
    await page.getByText('Kundkoder och installation', {exact: true}).click();
    await page.locator('#new-code').fill('FAIR1234');
    await page.locator('#add-code').click();
    await expect(page.locator('#customer-codes')).toBeEnabled();
    await page.locator('#customer-codes').selectOption('FAIR1234');
    await expect(page.locator('#customer-code')).toHaveValue('FAIR1234');
});

test('mobile and desktop layouts fit the viewport and provide installation assets', async ({ page, context }, testInfo) => {
    await mockApi(context);
    await open(page);
    await context.setOffline(true);
    await save(page, 'Planering och utveckling');
    for (const [width, height] of [[320, 740], [390, 844], [1280, 960]]) {
        await page.setViewportSize({ width, height });
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.screenshot({ path: testInfo.outputPath(`layout-${width}.png`), fullPage: true });
    }
    const manifest = await (await page.request.get(`${origin}/manifest.json`)).json();
    expect(manifest.display).toBe('standalone');
    for (const icon of manifest.icons) expect((await page.request.get(new URL(icon.src, origin).href)).ok()).toBe(true);
});
