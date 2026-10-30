import {configureCustomerCodes} from './customer-codes.js';
import {renderConnectionStatus, renderSyncSummary as renderPendingSummary} from './connection-status.js';
import {configureSynchronization} from './synchronization.js';
import {configureMonthlyReport} from './report.js';
import {configureAppShell, configureNavigation, prepareOffline} from './app-shell.js';
import {configureInvoicingSettings} from './invoicing-settings.js';
import {configureAccountTools, logout, requireAccount} from './auth.js';
import {formatMinutes, normalizeTime, shiftDate, today, validDate, workedMinutes} from './domain.js';
import {
    deleteEntry,
    openStorage,
    retryWithRemoteVersion,
    saveEntry,
    selectAccount,
    setVacationDay,
    snapshot,
    useRemoteTimeEntry
} from './storage.js';

configureAppShell();
const currentAccount = await requireAccount();
selectAccount(currentAccount.accountId);
configureAccountTools(currentAccount);
const $ = id => document.getElementById(id);
let data = { entries: [], codes: [], billingLabels: [], outbox: [], vacationDays: [], vacationOutbox: [] };
let viewedDate = today();
let selectedDate = null;
let autosaveTimer;
let proposedCustomerCode;
let inputRevision = 0;
let autoSelectEnabled = false;
let formOpen = false;
let renderedWeekStart;
let formPlacedDate;
let lastFormOpen;
const weekButtons = new Map();
const entryPanel = $('entry-panel');
const desktopWeekLayout = window.matchMedia('(min-width: 760px)');
let editing = null;
let deleting = null;
let formDirty = false;
let ready = false;
let busy = false;
const changes = 'BroadcastChannel' in window ? new BroadcastChannel(`timereport-changes-${currentAccount.accountId}`) : null;

const monthlyReport = configureMonthlyReport({onCache: reloadLocal});
const invoicingSettings = configureInvoicingSettings();

const synchronization = configureSynchronization({
    isReady: () => ready,
    getDate: () => viewedDate,
    onLocalChange: reloadLocal,
    onRefreshed(successful) {
        void refreshBillingLabels();
        selectDefaultEntry();
        if (successful) {
            if (!editing && !formDirty) $('deduction').value = dayEntries().length ? 0 : 30;
            preview();
        }
    },
    onStateChange() {
        $('sync').disabled = synchronization.syncing;
        $('refresh').disabled = synchronization.refreshing;
        connectionStatus();
    },
    onSyncStatus: text => {
        $('sync-status').textContent = text;
    },
    onNotice: text => message('notice', text),
    notifyChanges: () => changes?.postMessage('changed'),
    onReturn: () => monthlyReport.refreshIfOpen()
});
const {scheduleSync, syncPending, refresh, reconnectSoon} = synchronization;

const customerCodes = configureCustomerCodes({
    getData: () => data,
    getEditing: () => editing,
    isNewCustomerCode,
    onLocalChange: reloadLocal,
    onStateChange: connectionStatus,
    onConnected: () => synchronization.setOnlineStatus(true),
    notifyChanges: () => changes?.postMessage('changed')
});
const {renderCodes, refreshCodes, refreshBillingLabels} = customerCodes;


configureNavigation(view => {
    if (view === 'report') void monthlyReport.refreshIfOpen();
    if (view === 'invoicing') void invoicingSettings.refreshIfOpen();
});

function message(id, text) {
    $(id).textContent = text;
    $(id).hidden = !text;
}

function clearFormError() {
    $('form-error').classList.remove('overlap-warning');
    message('form-error', '');
}

function dayEntries() {
    return data.entries.filter(entry => entry.date === selectedDate && !entry.deleted)
        .sort((a, b) => a.start.localeCompare(b.start) || a.id.localeCompare(b.id));
}

function weekBounds(date = viewedDate) {
    const weekday = new Date(`${date}T12:00:00Z`).getUTCDay();
    const start = shiftDate(date, -((weekday + 6) % 7));
    return { start, end: shiftDate(start, 6) };
}

