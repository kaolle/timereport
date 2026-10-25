import { test, expect } from '@playwright/test';

const account = { accountId: '55555555-5555-4555-8555-555555555555', email: 'codes@example.test' };
const snapshot = page => page.evaluate(async () => (await import('/js/storage.js')).snapshot());
const remove = page => page.getByRole('button', { name: 'Ta bort kundkod FAIR1234', exact: true });

async function openCodes(page) {
    await page.getByRole('button', { name: 'Fakturering', exact: true }).click();
    await page.getByRole('tab', { name: 'Kunder', exact: true }).click();
    await expect(page.getByRole('tabpanel', { name: 'Kunder', exact: true })).toBeVisible();
}

async function setup(page, context) {
    const state = { codes: [{ code: 'FAIR1234', active: true, version: 1 }, { code: 'OTHER', active: true, version: 1 }],
        writes: [], history: [], failure: null };
    await context.route('**/api/**', async route => {
        const request = route.request();
        const url = new URL(request.url());
        if (url.pathname === '/api/auth/refresh') return route.fulfill({ json: { accessToken: 'codes-access', expiresIn: 300, account } });
        if (url.pathname === '/api/customer-codes') return route.fulfill({ json: state.codes });
        if (url.pathname.startsWith('/api/customer-codes/') && request.method() === 'PUT') {
            const code = decodeURIComponent(url.pathname.slice('/api/customer-codes/'.length));
            const body = request.postDataJSON();
            state.writes.push({ code, ...body });
            if (state.failure) return route.fulfill({ status: state.failure, json: { message: 'Servern kunde inte ändra kundkoden.' } });
            const current = state.codes.find(row => row.code === code);
            if (body.expectedVersion !== (current?.version ?? 0)) return route.fulfill({ status: 409 });
            const saved = { code, active: body.active, version: body.expectedVersion + 1 };
            state.codes = [...state.codes.filter(row => row.code !== code), saved];
            return route.fulfill({ json: saved });
        }
        if (url.pathname === '/api/time-entries' && request.method() === 'GET') {
            return route.fulfill({ json: state.history.filter(entry => entry.date >= url.searchParams.get('from') && entry.date <= url.searchParams.get('to')) });
        }
        if (request.method() !== 'GET') return route.fulfill({ status: 503 });
        return route.fulfill({ json: [] });
    });
    await page.goto('/');
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#offline-readiness')).toContainText('Redo utan nät');
    await openCodes(page);
    await expect(remove(page)).toBeEnabled();
    return state;
}

async function confirmRemoval(page) {
    page.once('dialog', dialog => dialog.accept());
    await remove(page).click();
}

test('removing and restoring a used code preserves times and report totals', async ({ page, context }, testInfo) => {
    const state = await setup(page, context);
    const date = await page.locator('.week-day.today').getAttribute('data-date');
    state.history = [{ id: '11111111-1111-4111-8111-111111111111', date, start: '08:00', end: '17:00',
        deductedMinutes: 30, customerCode: 'FAIR1234', comment: 'History', version: 1 }];
    await page.getByRole('button', { name: 'Månadsrapport', exact: true }).click();
    await expect(page.locator('#report-total')).toHaveText('8 h 30 min');
    await openCodes(page);
    page.once('dialog', dialog => dialog.dismiss());
    await remove(page).click();
    expect(state.writes).toHaveLength(0);
    await confirmRemoval(page);
    await expect(remove(page)).toHaveCount(0);
    expect(state.writes[0]).toEqual({ code: 'FAIR1234', active: false, expectedVersion: 1 });
    await expect(page.locator('#customer-codes option[value="FAIR1234"]')).toHaveCount(0);
    expect((await snapshot(page)).entries[0].customerCode).toBe('FAIR1234');
    // A stale catalogue must not resurrect a removed code.
    await page.evaluate(async () => (await import('/js/storage.js')).cacheCodes([{ code: 'FAIR1234', active: true, version: 1 }]));
    expect((await snapshot(page)).codes.find(row => row.code === 'FAIR1234')).toMatchObject({ active: false, version: 2 });
    await page.getByText('Borttagna kundkoder', { exact: true }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: testInfo.outputPath('removed-codes-mobile.png'), fullPage: true });
    await page.getByRole('button', { name: 'Återställ kundkod FAIR1234' }).click();
    await expect(remove(page)).toBeVisible();
    expect(state.writes[1]).toEqual({ code: 'FAIR1234', active: true, expectedVersion: 2 });
    await expect(page.locator('#customer-codes option[value="FAIR1234"]')).toHaveCount(1);
    await page.getByRole('button', { name: 'Månadsrapport', exact: true }).click();
    await expect(page.locator('#report-total')).toHaveText('8 h 30 min');
    expect(state.history).toHaveLength(1);
    expect((await snapshot(page)).entries[0].customerCode).toBe('FAIR1234');
});

test('failed removal leaves the active code available', async ({ page, context }) => {
    const state = await setup(page, context);
    state.failure = 503;
    await confirmRemoval(page);
    await expect(page.locator('#code-error')).toContainText('Servern kunde inte ändra');
    await expect(remove(page)).toBeEnabled();
    expect((await snapshot(page)).codes.find(row => row.code === 'FAIR1234').active).toBe(true);
    await expect(page.locator('#customer-codes option[value="FAIR1234"]')).toHaveCount(1);
});

test('concurrent code changes refresh the catalogue before retrying with the latest version', async ({ page, context }) => {
    const state = await setup(page, context);
    state.codes = state.codes.map(row => row.code === 'FAIR1234' ? { ...row, version: 3 } : row);
    await confirmRemoval(page);
    await expect(page.locator('#code-error')).toContainText('ändrats på servern');
    expect((await snapshot(page)).codes.find(row => row.code === 'FAIR1234').version).toBe(3);
    await confirmRemoval(page);
    await expect(remove(page)).toHaveCount(0);
    expect(state.writes.map(row => row.expectedVersion)).toEqual([1, 3]);
});

test('offline and unsynced entries disable removal without discarding pending work', async ({ page, context }) => {
    const state = await setup(page, context);
    await context.setOffline(true);
    await expect(remove(page)).toBeDisabled();
    await page.evaluate(async () => {
        const storage = await import('/js/storage.js');
        const date = document.querySelector('.week-day.today').dataset.date;
        await storage.saveEntry({ id: crypto.randomUUID(), date, start: '08:00', end: '17:00',
            deductedMinutes: 30, customerCode: 'FAIR1234', comment: 'Pending' });
        const account = JSON.parse(localStorage.getItem('timereport-account'));
        const channel = new BroadcastChannel(`timereport-changes-${account.accountId}`);
        channel.postMessage('changed'); channel.close();
    });
    await context.setOffline(false);
    await expect(remove(page)).toBeDisabled();
    await expect(remove(page)).toHaveAttribute('title', 'Vänta tills tider med kundkoden har synkats.');
    expect((await snapshot(page)).outbox).toHaveLength(1);
    expect(state.writes).toHaveLength(0);
});
