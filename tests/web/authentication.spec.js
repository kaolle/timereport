import {expect, test} from '@playwright/test';

const A = {accountId: '11111111-1111-4111-8111-111111111111', email: 'a@example.test'};
const B = {accountId: '22222222-2222-4222-8222-222222222222', email: 'b@example.test'};

async function mock(context) {
    const state = {account: null, calls: [], writes: []};
    await context.route('**/api/**', async route => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        state.calls.push({path, headers: request.headers(), body: request.postData()});
        if (path === '/api/auth/login') {
            state.account = request.postDataJSON().email === B.email ? B : A;
        }
        if (['/api/auth/login', '/api/auth/refresh'].includes(path)) {
            return route.fulfill({
                status: state.account ? 200 : 401, json: state.account
                    ? {accessToken: `secret-access-${state.account.accountId}`, expiresIn: 300, account: state.account}
                    : {message: 'Logga in igen'}
            });
        }
        if (path === '/api/auth/logout') {
            state.account = null;
            return route.fulfill({status: 204});
        }
        if (path.startsWith('/api/auth/')) return route.fulfill({
            status: 202,
            json: {message: 'Kontrollera din e-post.'}
        });
        if (request.method() !== 'GET') {
            state.writes.push({account: state.account, body: request.postData()});
            return route.fulfill({status: 503});
        }
        if (path === '/api/customer-codes') return route.fulfill({
            json: [{
                code: 'FAIR1234',
                active: true,
                version: 1
            }]
        });
        return route.fulfill({json: []});
    });
    return state;
}

async function login(page, account = A) {
    await page.goto('/');
    await expect(page.locator('#auth-panel')).toBeVisible();
    await page.locator('#auth-email').fill(account.email);
    await page.locator('#auth-password').fill('a sufficiently long password');
    await page.locator('#auth-submit').click();
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#account-email')).toHaveText(account.email);
    await expect(page.locator('#offline-readiness')).toContainText('Redo utan nät');
}

async function seedPending(page, comment = 'Private offline entry') {
    await page.evaluate(async comment => {
        const storage = await import('/js/storage.js');
        await storage.cacheCodes([{code: 'FAIR1234', active: true, version: 1}]);
        await storage.saveEntry({
            id: crypto.randomUUID(), date: '2026-10-12', start: '08:00', end: '17:00',
            deductedMinutes: 30, customerCode: 'FAIR1234', comment
        });
    }, comment);
}

const snapshot = page => page.evaluate(async () => (await import('/js/storage.js')).snapshot());

test('login uses memory-only access tokens and refresh never sends a bearer token', async ({page, context}) => {
    const state = await mock(context);
    await login(page);
    const persisted = await page.evaluate(() => JSON.stringify({...localStorage}) + JSON.stringify({...sessionStorage}));
    expect(persisted).not.toContain('secret-access');
    expect(persisted).not.toContain('password');
    expect(state.calls.find(call => call.path === '/api/auth/login').headers['x-requested-with']).toBe('timereport');
    await page.reload();
    await expect(page.locator('#main')).toBeVisible();
    for (const call of state.calls.filter(call => call.path === '/api/auth/refresh')) expect(call.headers.authorization).toBeUndefined();
    await expect.poll(() => state.calls.filter(call => call.path === '/api/customer-codes').length).toBeGreaterThan(0);
    expect(state.calls.find(call => call.path === '/api/customer-codes').headers.authorization).toContain('Bearer secret-access');
});

test('offline queue survives reload and remains isolated on account switch', async ({page, context}) => {
    const state = await mock(context);
    await login(page);
    await context.setOffline(true);
    await seedPending(page);
    await page.reload();
    await expect(page.locator('#main')).toBeVisible();
    expect((await snapshot(page)).outbox).toHaveLength(1);
    await context.setOffline(false);
    // Mimic a completed logout: the account marker is removed, data itself is retained.
    state.account = null;
    await page.evaluate(() => localStorage.removeItem('timereport-account'));
    await login(page, B);
    expect((await snapshot(page)).entries).toHaveLength(0);
    expect(state.writes.filter(write => write.account?.accountId === B.accountId && write.body?.includes('Private offline entry'))).toHaveLength(0);
    state.account = null;
    await page.evaluate(() => localStorage.removeItem('timereport-account'));
    await login(page, A);
    expect((await snapshot(page)).entries.some(entry => entry.comment === 'Private offline entry')).toBe(true);
});

