import {expect, test} from '@playwright/test';

const account = {accountId: '44444444-4444-4444-8444-444444444444', email: 'report@example.test'};
const row = (index, date, customerCode, start = '08:00', end = '17:00', deductedMinutes = 30) => ({
    id: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`, date, customerCode, start, end,
    deductedMinutes, comment: '', version: 1
});
const entries = [row(1, '2026-10-01', 'FAIRA'), row(2, '2026-10-02', 'FAIRA', '08:00', '12:00', 0),
    row(3, '2026-10-03', 'FAIRB', '10:00', '12:00', 0), row(4, '2026-10-04', null, '13:00', '14:30', 0),
    row(5, '2026-10-05', 'FAIRA'), row(6, '2026-10-31', 'FAIRB', '08:00', '10:00', 0),
    row(7, '2026-09-30', 'OUTSIDE'), row(8, '2026-11-01', 'NOVEMBER', '08:00', '10:00', 0)];
const snapshot = page => page.evaluate(async () => (await import('/js/storage.js')).snapshot());

async function setup(page, context, source = entries) {
    const state = {calls: [], waitForOctober: null, vacations: []};
    await context.route('**/api/**', async route => {
        const request = route.request();
        const url = new URL(request.url());
        state.calls.push(url.pathname + url.search);
        if (url.pathname === '/api/auth/refresh') return route.fulfill({
            json: {
                accessToken: 'report-access',
                expiresIn: 300,
                account
            }
        });
        if (url.pathname === '/api/customer-codes') return route.fulfill({
            json: ['FAIRA', 'FAIRB'].map(code => ({
                code,
                active: true,
                version: 1
            }))
        });
        if (url.pathname === '/api/time-entries') {
            const from = url.searchParams.get('from'), to = url.searchParams.get('to');
            if (from === '2026-10-01' && state.waitForOctober) await state.waitForOctober;
            return route.fulfill({json: source.filter(entry => entry.date >= from && entry.date <= to)});
        }
        if (url.pathname === '/api/vacation-days') {
            const from = url.searchParams.get('from'), to = url.searchParams.get('to');
            return route.fulfill({json: state.vacations.filter(day => day.date >= from && day.date <= to)});
        }
        if (request.method() !== 'GET') return route.fulfill({status: 503});
        return route.fulfill({json: []});
    });
    await page.goto('/');
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#entry-fields')).toBeEnabled();
    await expect(page.locator('#offline-readiness')).toContainText('Redo utan nät');
    await expect(page.locator('#refresh')).toBeEnabled();
    return state;
}

async function openReport(page, month = '2026-10') {
    await page.locator('#report-month').evaluate((input, month) => {
        input.value = month;
    }, month);
    await page.getByRole('button', {name: 'Månadsrapport', exact: true}).click();
    await expect(page.locator('#report-status')).toContainText('Månadens tider har hämtats');
}

test('monthly report lists each week by customer and clips totals to the selected month', async ({page, context}) => {
    const state = await setup(page, context);
    expect(state.calls.some(path => path.includes('from=2026-10-01'))).toBe(false);
    await openReport(page);
    await expect(page.locator('#report-total')).toHaveText('26 h 30 min');
    await expect(page.locator('.report-week')).toHaveCount(5);
    const first = page.locator('.report-week').first();
    await expect(first.locator('h3')).toHaveText('Vecka 40');
    await expect(first.locator('.report-week-range')).toContainText('1 okt.');
    await expect(first.locator('tbody tr')).toHaveText(['FAIRA12 h 30 min', 'FAIRB2 h 00 min', 'Utan kundkod1 h 30 min']);
    await expect(first.locator('.report-week-total')).toHaveText('16 h 00 min');
    await expect(page.locator('.report-week').last().locator('.report-week-total')).toHaveText('2 h 00 min');
    await expect(page.locator('#report-weeks')).not.toContainText('OUTSIDE');
});

test('report includes unsynced edits and deletions, survives offline reload and labels uncached months', async ({
                                                                                                                    page,
                                                                                                                    context
                                                                                                                }) => {
    await setup(page, context);
    await openReport(page);
    await context.setOffline(true);
    await page.evaluate(async () => {
        const storage = await import('/js/storage.js');
        const data = await storage.snapshot();
        const edited = data.entries.find(entry => entry.date === '2026-10-02');
        await storage.saveEntry({...edited, end: '10:00'}, edited.localRevision);
        const removed = data.entries.find(entry => entry.date === '2026-10-03');
        await storage.deleteEntry(removed.id, removed.localRevision);
        await storage.saveEntry({
            id: crypto.randomUUID(), date: '2026-10-06', start: '13:00', end: '14:30',
            deductedMinutes: 0, customerCode: null, comment: 'Offline'
        });
        const channel = new BroadcastChannel(`timereport-changes-${JSON.parse(localStorage.getItem('timereport-account')).accountId}`);
        channel.postMessage('changed');
        channel.close();
    });
    await expect(page.locator('#report-total')).toHaveText('24 h 00 min');
    await expect(page.locator('#report-status')).toContainText('Ej synkroniserade');
    await page.reload();
    await expect(page.locator('#entry-fields')).toBeEnabled();
    await page.getByRole('button', {name: 'Månadsrapport', exact: true}).click();
    await expect(page.locator('#report-total')).toHaveText('24 h 00 min');
    await expect(page.locator('#report-status')).toContainText('Offline');
    await expect(page.locator('#report-status')).not.toContainText('ofullständig');
    await page.locator('#report-month').fill('2026-09');
    await expect(page.locator('#report-status')).toContainText('ofullständig');
    await expect(page.locator('#report-total')).toHaveText('0 h 00 min');
    expect((await snapshot(page)).reportMonths.some(row => row.month === '2026-10')).toBe(true);
});

test('switching month while loading ignores an older response', async ({page, context}) => {
    const state = await setup(page, context);
    let release;
    state.waitForOctober = new Promise(resolve => {
        release = resolve;
    });
    await page.locator('#report-month').evaluate(input => {
        input.value = '2026-10';
    });
    await page.getByRole('button', {name: 'Månadsrapport', exact: true}).click();
    await expect.poll(() => state.calls.some(path => path.includes('from=2026-10-01'))).toBe(true);
    await page.locator('#report-month').fill('2026-11');
    await expect(page.locator('#report-total')).toHaveText('2 h 00 min');
    await expect(page.locator('#report-status')).toContainText('Månadens tider har hämtats');
    release();
    await expect(page.locator('#report-month')).toHaveValue('2026-11');
    await expect(page.locator('#report-total')).toHaveText('2 h 00 min');
});

test('report fits mobile and desktop and renders customer codes as plain text', async ({page, context}, testInfo) => {
    await setup(page, context, [row(1, '2026-10-01', '<img src=x onerror=alert(1)>')]);
    await openReport(page);
    await expect(page.locator('#report-weeks')).toContainText('<img src=x onerror=alert(1)>');
    await expect(page.locator('#report-weeks img')).toHaveCount(0);
    for (const [width, height] of [[320, 740], [390, 844], [1280, 960]]) {
        await page.setViewportSize({width, height});
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.locator('#monthly-report').screenshot({path: testInfo.outputPath(`monthly-report-${width}.png`)});
    }
});


test('refreshing a month leaves unchanged entry revisions valid for open edits', async ({page, context}) => {
    await setup(page, context);
    await openReport(page);
    const before = (await snapshot(page)).entries.find(entry => entry.date === '2026-10-01');
    await page.locator('#report-refresh').click();
    await expect(page.locator('#report-refresh')).toBeEnabled();
    const after = (await snapshot(page)).entries.find(entry => entry.id === before.id);
    expect(after.localRevision).toBe(before.localRevision);
    await page.evaluate(async entry => {
        const storage = await import('/js/storage.js');
        await storage.saveEntry({...entry, comment: 'An open edit'}, entry.localRevision);
    }, before);
    expect((await snapshot(page)).entries.find(entry => entry.id === before.id).comment).toBe('An open edit');
});

test('failed month fetch never marks a partial local report as complete', async ({page, context}) => {
    await setup(page, context);
    await context.route('**/api/time-entries?from=2026-10-01&to=2026-10-31', route => route.fulfill({status: 503}));
    await page.getByRole('button', {name: 'Månadsrapport', exact: true}).click();
    await expect(page.locator('#report-status')).toContainText('Månaden kunde inte hämtas');
    await expect(page.locator('#report-status')).toContainText('ofullständig');
    expect((await snapshot(page)).reportMonths).toHaveLength(0);
});

test('vacation days and full workweeks are fetched for the month and retained offline', async ({page, context}, testInfo) => {
    const state = await setup(page, context, []);
    state.vacations = ['2026-09-30', '2026-10-01', '2026-10-05', '2026-10-06', '2026-10-07',
        '2026-10-08', '2026-10-09', '2026-10-31', '2026-11-01'].map(date => ({date}));
    await openReport(page);
    await expect(page.locator('#report-vacation-total')).toHaveText('7 semesterdagar');
    await expect(page.locator('#report-total')).toHaveText('0 h 00 min');
    await expect(page.locator('.report-vacation time')).toHaveCount(7);
    await expect(page.locator('[data-week-start="2026-10-05"] .report-vacation strong'))
        .toHaveText('Semester hela arbetsveckan');
    await expect(page.locator('[data-week-start="2026-09-28"] .report-vacation strong')).toHaveText('Semester');
    await expect(page.locator('time[datetime="2026-09-30"]')).toHaveCount(0);
    for (const width of [320, 1280]) {
        await page.setViewportSize({width, height: 960});
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.locator('#monthly-report').screenshot({path: testInfo.outputPath(`vacation-report-${width}.png`)});
    }
    await context.setOffline(true);
    await page.reload();
    await expect(page.locator('#entry-fields')).toBeEnabled();
    await page.getByRole('button', {name: 'Månadsrapport', exact: true}).click();
    await expect(page.locator('#report-vacation-total')).toHaveText('7 semesterdagar');
    await page.evaluate(async () => {
        const storage = await import('/js/storage.js');
        await storage.setVacationDay('2026-10-07', false);
    });
    await page.locator('#report-month').dispatchEvent('change');
    await expect(page.locator('#report-vacation-total')).toHaveText('6 semesterdagar');
    await expect(page.locator('[data-week-start="2026-10-05"] .report-vacation strong')).toHaveText('Semester');
    await expect(page.locator('#report-status')).toContainText('Ej synkroniserade');
});

test('failed vacation fetch does not mark the month complete', async ({page, context}) => {
    await setup(page, context);
    await context.route('**/api/vacation-days?from=2026-10-01&to=2026-10-31', route => route.fulfill({status: 503}));
    await page.getByRole('button', {name: 'Månadsrapport', exact: true}).click();
    await expect(page.locator('#report-status')).toContainText('Månaden kunde inte hämtas');
    expect((await snapshot(page)).reportMonths).toHaveLength(0);
});
