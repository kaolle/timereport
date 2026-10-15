import test from 'node:test';
import assert from 'node:assert/strict';
import {buildMonthlyReport, monthBounds} from '../../src/main/resources/META-INF/resources/js/monthly-report.js';

const entry = (date, customerCode, deductedMinutes = 30) => ({
    date,
    customerCode,
    start: '08:00',
    end: '17:00',
    deductedMinutes
});

test('month bounds validate input and handle leap years', () => {
    assert.deepEqual(monthBounds('2028-02'), {from: '2028-02-01', to: '2028-02-29'});
    assert.deepEqual(monthBounds('2026-02'), {from: '2026-02-01', to: '2026-02-28'});
    for (const month of [null, '', '2026-13', '2026-2', '2026-02-01']) assert.throws(() => monthBounds(month));
});

test('groups codes per ISO week and excludes adjacent months and deleted entries', () => {
    const report = buildMonthlyReport('2026-10', [entry('2026-09-30', 'A'), entry('2026-11-01', 'A'),
        entry('2026-10-01', 'A'), entry('2026-10-02', 'A', 15), entry('2026-10-03', 'B', 0),
        entry('2026-10-04', null), entry('2026-10-05', 'A'), {...entry('2026-10-06', 'A'), deleted: true}]);
    assert.equal(report.weeks.length, 5);
    assert.equal(report.weeks[0].from, '2026-10-01');
    assert.equal(report.weeks[0].to, '2026-10-04');
    assert.equal(report.weeks[4].to, '2026-10-31');
    assert.deepEqual(report.weeks[0].customers, [
        {customerCode: 'A', workedMinutes: 1035}, {customerCode: 'B', workedMinutes: 540}, {
            customerCode: null,
            workedMinutes: 510
        }]);
    assert.equal(report.workedMinutes, 2595);
    assert.equal(report.workedMinutes, report.weeks.reduce((sum, week) => sum + week.workedMinutes, 0));
});

test('weeks follow ISO week year across New Year and show zero for empty weeks', () => {
    const report = buildMonthlyReport('2021-01', []);
    assert.equal(report.weeks[0].weekStart, '2020-12-28');
    assert.equal(report.weeks[0].weekNumber, 53);
    assert.equal(report.weeks[0].weekYear, 2020);
    assert.equal(report.weeks[1].weekNumber, 1);
    assert.equal(report.workedMinutes, 0);
    assert.ok(report.weeks.every(week => week.workedMinutes === 0));
});

test('null, missing and blank codes group together and zero net time is valid', () => {
    const report = buildMonthlyReport('2026-10', [entry('2026-10-01', null), entry('2026-10-02', undefined),
        entry('2026-10-03', ''), entry('2026-10-04', 'A', 540)]);
    assert.deepEqual(report.weeks[0].customers, [
        {customerCode: 'A', workedMinutes: 0}, {customerCode: null, workedMinutes: 1530}]);
});