test('session expiry never discards queued edits and logout hides local data', async ({page, context}) => {
    const state = await mock(context);
    await login(page);
    await seedPending(page);
    state.account = null;
    await page.reload();
    await expect(page.locator('#auth-panel')).toBeVisible();
    await page.locator('#auth-offline').click();
    await expect(page.locator('#main')).toBeVisible();
    expect((await snapshot(page)).outbox).toHaveLength(1);
    await expect(page.locator('#sync')).toBeEnabled();
    await page.locator('#logout').click();
    await expect(page.locator('#auth-panel')).toBeVisible();
    await expect(page.locator('#main')).toBeHidden();
    expect(await page.evaluate(() => localStorage.getItem('timereport-account'))).toBeNull();
});

test('reset link token is removed from URL and sent only in a POST body', async ({page, context}) => {
    const state = await mock(context);
    await page.goto('/#reset=secret-reset-token');
    await expect(page.locator('#auth-mode')).toHaveValue('reset-password');
    expect(new URL(page.url()).hash).toBe('');
    await page.locator('#auth-password').fill('a new sufficiently long password');
    await page.locator('#auth-submit').click();
    await expect(page.locator('#auth-mode')).toHaveValue('login');
    const reset = state.calls.find(call => call.path === '/api/auth/reset-password');
    expect(JSON.parse(reset.body).token).toBe('secret-reset-token');
    expect(await page.evaluate(() => JSON.stringify({...localStorage}))).not.toContain('secret-reset-token');
});


test('registration requests email only and verification lets the owner choose a password', async ({page, context}) => {
    const state = await mock(context);
    await page.goto('/');
    await expect(page.locator('#auth-panel')).toBeVisible();
    await page.locator('#auth-mode').selectOption('register');
    await expect(page.locator('#auth-password')).toBeHidden();
    await page.locator('#auth-email').fill(A.email);
    await page.locator('#auth-submit').click();
    await expect(page.locator('#auth-status')).toContainText('Kontrollera din e-post');
    const registration = state.calls.find(call => call.path === '/api/auth/register');
    expect(JSON.parse(registration.body)).toEqual({email: A.email});
    // Opening the email link in a fresh tab starts the verification flow.
    page = await context.newPage();
    await page.goto('/#verify=secret-verification-token');
    await expect(page.locator('#auth-mode')).toHaveValue('verify-email');
    await expect(page.locator('#auth-email')).toBeHidden();
    await expect(page.getByLabel('Välj nytt lösenord')).toBeVisible();
    await expect(page.locator('#auth-password')).toHaveAttribute('autocomplete', 'new-password');
    expect(new URL(page.url()).hash).toBe('');
    await page.locator('#auth-password').fill('my newly chosen password 123!');
    await page.locator('#auth-submit').click();
    await expect(page.locator('#auth-mode')).toHaveValue('login');
    const verification = state.calls.find(call => call.path === '/api/auth/verify-email');
    expect(JSON.parse(verification.body)).toEqual({
        token: 'secret-verification-token',
        password: 'my newly chosen password 123!'
    });
    expect(await page.locator('#auth-password').inputValue()).toBe('');
});


