const CACHE_NAME = 'geldmeister-shell-v2';
const APP_FILES = ['./', './index.html', './styles.css', './app.js', './manifest.webmanifest', './icon.svg'];

self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);
    const files = APP_FILES.map((file) => new URL(file, self.registration.scope).href);
    await cache.addAll(files);
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter((key) => key.startsWith('geldmeister-shell-') && key !== CACHE_NAME).map((key) => caches.delete(key)));
    await self.clients.claim();
  })());
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;

  event.respondWith((async () => {
    const cached = await caches.match(request);
    if (cached) return cached;

    try {
      const response = await fetch(request);
      if (request.mode === 'navigate' && response.ok) {
        const cache = await caches.open(CACHE_NAME);
        await cache.put(new URL('./index.html', self.registration.scope).href, response.clone());
      }
      return response;
    } catch {
      if (request.mode === 'navigate') {
        return caches.match(new URL('./index.html', self.registration.scope).href);
      }
      throw new Error('Offline asset unavailable');
    }
  })());
});
