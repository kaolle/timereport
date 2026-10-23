import {authenticatedFetch} from './auth.js';
import {formatMinutes} from './domain.js';
import {snapshot} from './storage.js';

const $ = id => document.getElementById(id);
let busy = false;
let customers = [];
let codeSettings = [];
let defaultVat = '25';
let currencyCode = 'SEK';

async function request(path, method = 'GET', body) {
    const response = await authenticatedFetch(path, {
        method, cache: 'no-store', signal: AbortSignal.timeout(10000),
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

function renderDraft(draft) {
    const result = $('invoice-draft-result');
    result.replaceChildren();
    const summary = node('div', 'invoice-draft-summary');
    summary.append(node('p', 'eyebrow', `UTKAST · ${draft.customerName} · ${draft.month}`),
        node('strong', '', `${formatMinutes(draft.workedMinutes)} · ${money(draft.totalAmount)} inkl. moms`),
        node('p', '', `Netto ${money(draft.netAmount)} · moms ${money(draft.vatAmount)}`));
    result.append(summary);
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
        foot.append(total); table.append(foot); scroll.append(table); section.append(scroll); result.append(section);
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
        renderDraft(draft);
        let pendingCount = 0;
        try {
            const local = await snapshot();
            pendingCount = local.entries.filter(entry => !entry.deleted && entry.date.startsWith(`${month}-`)
                && entry.syncState !== 'synced').length;
        } catch { /* Server preview remains available if local browser storage cannot be read. */ }
        const warning = pendingCount ? ` ${pendingCount} lokal(a) registrering(ar) i månaden är inte synkroniserad(e) och ingår inte.` : '';
        status('invoice-draft-status', `Förhandsvisningen är hämtad från servern. Inget fakturanummer har tilldelats och ingen faktura har skickats.${warning}`);
    } catch (error) {
        status('invoice-draft-status', error.message || 'Fakturaunderlaget kunde inte hämtas.');
    } finally { button.disabled = false; }
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
    return formValues(form);
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
        details.append(summary, form);
        item.append(details);
        list.append(item);
    }
}

function renderCodeSettings() {
    const list = $('billing-code-list');
    list.replaceChildren();
    if (!codeSettings.length) {
        const empty = document.createElement('p');
        empty.className = 'field-help';
        empty.textContent = 'Lägg först till kundkoder under Kundkoder. De visas sedan här.';
        list.append(empty);
        return;
    }
    for (const code of codeSettings) {
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
        list.append(card);
    }
}

async function loadSettings() {
    if (!navigator.onLine || busy) {
        if (!navigator.onLine) status('invoicing-status', 'Anslut till nätet för att ändra faktureringsinställningar.');
        return;
    }
    busy = true;
    status('invoicing-status', 'Hämtar inställningar …');
    try {
        const [profile, loadedCustomers, loadedCodes] = await Promise.all([
            request('/api/invoicing/profile'), request('/api/invoicing/customers'), request('/api/invoicing/customer-codes')
        ]);
        setProfile(profile);
        customers = loadedCustomers;
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
    const now = new Date();
    $('invoice-draft-month').value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    $('invoice-draft-form').addEventListener('submit', loadDraft);
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
        try {
            await request('/api/invoicing/customers', 'POST', customerPayload(form));
            form.reset();
            $('billing-customer-country').value = 'Sverige';
            $('billing-customer-terms').value = '30';
            status('billing-customer-status', 'Kunden har lagts till.');
            await loadSettings();
        } catch (error) { status('billing-customer-status', error.message); }
    });

    window.addEventListener('online', () => { if (document.querySelector('[data-view-panel="invoicing"]:not([hidden])')) void loadSettings(); });
    window.addEventListener('offline', () => status('invoicing-status', 'Utan nät. Inställningarna kan inte ändras offline.'));
    return {refreshIfOpen: loadSettings};
}
