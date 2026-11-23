import {expect, test} from '@playwright/test';

async function setup(page, context, administrator = true) {
    const account = {accountId: '88888888-8888-4888-8888-888888888888', email: 'admin@example.test',
        roles: administrator ? ['user', 'admin'] : ['user']};
    const state = {theme: 'STANDARD', forbidden: false, writes: []};
    await context.route('**/api/**', async route => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/auth/refresh') return route.fulfill({json: {accessToken: 'test-token', expiresIn: 300, account}});
        if (path === '/api/auth/config') return route.fulfill({json: {emailVerificationRequired: true}});
        if (path === '/api/appearance') return route.fulfill({json: {theme: state.theme}});
        if (path === '/api/admin/appearance') {
            if (state.forbidden) return route.fulfill({status: 403, json: {}});
            state.writes.push(route.request().postDataJSON());
            state.theme = state.writes.at(-1).theme;
            return route.fulfill({json: {theme: state.theme}});
        }
        return route.fulfill({json: []});
    });
    await page.goto('/');
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#offline-readiness')).toContainText('Redo utan nät');
    return state;
}

test('admin switches between reusable Christmas and standard themes; cached theme works offline', async ({page, context}) => {
    const state = await setup(page, context);
    await page.getByRole('button', {name: 'Administration', exact: true}).click();
    await page.getByLabel('Tema för alla användare').selectOption('CHRISTMAS');
    // Choosing is a draft until saved.
    await expect(page.locator('html')).not.toHaveAttribute('data-theme', 'christmas');
    await page.getByRole('button', {name: 'Spara tema', exact: true}).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'christmas');
    expect(state.writes).toEqual([{theme: 'CHRISTMAS'}]);
    await expect(page.locator('.christmas-snow')).toBeVisible();
    await expect(page.locator('.christmas-santas svg')).toHaveCount(2);
    expect(await page.locator('.christmas-snow').evaluate(node => getComputedStyle(node).pointerEvents)).toBe('none');
    await expect(page.locator('#appearance-status')).toContainText('alla användare');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({path: '/tmp/tidrapporten-christmas-mobile.png'});
    await context.setOffline(true);
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'christmas');
    await context.setOffline(false);
    await expect(page.locator('#main')).toBeVisible();
    await page.getByRole('button', {name: 'Administration', exact: true}).click();
    await page.getByLabel('Tema för alla användare').selectOption('STANDARD');
    await page.getByRole('button', {name: 'Spara tema', exact: true}).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'standard');
    expect(state.theme).toBe('STANDARD');
    await expect(page.locator('.christmas-snow')).toBeHidden();
    await expect(page.locator('.christmas-santas')).toBeHidden();
});

test('ordinary users receive the global theme without admin controls', async ({page, context}) => {
    const state = await setup(page, context, false);
    state.theme = 'CHRISTMAS';
    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'christmas');
    await expect(page.locator('#administration-navigation')).toBeHidden();
    expect(state.writes).toHaveLength(0);
});

test('revoked administrative access cannot apply a local-only theme change', async ({page, context}) => {
    const state = await setup(page, context);
    state.forbidden = true;
    await page.getByRole('button', {name: 'Administration', exact: true}).click();
    await page.getByLabel('Tema för alla användare').selectOption('CHRISTMAS');
    await page.getByRole('button', {name: 'Spara tema', exact: true}).click();
    await expect(page.locator('#appearance-status')).toContainText('Administratörsbehörighet krävs');
    await expect(page.locator('html')).not.toHaveAttribute('data-theme', 'christmas');
});


test('snow moves without blocking controls and respects reduced motion on mobile and desktop', async ({page, context}) => {
    const state = await setup(page, context);
    state.theme = 'CHRISTMAS';
    await page.reload();
    const snow = page.locator('.christmas-snow');
    await expect(snow).toBeVisible();
    const flake = snow.locator('span').first();
    const initial = await flake.evaluate(node => getComputedStyle(node).transform);
    await expect.poll(() => flake.evaluate(node => getComputedStyle(node).transform)).not.toBe(initial);
    await page.getByRole('button', {name: 'Administration', exact: true}).click();
    await expect(page.locator('#appearance-theme')).toBeVisible();
    await page.setViewportSize({width: 1280, height: 800});
    await page.screenshot({path: '/tmp/tidrapporten-christmas-desktop.png'});
    await page.emulateMedia({reducedMotion: 'reduce'});
    await expect(snow).toBeHidden();
    expect(await flake.evaluate(node => getComputedStyle(node).animationName)).toBe('none');
    expect(await page.locator('.santa-leg').first().evaluate(node => getComputedStyle(node).animationName)).toBe('none');
    await page.getByLabel('Tema för alla användare').selectOption('STANDARD');
    await page.getByRole('button', {name: 'Spara tema', exact: true}).click();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'standard');
});

test('a device requesting reduced motion can explicitly start and pause Christmas animations', async ({page, context}) => {
    await page.emulateMedia({reducedMotion: 'reduce'});
    const state = await setup(page, context);
    state.theme = 'CHRISTMAS';
    await page.reload();
    const snow = page.locator('.christmas-snow');
    const leg = page.locator('.santa-leg').first();
    await expect(snow).toBeHidden();
    await expect(page.getByText('Enheten har minskad rörelse aktiverat.', {exact: false})).toBeVisible();
    await page.getByRole('button', {name: 'Starta julanimationer', exact: true}).click();
    await expect(snow).toBeVisible();
    // The global reduced-motion rule must not shorten explicitly enabled animations to .01ms.
    const timing = await snow.locator('span').first().evaluate(node => ({
        duration: parseFloat(getComputedStyle(node).animationDuration),
        size: parseFloat(getComputedStyle(node).fontSize),
        iterations: getComputedStyle(node).animationIterationCount
    }));
    expect(timing.duration).toBeGreaterThanOrEqual(24);
    expect(timing.size).toBeGreaterThanOrEqual(28);
    expect(timing.iterations).toBe('infinite');
    const legTiming = await leg.evaluate(node => ({duration: parseFloat(getComputedStyle(node).animationDuration),
        iterations: getComputedStyle(node).animationIterationCount}));
    // Alternating half-second swings make one full back-and-forth cycle per second.
    expect(legTiming.duration).toBe(0.5);
    expect(legTiming.iterations).toBe('infinite');
    const start = await snow.locator('span').first().boundingBox();
    await page.waitForTimeout(600);
    const end = await snow.locator('span').first().boundingBox();
    expect(end.y - start.y).toBeGreaterThan(1);
    expect(end.y - start.y).toBeLessThan(80);
    expect(Math.abs(end.x - start.x)).toBeLessThan(10);
    const initialSnow = await snow.locator('span').first().evaluate(node => getComputedStyle(node).transform);
    const initialLeg = await leg.evaluate(node => getComputedStyle(node).transform);
    await expect.poll(() => snow.locator('span').first().evaluate(node => getComputedStyle(node).transform)).not.toBe(initialSnow);
    await expect.poll(() => leg.evaluate(node => getComputedStyle(node).transform)).not.toBe(initialLeg);
    await page.reload();
    await expect(snow).toBeVisible();
    await page.getByRole('button', {name: 'Pausa julanimationer', exact: true}).click();
    await expect(snow).toBeHidden();
    expect(await leg.evaluate(node => getComputedStyle(node).animationName)).toBe('none');
    await page.reload();
    await expect(snow).toBeHidden();
    await expect(page.getByRole('button', {name: 'Starta julanimationer', exact: true})).toBeVisible();
});