function dateLabel(value, options = { weekday: 'long', day: 'numeric', month: 'long' }) {
    return new Intl.DateTimeFormat('sv-SE', { timeZone: 'UTC', ...options }).format(new Date(`${value}T12:00:00Z`));
}

function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

function connectionStatus() {
    renderConnectionStatus({onlineStatus: synchronization.onlineStatus, codesBusy: customerCodes.busy});
    renderCodes();
    renderSyncSummary();
}

function renderSyncSummary() {
    renderPendingSummary(data, synchronization.onlineStatus);
}

function renderWeek() {
    const { start, end } = weekBounds();
    $('week-label').textContent = `${dateLabel(start, { day: 'numeric', month: 'short' })} – ${dateLabel(end, { day: 'numeric', month: 'short', year: 'numeric' })}`;
    $('next-week').disabled = weekBounds(shiftDate(viewedDate, 7)).start > weekBounds(today()).start;
    $('selected-date-label').textContent = selectedDate ? dateLabel(selectedDate) : '';
    const list = $('week-days');
    if (renderedWeekStart !== start) {
        list.replaceChildren();
        weekButtons.clear();
        renderedWeekStart = start;
        for (let offset = 0; offset < 7; offset++) {
            const date = shiftDate(start, offset);
            const slot = element('div', 'week-day-slot');
            slot.dataset.date = date;
            const button = element('button', 'week-day');
            button.type = 'button';
            button.dataset.date = date;
            button.append(element('span', 'week-day-name'), element('strong', 'week-day-date'),
                element('span', 'week-day-time'), element('span', 'week-day-total'), element('span', 'week-day-count'));
            button.addEventListener('click', () => date === selectedDate ? void toggleForm() : void chooseDate(date));
            slot.append(button);
            const vacation = element('label', 'vacation-toggle');
            const checkbox = element('input');
            checkbox.type = 'checkbox';
            checkbox.addEventListener('change', () => {
                void toggleVacation(date).then(() => {
                    checkbox.checked = data.vacationDays.some(day => day.date === date);
                });
            });
            vacation.append(checkbox, element('span', '', 'Semester'));
            slot.append(vacation);
            list.append(slot);
            weekButtons.set(date, button);
        }
    }
    const weekTotal = data.entries.filter(entry => entry.date >= start && entry.date <= end && !entry.deleted)
        .reduce((sum, entry) => sum + workedMinutes(entry), 0);
    $('week-total').textContent = formatMinutes(weekTotal);
    for (let offset = 0; offset < 7; offset++) {
        const date = shiftDate(start, offset);
        const entries = data.entries.filter(entry => entry.date === date && !entry.deleted)
            .sort((first, second) => first.start.localeCompare(second.start) || first.end.localeCompare(second.end));
        const total = entries.reduce((sum, entry) => sum + workedMinutes(entry), 0);
        const button = weekButtons.get(date);
        const vacationButton = button.parentElement.querySelector('.vacation-toggle');
        const vacationCheckbox = vacationButton.querySelector('input');
        const future = date > today();
        const isVacation = data.vacationDays.some(day => day.date === date);
        button.className = `week-day${date === selectedDate ? ' selected' : ''}${date === today() ? ' today' : ''}${isVacation ? ' vacation' : ''}`;
        button.disabled = future;
        button.setAttribute('aria-pressed', String(date === selectedDate));
        button.setAttribute('aria-disabled', String(future));
        button.setAttribute('aria-expanded', String(date === selectedDate && formOpen));
        button.querySelector('.week-day-name').textContent = dateLabel(date, { weekday: 'short' });
        button.querySelector('.week-day-date').textContent = dateLabel(date, { day: 'numeric', month: 'short' });
        const first = entries[0];
        const time = button.querySelector('.week-day-time');
        time.textContent = first ? `${first.start}–${first.end}` : '';
        time.hidden = !first;
        time.setAttribute('aria-label', first ? `${entries.length > 1 ? 'Första registreringen' : 'Arbetspass'} ${first.start}–${first.end}` : '');
        button.querySelector('.week-day-total').textContent = entries.length ? formatMinutes(total) : '—';
        const count = button.querySelector('.week-day-count');
        count.textContent = entries.length > 1 ? `${entries.length} tider` : '';
        count.hidden = entries.length < 2;
        vacationCheckbox.checked = isVacation;
        vacationCheckbox.disabled = future;
        vacationCheckbox.setAttribute('aria-label', isVacation ? `Ta bort semestermarkering ${date}` : `Markera ${date} som semester`);
        vacationButton.classList.toggle('marked', isVacation);
    }
    const animateIn = formPlacedDate !== selectedDate || (formOpen && lastFormOpen === false);
    const anchorDate = desktopWeekLayout.matches || !selectedDate ? end : selectedDate;
    const anchor = weekButtons.get(anchorDate)?.parentElement;
    // Desktop keeps the whole week above the form; mobile inserts it below the selected day.
    // Avoid moving the form on ordinary rerenders so autosave preserves input focus.
    if (anchor && entryPanel.previousElementSibling !== anchor) {
        const focused = entryPanel.contains(document.activeElement) ? document.activeElement : null;
        anchor.after(entryPanel);
        focused?.focus({preventScroll: true});
    }
    entryPanel.hidden = !selectedDate || !formOpen;
    if (formPlacedDate !== selectedDate) {
        formPlacedDate = selectedDate;
    }
    if (animateIn) {
        requestAnimationFrame(() => {
            entryPanel.classList.remove('slide-in');
            void entryPanel.offsetWidth;
            entryPanel.classList.add('slide-in');
            setTimeout(() => entryPanel.classList.remove('slide-in'), 300);
        });
    }
    lastFormOpen = formOpen;
}

