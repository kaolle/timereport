import {authenticatedFetch} from './auth.js';
import {today} from './domain.js';

const $ = id => document.getElementById(id);
const money = (amount, currency) => new Intl.NumberFormat('sv-SE', {
    style: 'currency',
    currency
}).format(Number(amount));
const element = (tag, text, className) => {
    const item = document.createElement(tag);
    if (text !== undefined) item.textContent = text;
    if (className) item.className = className;
    return item;
};

export function configureInvoiceDelivery({request}) {
    const dialog = $('invoice-delivery-dialog');
    let active = null;
    let working = false;
    let history = [];
    let revision = 0;

    function open({number, recipient = '', pdf = null, lastDelivery = null}) {
        active = {number, pdf, requestId: crypto.randomUUID(), lastState: lastDelivery?.state, attemptRecipients: null};
        const opened = active;
        $('invoice-delivery-title').textContent = `Faktura ${number} är utfärdad`;
        $('invoice-delivery-download').checked = true;
        $('invoice-delivery-email').checked = false;
        $('invoice-delivery-email').disabled = true;
        $('invoice-delivery-recipient').value = recipient;
        $('invoice-delivery-recipient').disabled = true;
        $('invoice-delivery-recipient').required = false;
        for (const id of ['invoice-delivery-cc', 'invoice-delivery-bcc']) {
            $(id).value = '';
            $(id).disabled = true;
        }
        $('invoice-delivery-error').textContent = '';
        $('invoice-download-status').textContent = '';
        $('invoice-email-status').textContent = 'Hämtar standardmottagare …';
        dialog.showModal();
        void request(`/api/invoicing/invoices/${number}/delivery-defaults`).then(defaults => {
            if (active !== opened || !dialog.open) return;
            $('invoice-delivery-cc').value = (defaults.cc || []).join(', ');
            $('invoice-delivery-bcc').value = (defaults.bcc || []).join(', ');
            $('invoice-delivery-email').disabled = false;
            $('invoice-email-status').textContent = lastDelivery ? deliveryMessage(lastDelivery) : '';
        }).catch(() => {
            if (active === opened && dialog.open) $('invoice-email-status').textContent = 'Standardmottagarna kunde inte hämtas. Stäng dialogen och försök igen för att skicka mejl. Du kan fortfarande ladda ner PDF:en.';
        });
    }

    function deliveryMessage(delivery) {
        if (delivery.state === 'SENT') return `Mejlet har lämnats till e-postservern för ${delivery.recipient}.`;
        if (delivery.state === 'FAILED') return 'Utskicket kunde inte bekräftas. Kontrollera om mejlet kom fram innan du försöker igen.';
        return 'Utskicket pågår eller saknar bekräftelse. Samma försök skickas inte automatiskt igen.';
    }

    async function download() {
        let pdf = active.pdf;
        if (!pdf) {
            const response = await authenticatedFetch(`/api/invoicing/invoices/${active.number}/pdf`, {
                cache: 'no-store',
                timeoutMs: 30000
            });
            if (!response.ok) throw new Error('PDF-filen kunde inte hämtas. Försök igen.');
            pdf = await response.blob();
        }
        const url = URL.createObjectURL(pdf);
        const link = element('a');
        link.href = url;
        link.download = `Faktura-${active.number}.pdf`;
        document.body.append(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
    }

    $('invoice-delivery-email').addEventListener('change', event => {
        $('invoice-delivery-recipient').disabled = !event.target.checked;
        $('invoice-delivery-recipient').required = event.target.checked;
        for (const id of ['invoice-delivery-cc', 'invoice-delivery-bcc']) $(id).disabled = !event.target.checked;
        if (event.target.checked) $('invoice-delivery-recipient').focus();
    });
    $('invoice-delivery-close').addEventListener('click', () => dialog.close());
    dialog.addEventListener('cancel', event => {
        if (working) event.preventDefault();
    });
    $('invoice-delivery-form').addEventListener('submit', async event => {
        event.preventDefault();
        if (working || !active) return;
        const getPdf = $('invoice-delivery-download').checked;
        const sendEmail = $('invoice-delivery-email').checked;
        $('invoice-delivery-error').textContent = '';
        if (!getPdf && !sendEmail) {
            $('invoice-delivery-error').textContent = 'Välj minst ett alternativ eller stäng dialogen.';
            return;
        }
        if (!navigator.onLine && (sendEmail || !active.pdf)) {
            $('invoice-delivery-error').textContent = 'Anslut till nätet för att hämta eller skicka fakturan.';
            return;
        }
        working = true;
        $('invoice-delivery-submit').disabled = true;
        $('invoice-delivery-close').disabled = true;
        $('invoice-delivery-options').disabled = true;
        try {
            if (getPdf) {
                try {
                    await download();
                    $('invoice-download-status').textContent = 'PDF-filen har skickats till webbläsarens nedladdningar.';
                    $('invoice-delivery-download').checked = false;
                } catch (error) {
                    $('invoice-download-status').textContent = error.message;
                }
            }
            if (sendEmail) {
                const recipient = $('invoice-delivery-recipient').value.trim();
                const addresses = id => $(id).value.split(',').map(value => value.trim()).filter(Boolean);
                const cc = addresses('invoice-delivery-cc');
                const bcc = addresses('invoice-delivery-bcc');
                const recipients = JSON.stringify({recipient, cc, bcc});
                if (active.lastState) {
                    const message = active.lastState === 'SENT' ? 'Fakturan har redan lämnats till e-postservern. Vill du skicka ett nytt mejl?'
                        : 'Ett tidigare utskick saknar bekräftelse och kan ha kommit fram. Vill du göra ett nytt utskicksförsök?';
                    if (!window.confirm(message)) return;
                    active.requestId = crypto.randomUUID();
                    active.lastState = null;
                } else if (active.attemptRecipients && active.attemptRecipients !== recipients) {
                    if (!window.confirm('Mottagarna har ändrats och ett tidigare utskick kan ha kommit fram. Vill du göra ett nytt utskick?')) return;
                    active.requestId = crypto.randomUUID();
                }
                active.attemptRecipients = recipients;
                $('invoice-email-status').textContent = 'Skickar fakturan …';
                try {
                    const result = await request(`/api/invoicing/invoices/${active.number}/email`, 'POST', {
                        recipient,
                        cc,
                        bcc,
                        requestId: active.requestId
                    }, 60000);
                    active.lastState = result.state;
                    $('invoice-email-status').textContent = deliveryMessage(result);
                    if (result.state === 'SENT') {
                        $('invoice-delivery-email').checked = false;
                        $('invoice-delivery-recipient').disabled = true;
                        $('invoice-delivery-recipient').required = false;
                        $('invoice-delivery-cc').disabled = true;
                        $('invoice-delivery-bcc').disabled = true;
                    }
                    await refresh();
                } catch (error) {
                    // Retain the request ID: retrying after a lost response must not send twice.
                    $('invoice-email-status').textContent = `${error.message} Samma utskicksförsök används om du försöker igen.`;
                }
            }
        } finally {
            working = false;
            $('invoice-delivery-submit').disabled = false;
            $('invoice-delivery-close').disabled = false;
            $('invoice-delivery-options').disabled = false;
        }
    });

    async function changeStatus(invoice, field, checkbox) {
        revision++;
        const original = invoice.status[field];
        checkbox.disabled = true;
        const values = {
            sent: invoice.status.sent,
            paid: invoice.status.paid,
            expectedVersion: invoice.status.version,
            [field]: checkbox.checked
        };
        try {
            invoice.status = await request(`/api/invoicing/invoices/${invoice.invoiceNumber}/status`, 'PUT', values);
            render();
            $('invoice-history-status').textContent = `Status för faktura ${invoice.invoiceNumber} är sparad.`;
        } catch (error) {
            checkbox.checked = original;
            await refresh();
            $('invoice-history-status').textContent = error.message;
        } finally {
            checkbox.disabled = false;
        }
    }

    function render() {
        const list = $('invoice-history-list');
        list.replaceChildren();
        if (!history.length) {
            list.append(element('p', 'Inga fakturor är utfärdade ännu.', 'field-help'));
            return;
        }
        const groups = [
            ['Förfallna', invoice => !invoice.status.paid && invoice.dueDate < today()],
            ['Obetalda', invoice => !invoice.status.paid && invoice.dueDate >= today()],
            ['Betalda', invoice => invoice.status.paid]
        ];
        for (const [name, matches] of groups) {
            const invoices = history.filter(matches);
            if (!invoices.length) continue;
            const section = element('section', undefined, 'invoice-history-group');
            section.append(element('h4', name));
            const totals = new Map();
            for (const invoice of invoices) {
                const sum = totals.get(invoice.currencyCode) || {total: 0, vat: 0};
                sum.total += Math.round(Number(invoice.totalAmount) * 100);
                sum.vat += Math.round(Number(invoice.vatAmount) * 100);
                totals.set(invoice.currencyCode, sum);
            }
            for (const [currency, sum] of totals) section.append(element('p', `${money(sum.total / 100, currency)} · varav moms ${money(sum.vat / 100, currency)}`, 'invoice-history-total'));
            const table = element('table', undefined, 'invoice-history-table');
            const head = element('thead');
            const headings = element('tr');
            for (const title of ['Nr', 'Kund', 'Summa', 'Förfaller', 'Betald', 'Skickad', 'Faktura']) {
                const cell = element('th', title);
                cell.scope = 'col';
                headings.append(cell);
            }
            head.append(headings);
            table.append(head);
            const body = element('tbody');
            for (const invoice of invoices) {
                const row = element('tr');
                row.dataset.invoiceNumber = invoice.invoiceNumber;
                const cell = (label, value) => {
                    const td = element('td', value);
                    td.dataset.label = label;
                    row.append(td);
                    return td;
                };
                cell('Nr', String(invoice.invoiceNumber));
                const customer = cell('Kund', invoice.customerName);
                customer.append(element('small', invoice.month));
                cell('Summa', money(invoice.totalAmount, invoice.currencyCode));
                cell('Förfaller', invoice.dueDate);
                for (const [field, label] of [['paid', 'Betald'], ['sent', 'Skickad']]) {
                    const input = element('input');
                    input.type = 'checkbox';
                    input.checked = invoice.status[field];
                    input.setAttribute('aria-label', `${label} faktura ${invoice.invoiceNumber}`);
                    input.title = field === 'sent' ? 'Markera även fakturor som skickats på annat sätt.' : 'Markera när betalningen har kommit in.';
                    input.addEventListener('change', () => void changeStatus(invoice, field, input));
                    cell(label).append(input);
                }
                const deliver = element('button', 'Leverera', 'secondary-button');
                deliver.type = 'button';
                deliver.setAttribute('aria-label', `Leverera faktura ${invoice.invoiceNumber}`);
                deliver.addEventListener('click', () => open({
                    number: invoice.invoiceNumber,
                    recipient: invoice.invoiceEmail,
                    lastDelivery: invoice.lastDelivery
                }));
                cell('Faktura').append(deliver);
                if (invoice.lastDelivery) {
                    const message = element('small', deliveryMessage(invoice.lastDelivery), 'invoice-last-delivery');
                    customer.append(message);
                }
                body.append(row);
            }
            table.append(body);
            section.append(table);
            list.append(section);
        }
    }

    async function refresh() {
        if (!navigator.onLine) {
            $('invoice-history-status').textContent = 'Anslut till nätet för att hämta utfärdade fakturor.';
            return;
        }
        const current = ++revision;
        $('invoice-history-status').textContent = 'Hämtar fakturor …';
        try {
            const invoices = await request('/api/invoicing/invoices');
            if (current !== revision) return;
            history = invoices;
            render();
            $('invoice-history-status').textContent = 'Fakturorna är hämtade. Skickad och Betald kan även markeras manuellt.';
        } catch (error) {
            if (current === revision) $('invoice-history-status').textContent = error.message;
        }
    }

    $('invoice-history-refresh').addEventListener('click', () => void refresh());
    return {open, refresh};
}
