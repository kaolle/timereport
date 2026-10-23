import {remoteEntry, validateEntry, validDate} from './domain.js';

let DB_NAME;

export function selectAccount(accountId) {
    if (!/^[0-9a-f-]{36}$/i.test(accountId)) throw new Error('Ett konto krävs för lokal lagring.');
    const name = `timereport-account-${accountId}`;
    if (DB_NAME && DB_NAME !== name) throw new Error('Ladda om sidan för att byta konto.');
    DB_NAME = name;
}
let database;

export function openStorage() {
    if (!DB_NAME) throw new Error('Logga in innan lokal lagring öppnas.');
    if (database) return database;
    database = new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, 5);
        request.onupgradeneeded = () => {
            const db = request.result;
            if (!db.objectStoreNames.contains('reportMonths')) db.createObjectStore('reportMonths', {keyPath: 'month'});
            if (!db.objectStoreNames.contains('entries')) db.createObjectStore('entries', { keyPath: 'id' }).createIndex('date', 'date');
            if (!db.objectStoreNames.contains('codes')) db.createObjectStore('codes', { keyPath: 'code' });
            if (!db.objectStoreNames.contains('outbox')) db.createObjectStore('outbox', { keyPath: 'id' });
            if (!db.objectStoreNames.contains('vacations')) db.createObjectStore('vacations', { keyPath: 'date' });
            if (!db.objectStoreNames.contains('vacationOutbox')) db.createObjectStore('vacationOutbox', { keyPath: 'date' });
            if (!db.objectStoreNames.contains('billingLabels')) db.createObjectStore('billingLabels', { keyPath: 'code' });
        };
        request.onsuccess = () => {
            const db = request.result;
            db.onversionchange = () => db.close();
            resolve(db);
        };
        request.onerror = () => reject(request.error);
        request.onblocked = () => reject(new Error('Stäng andra flikar med Tidrapport och öppna sidan igen.'));
    });
    return database;
}

function requestResult(request) {
    return new Promise((resolve, reject) => {
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
    });
}

async function transaction(stores, mode, work) {
    const db = await openStorage();
    return new Promise((resolve, reject) => {
        const tx = db.transaction(stores, mode);
        let result;
        let failure;
        tx.oncomplete = () => resolve(result);
        tx.onabort = () => reject(failure || tx.error || new Error('Lagringen avbröts. Försök igen.'));
        Promise.resolve().then(() => work(tx)).then(value => { result = value; }).catch(error => {
            failure = error;
            try { tx.abort(); } catch { reject(error); }
        });
    });
}

export async function snapshot() {
    return transaction(['entries', 'codes', 'outbox', 'vacations', 'vacationOutbox', 'reportMonths', 'billingLabels'], 'readonly', async tx => {
        const [entries, codes, outbox, vacationDays, vacationOutbox, reportMonths, billingLabels] = await Promise.all(['entries', 'codes', 'outbox', 'vacations', 'vacationOutbox', 'reportMonths', 'billingLabels']
            .map(name => requestResult(tx.objectStore(name).getAll())));
        return {entries, codes, outbox, vacationDays, vacationOutbox, reportMonths, billingLabels};
    });
}

export async function cacheBillingCodeLabels(labels) {
    if (!Array.isArray(labels) || labels.some(label => typeof label.code !== 'string'
            || typeof label.customerShortName !== 'string')) throw new Error('Kundnamnens svar är ogiltigt.');
    return transaction(['billingLabels'], 'readwrite', async tx => {
        const store = tx.objectStore('billingLabels');
        store.clear();
        for (const label of labels) store.put({code: label.code, customerShortName: label.customerShortName});
    });
}

