import { today, shiftDate, validDate, workedMinutes, formatMinutes, intervalsOverlap } from './domain.js';
import { openStorage, snapshot, saveEntry, deleteEntry, setVacationDay, cacheVacationRange, markVacationSynced, cacheCodes, cacheRange, markSyncing, markSyncFailed,
    markConflict, markTimeEntrySynced, markTimeEntryDeleted, useRemoteTimeEntry, retryWithRemoteVersion, retryRejected } from './storage.js';
import { fetchCodes, fetchDay, fetchRange, fetchVacationDays, setVacationDayRemote, fetchTimeEntry, uploadTimeEntry, removeTimeEntry, createCode, ApiError } from './api.js';

const $ = id => document.getElementById(id);
let data = { entries: [], codes: [], outbox: [], vacationDays: [], vacationOutbox: [] };
let selectedDate = today();
let autosaveTimer;
let inputRevision = 0;
let editingExisting = false;
let autoSelectEnabled = true;
let formOpen = true;
let renderedWeekStart;
let formPlacedDate;
let lastFormOpen;
const weekButtons = new Map();
const entryPanel = $('entry-panel');
let editing = null;
let deleting = null;
let formDirty = false;
let ready = false;
let busy = false;
let refreshing = false;
let syncing = false;
let onlineStatus = null;
let installPrompt;
const changes = 'BroadcastChannel' in window ? new BroadcastChannel('timereport-changes') : null;

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

function weekBounds(date = selectedDate) {
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
    const offline = !navigator.onLine || onlineStatus === false;
    $('connection').classList.toggle('offline', offline);
    $('connection').textContent = !navigator.onLine ? 'Utan nät · sparar lokalt'
        : onlineStatus === false ? 'Servern kan inte nås' : onlineStatus ? 'Ansluten' : 'Nät tillgängligt';
    $('add-code').disabled = !navigator.onLine;
}

function renderCodes() {
    const selected = $('customer-code').value || editing?.customerCode;
    const select = $('customer-code');
    select.replaceChildren(new Option('Välj kundkod', ''));
    const codes = data.codes.filter(code => code.active || code.code === editing?.customerCode)
        .sort((a, b) => a.code.localeCompare(b.code, 'sv'));
    for (const code of codes) select.add(new Option(`${code.code}${code.active ? '' : ' (inaktiv)'}`, code.code));
    if (selected && codes.some(code => code.code === selected)) select.value = selected;
    else if (codes.length === 1) select.value = codes[0].code;
    $('code-help').textContent = codes.length ? ''
        : 'Inga kundkoder hämtade. Lägg till en kod under ”Kundkoder och installation” när du har nät.';
    $('codes-status').textContent = `${data.codes.length} kundkoder sparade på enheten. Nya koder läggs till på servern när du har anslutning.`;
}

function renderWeek() {
    const { start, end } = weekBounds();
    $('week-label').textContent = `${dateLabel(start, { day: 'numeric', month: 'short' })} – ${dateLabel(end, { day: 'numeric', month: 'short', year: 'numeric' })}`;
    $('next-week').disabled = weekBounds(shiftDate(selectedDate, 7)).start > weekBounds(today()).start;
    $('selected-date-label').textContent = dateLabel(selectedDate);
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
                element('span', 'week-day-total'), element('span', 'week-day-count'));
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
        const entries = data.entries.filter(entry => entry.date === date && !entry.deleted);
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
        button.querySelector('.week-day-total').textContent = total ? formatMinutes(total) : '—';
        button.querySelector('.week-day-count').textContent = entries.length ? `${entries.length} tider` : '';
        vacationCheckbox.checked = isVacation;
        vacationCheckbox.disabled = future;
        vacationCheckbox.setAttribute('aria-label', isVacation ? `Ta bort semestermarkering ${date}` : `Markera ${date} som semester`);
        vacationButton.classList.toggle('marked', isVacation);
    }
    const animateIn = formPlacedDate !== selectedDate || (formOpen && lastFormOpen === false);
    weekButtons.get(selectedDate)?.parentElement.after(entryPanel);
    entryPanel.hidden = !formOpen;
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
    $('sync-status').textContent = pending ? `${pending} lokal ${pending === 1 ? 'ändring väntar' : 'ändringar väntar'} på servern.` : 'Alla tider är synkroniserade.';
}

