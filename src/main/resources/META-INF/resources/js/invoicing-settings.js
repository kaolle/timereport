import {authenticatedFetch} from './auth.js';
import {formatMinutes} from './domain.js';
import {snapshot} from './storage.js';
import {configureInvoiceDelivery} from './invoice-delivery.js?v=49';

const $ = id => document.getElementById(id);
let busy = false;
let customers = [];
let codeSettings = [];
let defaultVat = '25';
let currencyCode = 'SEK';
let previewUrl = null;
let previewReadySignature = null;
let invoiceDelivery;

function showInvoiceView(view) {
    document.querySelectorAll('[data-invoice-panel]').forEach(panel => {
        panel.hidden = panel.dataset.invoicePanel !== view;
    });
    document.querySelectorAll('.invoice-navigation [data-invoice-view]').forEach(button => {
        const selected = button.dataset.invoiceView === view;
        button.setAttribute('aria-selected', String(selected));
        button.tabIndex = selected ? 0 : -1;
    });
    if (view === 'customers') window.dispatchEvent(new Event('billing-customer-codes-opened'));
    if (view === 'history') void invoiceDelivery.refresh();
}

async function request(path, method = 'GET', body, timeout = 10000) {
    const response = await authenticatedFetch(path, {
        method, cache: 'no-store', signal: AbortSignal.timeout(timeout),
        headers: body === undefined ? {} : {'Content-Type': 'application/json'},
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (response.status === 204) return undefined;
    let result;
    try { result = await response.json(); } catch { result = null; }
    if (!response.ok) throw new Error(result?.message || `Begäran misslyckades (${response.status}).`);
    return result;
}

function status(id, value) {
    $(id).textContent = value || '';
}

function node(tag, className, value) {
    const result = document.createElement(tag);
    if (className) result.className = className;
    if (value !== undefined) result.textContent = value;
    return result;
}

function money(value) {
    return new Intl.NumberFormat('sv-SE', {style: 'currency', currency: currencyCode}).format(Number(value));
}

function setDraftCustomers() {
    const select = $('invoice-draft-customer');
    const selected = select.value;
    select.replaceChildren(new Option('Välj kund…', ''));
    for (const customer of customers) {
        select.append(new Option(`${customer.customerNumber} · ${customer.name}`, customer.id,
            false, customer.id === selected));
    }
}

function monthlyInvoiceLines(draft) {
    const grouped = new Map();
    for (const week of draft.weeks) for (const line of week.lines) {
        const current = grouped.get(line.customerCode);
        if (!current) grouped.set(line.customerCode, {...line});
        else current.workedMinutes += line.workedMinutes;
    }
    return [...grouped.values()];
}

function invoicePayload(draft) {
    const adjustments = [...$('invoice-adjustment-list').querySelectorAll('[data-invoice-code]')].map(card => {
        const value = name => card.querySelector(`[name="${name}"]`);
        const hours = value('hours');
        if (!hours.checkValidity() || !value('description').checkValidity()
            || !value('rate').checkValidity() || !value('vat').checkValidity()) {
            throw new Error('Kontrollera fakturaradernas beskrivning, timmar, timpris och moms.');
        }
        const workedMinutes = Math.round(Number(hours.value) * 60);
        if (!Number.isFinite(workedMinutes) || workedMinutes > 10_000_000) throw new Error('Antalet timmar är för stort.');
        return {
            customerCode: card.dataset.invoiceCode,
            description: value('description').value.trim(),
            workedMinutes,
            hourlyRate: Number(value('rate').value),
            vatPercent: Number(value('vat').value)
        };
    });
    return {month: draft.month, customerId: draft.customerId, adjustments};
}

function invalidatePreview() {
    previewReadySignature = null;
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = null;
    const frame = $('invoice-pdf-frame');
    if (frame) frame.src = 'about:blank';
    const preview = $('invoice-pdf-preview');
    if (preview) preview.hidden = true;
    const issue = $('invoice-issue-button');
    if (issue) issue.disabled = true;
    status('invoice-draft-status', 'Utkastet har ändrats. Skapa en ny PDF-förhandsvisning innan fakturan kan utfärdas.');
}

function renderDraft(draft, pendingCount = 0) {
    previewReadySignature = null;
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    previewUrl = null;
    const result = $('invoice-draft-result');
    result.replaceChildren();
    const invoiceLines = monthlyInvoiceLines(draft);
    const summary = node('div', 'invoice-draft-summary');
    summary.append(node('p', 'eyebrow', `UTKAST · ${draft.customerName} · ${draft.month}`),
        node('strong', '', `${formatMinutes(draft.workedMinutes)} synkroniserad tid i underlaget`),
        node('p', '', 'Fakturans slutliga timmar och belopp räknas om efter dina ändringar och visas i PDF-förhandsvisningen.'),
        node('p', 'field-help', 'Visa tidsunderlag öppnar veckosammanställningen före fakturaändringar.'));
    result.append(summary);
    const editor = node('section', 'invoice-adjustment-section');
    editor.append(node('h4', '', 'Fakturarader · redigera utkastet'));
    editor.append(node('p', 'field-help', 'Ändra beskrivning, timmar, timpris eller moms. Noll timmar tar bort raden från utkastet. Utkastet sparas inte på servern; ändringarna påverkar inte tidsregistreringarna.'));
    const editorList = node('div', 'invoice-adjustment-list');
    editorList.id = 'invoice-adjustment-list';
    let rowNumber = 0;
    for (const line of invoiceLines) {
        const card = node('div', 'invoice-adjustment-card');
        card.dataset.invoiceCode = line.customerCode;
        card.append(node('strong', '', line.customerCode));
        const fields = node('div', 'invoice-adjustment-fields');
        const addField = (name, labelText, value, type = 'text', step = undefined) => {
            const wrapper = node('div', 'field');
            const input = document.createElement('input');
            input.id = `invoice-adjustment-${rowNumber}-${name}`;
            input.name = name;
            input.type = type;
            input.value = value;
            input.required = true;
            if (name === 'description') input.maxLength = 240;
            if (type === 'number') {
                input.min = '0';
                input.step = step || '0.01';
                input.max = name === 'hours' ? '166666.6666' : name === 'rate' ? '1000000' : '100';
            }
            const label = node('label', '', labelText);
            label.htmlFor = input.id;
            wrapper.append(label, input);
            fields.append(wrapper);
        };
        addField('description', 'Beskrivning', line.description);
        addField('hours', 'Timmar', (line.workedMinutes / 60).toFixed(4), 'number', '0.0001');
        addField('rate', 'Timpris', String(line.hourlyRate), 'number', '0.01');
        addField('vat', 'Moms (%)', String(line.vatPercent), 'number', '0.01');
        card.append(fields);
        editorList.append(card);
        rowNumber++;
    }
    editor.append(editorList);
    result.append(editor);
    const controls = node('div', 'invoice-draft-controls');
    if (invoiceLines.length > 0) {
        const previewButton = node('button', 'secondary-button', 'Förhandsgranska PDF');
        previewButton.type = 'button';
        previewButton.disabled = pendingCount > 0;
        previewButton.addEventListener('click', () => void previewDraft(draft, previewButton));
        controls.append(previewButton);
        const issue = node('button', 'primary-button invoice-issue-button', 'Godkänn och utfärda fakturan');
        issue.id = 'invoice-issue-button';
        issue.type = 'button';
        issue.disabled = true;
        issue.addEventListener('click', () => void issueDraft(draft, issue));
        controls.append(issue);
    } else {
        controls.append(node('p', 'field-help', 'Det finns inga fakturarader för vald kund och månad. Kontrollera kundkodernas kopplingar.'));
    }
    result.append(controls);
    const preview = node('section', 'invoice-pdf-preview');
    preview.id = 'invoice-pdf-preview';
    preview.hidden = true;
    const open = node('a', 'text-button', 'Öppna PDF i ny flik');
    open.id = 'invoice-pdf-open';
    open.target = '_blank';
    open.rel = 'noopener';
    const frame = document.createElement('iframe');
    frame.id = 'invoice-pdf-frame';
    frame.title = 'Förhandsvisning av fakturautkast';
    preview.append(open, frame);
    result.append(preview);
    for (const input of editorList.querySelectorAll('input')) input.addEventListener('input', invalidatePreview);
    const source = node('section');
    source.id = 'invoice-source-panel';
    source.hidden = true;
    source.setAttribute('aria-labelledby', 'invoice-source-title');
    const sourceTitle = node('h4', 'invoice-source-heading', 'Tidsunderlag per vecka');
    sourceTitle.id = 'invoice-source-title';
    source.append(sourceTitle);
    const sourceControls = node('div', 'invoice-draft-controls');
    const toggleSource = node('button', 'secondary-button', 'Visa tidsunderlag');
    toggleSource.id = 'invoice-source-toggle';
    toggleSource.type = 'button';
    toggleSource.setAttribute('aria-controls', source.id);
    toggleSource.setAttribute('aria-expanded', 'false');
    toggleSource.addEventListener('click', () => {
        source.hidden = !source.hidden;
        toggleSource.setAttribute('aria-expanded', String(!source.hidden));
        toggleSource.textContent = source.hidden ? 'Visa tidsunderlag' : 'Dölj tidsunderlag';
    });
    sourceControls.append(toggleSource);
    result.append(sourceControls, source);
    for (const week of draft.weeks) {
        const section = node('section', 'invoice-draft-week');
        section.append(node('h4', '', `Vecka ${week.weekNumber} (${week.weekYear})`));
        const scroll = node('div', 'invoice-table-scroll');
        const table = node('table', 'invoice-draft-table');
        const head = node('thead');
        const header = node('tr');
        for (const label of ['Kundkod och tjänst', 'Tid', 'Timpris', 'Netto', 'Moms', 'Summa']) {
            const cell = node('th', '', label); cell.scope = 'col'; header.append(cell);
        }
        head.append(header); table.append(head);
        const body = node('tbody');
        for (const line of week.lines) {
            const row = node('tr');
            row.append(node('td', '', `${line.customerCode} · ${line.description}`),
                node('td', '', formatMinutes(line.workedMinutes)), node('td', '', money(line.hourlyRate)),
                node('td', '', money(line.netAmount)), node('td', '', `${money(line.vatAmount)} (${line.vatPercent}%)`),
                node('td', '', money(line.totalAmount)));
            body.append(row);
        }
        if (!week.lines.length) {
            const row = node('tr'); const cell = node('td', 'report-empty', 'Ingen fakturerbar tid den här veckan.');
            cell.colSpan = 6; row.append(cell); body.append(row);
        }
        table.append(body);
        const foot = node('tfoot'); const total = node('tr');
        const label = node('th', '', 'Veckans summa'); label.scope = 'row';
        total.append(label, node('td', '', formatMinutes(week.workedMinutes)), node('td'),
            node('td', '', money(week.netAmount)), node('td', '', money(week.vatAmount)), node('td', '', money(week.totalAmount)));
        foot.append(total);
        table.append(foot);
        scroll.append(table);
        section.append(scroll);
        source.append(section);
    }
    if (draft.unallocated.length) {
        const section = node('section', 'invoice-unallocated');
        section.append(node('h4', '', 'Tid som inte ingår i underlaget'));
        const list = node('ul');
        for (const item of draft.unallocated) {
            list.append(node('li', '', `${item.customerCode || 'Utan kundkod'} · ${formatMinutes(item.workedMinutes)} · ${item.reason}`));
        }
        section.append(list); result.append(section);
    }
}

async function previewDraft(draft, button) {
    if (await pendingLocalCount(draft.month)) {
        status('invoice-draft-status', 'Synka månadens lokala registreringar och hämta underlaget igen innan du förhandsgranskar fakturan.');
        button.disabled = true;
        return;
    }
    let payload;
    try {
        payload = invoicePayload(draft);
    } catch (error) {
        status('invoice-draft-status', error.message);
        return;
    }
    button.disabled = true;
    status('invoice-draft-status', 'Skapar PDF-förhandsvisning av utkastet …');
    try {
        const response = await authenticatedFetch('/api/invoicing/draft/pdf', {
            method: 'POST', cache: 'no-store', signal: AbortSignal.timeout(30000),
            headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)
        });
        if (!response.ok) {
            let body;
            try {
                body = await response.json();
            } catch {
                body = null;
            }
            throw new Error(body?.message || `Förhandsvisningen misslyckades (${response.status}).`);
        }
        if (previewUrl) URL.revokeObjectURL(previewUrl);
        previewUrl = URL.createObjectURL(await response.blob());
        $('invoice-pdf-frame').src = previewUrl;
        $('invoice-pdf-open').href = previewUrl;
        $('invoice-pdf-preview').hidden = false;
        if (JSON.stringify(invoicePayload(draft)) !== JSON.stringify(payload)) {
            invalidatePreview();
            throw new Error('Utkastet ändrades medan PDF-förhandsvisningen skapades. Förhandsgranska igen.');
        }
        previewReadySignature = JSON.stringify(payload);
        const issue = $('invoice-issue-button');
        if (issue) issue.disabled = false;
        status('invoice-draft-status', 'Granska PDF-förhandsvisningen. Utkastet får inget permanent fakturanummer förrän du utfärdar det.');
    } catch (error) {
        status('invoice-draft-status', error.message || 'Förhandsvisningen kunde inte skapas.');
    } finally {
        button.disabled = false;
    }
}

async function issueDraft(draft, button) {
    if (await pendingLocalCount(draft.month)) {
        status('invoice-draft-status', 'Synka alla lokala registreringar för månaden och hämta ett nytt underlag innan du utfärdar fakturan.');
        button.disabled = true;
        return;
    }
    let payload;
    try {
        payload = invoicePayload(draft);
    } catch (error) {
        status('invoice-draft-status', error.message);
        return;
    }
    if (previewReadySignature !== JSON.stringify(payload)) {
        status('invoice-draft-status', 'Förhandsgranska den aktuella versionen av fakturan innan du utfärdar den.');
        button.disabled = true;
        return;
    }
    const summary = `${draft.customerName}, ${draft.month}`;
    if (!window.confirm(`Utfärda fakturan för ${summary}? Detta tilldelar ett permanent fakturanummer och låser fakturans innehåll.`)) return;
    button.disabled = true;
    status('invoice-draft-status', 'Utfärdar fakturan och skapar PDF …');
    try {
        const response = await authenticatedFetch('/api/invoicing/invoices', {
            method: 'POST', cache: 'no-store', signal: AbortSignal.timeout(30000),
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        if (!response.ok) {
            let body;
            try {
                body = await response.json();
            } catch {
                body = null;
            }
            throw new Error(body?.message || `Fakturan kunde inte utfärdas (${response.status}).`);
        }
        const pdf = await response.blob();
        const number = Number(response.headers.get('X-Invoice-Number'));
        status('invoice-draft-status', `Faktura ${number} är utfärdad. Välj hur den ska levereras. Den finns också under Fakturor.`);
        for (const control of $('invoice-draft-result').querySelectorAll('input,button:not(#invoice-source-toggle)')) control.disabled = true;
        button.remove();
        const recipient = customers.find(customer => customer.id === draft.customerId)?.invoiceEmail || '';
        const deliver = node('button', 'primary-button', `Leverera faktura ${number}`);
        deliver.type = 'button';
        deliver.addEventListener('click', () => invoiceDelivery.open({number, recipient, pdf}));
        $('invoice-draft-result').prepend(deliver);
        invoiceDelivery.open({number, recipient, pdf});
        void request('/api/invoicing/invoice-numbering').then(result => {
            $('invoice-next-number').value = result.nextNumber;
        }).catch(() => {
        });
        void invoiceDelivery.refresh();
    } catch (error) {
        status('invoice-draft-status', error.message || 'Fakturan kunde inte utfärdas.');
        button.disabled = false;
    }
}

async function loadDraft(event) {
    event?.preventDefault();
    if (!navigator.onLine) { status('invoice-draft-status', 'Anslut till nätet för att hämta fakturaunderlaget.'); return; }
    const month = $('invoice-draft-month').value;
    const customerId = $('invoice-draft-customer').value;
    if (!month || !customerId) { status('invoice-draft-status', 'Välj månad och kund.'); return; }
    const button = $('invoice-draft-load');
    button.disabled = true;
    status('invoice-draft-status', 'Beräknar underlaget från synkroniserade tider …');
    try {
        const query = new URLSearchParams({month, customerId});
        const draft = await request(`/api/invoicing/draft?${query}`);
        const pendingCount = await pendingLocalCount(month);
        renderDraft(draft, pendingCount);
        const warning = pendingCount ? ` ${pendingCount} lokal(a) registrering(ar) i månaden är inte synkroniserad(e) och ingår inte.` : '';
        status('invoice-draft-status', `Tidsunderlaget är hämtat från servern. Förhandsgranska den justerade PDF:en innan du utfärdar fakturan.${warning}`);
    } catch (error) {
        status('invoice-draft-status', error.message || 'Fakturaunderlaget kunde inte hämtas.');
    } finally { button.disabled = false; }
}

async function pendingLocalCount(month) {
    try {
        const local = await snapshot();
        return local.entries.filter(entry => !entry.deleted && entry.date.startsWith(`${month}-`)
            && entry.syncState !== 'synced').length;
    } catch {
        return 0;
    }
}

function formValues(form) {
    const values = Object.fromEntries(new FormData(form));
    for (const name of ['paymentTermsDays']) if (name in values) values[name] = Number(values[name]);
    for (const name of ['defaultVatPercent']) if (name in values) values[name] = Number(values[name]);
    if ('fTaxRegistered' in form.elements) values.fTaxRegistered = form.elements.fTaxRegistered.checked;
    return values;
}

function setProfile(profile) {
    const values = profile || {country: 'Sverige', currencyCode: 'SEK', paymentTermsDays: 30, defaultVatPercent: 25};
    const mapping = {
        sellerName: 'seller-name', addressLine: 'seller-address', postalCode: 'seller-postal', city: 'seller-city',
        country: 'seller-country', currencyCode: 'seller-currency', organizationNumber: 'seller-org',
        vatNumber: 'seller-vat', bankgiro: 'seller-bankgiro', email: 'seller-email',
        paymentTermsDays: 'seller-terms', defaultVatPercent: 'seller-vat-rate'
    };
    for (const [name, id] of Object.entries(mapping)) $(id).value = values[name] ?? '';
    $('seller-f-tax').checked = Boolean(values.fTaxRegistered);
    defaultVat = String(values.defaultVatPercent ?? 25);
    currencyCode = values.currencyCode || 'SEK';
}

function customerPayload(form) {
    const values = formValues(form);
    for (const name of ['initialCode', 'initialCodeDescription', 'initialCodeRate', 'initialCodeVat']) delete values[name];
    return values;
}

function createInput(field, value, prefix, required = false) {
    const wrapper = document.createElement('div');
    wrapper.className = 'field';
    const label = document.createElement('label');
    const input = document.createElement('input');
    input.name = field.name;
    input.id = `${prefix}-${field.name}`;
    input.type = field.type || 'text';
    input.maxLength = field.maxLength || 160;
    if (field.type === 'number') {
        input.min = field.min ?? '0';
        input.max = field.max ?? '365';
        input.step = field.step ?? '1';
    }
    input.required = required;
    input.value = value ?? field.defaultValue ?? '';
    label.htmlFor = input.id;
    label.textContent = field.label;
    wrapper.append(label, input);
    if (field.help) wrapper.append(node('p', 'field-help', field.help));
    return wrapper;
}

const customerFields = [
    {name: 'customerNumber', label: 'Kundnummer', maxLength: 40},
    {name: 'name', label: 'Kundens namn', maxLength: 160},
    {name: 'shortName', label: 'Kortnamn', maxLength: 40,
        help: 'Visas tillsammans med kundkoden när du registrerar tid, så att du lättare väljer rätt kund.'},
    {name: 'addressLine', label: 'Fakturaadress', maxLength: 200},
    {name: 'postalCode', label: 'Postnummer', maxLength: 40},
    {name: 'city', label: 'Ort', maxLength: 100},
    {name: 'country', label: 'Land', maxLength: 80, defaultValue: 'Sverige'},
    {name: 'organizationNumber', label: 'Organisationsnummer', maxLength: 40},
    {name: 'vatNumber', label: 'Momsregistreringsnummer', maxLength: 40},
    {name: 'invoiceEmail', label: 'Faktura-e-post', maxLength: 254, type: 'email'},
    {name: 'customerReference', label: 'Er referens', maxLength: 160},
    {name: 'ourReference', label: 'Vår referens', maxLength: 160},
    {name: 'paymentTermsDays', label: 'Betalningsvillkor (dagar)', type: 'number', max: 365, defaultValue: 30}
];

function renderCustomers() {
    const list = $('billing-customer-list');
    list.replaceChildren();
    if (!customers.length) {
        const empty = document.createElement('li');
        empty.textContent = 'Inga kunder registrerade ännu.';
        list.append(empty);
    }
    for (const customer of customers) {
        const item = document.createElement('li');
        const details = document.createElement('details');
        const summary = document.createElement('summary');
        summary.textContent = `${customer.customerNumber} · ${customer.name}`;
        const form = document.createElement('form');
        form.className = 'billing-form billing-customer-editor';
        for (const field of customerFields) {
            form.append(createInput(field, customer[field.name], `customer-${customer.id}`,
                ['customerNumber', 'name', 'addressLine', 'postalCode', 'city', 'country', 'paymentTermsDays'].includes(field.name)));
        }
        const actions = document.createElement('div');
        actions.className = 'billing-actions';
        const save = document.createElement('button');
        save.type = 'submit'; save.className = 'secondary-button'; save.textContent = 'Spara ändringar';
        const remove = document.createElement('button');
        remove.type = 'button'; remove.className = 'danger-button'; remove.textContent = 'Ta bort kund';
        remove.addEventListener('click', async () => {
            if (!window.confirm(`Ta bort kunden ”${customer.name}”?`)) return;
            try {
                await request(`/api/invoicing/customers/${encodeURIComponent(customer.id)}`, 'DELETE');
                await loadSettings();
                status('billing-error', '');
            } catch (error) { status('billing-error', error.message); }
        });
        actions.append(save, remove);
        form.append(actions);
        form.addEventListener('submit', async event => {
            event.preventDefault();
            try {
                await request(`/api/invoicing/customers/${encodeURIComponent(customer.id)}`, 'PUT', customerPayload(form));
                status('billing-error', 'Kunden har sparats.');
                await loadSettings();
            } catch (error) { status('billing-error', error.message); }
        });
        const codeSection = document.createElement('section');
        codeSection.className = 'customer-code-settings';
        const codeHeading = document.createElement('h4');
        codeHeading.textContent = 'Kundkoder och fakturering';
        codeSection.append(codeHeading);
        const assigned = codeSettings.filter(code => code.customerId === customer.id);
        if (!assigned.length) {
            const empty = document.createElement('p');
            empty.className = 'field-help';
            empty.textContent = 'Inga kundkoder är kopplade ännu.';
            codeSection.append(empty);
        }
        for (const code of assigned) codeSection.append(renderCodeSettingsCard(code));
        codeSection.append(createCustomerCodeForm(customer));
        details.append(summary, form, codeSection);
        item.append(details);
        list.append(item);
    }
}

function renderCodeSettingsCard(code) {
        const card = document.createElement('article');
        card.className = 'billing-code-card';
        const heading = document.createElement('h4');
        heading.textContent = `${code.code}${code.active ? '' : ' · borttagen kod'}`;
        const form = document.createElement('form');
        form.className = 'billing-form';
        const customerField = document.createElement('div');
        customerField.className = 'field';
        const customerLabel = document.createElement('label');
        customerLabel.textContent = 'Faktureras till kund';
        customerLabel.htmlFor = `billing-map-${code.code}`;
        const select = document.createElement('select');
        select.id = customerLabel.htmlFor;
        select.required = true;
        select.append(new Option('Välj kund…', ''));
        for (const customer of customers) {
            select.append(new Option(`${customer.customerNumber} · ${customer.name}`, customer.id,
                false, customer.id === code.customerId));
        }
        customerField.append(customerLabel, select);
        const grid = document.createElement('div');
        grid.className = 'billing-grid';
        const description = createInput({name: 'serviceDescription', label: 'Tjänst på fakturan', maxLength: 240}, code.serviceDescription, `billing-${code.code}`, true);
        const pricing = document.createElement('div');
        pricing.className = 'billing-grid';
        const rate = createInput({name: 'hourlyRate', label: 'Timpris', type: 'number', min: '0', max: '99999999', step: '0.01'}, code.hourlyRate, `billing-${code.code}`, true);
        const vat = createInput({name: 'vatPercent', label: 'Moms (%)', type: 'number', min: '0', max: '100', step: '0.01'}, code.vatPercent ?? defaultVat, `billing-${code.code}`, true);
        grid.append(rate, vat);
        const actions = document.createElement('div');
        actions.className = 'billing-actions';
        const save = document.createElement('button');
        save.type = 'submit'; save.className = 'secondary-button'; save.textContent = code.customerId ? 'Spara koppling' : 'Koppla till kund';
        actions.append(save);
        if (code.customerId) {
            const unlink = document.createElement('button');
            unlink.type = 'button'; unlink.className = 'text-button'; unlink.textContent = 'Ta bort koppling';
            unlink.addEventListener('click', async () => {
                try {
                    await request(`/api/invoicing/customer-codes/${encodeURIComponent(code.code)}`, 'DELETE');
                    status('billing-error', 'Kopplingen har tagits bort; koden och dess tider finns kvar.');
                    await loadSettings();
                } catch (error) { status('billing-error', error.message); }
            });
            actions.append(unlink);
        }
        form.append(customerField, description, grid, actions);
        form.addEventListener('submit', async event => {
            event.preventDefault();
            if (!customers.length) { status('billing-error', 'Lägg till en kund innan du kopplar kundkoder.'); return; }
            try {
                await request(`/api/invoicing/customer-codes/${encodeURIComponent(code.code)}`, 'PUT', {
                    customerId: select.value, serviceDescription: form.elements.serviceDescription.value,
                    hourlyRate: Number(form.elements.hourlyRate.value), vatPercent: Number(form.elements.vatPercent.value)
                });
                status('billing-error', `Kundkoden ${code.code} har kopplats.`);
                await loadSettings();
            } catch (error) { status('billing-error', error.message); }
        });
        card.append(heading, form);
        return card;
}

function createCustomerCodeForm(customer) {
    const form = document.createElement('form');
    form.className = 'billing-form customer-code-form';
    const heading = document.createElement('h5');
    heading.textContent = 'Lägg till eller koppla kundkod';
    const codeField = createInput({name: 'code', label: 'Kundkod', maxLength: 80,
        help: 'Skriv en ny kod eller välj en befintlig. Den kan även skapas direkt vid tidsregistrering.'}, '', `new-code-${customer.id}`, true);
    const codeInput = codeField.querySelector('input');
    const datalistId = `customer-code-options-${customer.id}`;
    codeInput.setAttribute('list', datalistId);
    form.append(heading, codeField,
        createInput({name: 'serviceDescription', label: 'Tjänst på fakturan', maxLength: 240}, 'Konsulttjänster', `new-code-description-${customer.id}`, true),
        createInput({name: 'hourlyRate', label: 'Timpris', type: 'number', min: '0', max: '99999999', step: '0.01'}, '', `new-code-rate-${customer.id}`, true),
        createInput({name: 'vatPercent', label: 'Moms (%)', type: 'number', min: '0', max: '100', step: '0.01', defaultValue: defaultVat}, defaultVat, `new-code-vat-${customer.id}`, true));
    const datalist = document.createElement('datalist');
    datalist.id = datalistId;
    for (const item of codeSettings.filter(candidate => candidate.active && (!candidate.customerId || candidate.customerId === customer.id))) {
        datalist.append(new Option(item.code));
    }
    form.append(datalist);
    const save = document.createElement('button');
    save.type = 'submit'; save.className = 'secondary-button'; save.textContent = 'Spara kundkod';
    const message = document.createElement('p');
    message.className = 'field-help'; message.setAttribute('role', 'status');
    form.append(save, message);
    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (!navigator.onLine) { message.textContent = 'Anslut till nätet för att spara kopplingen.'; return; }
        const values = formValues(form);
        const code = values.code.trim();
        try {
            await saveCodeForCustomer(customer.id, code, values.serviceDescription, Number(values.hourlyRate), Number(values.vatPercent));
            message.textContent = `Kundkoden ${code} är sparad och kopplad till ${customer.name}.`;
            await loadSettings();
        } catch (error) { message.textContent = error.message || 'Kundkoden kunde inte kopplas.'; }
    });
    return form;
}

async function saveCodeForCustomer(customerId, code, description, rate, vat) {
    if (!code) throw new Error('Ange en kundkod.');
    const existing = codeSettings.find(item => item.code === code);
    if (existing && !existing.active) throw new Error('Kundkoden är borttagen. Återställ den under Alla kundkoder först.');
    if (existing?.customerId && existing.customerId !== customerId) {
        throw new Error('Kundkoden är redan kopplad till en annan kund.');
    }
    if (!existing) await request(`/api/customer-codes/${encodeURIComponent(code)}`, 'PUT', {active: true, expectedVersion: 0});
    await request(`/api/invoicing/customer-codes/${encodeURIComponent(code)}`, 'PUT', {
        customerId, serviceDescription: description, hourlyRate: rate, vatPercent: vat
    });
}

function renderCodeSettings() {
    const list = $('unassigned-billing-codes');
    list.replaceChildren();
    const unassigned = codeSettings.filter(code => !code.customerId);
    if (!unassigned.length) {
        const empty = document.createElement('p');
        empty.className = 'field-help';
        empty.textContent = 'Alla faktureringskoder är kopplade till en kund.';
        list.append(empty);
        return;
    }
    for (const code of unassigned) list.append(renderCodeSettingsCard(code));
}

async function loadSettings() {
    if (!navigator.onLine || busy) {
        if (!navigator.onLine) status('invoicing-status', 'Anslut till nätet för att ändra faktureringsinställningar.');
        return;
    }
    busy = true;
    status('invoicing-status', 'Hämtar inställningar …');
    try {
        const [profile, loadedCustomers, loadedCodes, numbering] = await Promise.all([
            request('/api/invoicing/profile'), request('/api/invoicing/customers'), request('/api/invoicing/customer-codes'),
            request('/api/invoicing/invoice-numbering')
        ]);
        setProfile(profile);
        customers = loadedCustomers;
        $('invoice-next-number').value = numbering.nextNumber;
        setDraftCustomers();
        codeSettings = loadedCodes;
        renderCustomers();
        renderCodeSettings();
        window.dispatchEvent(new Event('billing-labels-updated'));
        status('invoicing-status', 'Inställningarna är hämtade.');
    } catch (error) {
        status('invoicing-status', error.message || 'Inställningarna kunde inte hämtas.');
    } finally { busy = false; }
}

export function configureInvoicingSettings() {
    invoiceDelivery = configureInvoiceDelivery({request});
    const now = new Date();
    $('invoice-draft-month').value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    $('invoice-draft-form').addEventListener('submit', loadDraft);
    const tabs = [...document.querySelectorAll('.invoice-navigation [data-invoice-view]')];
    tabs.forEach((button, index) => {
        button.addEventListener('click', () => showInvoiceView(button.dataset.invoiceView));
        button.addEventListener('keydown', event => {
            const target = event.key === 'ArrowRight' ? (index + 1) % tabs.length
                : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length
                : event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : null;
            if (target === null) return;
            event.preventDefault();
            showInvoiceView(tabs[target].dataset.invoiceView);
            tabs[target].focus();
        });
    });
    showInvoiceView('new');
    $('invoice-numbering-form').addEventListener('submit', async event => {
        event.preventDefault();
        if (!navigator.onLine) {
            status('invoice-numbering-status', 'Anslut till nätet för att spara.');
            return;
        }
        const form = event.currentTarget;
        const button = form.querySelector('button[type="submit"]');
        button.disabled = true;
        try {
            const result = await request('/api/invoicing/invoice-numbering', 'PUT', {nextNumber: Number($('invoice-next-number').value)});
            $('invoice-next-number').value = result.nextNumber;
            status('invoice-numbering-status', `Nästa faktura får nummer ${result.nextNumber}.`);
        } catch (error) {
            status('invoice-numbering-status', error.message);
        } finally {
            button.disabled = false;
        }
    });
    $('seller-profile-form').addEventListener('submit', async event => {
        event.preventDefault();
        if (!navigator.onLine) { status('seller-profile-status', 'Anslut till nätet för att spara.'); return; }
        try {
            const values = formValues(event.currentTarget);
            values.fTaxRegistered = $('seller-f-tax').checked;
            const profile = await request('/api/invoicing/profile', 'PUT', values);
            setProfile(profile);
            status('seller-profile-status', 'Fakturauppgifterna har sparats.');
        } catch (error) { status('seller-profile-status', error.message); }
    });

    $('billing-customer-form').addEventListener('submit', async event => {
        event.preventDefault();
        const form = event.currentTarget;
        if (!navigator.onLine) { status('billing-customer-status', 'Anslut till nätet för att spara.'); return; }
        const initialCode = form.elements.initialCode.value.trim();
        if (initialCode && (!form.elements.initialCodeDescription.value.trim() || !form.elements.initialCodeRate.value)) {
            status('billing-customer-status', 'Ange tjänstebeskrivning och timpris för kundkoden.');
            (form.elements.initialCodeDescription.value.trim() ? form.elements.initialCodeRate : form.elements.initialCodeDescription).focus();
            return;
        }
        const initialCodeSettings = codeSettings.find(code => code.code === initialCode);
        if (initialCodeSettings?.customerId) {
            status('billing-customer-status', 'Kundkoden är redan kopplad till en kund. Koppla den från kundens kundkodsinställningar i stället.');
            return;
        }
        if (initialCodeSettings && !initialCodeSettings.active) {
            status('billing-customer-status', 'Kundkoden är borttagen. Återställ den under Alla kundkoder först.');
            return;
        }
        let createdCustomer;
        try {
            createdCustomer = await request('/api/invoicing/customers', 'POST', customerPayload(form));
        } catch (error) {
            status('billing-customer-status', error.message || 'Kunden kunde inte sparas.');
            return;
        }
        if (initialCode) {
            try {
                await saveCodeForCustomer(createdCustomer.id, initialCode, form.elements.initialCodeDescription.value.trim(),
                    Number(form.elements.initialCodeRate.value), Number(form.elements.initialCodeVat.value || defaultVat));
            } catch (error) {
                status('billing-customer-status', `Kunden sparades, men kundkoden kunde inte kopplas: ${error.message}`);
                await loadSettings();
                return;
            }
        }
        try {
            form.reset();
            $('billing-customer-country').value = 'Sverige';
            $('billing-customer-terms').value = '30';
            $('billing-initial-code-vat').value = defaultVat;
            status('billing-customer-status', initialCode ? 'Kunden och kundkoden har lagts till.' : 'Kunden har lagts till.');
            await loadSettings();
        } catch (error) { status('billing-customer-status', error.message || 'Kundvyn kunde inte uppdateras.'); }
    });

    $('billing-initial-code').addEventListener('input', event => {
        const required = Boolean(event.currentTarget.value.trim());
        $('billing-initial-code-description').required = required;
        $('billing-initial-code-rate').required = required;
    });


    window.addEventListener('online', () => { if (document.querySelector('[data-view-panel="invoicing"]:not([hidden])')) void loadSettings(); });
    window.addEventListener('offline', () => status('invoicing-status', 'Utan nät. Inställningarna kan inte ändras offline.'));
    return {refreshIfOpen: loadSettings};
}