export async function setVacationDay(date, vacation, expectedEntryIds = []) {
    if (!validDate(date)) throw new Error('Välj ett giltigt datum.');
    return transaction(['vacations', 'entries', 'outbox', 'vacationOutbox'], 'readwrite', async tx => {
        const store = tx.objectStore('vacations');
        const vacationOutbox = tx.objectStore('vacationOutbox');
        const pending = await requestResult(vacationOutbox.get(date));
        const revision = (pending?.localRevision ?? 0) + 1;
        if (!vacation) {
            store.delete(date);
            vacationOutbox.put({ date, vacation: false, localRevision: revision });
            return 0;
        }

        const entries = tx.objectStore('entries');
        const todaysEntries = (await requestResult(entries.getAll())).filter(entry => entry.date === date && !entry.deleted);
        const actualIds = todaysEntries.map(entry => entry.id).sort();
        if (JSON.stringify(actualIds) !== JSON.stringify([...expectedEntryIds].sort())) {
            throw new Error('Dagens registreringar har ändrats. Ladda om sidan och försök igen.');
        }
        for (const current of todaysEntries) {
            if (current.serverVersion === 0 && current.syncState !== 'syncing') {
                entries.delete(current.id);
                tx.objectStore('outbox').delete(current.id);
            } else {
                const tombstone = { ...current, deleted: true, syncState: 'pending',
                    localRevision: current.localRevision + 1, conflict: null, updatedAt: new Date().toISOString() };
                entries.put(tombstone);
                tx.objectStore('outbox').put({ id: current.id,
                    operation: current.serverVersion === 0 ? 'delete-after-create' : 'delete',
                    expectedVersion: current.serverVersion, localRevision: tombstone.localRevision,
                    state: 'pending', creationMayBeInFlight: current.serverVersion === 0 && current.syncState === 'syncing' });
            }
        }
        store.put({ date });
        vacationOutbox.put({ date, vacation: true, localRevision: revision });
        return todaysEntries.length;
    });
}

export async function markVacationSynced(date, localRevision) {
    return transaction(['vacationOutbox'], 'readwrite', async tx => {
        const queue = tx.objectStore('vacationOutbox');
        const current = await requestResult(queue.get(date));
        if (current?.localRevision === localRevision) queue.delete(date);
    });
}

export async function cacheVacationRange(from, to, remote) {
    if (!Array.isArray(remote) || remote.some(day => !validDate(day?.date) || day.date < from || day.date > to)) {
        throw new Error('Semesterdagarna från servern kunde inte läsas.');
    }
    return transaction(['vacations', 'vacationOutbox'], 'readwrite', async tx => {
        const store = tx.objectStore('vacations');
        const queue = tx.objectStore('vacationOutbox');
        const [local, pending] = await Promise.all([requestResult(store.getAll()), requestResult(queue.getAll())]);
        const remoteDates = new Set(remote.map(day => day.date));
        const pendingByDate = new Map(pending.map(change => [change.date, change]));
        for (const day of local) {
            if (day.date >= from && day.date <= to && !remoteDates.has(day.date) && !pendingByDate.has(day.date)) store.delete(day.date);
        }
        for (const day of remote) {
            if (!pendingByDate.has(day.date)) store.put({ date: day.date });
        }
        for (const change of pending) {
            if (change.date < from || change.date > to) continue;
            if (change.vacation) store.put({ date: change.date });
            else store.delete(change.date);
        }
    });
}

export async function saveEntry(input, expectedRevision = null) {
    input = {...input, customerCode: input.customerCode?.trim() || null};
    return transaction(['entries', 'codes', 'outbox'], 'readwrite', async tx => {
        const entries = tx.objectStore('entries');
        const all = await requestResult(entries.getAll());
        const current = all.find(entry => entry.id === input.id);
        if ((expectedRevision === null && current)
                || (expectedRevision !== null && (!current || current.localRevision !== expectedRevision))
                || current?.deleted) {
            throw new Error('Registreringen ändrades eller togs bort i en annan flik eller på servern. Avbryt redigeringen och öppna den igen.');
        }
        validateEntry(input, all);
        if (input.customerCode) {
            const store = tx.objectStore('codes');
            const code = await requestResult(store.get(input.customerCode));
            if (code && !code.active && current?.customerCode !== input.customerCode) {
                throw new Error('Kundkoden är inaktiv. Välj en aktiv kundkod.');
            }
            if (!code) store.put({code: input.customerCode, active: true, version: 0});
        }
        const saved = { ...input, serverVersion: current?.serverVersion ?? 0, localRevision: (current?.localRevision ?? 0) + 1,
            syncState: 'pending', deleted: false, conflict: null, syncError: null, updatedAt: new Date().toISOString() };
        entries.put(saved);
        tx.objectStore('outbox').put({ id: saved.id, operation: 'put', expectedVersion: saved.serverVersion,
            localRevision: saved.localRevision, entry: saved, state: 'pending' });
        return saved;
    });
}

