import test from 'node:test';
import assert from 'node:assert/strict';
import {recentComments} from '../../src/main/resources/META-INF/resources/js/comment-suggestions.js';

const date = '2026-10-12';
const entry = (id, day, comment, customerCode = 'ACME', start = '08:00') =>
    ({id, date: day, start, comment, customerCode});

test('returns five recent distinct comments from the preceding five calendar days', () => {
    const entries = [
        entry('today', date, 'Today'),
        entry('first', '2026-10-11', '  Planning  ', 'ACME', '09:00'),
        entry('repeat', '2026-10-10', 'Planning'),
        entry('second', '2026-10-10', 'Review'),
        entry('third', '2026-10-09', 'Testing'),
        entry('fourth', '2026-10-08', 'Design'),
        entry('fifth', '2026-10-07', 'Release'),
        entry('too-old', '2026-10-06', 'Old'),
        {...entry('deleted', '2026-10-11', 'Deleted'), deleted: true},
        entry('blank', '2026-10-11', '  '),
        entry('other', '2026-10-11', 'Other', 'OTHER')
    ];
    assert.deepEqual(recentComments(entries, date, 'ACME'), ['Planning', 'Review', 'Testing', 'Design', 'Release']);
    assert.deepEqual(recentComments(entries, date, 'ACME', '  EW '), ['Review']);
    assert.deepEqual(recentComments(entries, date, 'OTHER'), ['Other']);
    assert.deepEqual(recentComments(entries, null, 'ACME'), []);
});
