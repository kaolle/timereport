import {expect, test} from '@playwright/test';
import {readFileSync} from 'node:fs';

const properties = readFileSync('src/main/resources/application.properties', 'utf8');
const csp = properties.match(/^quarkus\.http\.header\."Content-Security-Policy"\.value=(.+)$/m)[1];
const customerId = '55555555-5555-4555-8555-555555555555';
const line = {
    customerCode: 'FAIR1234', description: 'Konsultarbete', workedMinutes: 90,
    hourlyRate: 1000, vatPercent: 25, netAmount: 1500, vatAmount: 375, totalAmount: 1875
};
const draft = {
    month: '2026-10', customerId, customerName: 'Kunden AB', ...line,
    weeks: [{weekNumber: 40, weekYear: 2026, ...line, lines: [line]}], unallocated: []
};

// A valid, single-page PDF so Chromium exercises its PDF navigation under the real CSP.
function samplePdf() {
    const stream = 'BT /F1 12 Tf 20 80 Td (Invoice preview) Tj ET';
    const objects = ['<< /Type /Catalog /Pages 2 0 R >>',
        '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`];
    let pdf = '%PDF-1.4\n';
    const offsets = [];
    objects.forEach((object, i) => {
        offsets.push(pdf.length);
        pdf += `${i + 1} 0 obj\n${object}\nendobj\n`;
    });
    const start = pdf.length;
    pdf += `xref\n0 6\n0000000000 65535 f \n${offsets.map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('')}`;
    return Buffer.from(`${pdf}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${start}\n%%EOF\n`);
}

async function setup(page, context) {
    const state = {previews: [], issued: 0, failPreview: false};
    await context.route('http://127.0.0.1:4173/', async route => {
        const response = await route.fetch();
        await route.fulfill({response, headers: {...response.headers(), 'Content-Security-Policy': csp}});
    });
    await page.addInitScript(() => {
        window.previewPolicyViolations = [];
        document.addEventListener('securitypolicyviolation', event => {
            window.previewPolicyViolations.push({directive: event.effectiveDirective, blocked: event.blockedURI});
        });
    });
    await context.route('**/api/**', async route => {
        const path = new URL(route.request().url()).pathname;
        if (path === '/api/auth/refresh') return route.fulfill({
            json: {
                accessToken: 'preview-access', expiresIn: 300,
                account: {accountId: '44444444-4444-4444-8444-444444444444', email: 'preview@example.test'}
            }
        });
        if (path === '/api/invoicing/profile') return route.fulfill({status: 204});
        if (path === '/api/invoicing/customers') return route.fulfill({
            json: [{
                id: customerId,
                name: 'Kunden AB',
                customerNumber: '1'
            }]
        });
        if (path === '/api/invoicing/invoice-numbering') return route.fulfill({json: {nextNumber: 1061}});
        if (path === '/api/invoicing/draft') return route.fulfill({json: draft});
        if (path === '/api/invoicing/draft/pdf') {
            state.previews.push(route.request().postDataJSON());
            if (state.failPreview) return route.fulfill({
                status: 503,
                json: {message: 'PDF kunde inte skapas. Försök igen.'}
            });
            return route.fulfill({contentType: 'application/pdf', body: samplePdf()});
        }
        if (path === '/api/invoicing/invoices') state.issued++;
        return route.fulfill({json: []});
    });
    await page.goto('/');
    await expect(page.locator('#entry-fields')).toBeEnabled();
    await openDraft(page);
    return state;
}

async function openDraft(page) {
    await page.getByRole('button', {name: 'Fakturering', exact: true}).click();
    await expect(page.locator('#invoicing-status')).toHaveText('Inställningarna är hämtade.');
    await page.locator('#invoice-draft-month').fill('2026-10');
    await page.locator('#invoice-draft-customer').selectOption(customerId);
    await page.getByRole('button', {name: 'Visa fakturaunderlag', exact: true}).click();
    await expect(page.locator('.invoice-adjustment-card')).toHaveCount(1);
}

test('preview sends the edited rows and opens a PDF under the application security policy without issuing', async ({
                                                                                                                       page,
                                                                                                                       context
                                                                                                                   }) => {
    const state = await setup(page, context);
    await expect(page.locator('#invoice-issue-button')).toBeDisabled();
    await page.getByRole('button', {name: 'Förhandsgranska faktura som PDF', exact: true}).click();
    await expect.poll(() => state.previews.length).toBe(1);
    expect(state.previews[0]).toEqual({
        month: '2026-10', customerId, adjustments: [{
            customerCode: 'FAIR1234', description: 'Konsultarbete', workedMinutes: 90, hourlyRate: 1000, vatPercent: 25
        }]
    });
    await expect(page.locator('#invoice-pdf-preview')).toBeVisible();
    await expect(page.locator('#invoice-pdf-frame')).toHaveAttribute('src', /^blob:/);
    await expect(page.locator('#invoice-pdf-open')).toHaveAttribute('href', /^blob:/);
    await expect(page.locator('#invoice-issue-button')).toBeEnabled();
    await page.waitForFunction(() => [...document.querySelectorAll('iframe')].every(frame => frame.contentWindow !== null));
    expect(await page.evaluate(() => window.previewPolicyViolations)).toEqual([]);
    expect(state.issued).toBe(0);
});

test('changing a row requires a new preview and includes the new values', async ({page, context}) => {
    const state = await setup(page, context);
    const preview = page.getByRole('button', {name: 'Förhandsgranska faktura som PDF', exact: true});
    await preview.click();
    await expect(page.locator('#invoice-issue-button')).toBeEnabled();
    await page.locator('.invoice-adjustment-card input[name="hours"]').fill('2');
    await expect(page.locator('#invoice-pdf-preview')).toBeHidden();
    await expect(page.locator('#invoice-issue-button')).toBeDisabled();
    await preview.click();
    await expect(page.locator('#invoice-issue-button')).toBeEnabled();
    expect(state.previews[1].adjustments[0].workedMinutes).toBe(120);
    expect(state.issued).toBe(0);
});

test('a failed preview shows its error and can be retried', async ({page, context}) => {
    const state = await setup(page, context);
    state.failPreview = true;
    const preview = page.getByRole('button', {name: 'Förhandsgranska faktura som PDF', exact: true});
    await preview.click();
    await expect(page.locator('#invoice-draft-status')).toContainText('PDF kunde inte skapas. Försök igen.');
    await expect(preview).toBeEnabled();
    await expect(page.locator('#invoice-issue-button')).toBeDisabled();
    state.failPreview = false;
    await preview.click();
    await expect(page.locator('#invoice-pdf-preview')).toBeVisible();
    await expect(page.locator('#invoice-issue-button')).toBeEnabled();
});

test('online reload replaces an outdated invoicing module in the service worker cache', async ({page, context}) => {
    const state = await setup(page, context);
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
    // Reproduce the actual stale version: HTML is current, but the cached module
    // still creates the invoice editor without the ID needed by the preview.
    const outdatedModule = readFileSync('src/main/resources/META-INF/resources/js/invoicing-settings.js', 'utf8')
        .replace("    editorList.id = 'invoice-adjustment-list';\n", '');
    await page.evaluate(async source => {
        const version = new URL(navigator.serviceWorker.controller.scriptURL).searchParams.get('v');
        const cache = await caches.open(`timereport-shell-v${version}`);
        await cache.put('/js/invoicing-settings.js', new Response(source, {
            headers: {'Content-Type': 'application/javascript'}
        }));
    }, outdatedModule);
    await page.reload();
    await expect(page.locator('#entry-fields')).toBeEnabled();
    await openDraft(page);
    await page.getByRole('button', {name: 'Förhandsgranska faktura som PDF', exact: true}).click();
    await expect.poll(() => state.previews.length).toBe(1);
    await expect(page.locator('#invoice-pdf-preview')).toBeVisible();
    expect(state.issued).toBe(0);
});
