import {authenticatedFetch} from './auth.js';

export function configureInvoicingAccess(onAvailable) {
    const view = document.getElementById('invoicing-view');
    const notice = document.getElementById('invoicing-coming-soon');
    const title = document.getElementById('invoicing-availability-title');
    const message = document.getElementById('invoicing-availability-message');
    const retry = document.getElementById('invoicing-availability-retry');
    let pending = false;

    async function refreshIfOpen() {
        if (view.hidden || pending) return;
        pending = true;
        view.classList.add('invoicing-restricted');
        notice.hidden = false;
        title.textContent = 'Kontrollerar fakturering';
        message.textContent = 'Kontrollerar åtkomst …';
        try {
            const response = await authenticatedFetch('/api/subscription', {cache: 'no-store', timeoutMs: 10000});
            if (!response.ok) throw new Error();
            const subscription = await response.json();
            if (subscription.invoicingAvailable === true) {
                view.classList.remove('invoicing-restricted');
                notice.hidden = true;
                void onAvailable();
            } else if (subscription.invoicingAvailable === false) {
                title.textContent = 'Fakturering kommer snart';
                message.textContent = 'Faktureringen planeras att öppna kring årsskiftet. Du kan redan nu registrera tid och kundkoder.';
            } else {
                throw new Error();
            }
        } catch {
            title.textContent = 'Faktureringen kan inte öppnas just nu';
            message.textContent = 'Kunde inte kontrollera åtkomsten. Anslut till nätet och försök igen.';
        } finally {
            pending = false;
        }
    }

    retry.addEventListener('click', () => void refreshIfOpen());
    return {refreshIfOpen};
}
