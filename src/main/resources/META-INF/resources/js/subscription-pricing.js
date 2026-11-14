import {authenticatedFetch, rememberedAccount} from './auth.js';

const endpoint = '/api/admin/subscription/prices';
const currency = new Intl.NumberFormat('sv-SE', {style: 'currency', currency: 'SEK'});
const period = plan => plan === 'MONTHLY' ? 'månad' : 'år';

export function configureSubscriptionPricing() {
    const $ = id => document.getElementById(id);
    const navigation = $('administration-navigation');
    const view = $('administration-view');
    const form = $('subscription-price-form');
    const status = $('subscription-price-status');
    const list = $('subscription-price-list');
    const create = $('subscription-price-create');
    let allowed = false;
    let busy = false;
    let loading = false;
    let pendingDraft;
    let loadRevision = 0;

    function setAccess(value) {
        allowed = value;
        navigation.hidden = !value;
        if (!value) {
            loadRevision++;
            list.replaceChildren();
            if (!view.hidden) document.querySelector('[data-view="time"]').click();
        }
    }

    function accessHint() {
        // This controls navigation only. The API verifies both JWT and current DB roles.
        setAccess(rememberedAccount()?.roles?.includes('admin') === true);
    }

    window.addEventListener('auth-session-changed', event => {
        if (event.detail === 'ready') accessHint();
        else if (event.detail === 'expired') setAccess(false);
    });
    if (document.documentElement.dataset.sessionState === 'ready') accessHint();

    async function request(path, options = {}) {
        const response = await authenticatedFetch(path, {cache: 'no-store', timeoutMs: 30000, ...options});
        const result = await response.json().catch(() => ({}));
        if (!response.ok) {
            if (response.status === 403) {
                const notice = $('notice');
                notice.textContent = 'Administratörsbehörighet saknas. Logga in igen om din roll har ändrats.';
                notice.hidden = false;
                setAccess(false);
            }
            throw new Error(result.message || 'Prisadministrationen kunde inte nås. Försök igen.');
        }
        return result;
    }

    function element(tag, text, className) {
        const node = document.createElement(tag);
        if (text !== undefined) node.textContent = text;
        if (className) node.className = className;
        return node;
    }

    function definition(model) {
        return {id: model.id, name: model.name, plan: model.plan, amount: model.amount,
            vatPercent: model.vatPercent, vatInclusive: model.vatInclusive};
    }

    async function mutate(action) {
        if (!allowed || busy) return;
        busy = true;
        loadRevision++;
        $('subscription-price-fields').disabled = true;
        create.disabled = true;
        $('subscription-prices-refresh').disabled = true;
        list.querySelectorAll('button').forEach(button => { button.disabled = true; });
        status.textContent = 'Sparar pris …';
        try {
            await action();
            await load();
        } catch (error) {
            status.textContent = error.message || 'Servern kunde inte nås. Försök igen med samma pris.';
        } finally {
            busy = false;
            $('subscription-price-fields').disabled = false;
            create.disabled = false;
            $('subscription-prices-refresh').disabled = false;
            list.querySelectorAll('button').forEach(button => { button.disabled = false; });
        }
    }

    function render(models) {
        list.replaceChildren();
        if (!models.length) list.append(element('p', 'Inga priser har skapats ännu.'));
        for (const model of models) {
            const card = element('article', undefined, `subscription-price-card${model.active ? ' current-price' : ''}`);
            card.append(element('h4', model.name));
            card.append(element('p', `${currency.format(model.amount)} per ${period(model.plan)} · ${model.vatPercent} % moms ${model.vatInclusive ? 'ingår' : 'tillkommer'}`));
            if (model.active) card.append(element('strong', `Aktuellt pris för nya ${model.plan === 'MONTHLY' ? 'månadsköp' : 'årsköp'}`));
            else {
                const ready = model.state === 'READY';
                const button = element('button', ready ? `Använd för nya ${model.plan === 'MONTHLY' ? 'månadsköp' : 'årsköp'}` : 'Försök skapa igen', 'secondary-button');
                button.type = 'button';
                button.addEventListener('click', () => void mutate(async () => {
                    if (ready) {
                        await request(`${endpoint}/${encodeURIComponent(model.id)}/activate`, {method: 'POST'});
                        status.textContent = 'Priset är nu aktuellt för nya köp.';
                    } else {
                        await createPrice(definition(model));
                        status.textContent = 'Priset har skapats. Välj det för nya köp när du är redo.';
                    }
                }));
                card.append(button);
            }
            if (model.state === 'READY') {
                const details = element('details');
                details.append(element('summary', 'Stripe-referenser'));
                for (const value of [model.stripeProductId, model.stripePriceId, model.stripeTaxRateId])
                    details.append(element('code', value));
                card.append(details);
            } else card.append(element('p', 'Skapandet behöver slutföras innan priset kan användas.'));
            list.append(card);
        }
    }

    async function load() {
        const revision = ++loadRevision;
        const models = await request(endpoint);
        if (!Array.isArray(models)) throw new Error('Prislistan kunde inte läsas.');
        if (allowed && revision === loadRevision) render(models);
    }

    async function refreshIfOpen() {
        if (view.hidden || !allowed || busy || loading) return;
        loading = true;
        status.textContent = 'Hämtar priser …';
        try { await load(); if (!busy) status.textContent = ''; }
        catch (error) { status.textContent = error.message || 'Priserna kunde inte hämtas.'; }
        finally { loading = false; }
    }

    function preview() {
        const amount = $('subscription-price-amount').valueAsNumber;
        const vat = $('subscription-price-vat').valueAsNumber;
        const inclusive = $('subscription-price-inclusive').checked;
        $('subscription-price-total').textContent = Number.isFinite(amount) && Number.isFinite(vat)
            ? `Att betala inklusive moms: ${currency.format(inclusive ? amount : amount * (1 + vat / 100))} per ${period($('subscription-price-plan').value)}.` : '';
    }

    async function createPrice(draft) {
        return request(endpoint, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(draft)});
    }

    form.addEventListener('input', () => {
        pendingDraft = undefined;
        create.textContent = 'Skapa pris i Stripe';
        preview();
    });
    form.addEventListener('submit', event => {
        event.preventDefault();
        if (busy || !allowed) return;
        pendingDraft ??= {id: crypto.randomUUID(), name: $('subscription-price-name').value.trim(),
            plan: $('subscription-price-plan').value, amount: $('subscription-price-amount').valueAsNumber,
            vatPercent: $('subscription-price-vat').valueAsNumber, vatInclusive: $('subscription-price-inclusive').checked};
        void mutate(async () => {
            await createPrice(pendingDraft);
            pendingDraft = undefined;
            form.reset();
            preview();
            status.textContent = 'Priset har skapats. Välj det för nya köp när du är redo.';
        }).then(() => { create.textContent = pendingDraft ? 'Försök skapa pris igen' : 'Skapa pris i Stripe'; });
    });
    $('subscription-prices-refresh').addEventListener('click', () => void refreshIfOpen());
    preview();
    return {refreshIfOpen};
}
