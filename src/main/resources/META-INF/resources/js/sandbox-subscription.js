import {authenticatedFetch} from './auth.js';

export function configureSandboxSubscription() {
    const view = document.getElementById('account-view');
    const panel = document.getElementById('subscription-sandbox');
    const status = document.getElementById('subscription-sandbox-status');
    const monthly = document.getElementById('subscription-buy-monthly');
    const yearly = document.getElementById('subscription-buy-yearly');
    let pending = false;

    async function refreshIfOpen() {
        if (view.hidden || pending) return;
        panel.hidden = true;
        status.textContent = '';
        try {
            const response = await authenticatedFetch('/api/subscription', {cache: 'no-store', timeoutMs: 10000});
            if (!response.ok) return;
            panel.hidden = (await response.json()).sandboxCheckoutAvailable !== true;
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
            monthly.disabled = yearly.disabled = false;
            pending = false;
        }
        if (destination) {
            status.textContent = '';
            window.location.assign(destination);
        }
    }

    monthly.addEventListener('click', () => void checkout('MONTHLY'));
    yearly.addEventListener('click', () => void checkout('YEARLY'));
    return {refreshIfOpen};
}