function renderEntryChoices(entries) {
    const choices = $('entry-choices');
    choices.replaceChildren();
    choices.hidden = entries.length < 2;
    for (const entry of entries) {
        const button = element('button', `entry-choice${editing?.id === entry.id ? ' selected' : ''}`,
            `${entry.start}–${entry.end} · ${entry.customerCode}`);
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
        void syncPending();
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
        item.append(element('strong', '', `${entry.date} · ${entry.start}–${entry.end} · ${entry.customerCode}`));
        item.append(element('p', '', entry.conflict.message || 'Serverns version skiljer sig från den lokala.'));
        const remote = entry.conflict.serverEntry;
        if (remote) item.append(element('p', 'conflict-code', `Servern: ${remote.start}–${remote.end} · ${remote.customerCode} · ${remote.comment || 'Ingen kommentar'}`));
        const local = element('p', 'conflict-code', `Din enhet: ${entry.start}–${entry.end} · ${entry.customerCode} · ${entry.comment || 'Ingen kommentar'}`);
        item.append(local);
        if (entry.conflict.overlaps?.length) item.append(element('p', '', `Överlappar serverns registrering: ${entry.conflict.overlaps.map(row => `${row.start}–${row.end} (${row.customerCode})`).join(', ')}. Ändra tiden innan du sparar.`));
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
    try { await action(); await reloadLocal(); changes?.postMessage('changed'); void syncPending(); }
    catch (error) { message('notice', error.message || 'Konflikten kunde inte hanteras.'); }
}

async function syncPending(manual = false) {
    if (!ready || syncing || !navigator.onLine) return;
    syncing = true;
    $('sync').disabled = true;
    try {
        if (manual) await retryRejected();
        let processed = 0;
        while (processed++ < 100 && navigator.onLine) {
            const { outbox, vacationOutbox } = await snapshot();
            const item = outbox.find(row => row.state === 'pending' || row.state === 'sending');
            if (!item) {
                const vacation = vacationOutbox[0];
                if (!vacation) break;
                try {
                    await setVacationDayRemote(vacation.date, vacation.vacation);
                    await markVacationSynced(vacation.date, vacation.localRevision);
                    await reloadLocal();
                    changes?.postMessage('changed');
                    continue;
                } catch (error) {
                    onlineStatus = error instanceof ApiError && error.status >= 500 ? false : true;
                    break;
                }
            }
            const claim = await markSyncing(item);
            if (!claim) { await reloadLocal(); continue; }
            if (claim.discard) { await reloadLocal(); continue; }
            try {
                if (claim.operation === 'put' || claim.operation === 'delete-after-create') {
                    const sent = claim.operation === 'put' ? claim.entry : (await snapshot()).entries.find(row => row.id === claim.id);
                    const result = await uploadTimeEntry(sent, claim.expectedVersion);
                    await markTimeEntrySynced(claim.id, claim.localRevision, result);
                } else {
                    await removeTimeEntry(claim.id, claim.expectedVersion);
                    await markTimeEntryDeleted(claim.id, claim.localRevision);
                }
            } catch (error) {
                if (error instanceof ApiError && error.status === 409) {
                    let remote = null;
                    try { remote = await fetchTimeEntry(claim.id); }
                    catch (readError) {
                        if (readError instanceof ApiError && readError.status === 404) remote = null;
                        else {
                            await markSyncFailed(claim.id, claim.localRevision, 'Serverns version kunde inte hämtas. Försök synka igen.', false);
                            onlineStatus = false;
                            break;
                        }
                    }
                    const current = (await snapshot()).entries.find(row => row.id === claim.id);
                    let overlaps = [];
                    if (claim.operation === 'put' && !remote) {
                        try { overlaps = (await fetchDay(current.date)).filter(row => row.id !== current.id && intervalsOverlap(current, row)); } catch {}
                    }
                    if (!remote && claim.operation === 'delete') {
                        await markTimeEntryDeleted(claim.id, claim.localRevision);
                    } else {
                        await markConflict(claim.id, claim.localRevision, { message: overlaps.length ? 'Tiden överlappar en annan registrering på servern.' : 'Registreringen ändrades på servern. Välj vilken version som ska behållas.', serverEntry: remote, overlaps });
                    }
                } else {
                    const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500;
                    await markSyncFailed(claim.id, claim.localRevision, error.message || 'Överföringen misslyckades.', rejected);
                    break;
                }
                onlineStatus = true;
            }
            await reloadLocal();
            changes?.postMessage('changed');
        }
    } catch (error) {
        onlineStatus = false;
        $('sync-status').textContent = error.message || 'Synkningen avbröts. Dina ändringar finns kvar lokalt.';
    } finally {
        await reloadLocal().catch(() => {});
        syncing = false;
        $('sync').disabled = false;
        connectionStatus();
        if (onlineStatus === true) {
            const rejected = data.entries.find(entry => entry.syncState === 'rejected');
            const pending = data.outbox.length + data.vacationOutbox.length;
            $('sync-status').textContent = rejected ? rejected.syncError : pending
                ? `${pending} ändringar väntar. Tryck för att försöka igen.` : 'Alla tider är synkroniserade.';
        }
    }
}

async function reloadLocal() {
    data = await snapshot();
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
            $('customer-code').value = latest.customerCode;
            preview();
        }
    }
}

