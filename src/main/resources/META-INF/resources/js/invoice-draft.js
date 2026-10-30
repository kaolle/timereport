import {authenticatedFetch} from './auth.js';
import {formatMinutes} from './domain.js';
import {snapshot} from './storage.js';

const $ = id => document.getElementById(id);

function status(id, value) {
    $(id).textContent = value || '';
    if (id === 'invoice-draft-status') $(id).className = 'field-help';
}

export function configureInvoiceDraft({request, getCustomers, getProfile, showCompany, invoiceDelivery}) {
    let previewUrl = null;
    let previewReadySignature = null;
    const missingProfileMessage = 'Du har inte registrerat ditt företag. Fyll i och spara uppgifterna under Ditt företag först.';

    function draftError(message) {
        status('invoice-draft-status', message);
        const feedback = $('invoice-draft-status');
        feedback.className = 'error-box';
        if (!$('invoice-panel-new').hidden) {
            feedback.focus();
            feedback.scrollIntoView({block: 'nearest'});
        }
    }

    function missingProfile() {
        return getProfile().loaded && !getProfile().profile;
    }

    function node(tag, className, value) {
        const result = document.createElement(tag);
        if (className) result.className = className;
        if (value !== undefined) result.textContent = value;
        return result;
    }

    function money(value) {
        return new Intl.NumberFormat('sv-SE', {
            style: 'currency',
            currency: getProfile().profile?.currencyCode || 'SEK'
        }).format(Number(value));
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
        // Keep the same live status element when rebuilding the editor, near its actions.
        const feedback = $('invoice-draft-status');
        result.replaceChildren(feedback);
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
        const profileNotice = node('div', 'notice invoice-profile-required');
        profileNotice.id = 'invoice-profile-required';
        profileNotice.hidden = !missingProfile();
        profileNotice.append(node('p', '', `${missingProfileMessage} Dina ändrade fakturarader finns kvar när du byter flik.`));
        const editProfile = node('button', 'secondary-button', 'Fyll i Ditt företag');
        editProfile.type = 'button';
        editProfile.addEventListener('click', () => {
            showCompany();
            ($('seller-profile-form').querySelector('input:invalid') || $('seller-name')).focus();
        });
        profileNotice.append(editProfile);
        result.append(profileNotice, controls, feedback);
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
                const cell = node('th', '', label);
                cell.scope = 'col';
                header.append(cell);
            }
            head.append(header);
            table.append(head);
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
                const row = node('tr');
                const cell = node('td', 'report-empty', 'Ingen fakturerbar tid den här veckan.');
                cell.colSpan = 6;
                row.append(cell);
                body.append(row);
            }
            table.append(body);
            const foot = node('tfoot');
            const total = node('tr');
            const label = node('th', '', 'Veckans summa');
            label.scope = 'row';
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
            section.append(list);
            result.append(section);
        }
    }

    async function previewDraft(draft, button) {
        if (missingProfile()) {
            draftError(missingProfileMessage);
            return;
        }
        if (await pendingLocalCount(draft.month)) {
            status('invoice-draft-status', 'Synka månadens lokala registreringar och hämta underlaget igen innan du förhandsgranskar fakturan.');
            button.disabled = true;
            return;
        }
        let payload;
        try {
            payload = invoicePayload(draft);
        } catch (error) {
            draftError(error.message);
            return;
        }
        button.disabled = true;
        status('invoice-draft-status', 'Skapar PDF-förhandsvisning av utkastet …');
        const profileSignature = JSON.stringify(getProfile().profile);
        try {
            const response = await authenticatedFetch('/api/invoicing/draft/pdf', {
                method: 'POST', cache: 'no-store', timeoutMs: 30000,
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
            if (JSON.stringify(invoicePayload(draft)) !== JSON.stringify(payload)
                || JSON.stringify(getProfile().profile) !== profileSignature) {
                invalidatePreview();
                throw new Error('Utkastet eller företagsuppgifterna ändrades medan PDF-förhandsvisningen skapades. Förhandsgranska igen.');
            }
            previewReadySignature = JSON.stringify(payload);
            const issue = $('invoice-issue-button');
            if (issue) issue.disabled = false;
            status('invoice-draft-status', 'Granska PDF-förhandsvisningen. Utkastet får inget permanent fakturanummer förrän du utfärdar det.');
        } catch (error) {
            draftError(error.message || 'Förhandsvisningen kunde inte skapas.');
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
                method: 'POST', cache: 'no-store', timeoutMs: 30000,
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
            const recipient = getCustomers().find(customer => customer.id === draft.customerId)?.invoiceEmail || '';
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
            draftError(error.message || 'Fakturan kunde inte utfärdas.');
            button.disabled = false;
        }
    }

    async function loadDraft(event) {
        event?.preventDefault();
        if (!navigator.onLine) {
            status('invoice-draft-status', 'Anslut till nätet för att hämta fakturaunderlaget.');
            return;
        }
        const month = $('invoice-draft-month').value;
        const customerId = $('invoice-draft-customer').value;
        if (!month || !customerId) {
            status('invoice-draft-status', 'Välj månad och kund.');
            return;
        }
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
        } finally {
            button.disabled = false;
        }
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


    const now = new Date();
    $('invoice-draft-month').value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    $('invoice-draft-form').addEventListener('submit', loadDraft);
    return {
        profileChanged(changed) {
            const notice = $('invoice-profile-required');
            if (notice) notice.hidden = !missingProfile();
            if (changed && previewReadySignature) invalidatePreview();
        }
    };
}
