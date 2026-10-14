export function today() {
    return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Stockholm', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date());
}

export function validDate(value) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
    const parsed = new Date(`${value}T12:00:00Z`);
    return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

export function shiftDate(value, days) {
    const date = new Date(`${value}T12:00:00Z`);
    date.setUTCDate(date.getUTCDate() + days);
    return date.toISOString().slice(0, 10);
}

export function timeMinutes(value) {
    if (typeof value !== 'string' || !/^([01]\d|2[0-3]):[0-5]\d$/.test(value)) return NaN;
    const [hours, minutes] = value.split(':').map(Number);
    return hours * 60 + minutes;
}

export function workedMinutes(entry) {
    return timeMinutes(entry.end) - timeMinutes(entry.start) - entry.deductedMinutes;
}

export function intervalsOverlap(first, second) {
    return first.date === second.date && timeMinutes(first.start) < timeMinutes(second.end)
        && timeMinutes(first.end) > timeMinutes(second.start);
}

export function formatMinutes(minutes) {
    if (!Number.isInteger(minutes) || minutes < 0) return '—';
    return `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`;
}

export function validateEntry(entry, existing = []) {
    if (!validDate(entry.date)) throw new Error('Välj ett giltigt datum.');
    if (!Number.isFinite(timeMinutes(entry.start)) || !Number.isFinite(timeMinutes(entry.end))) {
        throw new Error('Ange start och slut med hela minuter.');
    }
    const duration = timeMinutes(entry.end) - timeMinutes(entry.start);
    if (duration <= 0) throw new Error('Sluttiden måste vara efter starttiden samma dag.');
    if (!Number.isInteger(entry.deductedMinutes) || entry.deductedMinutes < 0 || entry.deductedMinutes > duration) {
        throw new Error('Avdraget måste vara mellan 0 och arbetspassets längd i minuter.');
    }
    if (entry.customerCode != null && typeof entry.customerCode !== 'string') throw new Error('Kundkoden måste vara text.');
    if (typeof entry.comment !== 'string') throw new Error('Kommentaren måste vara text.');
    const overlap = existing.some(other => !other.deleted && other.id !== entry.id && other.date === entry.date
        && timeMinutes(entry.start) < timeMinutes(other.end) && timeMinutes(entry.end) > timeMinutes(other.start));
    if (overlap) throw new Error('Tiden överlappar en annan registrering den här dagen.');
    return entry;
}

export function remoteEntry(value) {
    if (!value || typeof value.id !== 'string' || !Number.isSafeInteger(value.version) || value.version < 1) {
        throw new Error('Servern skickade en ogiltig registrering.');
    }
    const wholeTime = time => {
        if (typeof time !== 'string' || !/^\d{2}:\d{2}(:00)?$/.test(time)) return '';
        return time.slice(0, 5);
    };
    return validateEntry({ id: value.id, date: value.date, start: wholeTime(value.start), end: wholeTime(value.end),
        deductedMinutes: value.deductedMinutes, customerCode: value.customerCode, comment: value.comment ?? '',
        serverVersion: value.version, localRevision: 0, syncState: 'synced', deleted: false });
}
