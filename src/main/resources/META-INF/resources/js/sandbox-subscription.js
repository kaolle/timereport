import {authenticatedFetch} from './auth.js';

export function configureSandboxSubscription() {
    const view = document.getElementById('account-view');
    const panel = document.getElementById('subscription-sandbox');
    const status = document.getElementById('subscription-sandbox-status');
    const monthly = document.getElementById('subscription-buy-monthly');
    const yearly = document.getElementById('subscription-buy-yearly');
    const manage = document.getElementById('subscription-manage');
    const refreshButton = document.getElementById('subscription-status-refresh');
    let pending = false;
    let activePurchase = false;

    function date(value) {
        return new Intl.DateTimeFormat('sv-SE', {dateStyle: 'long'}).format(new Date(value));
    }

    function render(subscription) {
        const state = subscription.status || 'NONE';
        activePurchase = ['PENDING', 'ACTIVE', 'PAST_DUE'].includes(state)
            || Boolean(subscription.paidUntil && new Date(subscription.paidUntil).getTime() > Date.now());
        monthly.disabled = yearly.disabled = activePurchase;
        manage.hidden = state === 'NONE';
        if (state === 'PAST_DUE') {
            status.textContent = subscription.graceUntil
                ? `Förnyelsen är inte betald. Betala senast ${date(subscription.graceUntil)} för att behålla abonnemanget.`
                : 'Förnyelsen är inte betald. Öppna Hantera abonnemang för att kontrollera betalningen.';
        } else if (state === 'PENDING') {
            status.textContent = 'Betalningen behandlas. Uppdatera status om en stund.';
        } else if (subscription.paidUntil && ['ACTIVE', 'CANCELED'].includes(state)) {
            status.textContent = `${subscription.cancelAtPeriodEnd || state === 'CANCELED' ? 'Abonnemanget är uppsagt. Betalt' : 'Abonnemanget är betalt'} till ${date(subscription.paidUntil)}.`;
        } else if (state === 'CANCELED') {
            status.textContent = 'Abonnemanget är avslutat. Du kan starta ett nytt abonnemang.';
        } else {
            status.textContent = '';
        }
    }

    async function refreshIfOpen() {
        if (view.hidden || pending) return;
        panel.hidden = true;
        status.textContent = '';
        try {
            const response = await authenticatedFetch('/api/subscription', {cache: 'no-store', timeoutMs: 10000});
            if (!response.ok) return;
            const subscription = await response.json();
            panel.hidden = subscription.sandboxCheckoutAvailable !== true;
            if (!panel.hidden) render(subscription);
        } catch {
            // Keep purchase controls hidden when account access cannot be checked.
        }
    }

    async function checkout(plan) {
        if (pending || panel.hidden) return;
        pending = true;
        monthly.disabled = yearly.disabled = true;
        status.textContent = 'Öppnar Stripe Checkout …';
        let destination;
        try {
            const response = await authenticatedFetch('/api/subscription/checkout', {
                method: 'POST', cache: 'no-store', timeoutMs: 30000,
                headers: {'Content-Type': 'application/json'}, body: JSON.stringify({plan})
            });
            const result = await response.json().catch(() => ({}));
            if (!response.ok) throw new Error(result.message || 'Köpet kunde inte startas.');
            const url = new URL(result.url);
            if (url.protocol !== 'https:' || url.hostname !== 'checkout.stripe.com')
                throw new Error('Stripe returnerade en ogiltig köplänk.');
            destination = url.href;
        } catch (error) {
            status.textContent = error.message || 'Stripe kunde inte nås. Försök igen.';
        } finally {
            // Navigation may preserve this page in the back/forward cache.
            // A return from Checkout must not restore permanently locked controls.
            monthly.disabled = yearly.disabled = activePurchase;
            pending = false;
        }
        if (destination) {
            status.textContent = '';
            window.location.assign(destination);
        }
    }

    async function openPortal() {
        if (pending || panel.hidden || manage.hidden) return;
        pending = true;
        manage.disabled = true;
        status.textContent = 'Öppnar Stripe kundportal …';
        let destination;
        try {
            const response = await authenticatedFetch('/api/subscription/portal', {
                method: 'POST', cache: 'no-store', timeoutMs: 30000
            });
            const result = await response.json().catch(() => ({}));
            if (!response.ok) throw new Error(result.message || 'Kundportalen kunde inte öppnas.');
            const url = new URL(result.url);
            if (url.protocol !== 'https:' || url.hostname !== 'billing.stripe.com')
                throw new Error('Stripe returnerade en ogiltig portallänk.');
            destination = url.href;
        } catch (error) {
            status.textContent = error.message || 'Kundportalen kunde inte öppnas.';
        } finally {
            pending = false;
            manage.disabled = false;
        }
        if (destination) window.location.assign(destination);
    }

    monthly.addEventListener('click', () => void checkout('MONTHLY'));
    yearly.addEventListener('click', () => void checkout('YEARLY'));
    manage.addEventListener('click', () => void openPortal());
    refreshButton.addEventListener('click', () => void refreshIfOpen());
    window.addEventListener('focus', () => void refreshIfOpen());
    return {refreshIfOpen};
}
