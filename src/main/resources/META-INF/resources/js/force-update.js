document.getElementById('update').addEventListener('click', async event => {
    const button = event.currentTarget;
    const status = document.getElementById('status');
    button.disabled = true;
    status.textContent = 'Rensar appens cache …';
    try {
        const registrations = await navigator.serviceWorker?.getRegistrations() || [];
        await Promise.all(registrations.map(registration => registration.unregister()));
        const keys = await caches.keys();
        await Promise.all(keys.filter(key => key.startsWith('timereport-shell-')).map(key => caches.delete(key)));
    } catch {
        status.textContent = 'Appcachen kunde inte rensas automatiskt. Försöker ladda om ändå …';
    }
    status.textContent = 'Öppnar senaste versionen …';
    window.location.replace(`/?forceReload=${Date.now()}`);
});
