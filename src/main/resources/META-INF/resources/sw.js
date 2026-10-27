const VERSION = new URL(self.location.href).searchParams.get('v') || '1';
const CACHE = `timereport-shell-v${VERSION}`;
const SHELL = ['/', '/index.html', '/styles.css', '/js/app.js', '/js/domain.js', '/js/storage.js', '/js/api.js', '/js/auth.js', '/js/monthly-report.js', '/js/report.js', '/js/invoicing-settings.js',
    '/js/invoice-delivery.js', '/manifest.json', '/icons/icon.svg', '/icons/icon-192.png', '/icons/icon-512.png', '/icons/apple-touch-icon.png'];

self.addEventListener('install', event => {
    event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(
        SHELL.map(path => new Request(path, {cache: 'reload'})))));
});

self.addEventListener('activate', event => {
    event.waitUntil((async () => {
        for (const key of await caches.keys()) {
            if (key.startsWith('timereport-shell-') && key !== CACHE) await caches.delete(key);
        }
        await self.clients.claim();
    })());
});

// Refresh HTML and its scripts/styles together when online. A fresh HTML version
// must not keep loading older modules from the service worker's shell cache.
async function networkFirst(request, cacheKey) {
    const cache = await caches.open(CACHE);
    try {
        const response = await fetch(request, {cache: 'no-store'});
        if (response.ok) {
            await cache.put(cacheKey, response.clone());
            return response;
        }
        return await cache.match(cacheKey) || response;
    } catch {
        return await cache.match(cacheKey) || Response.error();
    }
}

self.addEventListener('fetch', event => {
    const url = new URL(event.request.url);
    if (event.request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;
    if (event.request.mode === 'navigate' && (url.pathname === '/' || url.pathname === '/index.html')) {
        event.respondWith(networkFirst(event.request, '/index.html'));
    } else if (SHELL.includes(url.pathname)) {
        event.respondWith(networkFirst(event.request, url.pathname));
    }
});