function selectDefaultEntry() {
    if (!autoSelectEnabled) return;
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
    editingExisting = false;
    $('entry-form').reset();
    $('deduction').value = dayEntries().length ? 0 : 30;
    $('form-title').textContent = 'Ny tid';
    $('cancel-edit').hidden = true;
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
    editingExisting = true;
    $('start').value = entry.start;
    $('end').value = entry.end;
    $('deduction').value = entry.deductedMinutes;
    $('comment').value = entry.comment;
    renderCodes();
    $('customer-code').value = entry.customerCode;
    $('form-title').textContent = 'Ändra arbetstid';
    $('cancel-edit').hidden = false;
    $('new-entry').hidden = false;
    $('delete-current').hidden = !editingExisting;
    $('autosave-status').textContent = '';
    formDirty = false;
    clearFormError();
    preview();
    renderEntryChoices(dayEntries());
    $('form-title').scrollIntoView({ block: 'start', behavior: 'auto' });
    $('start').focus({ preventScroll: true });
}

async function refresh(showFeedback = false) {
    if (!ready || refreshing) return;
    if (!navigator.onLine) {
        connectionStatus();
        if (showFeedback) message('notice', 'Du är offline. Tidigare hämtade uppgifter och dina lokala ändringar finns kvar.');
        return;
    }
    refreshing = true;
    $('refresh').disabled = true;
    const date = selectedDate;
    try {
        await syncPending();
        const { start, end } = weekBounds(date);
        const [codes, entries, vacations] = await Promise.allSettled([fetchCodes(), fetchRange(start, end), fetchVacationDays(start, end)]);
        if (codes.status === 'fulfilled') await cacheCodes(codes.value);
        if (entries.status === 'fulfilled') await cacheRange(start, end, entries.value);
        if (vacations.status === 'fulfilled') await cacheVacationRange(start, end, vacations.value);
        await reloadLocal();
        selectDefaultEntry();
        changes?.postMessage('changed');
        const failed = [codes, entries, vacations].find(result => result.status === 'rejected');
        if (failed) throw failed.reason;
        onlineStatus = true;
        if (!editing && !formDirty) $('deduction').value = dayEntries().length ? 0 : 30;
        preview();
        if (showFeedback) message('notice', 'Veckans tider och kundkoder är uppdaterade.');
    } catch {
        onlineStatus = false;
        if (showFeedback) message('notice', 'Servern kunde inte nås. Du kan fortsätta med dina lokalt sparade uppgifter.');
    } finally {
        refreshing = false;
        $('refresh').disabled = false;
        connectionStatus();
        if (date !== selectedDate && navigator.onLine) void refresh();
    }
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
    formOpen = true;
    autoSelectEnabled = true;
    await reloadLocal();
    resetForm();
    selectDefaultEntry();
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
}

function scheduleAutosave() {
    clearTimeout(autosaveTimer);
    $('autosave-status').textContent = 'Sparar ändringar …';
    autosaveTimer = setTimeout(() => void autosave(), 700);
}

