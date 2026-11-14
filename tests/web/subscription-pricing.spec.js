import {expect, test} from '@playwright/test';

const admin = {accountId: '88888888-8888-4888-8888-888888888888', email: 'admin@example.test', roles: ['user', 'admin']};

async function setup(page, context, {administrator = true, cachedAdmin = false} = {}) {
    const account = administrator ? admin : {...admin, roles: ['user']};
    const state = {models: [], creates: [], activations: [], reads: 0, createFailures: 0, forbidden: false};
    if (cachedAdmin) await page.addInitScript(account => {
        localStorage.setItem('timereport-account', JSON.stringify(account));
    }, admin);
    await context.route('**/api/**', async route => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        if (path === '/api/auth/refresh') return route.fulfill({json: {accessToken: 'admin-test-token', expiresIn: 300, account}});
        if (path === '/api/auth/config') return route.fulfill({json: {emailVerificationRequired: true}});
        if (path.startsWith('/api/admin/')) {
            if (!administrator || state.forbidden) return route.fulfill({status: 403, json: {message: 'Administratörsbehörighet krävs.'}});
            if (request.method() === 'GET') {
                state.reads++;
                return route.fulfill({json: state.models});
            }
            if (path.endsWith('/activate')) {
                const id = path.split('/').at(-2);
                state.activations.push(id);
                const target = state.models.find(model => model.id === id);
                state.models = state.models.map(model => model.plan === target.plan ? {...model, active: model.id === id} : model);
                return route.fulfill({json: {...target, active: true}});
            }
            const body = request.postDataJSON();
            state.creates.push(body);
            if (state.createFailures-- > 0) return route.fulfill({status: 503, json: {message: 'Stripe kunde inte nås. Försök igen.'}});
            const model = {...body, state: 'READY', active: false, createdAt: '2026-11-14T10:00:00Z',
                stripeProductId: `prod_${body.id}`, stripePriceId: `price_${body.id}`, stripeTaxRateId: `txr_${body.id}`};
            state.models = [...state.models.filter(existing => existing.id !== model.id), model];
            return route.fulfill({json: model});
        }
        return route.fulfill({json: []});
    });
    await page.goto('/');
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#offline-readiness')).toContainText('Redo utan nät');
    return state;
}

async function openPricing(page) {
    await page.getByRole('button', {name: 'Administration', exact: true}).click();
    await expect(page.locator('#administration-view')).toBeVisible();
}

test('cached administrator hints cannot expose administration after a normal-user refresh', async ({page, context}) => {
    const state = await setup(page, context, {administrator: false, cachedAdmin: true});
    await expect(page.locator('#administration-navigation')).toBeHidden();
    await expect(page.locator('#administration-view')).toBeHidden();
    expect(state.reads).toBe(0);
    expect(state.creates).toHaveLength(0);
});

test('administrator creates and selects monthly and yearly prices with explicit VAT handling', async ({page, context}, testInfo) => {
    const state = await setup(page, context);
    await openPricing(page);
    await page.locator('#subscription-price-name').fill('Standard <Månad>');
    await page.locator('#subscription-price-amount').fill('49');
    await expect(page.locator('#subscription-price-total')).toContainText('61,25');
    await page.locator('#subscription-price-create').click();
    await expect(page.locator('#subscription-price-list')).toContainText('Standard <Månad>');
    expect(state.creates[0]).toMatchObject({name: 'Standard <Månad>', plan: 'MONTHLY', amount: 49, vatPercent: 25, vatInclusive: false});
    expect(state.creates[0].id).toMatch(/^[0-9a-f-]{36}$/);
    expect(state.models[0].active).toBe(false);
    await page.getByRole('button', {name: 'Använd för nya månadsköp'}).click();
    await expect(page.locator('#subscription-price-list')).toContainText('Aktuellt pris för nya månadsköp');
    await page.locator('#subscription-price-name').fill('Standard År');
    await page.locator('#subscription-price-plan').selectOption('YEARLY');
    await page.locator('#subscription-price-amount').fill('490');
    await page.locator('#subscription-price-inclusive').check();
    await expect(page.locator('#subscription-price-total')).toContainText('490,00');
    await expect(page.locator('#subscription-price-total')).toContainText('per år');
    await page.locator('#subscription-price-create').click();
    await expect(page.getByRole('button', {name: 'Använd för nya årsköp'})).toBeVisible();
    await page.getByRole('button', {name: 'Använd för nya årsköp'}).click();
    await expect(page.locator('#subscription-price-list')).toContainText('Aktuellt pris för nya årsköp');
    expect(state.models.filter(model => model.active)).toHaveLength(2);
    expect(state.creates[1]).toMatchObject({plan: 'YEARLY', amount: 490, vatInclusive: true});
    expect(state.activations).toHaveLength(2);
    expect(await page.locator('#subscription-price-list img').count()).toBe(0);
    await page.setViewportSize({width: 390, height: 844});
    await page.getByText('Stripe-referenser', {exact: true}).first().click();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({path: testInfo.outputPath('subscription-prices-mobile.png'), fullPage: true});
});

test('retrying a failed price creation preserves its request id and entered fields', async ({page, context}) => {
    const state = await setup(page, context);
    state.createFailures = 1;
    await openPricing(page);
    await page.locator('#subscription-price-name').fill('Retry');
    await page.locator('#subscription-price-amount').fill('59');
    await page.locator('#subscription-price-create').click();
    await expect(page.locator('#subscription-price-status')).toContainText('Stripe kunde inte nås');
    await expect(page.locator('#subscription-price-name')).toHaveValue('Retry');
    await page.getByRole('button', {name: 'Försök skapa pris igen'}).click();
    await expect(page.locator('#subscription-price-list')).toContainText('Retry');
    expect(state.creates).toHaveLength(2);
    expect(state.creates[0]).toEqual(state.creates[1]);
    expect(state.models).toHaveLength(1);
});

test('a revoked role closes administration without creating a price', async ({page, context}) => {
    const state = await setup(page, context);
    await openPricing(page);
    await page.locator('#subscription-price-name').fill('Denied');
    await page.locator('#subscription-price-amount').fill('49');
    state.forbidden = true;
    await page.locator('#subscription-price-create').click();
    await expect(page.locator('#administration-navigation')).toBeHidden();
    await expect(page.locator('#administration-view')).toBeHidden();
    await expect(page.locator('#time-view')).toBeVisible();
    await expect(page.locator('#notice')).toContainText('Administratörsbehörighet saknas');
    expect(state.models).toHaveLength(0);
});