function renderEntries() {
    renderWeek();
    const entries = dayEntries();
    renderEntryChoices(entries);
    const pending = data.outbox.length + data.vacationOutbox.length;
    renderConflicts();
    renderSyncSummary();
    $('sync-status').textContent = pending ? `${pending} lokal ${pending === 1 ? 'ändring väntar' : 'ändringar väntar'} på servern.` : 'Alla tider är synkroniserade.';
}

function renderEntryChoices(entries) {
    const choices = $('entry-choices');
    choices.replaceChildren();
    choices.hidden = entries.length < 2;
    for (const entry of entries) {
        const button = element('button', `entry-choice${editing?.id === entry.id ? ' selected' : ''}`,
            `${entry.start}–${entry.end} · ${entry.customerCode || 'Ingen kundkod'}`);
        button.type = 'button';
        button.setAttribute('aria-pressed', String(editing?.id === entry.id));
        button.addEventListener('click', () => void editEntry(entry));
        choices.append(button);
    }
}

async function toggleVacation(date) {
    if (date > today()) return;
    if (date === selectedDate && formDirty) {
        clearTimeout(autosaveTimer);
        await autosave();
        if (formDirty) {
            message('notice', 'Ändringarna i formuläret kunde inte sparas. Spara dem innan du markerar semester.');
            return;
        }
    }
    const marked = data.vacationDays.some(day => day.date === date);
    const entries = data.entries.filter(entry => entry.date === date && !entry.deleted);
    if (!marked && entries.length && !window.confirm(`Markera dagen som semester och ta bort dagens ${entries.length} tidsregistrering${entries.length === 1 ? '' : 'ar'}?`)) return;
    try {
        await setVacationDay(date, !marked, entries.map(entry => entry.id));
        await reloadLocal();
        if (!marked && editing && entries.some(entry => entry.id === editing.id)) resetForm();
        changes?.postMessage('changed');
        message('notice', !marked && entries.length
            ? `Dagen markerades som semester och ${entries.length} tidsregistrering${entries.length === 1 ? ' togs' : 'ar togs'} bort. Semestermarkeringen synkas till dina andra enheter.`
            : `Semestermarkeringen ${marked ? 'togs bort' : 'sparades'} och synkas till dina andra enheter.`);
        scheduleSync();
    } catch (error) {
        message('notice', error.message || 'Semesterdagen kunde inte sparas.');
    }
}