export async function deleteEntry(id, expectedRevision) {
    return transaction(['entries', 'outbox'], 'readwrite', async tx => {
        const entries = tx.objectStore('entries');
        const current = await requestResult(entries.get(id));
        if (!current || current.deleted || current.localRevision !== expectedRevision) {
            throw new Error('Registreringen har ändrats. Öppna den aktuella registreringen och försök igen.');
        }
        if (current.serverVersion === 0 && current.syncState !== 'syncing') {
            entries.delete(id);
            tx.objectStore('outbox').delete(id);
        } else {
            const tombstone = { ...current, deleted: true, syncState: 'pending',
                localRevision: current.localRevision + 1, conflict: null, updatedAt: new Date().toISOString() };
            entries.put(tombstone);
            tx.objectStore('outbox').put({ id, operation: current.serverVersion === 0 ? 'delete-after-create' : 'delete',
                expectedVersion: current.serverVersion, localRevision: tombstone.localRevision,
                state: 'pending', creationMayBeInFlight: current.serverVersion === 0 && current.syncState === 'syncing' });
        }
    });
}

export async function markSyncing(item) {
    return transaction(['entries', 'outbox'], 'readwrite', async tx => {
        const queue = tx.objectStore('outbox');
        const currentItem = await requestResult(queue.get(item.id));
        const entries = tx.objectStore('entries');
        const current = await requestResult(entries.get(item.id));
        if (!currentItem || currentItem.localRevision !== item.localRevision
                || currentItem.state === 'conflict' || currentItem.state === 'rejected'
                || !current || current.localRevision !== item.localRevision) return null;
        if (currentItem.operation === 'delete-after-create' && currentItem.expectedVersion === 0
                && !currentItem.creationMayBeInFlight) {
            queue.delete(item.id);
            entries.delete(item.id);
            return { discard: true };
        }
        current.syncState = 'syncing';
        currentItem.state = 'sending';
        entries.put(current);
        queue.put(currentItem);
        return currentItem;
    });
}

export async function markSyncFailed(id, localRevision, error, rejected = false) {
    return transaction(['entries', 'outbox'], 'readwrite', async tx => {
        const queue = tx.objectStore('outbox');
        const item = await requestResult(queue.get(id));
        const store = tx.objectStore('entries');
        const current = await requestResult(store.get(id));
        if (!item || item.localRevision !== localRevision || !current) return;
        item.state = rejected ? 'rejected' : 'pending';
        item.error = error;
        current.syncState = rejected ? 'rejected' : 'pending';
        current.syncError = error;
        store.put(current);
        queue.put(item);
    });
}

export async function retryRejected() {
    return transaction(['entries', 'outbox'], 'readwrite', async tx => {
        const queue = tx.objectStore('outbox');
        const entries = tx.objectStore('entries');
        const items = await requestResult(queue.getAll());
        for (const item of items.filter(row => row.state === 'rejected')) {
            item.state = 'pending';
            item.error = null;
            queue.put(item);
            const current = await requestResult(entries.get(item.id));
            if (current) { current.syncState = 'pending'; current.syncError = null; entries.put(current); }
        }
    });
}

export async function markConflict(id, localRevision, conflict) {
    return transaction(['entries', 'outbox'], 'readwrite', async tx => {
        const queue = tx.objectStore('outbox');
        const item = await requestResult(queue.get(id));
        const store = tx.objectStore('entries');
        const current = await requestResult(store.get(id));
        if (!item || item.localRevision !== localRevision || !current) return false;
        item.state = 'conflict';
        item.error = conflict.message;
        current.syncState = 'conflict';
        current.conflict = conflict;
        current.syncError = conflict.message;
        store.put(current);
        queue.put(item);
        return true;
    });
}

