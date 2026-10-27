import {configureMonthlyReport} from './report.js';
import {configureInvoicingSettings} from './invoicing-settings.js?v=51';
import {configureAccountTools, logout, requireAccount} from './auth.js';
import {formatMinutes, intervalsOverlap, normalizeTime, shiftDate, today, validDate, workedMinutes} from './domain.js';
import {
    cacheBillingCodeLabels,
    cacheCodes,
    cacheCustomerCode,
    cacheRange,
    cacheVacationRange,
    deleteEntry,
    markConflict,
    markSyncFailed,
    markSyncing,
    markTimeEntryDeleted,
    markTimeEntrySynced,
    markVacationSynced,
    openStorage,
    retryRejected,
    retryWithRemoteVersion,
    saveEntry,
    selectAccount,
    setVacationDay,
    snapshot,
    useRemoteTimeEntry
} from './storage.js';
import {
    ApiError,
    createCode,
    fetchBillingCodeLabels,
    fetchCodes,
    fetchDay,
    fetchRange,
    fetchTimeEntry,
    fetchVacationDays,
    removeTimeEntry,
    saveCode,
    setVacationDayRemote,
    uploadTimeEntry
} from './api.js';

const applicationVersion = document.querySelector('meta[name="application-version"]')?.content;
const versionLabel = document.getElementById('app-version-label');
if (versionLabel && applicationVersion) versionLabel.textContent = `(v ${applicationVersion})`;
document.getElementById('force-reload')?.addEventListener('click', () => {
    if (!navigator.onLine) {
        const readiness = document.getElementById('offline-readiness');
        if (readiness) readiness.textContent = 'Anslut till nätet innan du hämtar en ny appversion.';
        else window.alert('Anslut till nätet innan du hämtar en ny appversion.');
        return;
    }
    window.location.assign('/force-update.html');
});
const currentAccount = await requireAccount();
selectAccount(currentAccount.accountId);
configureAccountTools(currentAccount);
const $ = id => document.getElementById(id);
let data = { entries: [], codes: [], billingLabels: [], outbox: [], vacationDays: [], vacationOutbox: [] };
let viewedDate = today();
let selectedDate = null;
let autosaveTimer;
let syncTimer;
let syncNotBefore = 0;
let proposedCustomerCode;
let renderedCodes;
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
let refreshing = false;
let lastRefreshAt = 0;
let lastBillingLabelsRefreshAt = 0;
let billingLabelsBusy = false;
let syncing = false;
let onlineStatus = null;
let codesBusy = false;
let codesRequestRevision = 0;
const changes = 'BroadcastChannel' in window ? new BroadcastChannel(`timereport-changes-${currentAccount.accountId}`) : null;

const monthlyReport = configureMonthlyReport({onCache: reloadLocal});
const invoicingSettings = configureInvoicingSettings();

function showView(view) {
    document.querySelectorAll('[data-view-panel]').forEach(panel => {
        panel.hidden = panel.dataset.viewPanel !== view;
    });
    document.querySelectorAll('.main-navigation [data-view]').forEach(button => {
        if (button.dataset.view === view) button.setAttribute('aria-current', 'page');
        else button.removeAttribute('aria-current');
    });
    if (view === 'report') void monthlyReport.refreshIfOpen();
    if (view === 'invoicing') void invoicingSettings.refreshIfOpen();
}

