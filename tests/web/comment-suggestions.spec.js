import {expect, test} from '@playwright/test';

const account = {accountId: '77777777-7777-4777-8777-777777777777', email: 'suggestions@example.test'};
const code = value => ({code: value, active: true, version: 1});
const row = (id, date, comment, customerCode = 'ACME', start = '08:00') => ({
    id: `00000000-0000-4000-8000-${String(id).padStart(12, '0')}`,
    date, start, end: `${String(Number(start.slice(0, 2)) + 1).padStart(2, '0')}:00`,
    deductedMinutes: 0, customerCode, comment, version: 1
});

test('shows recent comments across a week boundary, filters by customer and text, and saves a chosen editable value', async ({page, context}) => {
    const state = {entries: [], reads: []};
    await context.route('**/api/**', route => {
        const request = route.request();
        const url = new URL(request.url());
        if (url.pathname === '/api/auth/refresh') return route.fulfill({json: {
            accessToken: 'suggestions-access', expiresIn: 300, account
        }});
        if (url.pathname === '/api/customer-codes') return route.fulfill({json: [code('ACME'), code('OTHER')]});
        if (url.pathname === '/api/vacation-days') return route.fulfill({json: []});
        if (url.pathname === '/api/time-entries' && request.method() === 'GET') {
            state.reads.push(url);
            return route.fulfill({json: state.entries.filter(entry => entry.date >= url.searchParams.get('from')
                && entry.date <= url.searchParams.get('to'))});
        }
        return route.fulfill({status: 503});
    });
    await page.goto('/');
    await expect(page.locator('#entry-fields')).toBeEnabled();
    const dates = await page.evaluate(async () => {
        const {shiftDate, today} = await import('/js/domain.js');
        const current = today();
        const weekday = new Date(`${current}T12:00:00Z`).getUTCDay();
        const monday = shiftDate(current, -((weekday + 6) % 7));
        return {monday, previous: Array.from({length: 6}, (_, index) => shiftDate(monday, -index - 1))};
    });
    state.entries = [
        row(1, dates.previous[0], 'Kundmöte', 'ACME', '13:00'),
        row(2, dates.previous[0], 'Kodgranskning', 'ACME', '10:00'),
        row(3, dates.previous[1], 'Kundmöte'),
        row(4, dates.previous[2], 'Planering'),
        row(5, dates.previous[3], 'Drift'),
        row(6, dates.previous[4], 'Testning'),
        row(7, dates.previous[5], 'För gammal'),
        row(8, dates.previous[0], 'Annan kund', 'OTHER')
    ];
    await page.locator(`.week-day[data-date="${dates.monday}"]`).click();
    await expect.poll(() => state.reads.some(url => url.searchParams.get('from') === dates.previous[4])).toBe(true);
    await expect.poll(async () => (await page.evaluate(async () => (await import('/js/storage.js')).snapshot())).entries.length).toBe(7);
    await page.locator('#customer-codes').selectOption('ACME');
    await page.locator('#comment').focus();
    const suggestions = page.locator('#comment-suggestions button');
    await expect(suggestions).toHaveText(['Kundmöte', 'Kodgranskning', 'Planering', 'Drift', 'Testning']);
    await page.locator('#comment').fill('gransk');
    await expect(suggestions).toHaveText(['Kodgranskning']);
    await suggestions.click();
    await expect(page.locator('#comment')).toHaveValue('Kodgranskning');
    expect(await page.locator('#comment').evaluate(field => [field.selectionStart, field.selectionEnd]))
        .toEqual([13, 13]);
    await page.locator('#comment').pressSequentially(' klar');
    await page.locator('#start').fill('09:00');
    await page.locator('#end').fill('10:00');
    await expect.poll(async () => (await page.evaluate(async () => (await import('/js/storage.js')).snapshot()))
        .entries.some(entry => entry.date === dates.monday && entry.comment === 'Kodgranskning klar')).toBe(true);
    await page.locator('#comment').focus();
    await page.locator('#comment').fill('');
    await page.locator('#customer-codes').selectOption('OTHER');
    await page.locator('#comment').focus();
    await expect(suggestions).toHaveText(['Annan kund']);
    await context.setOffline(true);
    await page.reload();
    await page.locator(`.week-day[data-date="${dates.monday}"]`).click();
    await page.locator('#new-entry').click();
    await page.locator('#customer-codes').selectOption('ACME');
    await page.locator('#comment').focus();
    await expect(suggestions).toContainText(['Kundmöte', 'Kodgranskning']);
});