export async function markTimeEntrySynced(id, localRevision, value) {
    const remote = remoteEntry(value);
    if (remote.id !== id) throw new Error('Servern svarade med fel registrerings-ID.');
    return transaction(['entries', 'outbox'], 'readwrite', async tx => {
        const queue = tx.objectStore('outbox');
        const item = await requestResult(queue.get(id));
        const store = tx.objectStore('entries');
        const current = await requestResult(store.get(id));
        if (!current) {
            // A local delete raced an in-flight create. Keep a tombstone to delete the new server row.
            if (item?.operation === 'delete-after-create') {
                item.operation = 'delete';
                item.expectedVersion = remote.serverVersion;
                item.state = 'pending';
                item.creationMayBeInFlight = false;
                queue.put(item);
                const tombstone = { ...item.entry, serverVersion: remote.serverVersion, deleted: true,
                    syncState: 'pending', conflict: null, syncError: null };
                store.put(tombstone);
                return;
            }
            return;
        }
        const newerQueuedEdit = item?.operation === 'put' && item.localRevision !== localRevision
            && item.state !== 'conflict' && item.state !== 'rejected';
        if (newerQueuedEdit) {
            current.serverVersion = remote.serverVersion;
            current.syncState = 'pending';
            current.conflict = null;
            current.syncError = null;
            item.operation = 'put';
            item.expectedVersion = remote.serverVersion;
            item.state = 'pending';
            item.error = null;
            item.entry = { ...current };
            queue.put(item);
            store.put(current);
        } else if (item?.operation === 'delete-after-create') {
            // The user deleted the new local entry while its create request was in flight.
            item.operation = 'delete';
            item.expectedVersion = remote.serverVersion;
            item.creationMayBeInFlight = false;
            item.state = 'pending';
            item.error = null;
            queue.put(item);
            current.serverVersion = remote.serverVersion;
            current.syncState = 'pending';
            current.conflict = null;
            current.syncError = null;
            store.put(current);
        } else if (item && item.localRevision === localRevision) {
            store.put({ ...remote, localRevision: current.localRevision, syncState: 'synced',
                conflict: null, syncError: null, updatedAt: current.updatedAt });
            queue.delete(id);
        } else if (!item && !current.deleted) {
            // A second tab finished the same idempotent request first.
            store.put({ ...remote, localRevision: current.localRevision, syncState: 'synced',
                conflict: null, syncError: null, updatedAt: current.updatedAt });
        }
    });
}

export async function markTimeEntryDeleted(id, localRevision) {
    return transaction(['entries', 'outbox'], 'readwrite', async tx => {
        const queue = tx.objectStore('outbox');
        const item = await requestResult(queue.get(id));
        if (!item) return;
        if (item.localRevision !== localRevision) {
            if (item.operation === 'delete-after-create') {
                // Keep the tombstone; completion of the in-flight PUT sets its delete version.
                item.state = 'pending';
                queue.put(item);
            }
            return;
        }
        if (item.operation === 'delete-after-create' && item.expectedVersion === 0) {
            const store = tx.objectStore('entries');
            const current = await requestResult(store.get(id));
            if (current) {
                current.syncState = 'syncing';
                store.put(current);
            }
            item.state = 'pending';
            queue.put(item);
            return;
        }
        queue.delete(id);
        tx.objectStore('entries').delete(id);
    });
}

export async function useRemoteTimeEntry(id, localRevision, remote) {
    return transaction(['entries', 'outbox'], 'readwrite', async tx => {
        const queue = tx.objectStore('outbox');
        const item = await requestResult(queue.get(id));
        const store = tx.objectStore('entries');
        const current = await requestResult(store.get(id));
        if (!item || item.localRevision !== localRevision || !current || !current.conflict) {
            throw new Error('Konflikten har ändrats. Hämta den aktuella registreringen igen.');
        }
        if (remote) store.put({ ...remoteEntry(remote), localRevision: current.localRevision + 1,
            syncState: 'synced', conflict: null, syncError: null });
        else store.delete(id);
        queue.delete(id);
    });
}

export async function retryWithRemoteVersion(id, localRevision) {
    return transaction(['entries', 'outbox'], 'readwrite', async tx => {
        const queue = tx.objectStore('outbox');
        const item = await requestResult(queue.get(id));
        const store = tx.objectStore('entries');
        const current = await requestResult(store.get(id));
        if (!item || item.localRevision !== localRevision || !current?.conflict?.serverEntry) {
            throw new Error('Serverns version saknas. Hämta servern och välj hur konflikten ska hanteras.');
        }
        item.expectedVersion = current.conflict.serverEntry.version;
        item.state = 'pending';
        item.error = null;
        current.serverVersion = item.expectedVersion;
        current.syncState = 'pending';
        current.conflict = null;
        current.syncError = null;
        store.put(current);
        if (item.operation === 'put') item.entry = { ...current };
        queue.put(item);
    });
}

