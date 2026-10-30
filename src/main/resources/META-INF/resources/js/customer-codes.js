import {cacheBillingCodeLabels, cacheCodes, cacheCustomerCode} from './storage.js';
import {createCode, fetchBillingCodeLabels, fetchCodes, saveCode} from './api.js';

const $ = id => document.getElementById(id);

function message(id, text) {
    $(id).textContent = text;
    $(id).hidden = !text;
}

function element(tag, className, text) {
    const node = document.createElement(tag);
    node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

export function configureCustomerCodes({
                                           getData, getEditing, isNewCustomerCode,
                                           onLocalChange, onStateChange, onConnected, notifyChanges
                                       }) {
    let renderedCodes;
    let codesBusy = false;
    let codesRequestRevision = 0;
    let billingLabelsBusy = false;
    let lastBillingLabelsRefreshAt = 0;

    function renderCodes() {
        const data = getData();
        const editing = getEditing();
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
            await onLocalChange();
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
            await onLocalChange();
        } catch {
            // Cached labels remain available when billing settings cannot be reached.
        } finally {
            billingLabelsBusy = false;
        }
    }

    async function setCodeActive(code, active) {
        const data = getData();
        if (codesBusy || !navigator.onLine) return;
        if (!active && data.entries.some(entry => !entry.deleted && entry.customerCode === code.code && entry.syncState !== 'synced')) {
            message('code-error', 'Vänta tills tider med kundkoden har synkats innan du tar bort den.');
            return;
        }
        if (!active && !window.confirm(`Ta bort kundkod ”${code.code}”? Tidigare tidsregistreringar och rapporter behåller koden.`)) return;
        codesBusy = true;
        ++codesRequestRevision;
        message('code-error', '');
        onStateChange();
        try {
            await cacheCustomerCode(await saveCode(code.code, active, code.version));
            await onLocalChange();
            notifyChanges();
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
            onStateChange();
        }
    }

    $('code-form').addEventListener('submit', async event => {
        event.preventDefault();
        message('code-error', '');
        if (codesBusy || !navigator.onLine) return;
        const code = $('new-code').value.trim();
        if (!code) {
            message('code-error', 'Ange en kundkod.');
            return;
        }
        codesBusy = true;
        ++codesRequestRevision;
        onStateChange();
        try {
            await cacheCustomerCode(await createCode(code));
            await onLocalChange();
            notifyChanges();
            $('new-code').value = '';
            onConnected();
            message('notice', 'Kundkoden är sparad på servern och tillgänglig offline.');
        } catch (error) {
            message('code-error', error.status === 409 ? 'Kundkoden finns redan. Om den är borttagen kan du återställa den under Borttagna kundkoder.' : error.message || 'Kundkoden kunde inte sparas.');
        } finally {
            codesBusy = false;
            onStateChange();
        }
    });
    window.addEventListener('billing-labels-updated', () => void refreshBillingLabels(true));
    window.addEventListener('billing-customer-codes-opened', () => void refreshCodes());

    return {
        renderCodes, refreshCodes, refreshBillingLabels, get busy() {
            return codesBusy;
        }
    };
}
