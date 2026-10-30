import {shiftDate} from './domain.js';

export function recentComments(entries, date, customerCode, query = '') {
    if (!date) return [];
    const from = shiftDate(date, -5);
    const search = query.trim().toLocaleLowerCase('sv');
    const seen = new Set();
    return entries.filter(entry => !entry.deleted && entry.date >= from && entry.date < date
            && (!customerCode || entry.customerCode === customerCode)
            && typeof entry.comment === 'string' && entry.comment.trim())
        .sort((first, second) => second.date.localeCompare(first.date)
            || second.start.localeCompare(first.start) || second.id.localeCompare(first.id))
        .map(entry => entry.comment.trim())
        .filter(comment => {
            if (seen.has(comment) || !comment.toLocaleLowerCase('sv').includes(search)) return false;
            seen.add(comment);
            return true;
        })
        .slice(0, 5);
}

export function configureCommentSuggestions({getEntries, getDate, getCustomerCode}) {
    const textarea = document.getElementById('comment');
    const list = document.getElementById('comment-suggestions');
    const field = list.parentElement;

    function close() {
        list.hidden = true;
        textarea.setAttribute('aria-expanded', 'false');
    }

    function render() {
        if (!field.contains(document.activeElement)) return close();
        const comments = recentComments(getEntries(), getDate(), getCustomerCode(), textarea.value);
        list.replaceChildren();
        for (const comment of comments) {
            const item = document.createElement('li');
            const button = document.createElement('button');
            button.type = 'button';
            button.textContent = comment;
            button.addEventListener('click', () => {
                textarea.value = comment;
                textarea.dispatchEvent(new Event('input', {bubbles: true}));
                textarea.focus({preventScroll: true});
                textarea.setSelectionRange(comment.length, comment.length);
                close();
            });
            item.append(button);
            list.append(item);
        }
        list.hidden = comments.length === 0;
        textarea.setAttribute('aria-expanded', String(comments.length > 0));
    }

    textarea.addEventListener('focus', render);
    textarea.addEventListener('input', render);
    textarea.addEventListener('keydown', event => {
        if (event.key === 'Escape') close();
        if (event.key === 'ArrowDown' && !list.hidden) {
            event.preventDefault();
            list.querySelector('button')?.focus();
        }
    });
    field.addEventListener('focusout', event => {
        if (!field.contains(event.relatedTarget)) close();
    });
    document.getElementById('customer-code').addEventListener('input', render);
    return {render, close};
}