function renderConflicts() {
    const list = $('conflict-list');
    const conflicts = data.entries.filter(entry => entry.conflict);
    $('conflicts').hidden = !conflicts.length;
    list.replaceChildren();
    for (const entry of conflicts) {
        const item = element('article', 'conflict-item');
        item.append(element('strong', '', `${entry.date} · ${entry.start}–${entry.end} · ${entry.customerCode || 'Ingen kundkod'}`));
        item.append(element('p', '', entry.conflict.message || 'Serverns version skiljer sig från den lokala.'));
        const remote = entry.conflict.serverEntry;
        if (remote) item.append(element('p', 'conflict-code', `Servern: ${remote.start}–${remote.end} · ${remote.customerCode || 'Ingen kundkod'} · ${remote.comment || 'Ingen kommentar'}`));
        const local = element('p', 'conflict-code', `Din enhet: ${entry.start}–${entry.end} · ${entry.customerCode || 'Ingen kundkod'} · ${entry.comment || 'Ingen kommentar'}`);
        item.append(local);
        if (entry.conflict.overlaps?.length) item.append(element('p', '', `Överlappar serverns registrering: ${entry.conflict.overlaps.map(row => `${row.start}–${row.end} (${row.customerCode || 'Ingen kundkod'})`).join(', ')}. Ändra tiden innan du sparar.`));
        const actions = element('div', 'conflict-actions');
        if (remote) {
            const useServer = element('button', '', 'Använd serverns version');
            useServer.type = 'button';
            useServer.addEventListener('click', () => void resolveConflict(() => useRemoteTimeEntry(entry.id, entry.localRevision, remote)));
            actions.append(useServer);
        } else if (!entry.conflict.overlaps?.length) {
            const discard = element('button', '', entry.deleted ? 'Bekräfta borttagningen' : 'Ta bort min lokala kopia');
            discard.type = 'button';
            discard.addEventListener('click', () => void resolveConflict(() => useRemoteTimeEntry(entry.id, entry.localRevision, null)));
            actions.append(discard);
        }
        const edit = element('button', '', entry.deleted ? 'Behåll borttagningen' : entry.conflict.overlaps?.length ? 'Ändra min tid' : 'Behåll min version');
        edit.type = 'button';
        edit.addEventListener('click', () => {
            if (entry.deleted) { void resolveConflict(() => retryWithRemoteVersion(entry.id, entry.localRevision)); return; }
            if (entry.conflict.overlaps?.length) {
                void chooseDate(entry.date).then(() => editEntry(data.entries.find(row => row.id === entry.id)));
            } else void resolveConflict(() => retryWithRemoteVersion(entry.id, entry.localRevision));
        });
        actions.append(edit);
        item.append(actions);
        list.append(item);
    }
}

async function resolveConflict(action) {
    try {
        await action();
        await reloadLocal();
        changes?.postMessage('changed');
        scheduleSync();
    }
    catch (error) { message('notice', error.message || 'Konflikten kunde inte hanteras.'); }
}

async function reloadLocal() {
    data = await snapshot();
    monthlyReport.render(data);
    renderEntries();
    renderCodes();
    if (editing && !formDirty) {
        const latest = data.entries.find(entry => entry.id === editing.id && !entry.deleted);
        if (latest) {
            editing = latest;
            $('start').value = latest.start;
            $('end').value = latest.end;
            $('deduction').value = latest.deductedMinutes;
            $('comment').value = latest.comment;
            renderCodes();
            $('customer-code').value = latest.customerCode ?? '';
            preview();
        }
    }
}

function selectDefaultEntry() {
    if (!autoSelectEnabled || !selectedDate || !formOpen) return;
    const entries = dayEntries();
    if (entries.length === 1) {
        autoSelectEnabled = false;
        void editEntry(entries[0]);
    } else {
        resetForm();
    }
}

