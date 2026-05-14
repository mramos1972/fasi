// ════════════════════════════════════════════════════════════
//  FASI Service Worker — Network-first para HTML, cache-first para estáticos
//  Versión: 20260514_170228
// ════════════════════════════════════════════════════════════

const VERSION    = '20260514_170228';
const CACHE_NAME = 'fasi-' + VERSION;
const STATIC_ASSETS = [
  '/manifest.json',
  '/images/icon-192.png',
  '/images/icon-512.png'
];

// ── Instalación: pre-cachear estáticos ──────────────────────
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then(cache => cache.addAll(STATIC_ASSETS).catch(() => {}))
      .then(() => self.skipWaiting())   // activar inmediatamente
  );
});

// ── Activación: limpiar cachés viejas ───────────────────────
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))
      ))
      .then(() => self.clients.claim())  // tomar control de páginas abiertas
  );
});

// ── Fetch: estrategia híbrida según tipo de recurso ─────────
self.addEventListener('fetch', event => {
  const req = event.request;
  const url = new URL(req.url);

  // No interceptar peticiones que no son GET
  if (req.method !== 'GET') return;

  // No cachear APIs (siempre directo al servidor)
  if (url.pathname.startsWith('/calendario/api/')
   || url.pathname.startsWith('/ia/api/')
   || url.pathname.startsWith('/contexto/api/')
   || url.pathname.startsWith('/login')
   || url.pathname.startsWith('/logout')) {
    return;  // dejar que el navegador haga su petición normal
  }

  // ── HTML y navegación: network-first (siempre fresco) ────
  if (req.mode === 'navigate'
   || req.headers.get('accept')?.includes('text/html')) {
    event.respondWith(
      fetch(req)
        .then(resp => {
          // Guardar copia en caché por si luego hay offline
          const copy = resp.clone();
          caches.open(CACHE_NAME).then(c => c.put(req, copy));
          return resp;
        })
        .catch(() => caches.match(req))   // fallback offline
    );
    return;
  }

  // ── Estáticos (CSS, JS, imágenes): cache-first ───────────
  event.respondWith(
    caches.match(req).then(cached => {
      if (cached) return cached;
      return fetch(req).then(resp => {
        const copy = resp.clone();
        if (resp.status === 200) {
          caches.open(CACHE_NAME).then(c => c.put(req, copy));
        }
        return resp;
      });
    })
  );
});

// ── Permitir que la app fuerce un skipWaiting desde JS ──────
self.addEventListener('message', event => {
  if (event.data === 'SKIP_WAITING') self.skipWaiting();
});
