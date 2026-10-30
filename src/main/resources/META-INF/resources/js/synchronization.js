import {intervalsOverlap, shiftDate} from './domain.js';
import {
    cacheCodes,
    cacheRange,
    cacheVacationRange,
    markConflict,
    markSyncFailed,
    markSyncing,
    markTimeEntryDeleted,
    markTimeEntrySynced,
    markVacationSynced,
    retryRejected,
    snapshot
} from './storage.js';
import {
    ApiError,
    fetchCodes,
    fetchDay,
    fetchRange,
    fetchTimeEntry,
    fetchVacationDays,
    removeTimeEntry,
    setVacationDayRemote,
    uploadTimeEntry
} from './api.js';

// Owns transfer scheduling, conflict handling, server refresh and reconnection.
// Callbacks keep form rendering and navigation out of synchronization.
export function configureSynchronization({
                                             isReady, getDate, onLocalChange, onRefreshed,
                                             onStateChange, onSyncStatus, onNotice, notifyChanges, onReturn
                                         }) {
    let syncTimer;
    let syncNotBefore = 0;
    let syncing = false;
    let refreshing = false;
    let lastRefreshAt = 0;
    let onlineStatus = null;
    let reconnectTimer;

    function weekBounds(date) {
        const weekday = new Date(`${date}T12:00:00Z`).getUTCDay();
        const start = shiftDate(date, -((weekday + 6) % 7));
        return {start, end: shiftDate(start, 6)};
    }

    function scheduleSync(delay = 5000) {
        clearTimeout(syncTimer);
        syncNotBefore = Date.now() + delay;
        syncTimer = setTimeout(() => void syncPending(), delay);
    }

    async function syncPending(manual = false) {
        if (!isReady() || syncing || !navigator.onLine) return;
        if (!manual && Date.now() < syncNotBefore) {
            clearTimeout(syncTimer);
            syncTimer = setTimeout(() => void syncPending(), syncNotBefore - Date.now());
            return;
        }
        clearTimeout(syncTimer);
        if (manual) syncNotBefore = 0;
        syncing = true;
        onStateChange();
        try {
            if (manual) await retryRejected();
            let processed = 0;
            while (processed++ < 100 && navigator.onLine) {
                // Let an in-flight request finish, then pause if new edits arrived.
                if (!manual && Date.now() < syncNotBefore) break;
                const {outbox, vacationOutbox} = await snapshot();
                const item = outbox.find(row => row.state === 'pending' || row.state === 'sending');
                if (!item) {
                    const vacation = vacationOutbox[0];
                    if (!vacation) break;
                    try {
                        await setVacationDayRemote(vacation.date, vacation.vacation);
                        await markVacationSynced(vacation.date, vacation.localRevision);
                        await onLocalChange();
                        notifyChanges();
                        continue;
                    } catch (error) {
                        onlineStatus = error instanceof ApiError && error.status >= 500 ? false : true;
                        break;
                    }
                }
                const claim = await markSyncing(item);
                if (!claim) {
                    await onLocalChange();
                    continue;
                }
                if (claim.discard) {
                    await onLocalChange();
                    continue;
                }
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
                        try {
                            remote = await fetchTimeEntry(claim.id);
                        } catch (readError) {
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
                            try {
                                overlaps = (await fetchDay(current.date)).filter(row => row.id !== current.id && intervalsOverlap(current, row));
                            } catch {
                            }
                        }
                        if (!remote && claim.operation === 'delete') {
                            await markTimeEntryDeleted(claim.id, claim.localRevision);
                        } else {
                            await markConflict(claim.id, claim.localRevision, {
                                message: overlaps.length ? 'Tiden överlappar en annan registrering på servern.' : 'Registreringen ändrades på servern. Välj vilken version som ska behållas.',
                                serverEntry: remote,
                                overlaps
                            });
                        }
                    } else {
                        const rejected = error instanceof ApiError && error.status >= 400 && error.status < 500 && ![401, 403, 429].includes(error.status);
                        await markSyncFailed(claim.id, claim.localRevision, error.message || 'Överföringen misslyckades.', rejected);
                        break;
                    }
                    onlineStatus = true;
                }
                await onLocalChange();
                notifyChanges();
            }
        } catch (error) {
            onlineStatus = false;
            onSyncStatus(error.message || 'Synkningen avbröts. Dina ändringar finns kvar lokalt.');
        } finally {
            await onLocalChange().catch(() => {
            });
            syncing = false;
            onStateChange();
            const data = await snapshot().catch(() => null);
            if (onlineStatus === true && data) {
                const rejected = data.entries.find(entry => entry.syncState === 'rejected');
                const pending = data.outbox.length + data.vacationOutbox.length;
                onSyncStatus(rejected ? rejected.syncError : pending
                    ? `${pending} ändringar väntar. Tryck för att försöka igen.` : 'Alla tider är synkroniserade.');
            }
        }
    }

    async function refresh(showFeedback = false) {
        if (!isReady() || refreshing) return;
        if (!navigator.onLine) {
            onStateChange();
            if (showFeedback) onNotice('Du är offline. Tidigare hämtade uppgifter och dina lokala ändringar finns kvar.');
            return;
        }
        refreshing = true;
        lastRefreshAt = Date.now();
        onStateChange();
        const date = getDate();
        try {
            await syncPending();
            const {start, end} = weekBounds(date);
            const entryStart = shiftDate(start, -5);
            const [codes, entries, vacations] = await Promise.allSettled([fetchCodes(), fetchRange(entryStart, end), fetchVacationDays(start, end)]);
            if (codes.status === 'fulfilled') await cacheCodes(codes.value);
            if (entries.status === 'fulfilled') await cacheRange(entryStart, end, entries.value);
            if (vacations.status === 'fulfilled') await cacheVacationRange(start, end, vacations.value);
            await onLocalChange();
            onRefreshed(![codes, entries, vacations].some(result => result.status === 'rejected'));
            notifyChanges();
            const failed = [codes, entries, vacations].find(result => result.status === 'rejected');
            if (failed) throw failed.reason;
            onlineStatus = true;
            if (showFeedback) onNotice('Veckans tider och kundkoder är uppdaterade.');
        } catch {
            onlineStatus = false;
            if (showFeedback) onNotice('Servern kunde inte nås. Du kan fortsätta med dina lokalt sparade uppgifter.');
        } finally {
            refreshing = false;
            onStateChange();
            if (date !== getDate() && navigator.onLine) void refresh();
        }
    }

    function refreshOnReturn() {
        reconnectSoon();
        if (!isReady() || document.hidden || !navigator.onLine || refreshing || Date.now() - lastRefreshAt < 30000) return;
        void refresh().then(() => onReturn());
    }

    function reconnectSoon() {
        clearTimeout(reconnectTimer);
        if (!isReady() || document.hidden || !navigator.onLine
            || document.documentElement.dataset.sessionState !== 'unavailable') return;
        reconnectTimer = setTimeout(async () => {
            await syncPending();
            await refresh();
            reconnectSoon();
        }, 5000);
    }

    window.addEventListener('auth-session-changed', () => {
        onStateChange();
        reconnectSoon();
    });

    window.addEventListener('focus', refreshOnReturn);
    window.addEventListener('pageshow', refreshOnReturn);
    document.addEventListener('visibilitychange', refreshOnReturn);
    window.addEventListener('online', () => {
        onlineStatus = null;
        onStateChange();
        reconnectSoon();
        void syncPending().then(() => refresh());
    });

    return {
        scheduleSync, syncPending, refresh, reconnectSoon,
        get syncing() {
            return syncing;
        },
        get refreshing() {
            return refreshing;
        },
        get onlineStatus() {
            return onlineStatus;
        },
        setOnlineStatus(value) {
            onlineStatus = value;
            onStateChange();
        }
    };
}