function preview() {
    const deduction = $('deduction').value;
    const entry = { start: $('start').value, end: $('end').value, deductedMinutes: deduction === '' ? NaN : Number(deduction) };
    const minutes = workedMinutes(entry);
    $('preview').value = formatMinutes(entry.deductedMinutes >= 0 ? minutes : NaN);
    document.querySelectorAll('[data-deduction]').forEach(button => {
        const selected = button.dataset.deduction === deduction;
        button.classList.toggle('selected', selected);
        button.setAttribute('aria-pressed', String(selected));
    });
}

function resetForm() {
    clearTimeout(autosaveTimer);
    editing = null;
    $('entry-form').reset();
    $('save-new-code').hidden = true;
    $('deduction').value = dayEntries().length ? 0 : 30;
    $('form-title').textContent = 'Ny tid';
    $('new-entry').hidden = true;
    $('delete-current').hidden = true;
    $('autosave-status').textContent = '';
    clearFormError();
    formDirty = false;
    renderCodes();
    preview();
}

function mayDiscard() {
    return !formDirty || window.confirm('Lämna formuläret utan att spara dina ändringar?');
}

async function editEntry(entry) {
    if (busy) return;
    if (formDirty) {
        clearTimeout(autosaveTimer);
        await autosave();
        if (formDirty && !mayDiscard()) return;
    }
    clearTimeout(autosaveTimer);
    autoSelectEnabled = false;
    editing = entry;
    $('start').value = entry.start;
    $('end').value = entry.end;
    $('deduction').value = entry.deductedMinutes;
    $('comment').value = entry.comment;
    renderCodes();
    $('customer-code').value = entry.customerCode ?? '';
    $('save-new-code').hidden = true;
    $('form-title').textContent = 'Ändra arbetstid';
    $('new-entry').hidden = false;
    $('delete-current').hidden = false;
    $('autosave-status').textContent = '';
    formDirty = false;
    clearFormError();
    preview();
    renderEntryChoices(dayEntries());
    $('form-title').scrollIntoView({ block: 'start', behavior: 'auto' });
    $('start').focus({ preventScroll: true });
}

async function chooseDate(date) {
    if (!validDate(date) || date > today() || busy) return;
    if (date === selectedDate) return;
    if (formDirty) {
        clearTimeout(autosaveTimer);
        await autosave();
        if (formDirty && !mayDiscard()) return;
    }
    clearTimeout(autosaveTimer);
    selectedDate = date;
    viewedDate = date;
    formOpen = true;
    autoSelectEnabled = true;
    await reloadLocal();
    resetForm();
    selectDefaultEntry();
    $('start').focus();
    void refresh();
}

async function changeWeek(date) {
    if (!validDate(date) || weekBounds(date).start > weekBounds(today()).start || busy) return;
    if (formDirty) {
        clearTimeout(autosaveTimer);
        await autosave();
        if (formDirty && !mayDiscard()) return;
    }
    clearTimeout(autosaveTimer);
    viewedDate = date;
    selectedDate = null;
    formOpen = false;
    autoSelectEnabled = false;
    resetForm();
    await reloadLocal();
    void refresh();
}

async function toggleForm() {
    if (busy) return;
    if (formDirty) {
        clearTimeout(autosaveTimer);
        await autosave();
        if (formDirty) {
            if (!mayDiscard()) return;
            resetForm();
        }
    }
    formOpen = !formOpen;
    renderWeek();
    if (formOpen) $('start').focus();
}

function isNewCustomerCode() {
    const code = $('customer-code').value.trim();
    return Boolean(code) && !data.codes.some(existing => existing.code === code);
}

function scheduleAutosave() {
    clearTimeout(autosaveTimer);
    scheduleSync();
    $('save-new-code').hidden = true;
    proposedCustomerCode = null;
    $('autosave-status').textContent = isNewCustomerCode()
        ? 'Kontrollerar kundkod …' : 'Sparar ändringar …';
    autosaveTimer = setTimeout(() => void autosave(), 700);
}

