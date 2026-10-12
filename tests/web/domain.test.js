import test from 'node:test';
import assert from 'node:assert/strict';
import { validateEntry, workedMinutes, formatMinutes, validDate, shiftDate, remoteEntry } from '../../src/main/resources/META-INF/resources/js/domain.js';

const entry = { id: 'one', date: '2026-10-10', start: '08:00', end: '17:00', deductedMinutes: 30, customerCode: 'FAIR1234', comment: '' };

test('calculates whole minutes, including zero and custom lunch', () => {
    for (const [deductedMinutes, expected] of [[0, 540], [15, 525], [30, 510], [60, 480], [540, 0]]) {
        assert.equal(workedMinutes(validateEntry({ ...entry, deductedMinutes })), expected);
    }
    assert.equal(formatMinutes(510), '8 h 30 min');
    assert.equal(formatMinutes(525), '8 h 45 min');
    assert.equal(formatMinutes(-1), '—');
});

test('rejects invalid dates, seconds, overnight intervals, deductions and codes', () => {
    for (const change of [{ date: '2026-02-30' }, { start: '08:00:01' }, { end: '07:00' },
        { end: '08:00' }, { deductedMinutes: -1 }, { deductedMinutes: 0.5 }, { deductedMinutes: 541 }, { customerCode: '' }]) {
        assert.throws(() => validateEntry({ ...entry, ...change }));
    }
    assert.equal(validDate('2028-02-29'), true);
    assert.equal(validDate('2026-02-29'), false);
    assert.equal(shiftDate('2026-03-29', 1), '2026-03-30');
    assert.equal(shiftDate('2026-01-01', -1), '2025-12-31');
});

test('rejects overlap across codes, allows touching intervals, edits and deleted entries', () => {
    const other = { ...entry, id: 'other', customerCode: 'OTHER' };
    assert.throws(() => validateEntry(entry, [other]), /överlappar/);
    assert.doesNotThrow(() => validateEntry(entry, [{ ...other, deleted: true }]));
    assert.doesNotThrow(() => validateEntry(entry, [entry]));
    assert.doesNotThrow(() => validateEntry(entry, [{ ...other, start: '17:00', end: '18:00' }]));
});

test('normalizes REST times but rejects non-minute precision and invalid versions', () => {
    assert.equal(remoteEntry({ ...entry, start: '08:00:00', end: '17:00:00', version: 1 }).start, '08:00');
    assert.throws(() => remoteEntry({ ...entry, start: '08:00:01', version: 1 }));
    assert.throws(() => remoteEntry({ ...entry, version: 0 }));
});
