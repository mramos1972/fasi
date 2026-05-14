#!/usr/bin/env bash
# ============================================================
#  FASI — Fix de caché del Service Worker (sync móvil/PC)
# ============================================================
set -euo pipefail

SW="src/main/resources/static/sw.js"
TARGET_SW="target/classes/static/sw.js"
STAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="backups/sw_cache_fix_$STAMP"

mkdir -p "$BACKUP_DIR"
[[ -f "$SW" ]] && cp "$SW" "$BACKUP_DIR/sw.js.bak"

echo "🔧 Aplicando fix de Service Worker — estrategia network-first para HTML"

cat > "$SW" <<'JS'
// ════════════════════════════════════════════════════════════
//  FASI Service Worker — Network-first para HTML, cache-first para estáticos
//  Versión: ${VERSION}
// ════════════════════════════════════════════════════════════

const VERSION    = '${VERSION}';
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
JS

# Sustituir el placeholder de versión por la fecha actual
sed -i "s|\${VERSION}|$STAMP|g" "$SW"

echo "✅ Service Worker reescrito con versión: $STAMP"

# Copiar a target/ si existe (para no recompilar)
if [[ -f "$TARGET_SW" ]]; then
  cp "$SW" "$TARGET_SW"
  echo "✅ Copiado a target/classes/static/sw.js"
fi

echo ""
echo "────────────────────────────────────────────────────"
echo "📋 En el navegador del PC:"
echo "   1. F12 → Application → Service Workers → Unregister"
echo "   2. Ctrl + Shift + R"
echo "   (Solo esta vez, luego ya no hará falta)"
echo "────────────────────────────────────────────────────"
