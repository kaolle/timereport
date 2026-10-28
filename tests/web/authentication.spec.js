import {expect, test} from '@playwright/test';

const A = {accountId: '11111111-1111-4111-8111-111111111111', email: 'a@example.test'};
const B = {accountId: '22222222-2222-4222-8222-222222222222', email: 'b@example.test'};

async function mock(context) {
    const state = {account: null, calls: [], writes: [], entries: [], refreshWait: null, refreshFailures: 0, acceptWrites: false};
    await context.route('**/api/**', async route => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        state.calls.push({path, headers: request.headers(), body: request.postData()});
        if (path === '/api/auth/refresh') {
            if (state.refreshWait) await state.refreshWait;
            if (state.refreshFailures > 0) {
                state.refreshFailures--;
                return route.fulfill({status: 503});
            }
        }
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
            if (state.acceptWrites && path.startsWith('/api/time-entries/') && request.method() === 'PUT') {
                const body = request.postDataJSON();
                const entry = {...body, id: path.split('/').at(-1), version: body.expectedVersion + 1};
                state.entries = [...state.entries.filter(row => row.id !== entry.id), entry];
                return route.fulfill({json: entry});
            }
            return route.fulfill({status: 503});
        }
        if (path === '/api/time-entries') return route.fulfill({json: state.entries});
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

async function login(page, account = A, {selectDay = true} = {}) {
    await page.goto('/');
    await expect(page.locator('#auth-panel')).toBeVisible();
    await page.locator('#auth-email').fill(account.email);
    await page.locator('#auth-password').fill('a sufficiently long password');
    await page.locator('#auth-submit').click();
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#account-email')).toHaveText(account.email);
    await expect(page.locator('#offline-readiness')).toContainText('Redo utan nät');
    if (selectDay) {
        await page.locator('.week-day.today').click();
        await expect(page.locator('#entry-panel')).toBeVisible();
        await expect(page.locator('#start')).toBeFocused();
    }
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

test('unavailable registration settings do not block session recovery', async ({page, context}) => {
    const state = await mock(context);
    state.account = A;
    let release;
    const waiting = new Promise(resolve => { release = resolve; });
    await context.route('**/api/auth/config', async route => {
        await waiting;
        await route.fulfill({json: {emailVerificationRequired: true}});
    });
    try {
        await page.goto('/');
        await expect(page.locator('#main')).toBeVisible();
        await expect(page.locator('#entry-fields')).toBeEnabled();
    } finally {
        release();
    }
});

test('a temporary refresh failure on reload opens remembered local data and can recover', async ({page, context}) => {
    await mock(context);
    await login(page);
    await seedPending(page);
    await context.route('**/api/auth/refresh', route => route.abort('failed'), {times: 1});
    await page.reload();
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#auth-panel')).toBeHidden();
    await expect(page.locator('#entry-fields')).toBeEnabled();
    expect((await snapshot(page)).outbox).toHaveLength(1);
    await expect.poll(() => page.evaluate(async () => {
        const {authenticatedFetch} = await import('/js/auth.js');
        try { return (await authenticatedFetch('/api/customer-codes')).status; }
        catch { return 0; }
    }), {timeout: 10000}).toBe(200);
});

test('returning to a restored page renews expired access before starting the API timeout', async ({page, context}) => {
    const state = await mock(context);
    await login(page);
    await expect(page.locator('#refresh')).toBeEnabled();
    const before = state.calls.filter(call => call.path === '/api/customer-codes').length;
    let renewalStarted = false;
    await context.route('**/api/auth/refresh', async route => {
        renewalStarted = true;
        // Longer than the data API's eight-second timeout, but within the refresh allowance.
        await new Promise(resolve => setTimeout(resolve, 9000));
        await route.fallback();
    });
    await page.clock.setFixedTime(new Date(Date.now() + 3 * 60 * 60 * 1000));
    await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', {persisted: true})));
    await expect.poll(() => renewalStarted).toBe(true);
    await expect.poll(() => state.calls.filter(call => call.path === '/api/customer-codes').length,
        {timeout: 15000}).toBeGreaterThan(before);
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#auth-panel')).toBeHidden();
});

test('a stalled refresh never blocks local time entry or inline codes and upload waits for authentication', async ({page, context}, testInfo) => {
    const state = await mock(context);
    await login(page);
    state.acceptWrites = true;
    let release;
    state.refreshWait = new Promise(resolve => { release = resolve; });
    try {
        await page.reload();
        await expect(page.locator('#entry-fields')).toBeEnabled({timeout: 3000});
        await expect(page.locator('#session-message')).toContainText('sparas lokalt');
        await page.locator('.week-day.today').click();
        await page.locator('#start').fill('08:00');
        await page.locator('#end').fill('17:00');
        await page.locator('#customer-code').fill('COLD-START');
        await expect(page.locator('#save-new-code')).toBeVisible();
        await page.locator('#save-new-code').click();
        await expect.poll(async () => (await snapshot(page)).entries[0]?.customerCode).toBe('COLD-START');
        expect((await snapshot(page)).outbox).toHaveLength(1);
        expect(state.writes).toHaveLength(0);
        await page.screenshot({path: testInfo.outputPath('local-entry-while-reconnecting.png')});
        // Strict server-side settings also wait for the token; remembered identity is no credential.
        await page.getByRole('button', {name: 'Fakturering', exact: true}).click();
        expect(state.calls.filter(call => call.path === '/api/invoicing/profile')).toHaveLength(0);
    } finally {
        state.refreshWait = null;
        release();
    }
    await expect.poll(async () => (await snapshot(page)).outbox.length, {timeout: 10000}).toBe(0);
    expect(state.writes).toHaveLength(1);
    expect(JSON.parse(state.writes[0].body).customerCode).toBe('COLD-START');
    expect(state.writes[0].account).toEqual(A);
    await expect(page.locator('#session-status')).toBeHidden();
});

test('a temporarily unavailable refresh is retried without an online event or a new login', async ({page, context}) => {
    const state = await mock(context);
    await login(page);
    await seedPending(page);
    state.acceptWrites = true;
    state.refreshFailures = 1;
    await page.reload();
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#connection')).toContainText('väntar på servern');
    await expect.poll(async () => (await snapshot(page)).outbox.length, {timeout: 15000}).toBe(0);
    await expect(page.locator('#session-status')).toBeHidden();
    expect(state.calls.filter(call => call.path === '/api/auth/login')).toHaveLength(1);
});

test('a different refresh-cookie account cannot upload the remembered account queue', async ({page, context}) => {
    const state = await mock(context);
    await login(page);
    await seedPending(page);
    state.account = B;
    state.acceptWrites = true;
    await page.reload();
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#session-login')).toBeVisible();
    expect((await snapshot(page)).outbox).toHaveLength(1);
    expect(state.writes).toHaveLength(0);
    await page.locator('#session-login').click();
    await expect(page.locator('#auth-panel')).toBeVisible();
    await page.locator('#auth-email').fill(B.email);
    await page.locator('#auth-password').fill('a sufficiently long password');
    await page.locator('#auth-submit').click();
    await expect(page.locator('#account-email')).toHaveText(B.email);
    expect((await snapshot(page)).entries).toHaveLength(0);
    expect(state.writes).toHaveLength(0);
});

test('explicit login waits for its handlers before accepting input on a slow startup', async ({page, context}) => {
    const state = await mock(context);
    state.account = B;
    await context.addInitScript(account => {
        localStorage.setItem('timereport-account', JSON.stringify(account));
    }, A);
    let release;
    const waiting = new Promise(resolve => { release = resolve; });
    await context.route('**/js/app.js*', async route => {
        await waiting;
        await route.fallback();
    });
    try {
        await page.goto('/?login=1', {waitUntil: 'commit'});
        await expect(page.locator('#auth-panel')).toBeVisible();
        await expect(page.locator('#auth-mode')).toBeDisabled();
        await expect(page.locator('#auth-email')).toBeDisabled();
        await expect(page.locator('#auth-password')).toBeDisabled();
        await expect(page.locator('#auth-submit')).toBeDisabled();
    } finally {
        release();
    }
    await page.locator('#auth-email').fill(B.email);
    await page.locator('#auth-password').fill('a sufficiently long password');
    await page.locator('#auth-submit').click();
    await expect(page.locator('#account-email')).toHaveText(B.email);
    const login = state.calls.find(call => call.path === '/api/auth/login');
    expect(JSON.parse(login.body).email).toBe(B.email);
    expect((await snapshot(page)).entries).toHaveLength(0);
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
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#session-login')).toBeVisible();
    await expect(page.locator('#session-message')).toContainText('Logga in igen');
    expect((await snapshot(page)).outbox).toHaveLength(1);
    await expect(page.locator('#sync')).toBeEnabled();
    await expect(page.locator('#refresh')).toBeEnabled();
    await page.getByRole('button', { name: 'Konto', exact: true }).click();
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
    if (!await page.locator('#entry-panel').isVisible()) await page.locator('.week-day.today').click();
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
    if (!await page.locator('#entry-panel').isVisible()) await page.locator('.week-day.today').click();
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
    await page.getByRole('button', { name: 'Konto', exact: true }).click();
    if (!await page.locator('#local-tools').evaluate(details => details.open)) await page.getByText('Fler alternativ', { exact: true }).click();
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


test('desktop keeps all seven days above the form and resizing preserves mobile placement and focus', async ({
                                                                                                                 page,
                                                                                                                 context
                                                                                                             }, testInfo) => {
    await mock(context);
    await page.setViewportSize({width: 1280, height: 960});
    await login(page);
    await page.locator('#previous-week').click();
    await expect(page.locator('#refresh')).toBeEnabled();
    await page.locator('.week-day').nth(1).click();
    await expect(page.locator('#entry-panel')).toBeVisible();
    const desktopLayout = () => page.evaluate(() => {
        const days = [...document.querySelectorAll('.week-day-slot')].map(day => day.getBoundingClientRect());
        const form = document.getElementById('entry-panel');
        return days.length === 7 && days.every(day => Math.abs(day.top - days[0].top) < 1)
            && form.getBoundingClientRect().top >= Math.max(...days.map(day => day.bottom))
            && form === document.getElementById('week-days').lastElementChild;
    });
    await expect.poll(desktopLayout).toBe(true);
    await page.screenshot({path: testInfo.outputPath('desktop-week.png'), fullPage: true});
    await page.locator('#customer-code').fill('DRAFT');
    await page.locator('#customer-code').evaluate(input => input.setSelectionRange(2, 2));
    await page.setViewportSize({width: 390, height: 844});
    await expect.poll(() => page.locator('#entry-panel').evaluate(form =>
        form.previousElementSibling.dataset.date === document.querySelector('.week-day.selected').dataset.date)).toBe(true);
    await expect(page.locator('#customer-code')).toBeFocused();
    await expect(page.locator('#customer-code')).toHaveValue('DRAFT');
    expect(await page.locator('#customer-code').evaluate(input => input.selectionStart)).toBe(2);
    await page.setViewportSize({width: 1280, height: 960});
    await expect.poll(desktopLayout).toBe(true);
    await expect(page.locator('#customer-code')).toBeFocused();
    await expect(page.locator('#customer-code')).toHaveValue('DRAFT');
});


test('collapsed day cards show the first interval, net total and a multiple-entry badge', async ({
                                                                                                     page,
                                                                                                     context
                                                                                                 }, testInfo) => {
    await mock(context);
    await login(page);
    await context.setOffline(true);
    await page.evaluate(async () => {
        const storage = await import('/js/storage.js');
        const date = document.querySelector('.week-day.selected').dataset.date;
        for (const [start, end, deductedMinutes] of [['14:00', '17:00', 0], ['08:00', '12:00', 30]]) {
            await storage.saveEntry({
                id: crypto.randomUUID(),
                date,
                start,
                end,
                deductedMinutes,
                customerCode: null,
                comment: ''
            });
        }
        const account = JSON.parse(localStorage.getItem('timereport-account'));
        const channel = new BroadcastChannel(`timereport-changes-${account.accountId}`);
        channel.postMessage('changed');
        channel.close();
    });
    const day = page.locator('.week-day.selected');
    await expect(day.locator('.week-day-time')).toHaveText('08:00–12:00');
    await expect(day.locator('.week-day-total')).toHaveText('6 h 30 min');
    await expect(day.locator('.week-day-count')).toHaveText('2 tider');
    await day.click();
    await expect(page.locator('#entry-panel')).toBeHidden();
    for (const width of [320, 390, 1280]) {
        await page.setViewportSize({width, height: 960});
        await expect(day.locator('.week-day-time')).toBeVisible();
        await expect(day.locator('.week-day-count')).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        expect(await day.evaluate(button => [...button.children].filter(node => !node.hidden)
            .every(node => node.getBoundingClientRect().right <= button.getBoundingClientRect().right))).toBe(true);
        await page.screenshot({path: testInfo.outputPath(`day-summary-${width}.png`), fullPage: true});
    }
    await page.evaluate(async () => {
        const storage = await import('/js/storage.js');
        const entry = (await storage.snapshot()).entries.find(entry => entry.start === '08:00');
        await storage.deleteEntry(entry.id, entry.localRevision);
        const account = JSON.parse(localStorage.getItem('timereport-account'));
        const channel = new BroadcastChannel(`timereport-changes-${account.accountId}`);
        channel.postMessage('changed');
        channel.close();
    });
    await expect(day.locator('.week-day-time')).toHaveText('14:00–17:00');
    await expect(day.locator('.week-day-count')).toBeHidden();
    await expect(day.locator('.week-day-total')).toHaveText('3 h 00 min');
});

test('choosing a day focuses start and keyboard time entry tabs directly to the next field', async ({
                                                                                                        page,
                                                                                                        context
                                                                                                    }) => {
    await mock(context);
    await login(page);
    await context.setOffline(true);
    await page.locator('.week-day.selected').click();
    await expect(page.locator('#entry-panel')).toBeHidden();
    await page.locator('.week-day.selected').click();
    await expect(page.locator('#start')).toBeFocused();
    await page.locator('#previous-week').click();
    await expect(page.locator('#entry-panel')).toBeHidden();
    await page.locator('.week-day').nth(1).click();
    await expect(page.locator('#start')).toBeFocused();
    await page.locator('#start').fill('0830');
    await page.locator('#start').press('Tab');
    await expect(page.locator('#start')).toHaveValue('08:30');
    await expect(page.locator('#end')).toBeFocused();
    await page.locator('#end').fill('1730');
    await page.locator('#end').press('Tab');
    await expect(page.locator('#end')).toHaveValue('17:30');
    await expect(page.locator('#deduction')).toBeFocused();
    await expect.poll(async () => (await snapshot(page)).entries[0]?.start).toBe('08:30');
    expect((await snapshot(page)).entries[0].end).toBe('17:30');
    await expect(page.locator('#start')).toHaveAttribute('type', 'text');
    await expect(page.locator('#end')).toHaveAttribute('type', 'text');
});


test('login, reload and week navigation leave every day unselected until explicitly chosen', async ({
                                                                                                        page,
                                                                                                        context
                                                                                                    }) => {
    await mock(context);
    await login(page, A, {selectDay: false});
    await expect(page.locator('.week-day.selected')).toHaveCount(0);
    await expect(page.locator('#entry-panel')).toBeHidden();
    await context.setOffline(true);
    await seedPending(page);
    await page.reload();
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('.week-day.selected')).toHaveCount(0);
    await expect(page.locator('#entry-panel')).toBeHidden();
    for (const width of [390, 1280]) {
        await page.setViewportSize({width, height: 960});
        await page.locator('#previous-week').click();
        await expect(page.locator('.week-day.selected')).toHaveCount(0);
        await expect(page.locator('#entry-panel')).toBeHidden();
        await page.locator('.week-day').nth(1).click();
        await expect(page.locator('.week-day.selected')).toHaveCount(1);
        await expect(page.locator('#entry-panel')).toBeVisible();
        await expect(page.locator('#start')).toBeFocused();
        await page.locator('#next-week').click();
        await expect(page.locator('.week-day.selected')).toHaveCount(0);
        await expect(page.locator('#entry-panel')).toBeHidden();
        await page.locator('.week-day.today').click();
        await expect(page.locator('#entry-panel')).toBeVisible();
        await page.locator('#today').click();
        await expect(page.locator('.week-day.selected')).toHaveCount(0);
        await expect(page.locator('#entry-panel')).toBeHidden();
    }
});


test('top navigation defaults to time, keeps the selected week and opens customer codes through invoicing tabs', async ({ page, context }, testInfo) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await mock(context);
    await login(page, A, { selectDay: false });
    const menu = page.getByRole('navigation', { name: 'Huvudmeny' });
    await expect(menu.getByRole('button', { name: 'Tider', exact: true })).toHaveAttribute('aria-current', 'page');
    await expect(page.locator('#time-view')).toBeVisible();
    await expect(page.locator('#monthly-report')).toBeHidden();
    await expect(page.locator('#invoicing-view')).toBeHidden();
    await expect(menu.getByRole('button', { name: 'Kundkoder', exact: true })).toHaveCount(0);
    await expect(page.locator('#account-view')).toBeHidden();
    await page.locator('#previous-week').click();
    await page.locator('.week-day').nth(1).click();
    const week = await page.locator('#week-label').textContent();
    await page.locator('#start').fill('08:30');
    await page.locator('#end').fill('17:45');
    await expect.poll(async () => (await snapshot(page)).entries.length).toBe(1);
    await menu.getByRole('button', { name: 'Fakturering', exact: true }).click();
    await expect(page.getByRole('tab', { name: 'Ny faktura', exact: true })).toHaveAttribute('aria-selected', 'true');
    await page.getByRole('tab', { name: 'Kunder', exact: true }).click();
    await expect(page.getByRole('tabpanel', { name: 'Kunder', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Kundkoder', exact: true })).toBeVisible();
    await expect(page.locator('#code-list')).toContainText('FAIR1234');
    await expect(page.locator('#time-view')).toBeHidden();
    await expect(page.getByText('Kundkoder och installation', { exact: true })).toHaveCount(0);
    await menu.getByRole('button', { name: 'Månadsrapport', exact: true }).click();
    await expect(page.locator('#monthly-report')).toBeVisible();
    await expect(page.locator('#invoicing-view')).toBeHidden();
    await expect(page.locator('#report-status')).toContainText('Månadens tider har hämtats');
    await menu.getByRole('button', { name: 'Konto', exact: true }).click();
    await expect(page.locator('#account-view')).toBeVisible();
    await expect(page.locator('#local-tools')).not.toHaveAttribute('open', '');
    await expect(page.locator('#sync')).toBeHidden();
    await menu.getByRole('button', { name: 'Installation', exact: true }).click();
    await expect(page.locator('#installation-view')).toBeVisible();
    await expect(page.locator('#installation-view')).toContainText('På iPhone: öppna i Safari');
    await expect(page.locator('#installation-view')).toContainText('På Android finns installation i webbläsarens meny.');
    await expect(page.locator('#installation-view')).toContainText('Installation kräver HTTPS, eller localhost på datorn.');
    await expect(page.locator('#installation-view').getByRole('button')).toHaveCount(0);
    await expect(page.locator('#account-view')).toBeHidden();
    await menu.getByRole('button', { name: 'Tider', exact: true }).click();
    await expect(page.locator('#week-label')).toHaveText(week);
    await expect(page.locator('#start')).toHaveValue('08:30');
    await expect(page.locator('#end')).toHaveValue('17:45');
    for (const width of [320, 390, 1280]) {
        await page.setViewportSize({ width, height: 960 });
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.screenshot({ path: testInfo.outputPath(`navigation-${width}.png`), fullPage: true });
    }
    await page.reload();
    await expect(page.locator('#time-view')).toBeVisible();
    await expect(menu.getByRole('button', { name: 'Tider', exact: true })).toHaveAttribute('aria-current', 'page');
    await expect(page.locator('.week-day.selected')).toHaveCount(0);
    expect(errors).toHaveLength(0);
});
