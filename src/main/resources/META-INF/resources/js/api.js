import {authenticatedFetch} from './auth.js';

export class ApiError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

async function jsonRequest(path, options = {}) {
    const response = await authenticatedFetch(path, {cache: 'no-store', signal: AbortSignal.timeout(8000), ...options});
    if (response.status === 204) return undefined;
    let body;
    try { body = await response.json(); } catch { body = null; }
    if (!response.ok) throw new ApiError(response.status, body?.message
        || (response.status === 401 || response.status === 403
            ? 'Du behöver logga in igen. Dina lokala tider finns kvar.'
            : `Servern kunde inte hantera begäran (${response.status}). Försök igen senare.`));
    return body;
}

export const fetchCodes = () => jsonRequest('/api/customer-codes');
export const fetchBillingCodeLabels = () => jsonRequest('/api/invoicing/customer-code-labels');
export const fetchDay = date => jsonRequest(`/api/time-entries?from=${encodeURIComponent(date)}&to=${encodeURIComponent(date)}`);
export const fetchRange = (from, to) => jsonRequest(`/api/time-entries?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
export const fetchVacationDays = (from, to) => jsonRequest(`/api/vacation-days?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
export const setVacationDayRemote = (date, vacation) => jsonRequest(`/api/vacation-days/${encodeURIComponent(date)}`, { method: vacation ? 'PUT' : 'DELETE' });
export const fetchTimeEntry = id => jsonRequest(`/api/time-entries/${encodeURIComponent(id)}`);
export const uploadTimeEntry = (entry, expectedVersion) => jsonRequest(`/api/time-entries/${encodeURIComponent(entry.id)}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ date: entry.date, start: entry.start, end: entry.end,
        deductedMinutes: entry.deductedMinutes, customerCode: entry.customerCode,
        comment: entry.comment, expectedVersion })
});
export const removeTimeEntry = (id, expectedVersion) => jsonRequest(
    `/api/time-entries/${encodeURIComponent(id)}?expectedVersion=${encodeURIComponent(expectedVersion)}`, { method: 'DELETE' });
export const saveCode = (code, active, expectedVersion) => jsonRequest(`/api/customer-codes/${encodeURIComponent(code)}`, {
    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ active, expectedVersion })
});
export const createCode = code => saveCode(code, true, 0);