document.querySelectorAll('.main-navigation [data-view]').forEach(button => {
    button.addEventListener('click', () => showView(button.dataset.view));
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
    const offline = !navigator.onLine || onlineStatus === false;
    $('connection').classList.toggle('offline', offline);
    $('connection').textContent = !navigator.onLine ? 'Utan nät · sparar lokalt'
        : onlineStatus === false ? 'Servern kan inte nås' : onlineStatus ? 'Ansluten' : 'Nät tillgängligt';
    $('add-code').disabled = !navigator.onLine || codesBusy;
    renderCodes();
    renderSyncSummary();
}

function renderSyncSummary() {
    const pending = data.outbox.length + data.vacationOutbox.length;
    const problem = data.entries.find(entry => entry.syncError);
    message('sync-summary', problem?.syncError || (pending
        ? `${pending} ${pending === 1 ? 'ändring sparad' : 'ändringar sparade'} lokalt · ${!navigator.onLine || onlineStatus === false ? 'väntar på anslutning' : 'synkas automatiskt'}.`
        : ''));
}

function renderCodes() {
    const list = $('customer-codes');
    const codes = data.codes.filter(code => code.active || code.code === editing?.customerCode)
        .sort((a, b) => a.code.localeCompare(b.code, 'sv'));
    const shortNames = new Map((data.billingLabels || []).map(label => [label.code, label.customerShortName]));
    const signature = JSON.stringify(codes.map(code => [code.code, code.active, shortNames.get(code.code) || '']));
    if (signature !== renderedCodes) {
        list.replaceChildren(new Option('Välj kod…', ''));
        for (const code of codes) list.add(new Option(`${code.code}${shortNames.has(code.code) ? ` · ${shortNames.get(code.code)}` : ''}${code.active ? '' : ' (inaktiv)'}`, code.code));
        renderedCodes = signature;
    }
    list.disabled = !codes.length;
    $('code-help').textContent = 'Kundkod krävs för att tiden ska komma med på fakturaunderlag. Lämna tomt om tiden inte ska faktureras. En ny kod måste bekräftas med sparaknappen, även offline.';
    if (!isNewCustomerCode()) $('save-new-code').hidden = true;
    const activeCodes = data.codes.filter(code => code.active);
    $('codes-status').textContent = activeCodes.length === 1 ? '1 aktiv kundkod.' : `${activeCodes.length} aktiva kundkoder.`;
    const catalog = [...data.codes].sort((first, second) => first.code.localeCompare(second.code, 'sv'));
    for (const [id, active] of [['code-list', true], ['removed-code-list', false]]) {
        $(id).replaceChildren(...catalog.filter(code => code.active === active).map(code => {
            const item = element('li', '');
            item.dataset.code = code.code;
            const info = element('div', 'code-info');
            info.append(element('strong', '', code.code), element('span', '', code.version === 0
                ? 'Väntar på synkning' : active ? 'Aktiv' : 'Borttagen'));
            const button = element('button', active ? 'text-button remove-code' : 'secondary-button', active ? 'Ta bort' : 'Återställ');
            button.type = 'button';
            const pending = active && data.entries.some(entry => !entry.deleted && entry.customerCode === code.code && entry.syncState !== 'synced');
            button.disabled = codesBusy || !navigator.onLine || code.version === 0 || pending;
            if (pending || code.version === 0) button.title = 'Vänta tills tider med kundkoden har synkats.';
            else if (!navigator.onLine) button.title = 'Anslut till nätet för att ändra kundkoder.';
            button.setAttribute('aria-label', `${active ? 'Ta bort' : 'Återställ'} kundkod ${code.code}`);
            button.addEventListener('click', () => void setCodeActive(code, !active));
            item.append(info, button);
            return item;
        }));
    }
    $('removed-codes').hidden = !catalog.some(code => !code.active);
}

async function refreshCodes() {
    if (!navigator.onLine || codesBusy) return;
    const revision = ++codesRequestRevision;
    try {
        const codes = await fetchCodes();
        if (revision !== codesRequestRevision) return;
        await cacheCodes(codes);
        await reloadLocal();
    } catch (error) {
        if (revision === codesRequestRevision) message('code-error', error.message || 'Kundkoderna kunde inte hämtas.');
    }
}

async function refreshBillingLabels(force = false) {
    if (!navigator.onLine || billingLabelsBusy || !force && Date.now() - lastBillingLabelsRefreshAt < 300000) return;
    billingLabelsBusy = true;
    try {
        await cacheBillingCodeLabels(await fetchBillingCodeLabels());
        lastBillingLabelsRefreshAt = Date.now();
        await reloadLocal();
    } catch {
        // Cached labels remain available when billing settings cannot be reached.
    } finally { billingLabelsBusy = false; }
}

async function setCodeActive(code, active) {
    if (codesBusy || !navigator.onLine) return;
    if (!active && data.entries.some(entry => !entry.deleted && entry.customerCode === code.code && entry.syncState !== 'synced')) {
        message('code-error', 'Vänta tills tider med kundkoden har synkats innan du tar bort den.');
        return;
    }
    if (!active && !window.confirm(`Ta bort kundkod ”${code.code}”? Tidigare tidsregistreringar och rapporter behåller koden.`)) return;
    codesBusy = true;
    ++codesRequestRevision;
    message('code-error', '');
    connectionStatus();
    try {
        await cacheCustomerCode(await saveCode(code.code, active, code.version));
        await reloadLocal();
        changes?.postMessage('changed');
        message('notice', active ? `Kundkoden ”${code.code}” är återställd.`
            : `Kundkoden ”${code.code}” är borttagen från valen. Tidigare registreringar finns kvar.`);
    } catch (error) {
        if (error.status === 409) {
            codesBusy = false;
            await refreshCodes();
            message('code-error', 'Kundkoden har ändrats på servern. Listan har uppdaterats; försök igen.');
        } else message('code-error', error.message || 'Kundkoden kunde inte ändras. Försök igen.');
    } finally {
        codesBusy = false;
        connectionStatus();
    }
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

function scheduleSync(delay = 5000) {
    clearTimeout(syncTimer);
    syncNotBefore = Date.now() + delay;
    syncTimer = setTimeout(() => void syncPending(), delay);
}

async function syncPending(manual = false) {
    if (!ready || syncing || !navigator.onLine) return;
    if (!manual && Date.now() < syncNotBefore) {
        clearTimeout(syncTimer);
        syncTimer = setTimeout(() => void syncPending(), syncNotBefore - Date.now());
        return;
    }
    clearTimeout(syncTimer);
    if (manual) syncNotBefore = 0;
    syncing = true;
    $('sync').disabled = true;
    try {
        if (manual) await retryRejected();
        let processed = 0;
        while (processed++ < 100 && navigator.onLine) {
            // Let an in-flight request finish, then pause if new edits arrived.
            if (!manual && Date.now() < syncNotBefore) break;
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
                    const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500 && ![401, 403, 429].includes(error.status);
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

async function refresh(showFeedback = false) {
    if (!ready || refreshing) return;
    if (!navigator.onLine) {
        connectionStatus();
        if (showFeedback) message('notice', 'Du är offline. Tidigare hämtade uppgifter och dina lokala ändringar finns kvar.');
        return;
    }
    refreshing = true;
    lastRefreshAt = Date.now();
    $('refresh').disabled = true;
    const date = viewedDate;
    try {
        await syncPending();
        const { start, end } = weekBounds(date);
        const [codes, entries, vacations] = await Promise.allSettled([fetchCodes(), fetchRange(start, end), fetchVacationDays(start, end)]);
        if (codes.status === 'fulfilled') await cacheCodes(codes.value);
        if (entries.status === 'fulfilled') await cacheRange(start, end, entries.value);
        if (vacations.status === 'fulfilled') await cacheVacationRange(start, end, vacations.value);
        await reloadLocal();
        void refreshBillingLabels();
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
        if (date !== viewedDate && navigator.onLine) void refresh();
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
$('code-form').addEventListener('submit', async event => {
    event.preventDefault();
    message('code-error', '');
    if (codesBusy || !navigator.onLine) return;
    const code = $('new-code').value.trim();
    if (!code) { message('code-error', 'Ange en kundkod.'); return; }
    codesBusy = true;
    ++codesRequestRevision;
    connectionStatus();
    try {
        await cacheCustomerCode(await createCode(code));
        await reloadLocal();
        changes?.postMessage('changed');
        $('new-code').value = '';
        onlineStatus = true;
        message('notice', 'Kundkoden är sparad på servern och tillgänglig offline.');
    } catch (error) {
        message('code-error', error.status === 409 ? 'Kundkoden finns redan. Om den är borttagen kan du återställa den under Borttagna kundkoder.' : error.message || 'Kundkoden kunde inte sparas.');
    } finally {
        codesBusy = false;
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

desktopWeekLayout.addEventListener('change', () => {
    if (ready) renderWeek();
});
function refreshOnReturn() {
    if (!ready || document.hidden || !navigator.onLine || refreshing || Date.now() - lastRefreshAt < 30000) return;
    void refresh().then(() => monthlyReport.refreshIfOpen());
}

window.addEventListener('focus', refreshOnReturn);
window.addEventListener('pageshow', refreshOnReturn);
document.addEventListener('visibilitychange', refreshOnReturn);
window.addEventListener('online', () => { onlineStatus = null; connectionStatus(); void syncPending().then(() => refresh()); });
window.addEventListener('billing-labels-updated', () => void refreshBillingLabels(true));
window.addEventListener('billing-customer-codes-opened', () => void refreshCodes());
window.addEventListener('offline', connectionStatus);
window.addEventListener('beforeunload', event => {
    if (formDirty) { event.preventDefault(); event.returnValue = ''; }
});
changes?.addEventListener('message', () => { if (ready) reloadLocal().catch(() => message('fatal', 'Lagringen kunde inte läsas. Ladda om sidan.')); });

async function prepareOffline() {
    const reloadButton = $('force-reload');
    if (!window.isSecureContext || !('serviceWorker' in navigator)) {
        $('offline-readiness').textContent = 'Offlineöppning kräver HTTPS eller localhost och stöd för service workers.';
        return;
    }
    try {
        const version = document.querySelector('meta[name="application-version"]')?.content || '1';
        const registration = await navigator.serviceWorker.register(`/sw.js?v=${encodeURIComponent(version)}`, { updateViaCache: 'none' });
        const showWaitingUpdate = () => {
            if (registration.waiting && navigator.serviceWorker.controller) {
                reloadButton.textContent = '↻';
                reloadButton.setAttribute('aria-label', `Uppdatera appen till version ${version}`);
                reloadButton.title = `Uppdatera appen till version ${version}`;
                $('offline-readiness').textContent = 'En ny appversion är redo. Dina sparade tidsregistreringar lämnas kvar.';
            }
        };
        const watchInstall = () => {
            const worker = registration.installing;
            if (!worker) return;
            worker.addEventListener('statechange', () => {
                if (worker.state === 'installed') showWaitingUpdate();
            });
        };
        registration.addEventListener('updatefound', watchInstall);
        watchInstall();
        await navigator.serviceWorker.ready;
        $('offline-readiness').textContent = 'Redo utan nät. Du kan nu stänga och öppna appen offline.';
        showWaitingUpdate();
        void registration.update().catch(() => {});
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

$('logout').addEventListener('click', async () => {
    if (busy || syncing || refreshing) {
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