test('time entries autosave without a code and accept new codes offline', async ({page, context}) => {
    await mock(context);
    await login(page);
    await context.setOffline(true);
    if (!await page.locator('#entry-panel').isVisible()) await page.locator('.week-day.selected').click();
    await expect(page.locator('#entry-panel')).toBeVisible();
    await page.locator('#start').fill('08:00');
    await page.locator('#end').fill('17:00');
    await expect.poll(async () => (await snapshot(page)).entries.length).toBe(1);
    expect((await snapshot(page)).entries[0].customerCode).toBeNull();
    await page.locator('#customer-code').fill('  INLINE987  ');
    await expect(page.locator('#save-new-code')).toBeVisible();
    expect((await snapshot(page)).entries[0].customerCode).toBeNull();
    expect((await snapshot(page)).codes.some(code => code.code === 'INLINE987')).toBe(false);
    await page.locator('#save-new-code').click();
    await expect.poll(async () => (await snapshot(page)).entries[0].customerCode).toBe('INLINE987');
    expect((await snapshot(page)).codes.some(code => code.code === 'INLINE987')).toBe(true);
    // A catalogue refresh must preserve codes belonging to unsent entries.
    await page.evaluate(async () => (await import('/js/storage.js')).cacheCodes([]));
    expect((await snapshot(page)).codes.some(code => code.code === 'INLINE987')).toBe(true);
    await page.reload();
    expect((await snapshot(page)).entries[0].customerCode).toBe('INLINE987');
    if (!await page.locator('#entry-panel').isVisible()) await page.locator('.week-day.selected').click();
    await page.locator('#customer-code').fill('');
    await expect.poll(async () => (await snapshot(page)).entries[0].customerCode).toBeNull();
});


test('unknown customer codes wait for confirmation and correcting the code restores autosave', async ({
                                                                                                          page,
                                                                                                          context
                                                                                                      }) => {
    const state = await mock(context);
    await login(page);
    await context.setOffline(true);
    await page.locator('#customer-code').fill('MISSPELLED');
    await expect(page.locator('#save-new-code')).toBeVisible();
    expect((await snapshot(page)).entries).toHaveLength(0);
    expect((await snapshot(page)).codes.some(code => code.code === 'MISSPELLED')).toBe(false);
    await page.locator('#start').fill('08:00');
    await page.locator('#end').fill('17:00');
    await expect(page.locator('#save-new-code')).toBeVisible();
    expect((await snapshot(page)).outbox).toHaveLength(0);
    // Enter in the form must not bypass explicit confirmation of an unknown code.
    await page.locator('#entry-form').evaluate(form => form.requestSubmit());
    await expect(page.locator('#save-new-code')).toBeVisible();
    expect((await snapshot(page)).entries).toHaveLength(0);
    await page.locator('#customer-code').fill('FAIR1234');
    await expect(page.locator('#save-new-code')).toBeHidden();
    await expect.poll(async () => (await snapshot(page)).entries[0]?.customerCode).toBe('FAIR1234');
    await page.locator('#customer-code').fill('ANOTHER-TYPO');
    await expect(page.locator('#save-new-code')).toBeVisible();
    expect((await snapshot(page)).entries[0].customerCode).toBe('FAIR1234');
    await page.locator('#customer-code').fill('CORRECT123');
    await expect(page.locator('#save-new-code')).toHaveText('Spara kundkod ”CORRECT123”');
    await page.locator('#save-new-code').click();
    await expect.poll(async () => (await snapshot(page)).entries[0].customerCode).toBe('CORRECT123');
    const data = await snapshot(page);
    expect(data.codes.some(code => code.code === 'ANOTHER-TYPO')).toBe(false);
    expect(data.codes.some(code => code.code === 'MISSPELLED')).toBe(false);
    expect(state.writes.some(write => write.body?.includes('MISSPELLED') || write.body?.includes('ANOTHER-TYPO'))).toBe(false);
});