async function autosave(confirmedCode = null) {
    if (busy || !ready || !selectedDate) return;
    const code = $('customer-code').value.trim();
    if (isNewCustomerCode() && confirmedCode !== code) {
        proposedCustomerCode = code;
        $('save-new-code').textContent = `Spara kundkod ”${code}”`;
        $('save-new-code').hidden = false;
        $('autosave-status').textContent = 'Ny kundkod. Kontrollera stavningen och tryck på Spara.';
        return;
    }
    if (!$('start').value || !$('end').value || !$('deduction').value) {
        $('autosave-status').textContent = 'Fyll i tider så sparas registreringen.';
        return;
    }
    const revision = inputRevision;
    const current = editing;
    const input = { id: current?.id ?? crypto.randomUUID(), date: selectedDate, start: $('start').value, end: $('end').value,
        deductedMinutes: Number($('deduction').value),
        customerCode: $('customer-code').value.trim() || null,
        comment: $('comment').value
    };
    busy = true;
    $('save-new-code').disabled = true;
    try {
        const saved = await saveEntry(input, current?.localRevision ?? null);
        editing = saved;
        $('new-entry').hidden = false;
        $('delete-current').hidden = false;
        formDirty = inputRevision !== revision;
        clearFormError();
        $('autosave-status').textContent = 'Sparad lokalt · väntar på synkning';
        await reloadLocal();
        changes?.postMessage('changed');
        scheduleSync();
        if (navigator.storage?.persist) navigator.storage.persist().catch(() => {});
    } catch (error) {
        const text = error.message || 'Kunde inte spara. Rätta uppgifterna och försök igen.';
        const overlap = text.toLocaleLowerCase('sv').includes('överlapp');
        $('form-error').classList.toggle('overlap-warning', overlap);
        message('form-error', overlap ? `${text} Ändra start- eller sluttiden så att registreringarna inte överlappar.` : text);
        $('autosave-status').textContent = '';
    } finally {
        busy = false;
        $('save-new-code').disabled = false;
        if (formDirty && inputRevision !== revision) scheduleAutosave();
    }
}

for (const id of ['start', 'end']) {
    $(id).addEventListener('blur', () => {
        const normalized = normalizeTime($(id).value);
        if (normalized !== $(id).value) {
            $(id).value = normalized;
            $(id).dispatchEvent(new Event('input', {bubbles: true}));
        }
    });
}

$('customer-codes').addEventListener('change', () => {
    $('customer-code').value = $('customer-codes').value;
    $('customer-codes').value = '';
    $('customer-code').dispatchEvent(new Event('input', {bubbles: true}));
});

$('save-new-code').addEventListener('click', () => {
    clearTimeout(autosaveTimer);
    void autosave($('customer-code').value.trim());
});

