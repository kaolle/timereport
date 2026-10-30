import {expect, test} from '@playwright/test';

test('one version module controls metadata, assets, offline cache and the update page', async ({page, context}) => {
    const version = '9876';
    const requests = [];
    page.on('request', request => requests.push(new URL(request.url())));
    // Change only the version source; all other app files are served unchanged.
    await context.route('**/js/version.js', route => route.fulfill({
        contentType: 'application/javascript', body: `export const APP_VERSION = '${version}';`
    }));
    await context.route('**/api/**', route => {
        const path = new URL(route.request().url()).pathname;
        return route.fulfill({
            json: path === '/api/auth/refresh' ? {
                accessToken: 'version-test', expiresIn: 300,
                account: {accountId: '77777777-7777-4777-8777-777777777777', email: 'version@example.test'}
            } : []
        });
    });
    await page.goto('/');
    await expect(page.locator('#main')).toBeVisible();
    await expect(page.locator('#app-version-label')).toHaveText(`(v ${version})`);
    await expect(page.locator('meta[name="application-version"]')).toHaveAttribute('content', version);
    await expect(page.locator('link[rel="stylesheet"]')).toHaveAttribute('href', `/styles.css?v=${version}`);
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
    expect(await page.evaluate(() => new URL(navigator.serviceWorker.controller.scriptURL).searchParams.get('v'))).toBe(version);
    for (const file of ['/js/app.js', '/js/invoicing-settings.js', '/js/invoice-delivery.js']) {
        expect(requests.some(url => url.pathname === file)).toBe(true);
    }
    await page.goto('/force-update.html');
    await expect(page.locator('meta[name="application-version"]')).toHaveAttribute('content', version);
});