test('local autosave precedes delayed sync, later edits postpone upload, and manual sync is immediate', async ({
                                                                                                                   page,
                                                                                                                   context
                                                                                                               }) => {
    const state = await mock(context);
    await login(page);
    await page.clock.install();
    await page.clock.pauseAt(new Date());
    await page.locator('#start').fill('08:00');
    await page.locator('#end').fill('17:00');
    await page.locator('#end').press('Tab');
    await page.clock.runFor(700);
    await expect.poll(async () => (await snapshot(page)).entries.length).toBe(1);
    await expect(page.locator('#autosave-status')).toContainText('Sparad lokalt');
    expect(state.writes).toHaveLength(0);
    await page.clock.runFor(3000);
    await page.locator('#comment').fill('Latest revision');
    await page.locator('#comment').press('Tab');
    await page.clock.runFor(700);
    await expect.poll(async () => (await snapshot(page)).entries[0].comment).toBe('Latest revision');
    await expect(page.locator('#autosave-status')).toContainText('Sparad lokalt');
    await page.clock.runFor(4300);
    expect(state.writes).toHaveLength(0);
    await page.clock.runFor(700);
    await expect.poll(() => state.writes.length).toBe(1);
    expect(JSON.parse(state.writes[0].body).comment).toBe('Latest revision');
    await expect(page.locator('#sync')).toBeEnabled();
    await page.locator('#comment').fill('Manual upload');
    await page.locator('#sync').click();
    await expect.poll(() => state.writes.length).toBe(2);
    expect(JSON.parse(state.writes[1].body).comment).toBe('Manual upload');
});


test('mobile code picker selects existing codes and clearing the field preserves focus through autosave', async ({
                                                                                                                     page,
                                                                                                                     context
                                                                                                                 }) => {
    await mock(context);
    await login(page);
    await context.setOffline(true);
    await page.locator('#start').fill('08:00');
    await page.locator('#end').fill('17:00');
    await expect.poll(async () => (await snapshot(page)).entries.length).toBe(1);
    await page.getByRole('combobox', {name: 'Välj befintlig kundkod'}).selectOption('FAIR1234');
    await expect(page.locator('#customer-code')).toHaveValue('FAIR1234');
    await expect.poll(async () => (await snapshot(page)).entries[0].customerCode).toBe('FAIR1234');
    await page.locator('#customer-code').fill('');
    await expect(page.locator('#customer-code')).toBeFocused();
    await expect.poll(async () => (await snapshot(page)).entries[0].customerCode).toBeNull();
    await expect(page.locator('#autosave-status')).toContainText('Sparad lokalt');
    await expect(page.locator('#customer-code')).toBeFocused();
    // Background reloads must not detach the focused form either.
    await page.evaluate(() => {
        const account = JSON.parse(localStorage.getItem('timereport-account'));
        const channel = new BroadcastChannel(`timereport-changes-${account.accountId}`);
        channel.postMessage('changed');
        channel.close();
    });
    await expect(page.locator('#customer-code')).toBeFocused();
    await page.locator('#customer-code').fill('NEW-MOBILE-CODE');
    await expect(page.locator('#save-new-code')).toHaveText('Spara kundkod ”NEW-MOBILE-CODE”');
    await expect(page.locator('#save-new-code')).toBeVisible();
    await page.locator('#save-new-code').tap();
    await expect.poll(async () => (await snapshot(page)).entries[0].customerCode).toBe('NEW-MOBILE-CODE');
});


test('autosaved new entries can be deleted and keeping a registration leaves it intact', async ({page, context}) => {
    await mock(context);
    await login(page);
    await context.setOffline(true);
    await expect(page.locator('#cancel-edit')).toHaveCount(0);
    await expect(page.locator('#delete-current')).toBeHidden();
    await page.locator('#start').fill('08:00');
    await page.locator('#end').fill('17:00');
    await expect.poll(async () => (await snapshot(page)).entries.length).toBe(1);
    await expect(page.locator('#delete-current')).toHaveText('Ta bort');
    await expect(page.locator('#delete-current')).toBeVisible();
    await page.locator('#delete-current').click();
    await expect(page.locator('#delete-dialog')).toBeVisible();
    await page.locator('#cancel-delete').click();
    expect((await snapshot(page)).entries.filter(entry => !entry.deleted)).toHaveLength(1);
    await expect(page.locator('#start')).toHaveValue('08:00');
    // Deleting also discards edits waiting for autosave instead of resurrecting them.
    await page.locator('#comment').fill('pending change');
    await page.locator('#delete-current').click();
    await page.locator('#confirm-delete').click();
    await expect.poll(async () => (await snapshot(page)).entries.length).toBe(0);
    expect((await snapshot(page)).outbox).toHaveLength(0);
    await expect(page.locator('#start')).toHaveValue('');
    await expect(page.locator('#delete-current')).toBeHidden();
});