$('entry-form').addEventListener('input', () => { clearFormError(); autoSelectEnabled = false; formDirty = true; inputRevision++; preview(); scheduleAutosave(); });
$('entry-form').addEventListener('change', event => {
    // Blur fires change before a button click. Keep confirmation visible when input
    // has already handled this same customer-code value.
    if (event.target.id === 'customer-code' && !$('save-new-code').hidden
        && proposedCustomerCode === $('customer-code').value.trim()) return;
    clearFormError();
    autoSelectEnabled = false;
    formDirty = true;
    inputRevision++;
    scheduleAutosave();
});
document.querySelectorAll('[data-deduction]').forEach(button => button.addEventListener('click', () => {
    clearFormError();
    $('deduction').value = button.dataset.deduction;
    autoSelectEnabled = false;
    formDirty = true;
    inputRevision++;
    preview();
    scheduleAutosave();
}));
$('entry-form').addEventListener('submit', async event => {
    event.preventDefault();
    scheduleAutosave();
});
$('new-entry').addEventListener('click', async () => {
    autoSelectEnabled = false;
    if (formDirty) {
        clearTimeout(autosaveTimer);
        await autosave();
        if (formDirty && !mayDiscard()) return;
    }
    resetForm();
});
$('delete-current').addEventListener('click', () => {
    if (!editing || busy) return;
    clearTimeout(autosaveTimer);
    deleting = editing;
    $('delete-dialog').showModal();
});
$('previous-week').addEventListener('click', () => void changeWeek(shiftDate(viewedDate, -7)));
$('next-week').addEventListener('click', () => {
    const next = shiftDate(viewedDate, 7);
    void changeWeek(next > today() ? today() : next);
});
$('today').addEventListener('click', () => void changeWeek(today()));
$('refresh').addEventListener('click', () => void refresh(true));
$('sync').addEventListener('click', async () => {
    if (formDirty && !busy) {
        clearTimeout(autosaveTimer);
        await autosave();
    }
    await syncPending(true);
});
$('cancel-delete').addEventListener('click', () => {
    $('delete-dialog').close();
});
$('delete-dialog').addEventListener('close', () => {
    if (formDirty && !busy) scheduleAutosave();
});
$('confirm-delete').addEventListener('click', async () => {
    if (!deleting || busy) return;
    busy = true;
    $('confirm-delete').disabled = true;
    try {
        await deleteEntry(deleting.id, deleting.localRevision);
        await reloadLocal();
        if (editing?.id === deleting.id || !formDirty) resetForm();
        changes?.postMessage('changed');
        message('notice', 'Registreringen är borttagen på enheten.');
        scheduleSync();
    } catch (error) {
        message('notice', error.message || 'Det gick inte att ta bort registreringen.');
    } finally {
        busy = false;
        $('confirm-delete').disabled = false;
        $('delete-dialog').close();
        deleting = null;
        renderCodes();
    }
});
$('export').addEventListener('click', async () => {
    try {
        const contents = { schemaVersion: 1, exportedAt: new Date().toISOString(), ...await snapshot() };
        const url = URL.createObjectURL(new Blob([JSON.stringify(contents, null, 2)], { type: 'application/json' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = `tidrapport-lokal-kopia-${today()}.json`;
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 10000);
        message('notice', 'En lokal kopia har hämtats som JSON. Den innehåller tider, kundkoder och väntande ändringar.');
    } catch {
        message('notice', 'Kopian kunde inte skapas. Dina uppgifter har inte tagits bort.');
    }
});

desktopWeekLayout.addEventListener('change', () => {
    if (ready) renderWeek();
});
window.addEventListener('offline', connectionStatus);
window.addEventListener('beforeunload', event => {
    if (formDirty) { event.preventDefault(); event.returnValue = ''; }
});
changes?.addEventListener('message', () => { if (ready) reloadLocal().catch(() => message('fatal', 'Lagringen kunde inte läsas. Ladda om sidan.')); });

async function start() {
    connectionStatus();
    try {
        await openStorage();
        ready = true;
        reconnectSoon();
        await reloadLocal();
        selectDefaultEntry();
        $('entry-fields').disabled = false;
        void syncPending().then(() => refresh());
        void prepareOffline();
    } catch {
        ready = false;
        $('entry-fields').disabled = true;
        message('fatal', 'Lokal lagring är inte tillgänglig. Ingen tid kan sparas. Kontrollera webbläsarens lagringsinställningar och ladda om sidan.');
        $('offline-readiness').textContent = 'Lokal lagring är inte redo.';
    }
}

$('logout').addEventListener('click', async () => {
    if (busy || synchronization.syncing || synchronization.refreshing) {
        message('notice', 'Vänta tills pågående sparande är klart.');
        return;
    }
    if (formDirty) {
        await autosave();
        if (formDirty) return;
    }
    try {
        await logout();
    } catch {
        message('notice', 'Utloggning kräver nät så att sessionen kan spärras. Dina lokala ändringar finns kvar.');
    }
});
void start();
