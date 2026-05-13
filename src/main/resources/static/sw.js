const CACHE_NAME = 'fasi-v1';
const urlsToCache = [
  '/calendario',
  '/js/tailwind.min.js',
  '/js/htmx.min.js',
  '/manifest.json'
];

self.addEventListener('install', event => {
  console.log('⚡ FASI PWA instalando...');
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(urlsToCache))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  console.log('✅ FASI PWA activada');
  event.waitUntil(
    caches.keys().then(cacheNames => {
      return Promise.all(
        cacheNames.map(cacheName => {
          if (cacheName !== CACHE_NAME) {
            return caches.delete(cacheName);
          }
        })
      );
    })
  );
});

self.addEventListener('fetch', event => {
  event.respondWith(
    caches.match(event.request)
      .then(response => response || fetch(event.request))
      .catch(() => caches.match('/calendario'))
  );
});
