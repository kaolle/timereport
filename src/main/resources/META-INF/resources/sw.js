const VERSION = new URL(self.location.href).searchParams.get('v') || '1';
const CACHE = `timereport-shell-v${VERSION}`;
const SHELL = ['/', '/index.html', '/styles.css', '/js/version.js', '/js/synchronization.js', '/js/app-shell.js', '/js/connection-status.js', '/js/customer-codes.js', '/js/comment-suggestions.js', '/js/invoice-draft.js', '/js/app.js', '/js/domain.js', '/js/storage.js', '/js/api.js', '/js/auth.js', '/js/monthly-report.js', '/js/report.js', '/js/invoicing-settings.js',
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

// Prefer fresh assets, but a slow server must not block an already cached app.
// Let the network request finish in the background to refresh the next opening.
async function networkFirst(request, cacheKey, event) {
    const cache = await caches.open(CACHE);
    const cached = await cache.match(cacheKey);
    const network = fetch(request, {cache: 'no-store'}).then(async response => {
        if (response.ok) {
            await cache.put(cacheKey, response.clone()).catch(() => {});
            return response;
        }
        return cached || response;
    }).catch(() => cached || Response.error());
    event.waitUntil(network.then(() => {}));
    // An explicitly newer asset must never fall back to an older cached version.
    const requestedVersion = new URL(request.url).searchParams.get('v');
    if (!cached || requestedVersion && requestedVersion !== VERSION) return network;
    let timer;
    try {
        return await Promise.race([network, new Promise(resolve => {
            timer = setTimeout(() => resolve(cached), 750);
        })]);
    } finally {
        clearTimeout(timer);
    }
}

self.addEventListener('fetch', event => {
    const url = new URL(event.request.url);
    if (event.request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;
    if (event.request.mode === 'navigate' && (url.pathname === '/' || url.pathname === '/index.html')) {
        event.respondWith(networkFirst(event.request, '/index.html', event));
    } else if (SHELL.includes(url.pathname)) {
        event.respondWith(networkFirst(event.request, url.pathname, event));
    }
});
