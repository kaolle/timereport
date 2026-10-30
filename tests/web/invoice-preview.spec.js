import {expect, test} from '@playwright/test';
import {readFileSync} from 'node:fs';

const properties = readFileSync('src/main/resources/application.properties', 'utf8');
const csp = properties.match(/^quarkus\.http\.header\."Content-Security-Policy"\.value=(.+)$/m)[1];
const customerId = '55555555-5555-4555-8555-555555555555';
const missingProfileMessage = 'Du har inte registrerat ditt företag. Fyll i och spara uppgifterna under Ditt företag först.';
const line = {
    customerCode: 'FAIR1234', description: 'Konsultarbete', workedMinutes: 90,
    hourlyRate: 1000, vatPercent: 25, netAmount: 1500, vatAmount: 375, totalAmount: 1875
};
const draft = {
    month: '2026-10', customerId, customerName: 'Kunden AB', ...line,
    weeks: [{weekNumber: 40, weekYear: 2026, ...line, lines: [line]}], unallocated: []
};
const sellerProfile = {
    sellerName: 'Testföretaget AB', addressLine: 'Testgatan 1', postalCode: '12345', city: 'Stockholm',
    country: 'Sverige', organizationNumber: '', vatNumber: '', bankgiro: '', email: 'company@example.test',
    currencyCode: 'SEK', paymentTermsDays: 30, defaultVatPercent: 25, fTaxRegistered: true
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

async function setup(page, context, {profile = sellerProfile} = {}) {
    const state = {
        previews: [],
        issued: 0,
        failPreview: false,
        invoices: [],
        deliveryRequests: [],
        deliveries: new Map(),
        mailSends: 0,
        loseEmailResponse: false,
        failEmail: false,
        profile,
        waitForPreview: null
    };
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
        if (path === '/api/invoicing/profile') {
            if (route.request().method() === 'PUT') state.profile = route.request().postDataJSON();
            return route.fulfill(state.profile ? {json: state.profile} : {status: 204});
        }
        if (path === '/api/invoicing/customers') return route.fulfill({
            json: [{
                id: customerId,
                name: 'Kunden AB',
                customerNumber: '1', invoiceEmail: 'customer@example.test', contactEmail: 'contact@example.test'
            }]
        });
        if (path === '/api/invoicing/invoice-numbering') return route.fulfill({json: {nextNumber: 1061}});
        if (/\/api\/invoicing\/invoices\/\d+\/delivery-defaults$/.test(path)) return route.fulfill({
            json: {cc: ['contact@example.test'], bcc: ['company@example.test']}
        });
        if (path === '/api/invoicing/draft') return route.fulfill({json: draft});
        if (path === '/api/invoicing/draft/pdf') {
            state.previews.push(route.request().postDataJSON());
            if (state.waitForPreview) await state.waitForPreview;
            if (!state.profile) return route.fulfill({
                status: 400, json: {code: 'INVALID_INPUT', message: missingProfileMessage}
            });
            if (state.failPreview) return route.fulfill({
                status: 503,
                json: {message: 'PDF kunde inte skapas. Försök igen.'}
            });
            return route.fulfill({contentType: 'application/pdf', body: samplePdf()});
        }
        if (path === '/api/invoicing/invoices') {
            if (route.request().method() === 'GET') return route.fulfill({json: state.invoices});
            state.issued++;
            state.invoices = [{
                invoiceNumber: 1061,
                month: '2026-10',
                customerName: 'Kunden AB',
                invoiceEmail: 'customer@example.test',
                totalAmount: 1875,
                vatAmount: 375,
                currencyCode: 'SEK',
                dueDate: '2999-11-30',
                issuedAt: new Date().toISOString(),
                status: {sent: false, paid: false, version: 0},
                lastDelivery: null
            }];
            return route.fulfill({
                contentType: 'application/pdf',
                headers: {'X-Invoice-Number': '1061'},
                body: samplePdf()
            });
        }
        if (/\/api\/invoicing\/invoices\/\d+\/pdf$/.test(path)) return route.fulfill({
            contentType: 'application/pdf',
            body: samplePdf()
        });
        if (/\/api\/invoicing\/invoices\/\d+\/email$/.test(path)) {
            const request = route.request().postDataJSON();
            state.deliveryRequests.push(request);
            if (!state.deliveries.has(request.requestId)) {
                const result = {...request, state: state.failEmail ? 'FAILED' : 'SENT'};
                state.deliveries.set(request.requestId, result);
                if (!state.failEmail) state.mailSends++;
                state.invoices[0].lastDelivery = result;
                if (!state.failEmail) state.invoices[0].status = {
                    ...state.invoices[0].status,
                    sent: true,
                    version: state.invoices[0].status.version + 1
                };
            }
            if (state.loseEmailResponse) {
                state.loseEmailResponse = false;
                return route.abort('failed');
            }
            return route.fulfill({json: state.deliveries.get(request.requestId)});
        }
        if (/\/api\/invoicing\/invoices\/\d+\/status$/.test(path)) {
            const values = route.request().postDataJSON();
            const invoice = state.invoices.find(item => path.includes(`/${item.invoiceNumber}/`));
            if (values.expectedVersion !== invoice.status.version) return route.fulfill({
                status: 409,
                json: {message: 'Statusen har ändrats.'}
            });
            invoice.status = {sent: values.sent, paid: values.paid, version: invoice.status.version + 1};
            return route.fulfill({json: invoice.status});
        }
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
    await page.getByRole('button', {name: 'Hämta fakturaunderlag', exact: true}).click();
    await expect(page.locator('.invoice-adjustment-card')).toHaveCount(1);
}

test('invoicing tabs switch complete panels on mobile and desktop and support keyboard navigation', async ({
                                                                                                               page,
                                                                                                               context
                                                                                                           }, testInfo) => {
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await setup(page, context);
    for (const width of [412, 1280]) {
        await page.setViewportSize({width, height: 900});
        const company = page.getByRole('tab', {name: 'Ditt företag', exact: true});
        const customers = page.getByRole('tab', {name: 'Kunder', exact: true});
        const newInvoice = page.getByRole('tab', {name: 'Ny faktura', exact: true});
        await company.click();
        await expect(company).toHaveAttribute('aria-selected', 'true');
        await expect(page.locator('#seller-profile-form')).toBeVisible();
        await expect(page.locator('#invoice-numbering-form')).toBeVisible();
        await expect(page.locator('#invoice-draft-form')).toBeHidden();
        await expect(page.locator('#code-form')).toBeHidden();
        await customers.click();
        await expect(page.locator('#billing-customer-list')).toBeVisible();
        await expect(page.locator('#code-form')).toBeVisible();
        await expect(page.locator('#invoice-numbering-form')).toBeHidden();
        await expect(page.locator('#invoice-panel-customers #unassigned-billing-codes')).toHaveCount(1);
        await newInvoice.click();
        await expect(page.locator('#invoice-draft-form')).toBeVisible();
        await expect(page.locator('#billing-customer-list')).toBeHidden();
        await expect(page.locator('#invoice-source-panel')).toBeHidden();
        await page.getByRole('button', {name: 'Visa tidsunderlag', exact: true}).click();
        await expect(page.locator('#invoice-source-panel')).toBeVisible();
        await expect(page.locator('#invoice-source-panel')).toContainText('Vecka 40');
        await expect(page.locator('#invoice-source-toggle')).toHaveAttribute('aria-expanded', 'true');
        await page.getByRole('button', {name: 'Dölj tidsunderlag', exact: true}).click();
        await expect(page.locator('#invoice-source-panel')).toBeHidden();
        await page.screenshot({path: testInfo.outputPath(`invoice-buttons-${width}.png`), fullPage: true});
        await newInvoice.press('ArrowRight');
        await expect(page.getByRole('tab', {name: 'Fakturor', exact: true})).toBeFocused();
        await page.getByRole('tab', {name: 'Fakturor', exact: true}).press('ArrowRight');
        await expect(company).toBeFocused();
        await expect(company).toHaveAttribute('aria-selected', 'true');
        await company.press('End');
        await expect(customers).toBeFocused();
        await expect(customers).toHaveAttribute('aria-selected', 'true');
        await customers.press('Home');
        await expect(newInvoice).toBeFocused();
        await expect(newInvoice).toHaveAttribute('aria-selected', 'true');
    }
    expect(errors).toEqual([]);
});

async function issueInvoice(page) {
    await page.getByRole('button', {name: 'Förhandsgranska PDF', exact: true}).click();
    await expect(page.locator('#invoice-issue-button')).toBeEnabled();
    page.once('dialog', dialog => dialog.accept());
    await page.locator('#invoice-issue-button').click();
    await expect(page.getByRole('dialog', {name: 'Faktura 1061 är utfärdad', exact: true})).toBeVisible();
}

test('copy recipients can be removed for an individual email', async ({page, context}) => {
    const state = await setup(page, context);
    await issueInvoice(page);
    await page.getByLabel('Ladda ner fakturan som PDF', {exact: true}).uncheck();
    await page.getByLabel('Skicka fakturan via e-post', {exact: true}).check();
    await page.getByLabel('CC – kopia', {exact: true}).fill('');
    await page.getByLabel('BCC – dold kopia', {exact: true}).fill('');
    await page.getByRole('button', {name: 'Genomför valen', exact: true}).click();
    await expect(page.locator('#invoice-email-status')).toContainText('Mejlet har lämnats');
    expect(state.deliveryRequests[0]).toMatchObject({cc: [], bcc: []});
});

test('failed default lookup blocks email but still allows PDF download', async ({page, context}) => {
    const state = await setup(page, context);
    await context.route('**/delivery-defaults', route => route.fulfill({status: 503}));
    await issueInvoice(page);
    await expect(page.locator('#invoice-email-status')).toContainText('Standardmottagarna kunde inte hämtas');
    await expect(page.getByLabel('Skicka fakturan via e-post', {exact: true})).toBeDisabled();
    const downloaded = page.waitForEvent('download');
    await page.getByRole('button', {name: 'Genomför valen', exact: true}).click();
    expect((await downloaded).suggestedFilename()).toBe('Faktura-1061.pdf');
    expect(state.mailSends).toBe(0);
});

test('issuing opens optional delivery and supports email and download together without issuing again', async ({
                                                                                                                  page,
                                                                                                                  context
                                                                                                              }, testInfo) => {
    const state = await setup(page, context);
    let downloads = 0;
    page.on('download', download => {
        if (download.suggestedFilename() === 'Faktura-1061.pdf') downloads++;
    });
    await issueInvoice(page);
    expect(downloads).toBe(0);
    expect(state.mailSends).toBe(0);
    await expect(page.getByLabel('Ladda ner fakturan som PDF', {exact: true})).toBeChecked();
    await expect(page.getByLabel('Skicka fakturan via e-post', {exact: true})).not.toBeChecked();
    await expect(page.getByLabel('Till e-postadress', {exact: true})).toHaveValue('customer@example.test');
    await page.getByLabel('Skicka fakturan via e-post', {exact: true}).check();
    await expect(page.getByLabel('CC – kopia', {exact: true})).toHaveValue('contact@example.test');
    await expect(page.getByLabel('BCC – dold kopia', {exact: true})).toHaveValue('company@example.test');
    await page.getByLabel('CC – kopia', {exact: true}).fill('contact@example.test, colleague@example.test');
    await page.getByLabel('Till e-postadress', {exact: true}).fill('edited@example.test');
    await page.screenshot({path: testInfo.outputPath('invoice-delivery.png')});
    const downloaded = page.waitForEvent('download');
    await page.getByRole('button', {name: 'Genomför valen', exact: true}).click();
    expect((await downloaded).suggestedFilename()).toBe('Faktura-1061.pdf');
    await expect(page.locator('#invoice-email-status')).toContainText('e-postservern för edited@example.test');
    expect(state.mailSends).toBe(1);
    expect(state.deliveryRequests[0].cc).toEqual(['contact@example.test', 'colleague@example.test']);
    expect(state.deliveryRequests[0].bcc).toEqual(['company@example.test']);
    expect(state.issued).toBe(1);
    await page.getByRole('button', {name: 'Stäng', exact: true}).click();
    await page.getByRole('button', {name: 'Visa tidsunderlag', exact: true}).click();
    await expect(page.locator('#invoice-source-panel')).toBeVisible();
    await page.getByRole('tab', {name: 'Fakturor', exact: true}).click();
    await expect(page.getByLabel('Skickad faktura 1061', {exact: true})).toBeChecked();
});

test('history groups invoices and totals and payment and sent checkboxes persist separately', async ({
                                                                                                         page,
                                                                                                         context
                                                                                                     }, testInfo) => {
    const state = await setup(page, context);
    await issueInvoice(page);
    await page.getByRole('button', {name: 'Stäng', exact: true}).click();
    await page.getByRole('tab', {name: 'Fakturor', exact: true}).click();
    await expect(page.getByRole('heading', {name: 'Obetalda', exact: true})).toBeVisible();
    state.invoices[0].dueDate = '2000-01-01';
    await page.getByRole('button', {name: 'Hämta fakturor', exact: true}).click();
    const overdue = page.locator('.invoice-history-group').filter({
        has: page.getByRole('heading', {
            name: 'Förfallna',
            exact: true
        })
    });
    await expect(overdue).toContainText('varav moms');
    await expect(overdue).toContainText('375,00');
    await page.getByLabel('Betald faktura 1061', {exact: true}).check();
    const paid = page.locator('.invoice-history-group').filter({
        has: page.getByRole('heading', {
            name: 'Betalda',
            exact: true
        })
    });
    await expect(paid.getByLabel('Betald faktura 1061', {exact: true})).toBeChecked();
    await expect(page.getByLabel('Skickad faktura 1061', {exact: true})).not.toBeChecked();
    await page.getByLabel('Skickad faktura 1061', {exact: true}).check();
    await expect.poll(() => state.invoices[0].status).toMatchObject({sent: true, paid: true, version: 2});
    for (const width of [320, 1280]) {
        await page.setViewportSize({width, height: 900});
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        expect(await page.locator('#connection').evaluate(item => item.getBoundingClientRect().right <= innerWidth)).toBe(true);
        await page.screenshot({path: testInfo.outputPath(`invoice-history-${width}.png`), fullPage: true});
    }
    await page.getByRole('button', {name: 'Leverera faktura 1061', exact: true}).click();
    const downloaded = page.waitForEvent('download');
    await page.getByRole('button', {name: 'Genomför valen', exact: true}).click();
    expect((await downloaded).suggestedFilename()).toBe('Faktura-1061.pdf');
    expect(state.mailSends).toBe(0);
    expect(state.issued).toBe(1);
});

test('retry after a lost email response reuses its request id without a second mail', async ({page, context}) => {
    const state = await setup(page, context);
    await issueInvoice(page);
    await page.getByLabel('Ladda ner fakturan som PDF', {exact: true}).uncheck();
    await page.getByLabel('Skicka fakturan via e-post', {exact: true}).check();
    state.loseEmailResponse = true;
    await page.getByRole('button', {name: 'Genomför valen', exact: true}).click();
    await expect(page.locator('#invoice-email-status')).toContainText('Samma utskicksförsök');
    await page.getByRole('button', {name: 'Genomför valen', exact: true}).click();
    await expect(page.locator('#invoice-email-status')).toContainText('Mejlet har lämnats');
    expect(state.deliveryRequests).toHaveLength(2);
    expect(state.deliveryRequests[0].requestId).toBe(state.deliveryRequests[1].requestId);
    expect(state.mailSends).toBe(1);
    expect(state.issued).toBe(1);
});

test('email failure leaves an issued invoice and still allows its PDF download', async ({page, context}) => {
    const state = await setup(page, context);
    await issueInvoice(page);
    state.failEmail = true;
    await page.getByLabel('Skicka fakturan via e-post', {exact: true}).check();
    const downloaded = page.waitForEvent('download');
    await page.getByRole('button', {name: 'Genomför valen', exact: true}).click();
    await downloaded;
    await expect(page.locator('#invoice-email-status')).toContainText('kunde inte bekräftas');
    expect(state.issued).toBe(1);
    expect(state.mailSends).toBe(0);
    expect(state.invoices[0].status.sent).toBe(false);
});

test('preview sends the edited rows and opens a PDF under the application security policy without issuing', async ({
                                                                                                                       page,
                                                                                                                       context
                                                                                                                   }) => {
    const state = await setup(page, context);
    await expect(page.locator('#invoice-issue-button')).toBeDisabled();
    await page.getByRole('button', {name: 'Förhandsgranska PDF', exact: true}).click();
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
    const preview = page.getByRole('button', {name: 'Förhandsgranska PDF', exact: true});
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
    const preview = page.getByRole('button', {name: 'Förhandsgranska PDF', exact: true});
    await preview.click();
    await expect(page.locator('#invoice-draft-status')).toContainText('PDF kunde inte skapas. Försök igen.');
    await expect(page.locator('#invoice-draft-status')).toBeFocused();
    await expect(page.locator('#invoice-draft-status')).toBeInViewport();
    await expect(preview).toBeEnabled();
    await expect(page.locator('#invoice-issue-button')).toBeDisabled();
    state.failPreview = false;
    await preview.click();
    await expect(page.locator('#invoice-pdf-preview')).toBeVisible();
    await expect(page.locator('#invoice-issue-button')).toBeEnabled();
});

test('missing company details guide setup without losing edited invoice rows', async ({page, context}, testInfo) => {
    const state = await setup(page, context, {profile: null});
    const description = page.locator('.invoice-adjustment-card [name="description"]');
    await description.fill('Justerad beskrivning');
    await page.getByRole('button', {name: 'Förhandsgranska PDF', exact: true}).click();
    await expect(page.locator('#invoice-draft-status')).toHaveText(missingProfileMessage);
    await expect(page.locator('#invoice-draft-status')).toBeInViewport();
    await expect(page.locator('#invoice-profile-required')).toBeVisible();
    await expect(page.locator('#invoice-issue-button')).toBeDisabled();
    expect(state.previews).toHaveLength(0);
    expect(state.issued).toBe(0);
    await page.screenshot({path: testInfo.outputPath('company-profile-required.png')});
    await page.getByRole('button', {name: 'Fyll i Ditt företag', exact: true}).click();
    await expect(page.getByRole('tab', {name: 'Ditt företag', exact: true})).toHaveAttribute('aria-selected', 'true');
    await expect(page.locator('#seller-name')).toBeFocused();
    await page.locator('#seller-name').fill('Testföretaget AB');
    await page.locator('#seller-address').fill('Testgatan 1');
    await page.locator('#seller-postal').fill('12345');
    await page.locator('#seller-city').fill('Stockholm');
    await page.getByRole('button', {name: 'Spara fakturauppgifter', exact: true}).click();
    await expect(page.locator('#seller-profile-status')).toContainText('har sparats');
    await page.getByRole('tab', {name: 'Ny faktura', exact: true}).click();
    await expect(description).toHaveValue('Justerad beskrivning');
    await expect(page.locator('#invoice-profile-required')).toBeHidden();
    await page.getByRole('button', {name: 'Förhandsgranska PDF', exact: true}).click();
    await expect(page.locator('#invoice-pdf-preview')).toBeVisible();
    expect(state.previews).toHaveLength(1);
    expect(state.previews[0].adjustments[0].description).toBe('Justerad beskrivning');
});

test('a production validation response is visible beside the preview controls', async ({page, context}) => {
    const state = await setup(page, context);
    state.profile = null;
    await page.getByRole('button', {name: 'Förhandsgranska PDF', exact: true}).click();
    const feedback = page.locator('#invoice-draft-status');
    await expect(feedback).toHaveText(missingProfileMessage);
    await expect(feedback).toBeFocused();
    await expect(feedback).toBeInViewport();
    await expect(page.locator('#invoice-issue-button')).toBeDisabled();
    await expect(page.locator('#invoice-draft-result #invoice-draft-status')).toHaveCount(1);
});

test('changing saved company details requires a new invoice preview', async ({page, context}) => {
    await setup(page, context);
    await page.getByRole('button', {name: 'Förhandsgranska PDF', exact: true}).click();
    await expect(page.locator('#invoice-issue-button')).toBeEnabled();
    await page.getByRole('tab', {name: 'Ditt företag', exact: true}).click();
    await page.locator('#seller-name').fill('Nytt företagsnamn AB');
    await page.getByRole('button', {name: 'Spara fakturauppgifter', exact: true}).click();
    await expect(page.locator('#seller-profile-status')).toContainText('har sparats');
    await page.getByRole('tab', {name: 'Ny faktura', exact: true}).click();
    await expect(page.locator('#invoice-issue-button')).toBeDisabled();
    await expect(page.locator('#invoice-pdf-preview')).toBeHidden();
});

test('company details saved while a preview is loading invalidate its result', async ({page, context}) => {
    const state = await setup(page, context);
    let release;
    state.waitForPreview = new Promise(resolve => { release = resolve; });
    await page.getByRole('button', {name: 'Förhandsgranska PDF', exact: true}).click();
    await expect.poll(() => state.previews.length).toBe(1);
    await page.getByRole('tab', {name: 'Ditt företag', exact: true}).click();
    await page.locator('#seller-name').fill('Uppdaterat företag AB');
    await page.getByRole('button', {name: 'Spara fakturauppgifter', exact: true}).click();
    await expect(page.locator('#seller-profile-status')).toContainText('har sparats');
    await page.getByRole('tab', {name: 'Ny faktura', exact: true}).click();
    release();
    await expect(page.locator('#invoice-draft-status')).toContainText('företagsuppgifterna ändrades');
    await expect(page.locator('#invoice-issue-button')).toBeDisabled();
    await expect(page.locator('#invoice-pdf-preview')).toBeHidden();
});

test('online reload replaces an outdated invoice draft module in the service worker cache', async ({page, context}) => {
    const state = await setup(page, context);
    await page.waitForFunction(() => navigator.serviceWorker.controller !== null);
    // Reproduce the actual stale version: HTML is current, but the cached module
    // still creates the invoice editor without the ID needed by the preview.
    const outdatedModule = readFileSync('src/main/resources/META-INF/resources/js/invoice-draft.js', 'utf8')
        .replace("        editorList.id = 'invoice-adjustment-list';\n", '');
    await page.evaluate(async source => {
        const version = new URL(navigator.serviceWorker.controller.scriptURL).searchParams.get('v');
        const cache = await caches.open(`timereport-shell-v${version}`);
        await cache.put('/js/invoice-draft.js', new Response(source, {
            headers: {'Content-Type': 'application/javascript'}
        }));
    }, outdatedModule);
    await page.reload();
    await expect(page.locator('#entry-fields')).toBeEnabled();
    await openDraft(page);
    await page.getByRole('button', {name: 'Förhandsgranska PDF', exact: true}).click();
    await expect.poll(() => state.previews.length).toBe(1);
    await expect(page.locator('#invoice-pdf-preview')).toBeVisible();
    expect(state.issued).toBe(0);
});
