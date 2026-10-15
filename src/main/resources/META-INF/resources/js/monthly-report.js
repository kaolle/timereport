import {shiftDate, validDate, workedMinutes} from './domain.js';

export function monthBounds(month) {
    if (typeof month !== 'string' || !/^\d{4}-(0[1-9]|1[0-2])$/.test(month) || !validDate(`${month}-01`)) {
        throw new Error('Välj en giltig månad.');
    }
    const last = new Date(`${month}-01T12:00:00Z`);
    last.setUTCMonth(last.getUTCMonth() + 1);
    last.setUTCDate(0);
    return {from: `${month}-01`, to: last.toISOString().slice(0, 10)};
}

export function buildMonthlyReport(month, entries) {
    const {from, to} = monthBounds(month);
    const weekday = new Date(`${from}T12:00:00Z`).getUTCDay();
    const firstMonday = shiftDate(from, -((weekday + 6) % 7));
    const count = Math.floor((new Date(`${to}T12:00:00Z`) - new Date(`${firstMonday}T12:00:00Z`)) / (7 * 86400000)) + 1;
    const weeks = [];
    for (let offset = 0; offset < count; offset++) {
        const weekStart = shiftDate(firstMonday, offset * 7);
        const weekEnd = shiftDate(weekStart, 6);
        const thursday = new Date(`${shiftDate(weekStart, 3)}T12:00:00Z`);
        const weekYear = thursday.getUTCFullYear();
        const january = new Date(`${String(weekYear).padStart(4, '0')}-01-01T12:00:00Z`);
        const weekNumber = Math.ceil(((thursday - january) / 86400000 + 1) / 7);
        const start = weekStart < from ? from : weekStart;
        const end = weekEnd > to ? to : weekEnd;
        const groups = new Map();
        for (const entry of entries) {
            if (entry.deleted || entry.date < start || entry.date > end) continue;
            const code = entry.customerCode?.trim() || null;
            groups.set(code, (groups.get(code) || 0) + workedMinutes(entry));
        }
        const customers = [...groups].sort(([a], [b]) => a === b ? 0 : a === null ? 1 : b === null ? -1 : a.localeCompare(b, 'sv'))
            .map(([customerCode, workedMinutes]) => ({customerCode, workedMinutes}));
        weeks.push({
            weekStart, weekNumber, weekYear, from: start, to: end, customers,
            workedMinutes: customers.reduce((sum, row) => sum + row.workedMinutes, 0)
        });
    }
    return {month, from, to, weeks, workedMinutes: weeks.reduce((sum, week) => sum + week.workedMinutes, 0)};
}