export async function cacheCustomerCode(code) {
    if (typeof code?.code !== 'string' || !code.code.trim() || typeof code.active !== 'boolean'
            || !Number.isSafeInteger(code.version) || code.version < 1) {
        throw new Error('Kundkoden från servern kunde inte läsas.');
    }
    return transaction(['codes'], 'readwrite', async tx => {
        const store = tx.objectStore('codes');
        const current = await requestResult(store.get(code.code));
        if (!current || current.version <= code.version) store.put(code);
    });
}

export async function cacheCodes(codes) {
    if (!Array.isArray(codes) || codes.some(code => typeof code.code !== 'string' || !code.code.trim()
            || typeof code.active !== 'boolean' || !Number.isSafeInteger(code.version) || code.version < 1)) {
        throw new Error('Kundkoderna från servern kunde inte läsas.');
    }
    return transaction(['codes', 'entries'], 'readwrite', async tx => {
        const store = tx.objectStore('codes');
        const entries = await requestResult(tx.objectStore('entries').getAll());
        // Codes are never physically deleted on the server. A delayed catalogue read
        // must not overwrite a newer deactivation or restoration with an old version.
        const merged = new Map((await requestResult(store.getAll())).filter(code => code.version > 0)
            .map(code => [code.code, code]));
        for (const code of codes) {
            if (!merged.has(code.code) || merged.get(code.code).version <= code.version) merged.set(code.code, code);
        }
        for (const entry of entries) {
            if (!entry.deleted && entry.syncState !== 'synced' && entry.customerCode && !merged.has(entry.customerCode)) {
                merged.set(entry.customerCode, { code: entry.customerCode, active: true, version: 0 });
            }
        }
        store.clear();
        for (const code of merged.values()) store.put(code);
    });
}

export async function cacheDay(date, remote) {
    if (!Array.isArray(remote)) throw new Error('Dagens registreringar kunde inte läsas.');
    const records = remote.map(remoteEntry);
    if (records.some(entry => entry.date !== date)) throw new Error('Servern skickade fel arbetsdag.');
    return transaction(['entries'], 'readwrite', async tx => {
        const store = tx.objectStore('entries');
        const local = await requestResult(store.getAll());
        const byId = new Map(local.map(entry => [entry.id, entry]));
        const remoteIds = new Set(records.map(entry => entry.id));
        for (const entry of local) {
            if (entry.date === date && entry.syncState === 'synced' && !remoteIds.has(entry.id)) store.delete(entry.id);
        }
        for (const entry of records) {
            const current = byId.get(entry.id);
            // Never overwrite edits or deletion tombstones, even after a date change.
            if (current && current.syncState !== 'synced') continue;
            if (current && current.serverVersion > entry.serverVersion) continue;
            store.put({ ...entry, localRevision: current ? current.localRevision + 1 : 0 });
        }
    });
}

export async function cacheRange(from, to, remote, cachedMonth = null) {
    if (!Array.isArray(remote)) throw new Error('Periodens registreringar kunde inte läsas.');
    const records = remote.map(remoteEntry);
    if (records.some(entry => entry.date < from || entry.date > to)) {
        throw new Error('Servern skickade registreringar utanför den valda perioden.');
    }
    return transaction(cachedMonth ? ['entries', 'reportMonths'] : ['entries'], 'readwrite', async tx => {
        const store = tx.objectStore('entries');
        const local = await requestResult(store.getAll());
        const byId = new Map(local.map(entry => [entry.id, entry]));
        const remoteIds = new Set(records.map(entry => entry.id));
        for (const entry of local) {
            if (entry.date >= from && entry.date <= to && entry.syncState === 'synced' && !remoteIds.has(entry.id)) store.delete(entry.id);
        }
        for (const entry of records) {
            const current = byId.get(entry.id);
            if (current && current.syncState !== 'synced') continue;
            // Reading an unchanged server version must not invalidate an open local edit.
            if (current && current.serverVersion >= entry.serverVersion) continue;
            store.put({ ...entry, localRevision: current ? current.localRevision + 1 : 0 });
        }
        if (cachedMonth) tx.objectStore('reportMonths').put({month: cachedMonth, fetchedAt: new Date().toISOString()});
    });
}
