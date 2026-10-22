const CACHE = 'timereport-shell-v33-direct-registration-option';
const SHELL = ['/', '/index.html', '/styles.css', '/js/app.js', '/js/domain.js', '/js/storage.js', '/js/api.js', '/js/auth.js', '/js/monthly-report.js', '/js/report.js',
    '/manifest.json', '/icons/icon.svg', '/icons/icon-192.png', '/icons/icon-512.png', '/icons/apple-touch-icon.png'];

self.addEventListener('install', event => {
    event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(SHELL)));
});

self.addEventListener('activate', event => {
    event.waitUntil((async () => {
        for (const key of await caches.keys()) {
            if (key.startsWith('timereport-shell-') && key !== CACHE) await caches.delete(key);
        }
        await self.clients.claim();
    })());
});

self.addEventListener('fetch', event => {
    const url = new URL(event.request.url);
    if (event.request.method !== 'GET' || url.origin !== self.location.origin || url.pathname.startsWith('/api/')) return;
    if (event.request.mode === 'navigate' && (url.pathname === '/' || url.pathname === '/index.html')) {
        event.respondWith(caches.open(CACHE).then(cache => cache.match('/index.html')));
    } else if (SHELL.includes(url.pathname)) {
        event.respondWith(caches.open(CACHE).then(async cache => (await cache.match(url.pathname)) || fetch(event.request)));
    }
});
