import {APP_VERSION} from './version.js';

const $ = id => document.getElementById(id);

export function configureAppShell() {
    const applicationVersion = APP_VERSION;
    document.querySelector('meta[name="application-version"]').content = APP_VERSION;
    document.querySelector('link[rel="stylesheet"]').href = `/styles.css?v=${encodeURIComponent(APP_VERSION)}`;
    const versionLabel = document.getElementById('app-version-label');
    if (versionLabel && applicationVersion) versionLabel.textContent = `(v ${applicationVersion})`;
    document.getElementById('force-reload')?.addEventListener('click', () => {
        if (!navigator.onLine) {
            const readiness = document.getElementById('offline-readiness');
            if (readiness) readiness.textContent = 'Anslut till nätet innan du hämtar en ny appversion.';
            else window.alert('Anslut till nätet innan du hämtar en ny appversion.');
            return;
        }
        window.location.assign('/force-update.html');
    });
}

export async function prepareOffline() {
    const reloadButton = $('force-reload');
    if (!window.isSecureContext || !('serviceWorker' in navigator)) {
        $('offline-readiness').textContent = 'Offlineöppning kräver HTTPS eller localhost och stöd för service workers.';
        return;
    }
    try {
        const version = APP_VERSION;
        const registration = await navigator.serviceWorker.register(`/sw.js?v=${encodeURIComponent(version)}`, {updateViaCache: 'none'});
        const showWaitingUpdate = () => {
            if (registration.waiting && navigator.serviceWorker.controller) {
                reloadButton.textContent = '↻';
                reloadButton.setAttribute('aria-label', `Uppdatera appen till version ${version}`);
                reloadButton.title = `Uppdatera appen till version ${version}`;
                $('offline-readiness').textContent = 'En ny appversion är redo. Dina sparade tidsregistreringar lämnas kvar.';
            }
        };
        const watchInstall = () => {
            const worker = registration.installing;
            if (!worker) return;
            worker.addEventListener('statechange', () => {
                if (worker.state === 'installed') showWaitingUpdate();
            });
        };
        registration.addEventListener('updatefound', watchInstall);
        watchInstall();
        await navigator.serviceWorker.ready;
        $('offline-readiness').textContent = 'Redo utan nät. Du kan nu stänga och öppna appen offline.';
        showWaitingUpdate();
        void registration.update().catch(() => {
        });
    } catch {
        $('offline-readiness').textContent = 'Appen är inte klar för offlineöppning. Öppna sidan igen när du har anslutning.';
    }
}


export function configureNavigation(onSelect) {
    function showView(view) {
        document.querySelectorAll('[data-view-panel]').forEach(panel => {
            panel.hidden = panel.dataset.viewPanel !== view;
        });
        document.querySelectorAll('.main-navigation [data-view]').forEach(button => {
            if (button.dataset.view === view) button.setAttribute('aria-current', 'page');
            else button.removeAttribute('aria-current');
        });
        onSelect(view);
    }

    document.querySelectorAll('.main-navigation [data-view]').forEach(button => {
        button.addEventListener('click', () => showView(button.dataset.view));
    });

}
