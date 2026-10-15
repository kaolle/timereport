import {formatMinutes, today} from './domain.js';
import {buildMonthlyReport, monthBounds} from './monthly-report.js';
import {fetchRange} from './api.js';
import {cacheRange, snapshot} from './storage.js';

const dateLabel = date => new Intl.DateTimeFormat('sv-SE', {day: 'numeric', month: 'short', timeZone: 'UTC'})
    .format(new Date(`${date}T12:00:00Z`));

function element(tag, className, text) {
    const node = document.createElement(tag);
    node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

export function configureMonthlyReport({onCache}) {
    const panel = document.getElementById('monthly-report');
    const month = document.getElementById('report-month');
    const status = document.getElementById('report-status');
    const refresh = document.getElementById('report-refresh');
    let local;
    let loading = false;
    let failure = '';
    let requestRevision = 0;
    month.value = today().slice(0, 7);

    function render(data) {
        local = data;
        if (panel.hidden) return;
        let report;
        try {
            report = buildMonthlyReport(month.value, data.entries);
        } catch (error) {
            status.textContent = error.message;
            document.getElementById('report-total').textContent = '—';
            document.getElementById('report-weeks').replaceChildren();
            return;
        }
        document.getElementById('report-total').textContent = formatMinutes(report.workedMinutes);
        const list = document.getElementById('report-weeks');
        const cards = report.weeks.map(week => {
            const card = element('section', 'report-week');
            card.dataset.weekStart = week.weekStart;
            card.append(element('h3', '', `Vecka ${week.weekNumber}`),
                element('p', 'report-week-range', `${dateLabel(week.from)} – ${dateLabel(week.to)}`));
            const table = element('table', 'report-table');
            const head = element('thead', '');
            const header = element('tr', '');
            for (const text of ['Kundkod', 'Arbetad tid']) {
                const cell = element('th', '', text);
                cell.scope = 'col';
                header.append(cell);
            }
            head.append(header);
            table.append(head);
            const body = element('tbody', '');
            for (const customer of week.customers) {
                const row = element('tr', '');
                row.append(element('td', '', customer.customerCode ?? 'Utan kundkod'),
                    element('td', '', formatMinutes(customer.workedMinutes)));
                body.append(row);
            }
            if (!week.customers.length) {
                const row = element('tr', '');
                const cell = element('td', 'report-empty', 'Ingen registrerad tid');
                cell.colSpan = 2;
                row.append(cell);
                body.append(row);
            }
            table.append(body);
            const foot = element('tfoot', '');
            const total = element('tr', '');
            const label = element('th', '', 'Totalt för veckan');
            label.scope = 'row';
            total.append(label, element('td', 'report-week-total', formatMinutes(week.workedMinutes)));
            foot.append(total);
            table.append(foot);
            card.append(table);
            return card;
        });
        list.replaceChildren(...cards);
        const cached = data.reportMonths.find(row => row.month === month.value);
        const pending = data.entries.some(entry => entry.date >= report.from && entry.date <= report.to && entry.syncState !== 'synced');
        const notes = [];
        if (loading) notes.push('Hämtar månadens tider. Visar lokala uppgifter under tiden.');
        else if (failure) notes.push(failure);
        else if (!navigator.onLine) notes.push('Offline. Visar sparade uppgifter på den här enheten.');
        else if (cached) notes.push('Månadens tider har hämtats. Lokala ändringar ingår.');
        if (!cached) notes.push('Hela månaden har inte hämtats; summeringen kan vara ofullständig.');
        if (pending) notes.push('Ej synkroniserade ändringar ingår i summeringen.');
        status.textContent = notes.join(' ');
        refresh.disabled = loading || !navigator.onLine;
    }

    async function load() {
        const revision = ++requestRevision;
        failure = '';
        loading = navigator.onLine;
        try {
            render(await snapshot());
            if (!month.value || !navigator.onLine) return;
            const requestedMonth = month.value;
            const {from, to} = monthBounds(requestedMonth);
            const entries = await fetchRange(from, to);
            if (revision !== requestRevision) return;
            await cacheRange(from, to, entries, requestedMonth);
            await onCache();
        } catch (error) {
            if (revision === requestRevision) failure = `Månaden kunde inte hämtas. ${error.message || 'Försök igen när nätet fungerar.'}`;
        } finally {
            if (revision === requestRevision) {
                loading = false;
                // Keep the last readable local snapshot if browser storage itself fails.
                try {
                    render(await snapshot());
                } catch {
                    status.textContent = failure || 'Lokala tider kunde inte läsas. Försök igen.';
                    refresh.disabled = false;
                }
            }
        }
    }

    month.addEventListener('change', () => void load());
    refresh.addEventListener('click', () => void load());
    window.addEventListener('offline', () => {
        if (local) render(local);
    });
    window.addEventListener('online', () => {
        if (!panel.hidden) void load();
    });
    return { render, refreshIfOpen: () => !panel.hidden ? load() : Promise.resolve() };
}