async function autosave() {
    if (busy || !ready) return;
    if (!$('start').value || !$('end').value || !$('deduction').value || !$('customer-code').value) {
        $('autosave-status').textContent = 'Fyll i tider och kundkod så sparas registreringen.';
        return;
    }
    const revision = inputRevision;
    const current = editing;
    const input = { id: current?.id ?? crypto.randomUUID(), date: selectedDate, start: $('start').value, end: $('end').value,
        deductedMinutes: Number($('deduction').value), customerCode: $('customer-code').value, comment: $('comment').value };
    busy = true;
    try {
        const saved = await saveEntry(input, current?.localRevision ?? null);
        editing = saved;
        $('new-entry').hidden = false;
        $('cancel-edit').hidden = !editingExisting;
        $('delete-current').hidden = !editingExisting;
        formDirty = inputRevision !== revision;
        clearFormError();
        $('autosave-status').textContent = 'Sparad lokalt · synkar';
        await reloadLocal();
        changes?.postMessage('changed');
        void syncPending();
        if (navigator.storage?.persist) navigator.storage.persist().catch(() => {});
    } catch (error) {
        const text = error.message || 'Kunde inte spara. Rätta uppgifterna och försök igen.';
        const overlap = text.toLocaleLowerCase('sv').includes('överlapp');
        $('form-error').classList.toggle('overlap-warning', overlap);
        message('form-error', overlap ? `${text} Ändra start- eller sluttiden så att registreringarna inte överlappar.` : text);
        $('autosave-status').textContent = '';
    } finally {
        busy = false;
        if (formDirty && inputRevision !== revision) scheduleAutosave();
    }
}

$('entry-form').addEventListener('input', () => { clearFormError(); autoSelectEnabled = false; formDirty = true; inputRevision++; preview(); scheduleAutosave(); });
$('entry-form').addEventListener('change', () => { clearFormError(); autoSelectEnabled = false; formDirty = true; inputRevision++; scheduleAutosave(); });
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
$('cancel-edit').addEventListener('click', () => { if (mayDiscard()) resetForm(); });
$('delete-current').addEventListener('click', () => {
    if (!editing || busy) return;
    deleting = editing;
    $('delete-dialog').showModal();
});
$('previous-week').addEventListener('click', () => void chooseDate(shiftDate(selectedDate, -7)));
$('next-week').addEventListener('click', () => {
    const next = shiftDate(selectedDate, 7);
    void chooseDate(next > today() ? today() : next);
});
$('today').addEventListener('click', () => void chooseDate(today()));
$('refresh').addEventListener('click', () => void refresh(true));
$('sync').addEventListener('click', () => void syncPending(true));
$('cancel-delete').addEventListener('click', () => $('delete-dialog').close());
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
        void syncPending();
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
$('code-form').addEventListener('submit', async event => {
    event.preventDefault();
    message('code-error', '');
    const code = $('new-code').value.trim();
    if (!code) { message('code-error', 'Ange en kundkod.'); return; }
    $('add-code').disabled = true;
    try {
        await createCode(code);
        // If the following read fails, a subsequent refresh retrieves the saved code.
        await cacheCodes(await fetchCodes());
        await reloadLocal();
        changes?.postMessage('changed');
        $('new-code').value = '';
        onlineStatus = true;
        message('notice', 'Kundkoden är sparad på servern och tillgänglig offline.');
    } catch (error) {
        message('code-error', `${error.message || 'Kundkoden kunde inte sparas.'} Om anslutningen avbröts, hämta från servern innan du försöker igen.`);
    } finally {
        connectionStatus();
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

window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault();
    installPrompt = event;
    $('install').hidden = false;
});
$('install').addEventListener('click', async () => {
    if (!installPrompt) return;
    await installPrompt.prompt();
    installPrompt = null;
    $('install').hidden = true;
});
window.addEventListener('appinstalled', () => { $('install').hidden = true; });
window.addEventListener('online', () => { onlineStatus = null; connectionStatus(); void syncPending().then(() => refresh()); });
window.addEventListener('offline', connectionStatus);
window.addEventListener('beforeunload', event => {
    if (formDirty) { event.preventDefault(); event.returnValue = ''; }
});
changes?.addEventListener('message', () => { if (ready) reloadLocal().catch(() => message('fatal', 'Lagringen kunde inte läsas. Ladda om sidan.')); });

async function prepareOffline() {
    if (!window.isSecureContext || !('serviceWorker' in navigator)) {
        $('offline-readiness').textContent = 'Offlineöppning kräver HTTPS eller localhost och stöd för service workers.';
        return;
    }
    try {
        const registration = await navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' });
        await navigator.serviceWorker.ready;
        $('offline-readiness').textContent = 'Redo utan nät. Du kan nu stänga och öppna appen offline.';
        if (registration.waiting) $('offline-readiness').textContent += ' En uppdatering finns. Stäng alla appflikar och öppna igen när det passar.';
    } catch {
        $('offline-readiness').textContent = 'Appen är inte klar för offlineöppning. Öppna sidan igen när du har anslutning.';
    }
}

async function start() {
    connectionStatus();
    try {
        await openStorage();
        ready = true;
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
void start();
