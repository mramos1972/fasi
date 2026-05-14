#!/usr/bin/env bash
# ============================================================
#  FASI — Header blanco roto + nav mes compacta + swipe móvil
#  Fecha: 2026-05-13
# ============================================================
set -euo pipefail

TEMPLATES="src/main/resources/templates"
STATIC="src/main/resources/static"
BASE="$TEMPLATES/layout/base.html"
INDEX="$TEMPLATES/calendario/index.html"
SW="$STATIC/sw.js"
STAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="backups/header_redesign_$STAMP"

echo "🎨 FASI — Aplicando rediseño de header + navegación de mes"
echo "──────────────────────────────────────────────────────────"

# ── 0. Backup ─────────────────────────────────────────────
mkdir -p "$BACKUP_DIR"
cp "$BASE"  "$BACKUP_DIR/base.html.bak"
cp "$INDEX" "$BACKUP_DIR/index.html.bak"
[[ -f "$SW" ]] && cp "$SW" "$BACKUP_DIR/sw.js.bak" || true
echo "✅ Backup en $BACKUP_DIR"

# ── 1. Header blanco roto en base.html ────────────────────
python3 <<'PYEOF'
import re, pathlib

BASE = pathlib.Path("src/main/resources/templates/layout/base.html")
html = BASE.read_text(encoding="utf-8")

NEW_HEADER = '''  <!-- ═══════════════════════════════════════════════════════ -->
  <!-- HEADER FIJO — Minimalista (modo día: blanco roto)      -->
  <!-- ═══════════════════════════════════════════════════════ -->
  <header class="fixed top-0 left-0 right-0 z-50
                 bg-stone-50 dark:bg-gray-900
                 text-gray-800 dark:text-white
                 border-b border-stone-200 dark:border-gray-700
                 h-14 flex items-center justify-between shadow-sm px-2">

    <!-- Logo (izquierda) — vuelve siempre al inicio -->
    <a th:href="@{/}"
       class="flex items-center justify-center w-12 h-12 text-2xl
              hover:bg-stone-200 dark:hover:bg-gray-700
              rounded-full transition"
       title="Inicio">
      ⚡
    </a>

    <!-- Botones (derecha) -->
    <div class="flex items-center gap-1">

      <button onclick="if(typeof window.abrirModalVoz==='function') window.abrirModalVoz(); else window.location='/calendario/nuevo'"
              class="w-10 h-10 flex items-center justify-center rounded-full
                     bg-indigo-600 hover:bg-indigo-500 text-white
                     transition text-xl font-bold"
              title="Nuevo evento">
        +
      </button>

      <button onclick="toggleMenu()"
              class="w-10 h-10 flex items-center justify-center rounded-full
                     hover:bg-stone-200 dark:hover:bg-gray-700
                     transition text-2xl"
              title="Menú">
        ☰
      </button>

    </div>
  </header>'''

# Reemplazar el primer <header>
html = re.sub(r'<header[\s\S]*?</header>', NEW_HEADER, html, count=1)

BASE.write_text(html, encoding="utf-8")
print("✅ base.html — header blanco roto aplicado")
PYEOF

# ── 2. Navegación de mes compacta + sin "Hoy" + swipe ────
python3 <<'PYEOF'
import re, pathlib

INDEX = pathlib.Path("src/main/resources/templates/calendario/index.html")
html = INDEX.read_text(encoding="utf-8")

# ── 2.1 Reemplazar bloque de navegación del mes ─────────
# Buscamos el contenedor que tiene los botones < > y "Mayo 2026" + "Hoy"
NEW_NAV = '''    <!-- ═══════════════════════════════════════════════════ -->
    <!-- NAVEGACIÓN DE MES — compacta y centrada            -->
    <!-- ═══════════════════════════════════════════════════ -->
    <div class="flex items-center justify-center gap-3 mb-4 select-none">

      <button onclick="cambiarMes(-1)"
              class="w-9 h-9 flex items-center justify-center rounded-full
                     text-gray-600 dark:text-gray-300
                     hover:bg-stone-200 dark:hover:bg-gray-700
                     transition text-xl font-bold"
              title="Mes anterior">
        ‹
      </button>

      <h2 id="titulo-mes"
          class="text-xl md:text-2xl font-bold text-gray-800 dark:text-white
                 min-w-[180px] text-center cursor-pointer hover:opacity-70 transition"
          onclick="irHoy()"
          title="Ir a hoy">
        Mayo 2026
      </h2>

      <button onclick="cambiarMes(1)"
              class="w-9 h-9 flex items-center justify-center rounded-full
                     text-gray-600 dark:text-gray-300
                     hover:bg-stone-200 dark:hover:bg-gray-700
                     transition text-xl font-bold"
              title="Mes siguiente">
        ›
      </button>

    </div>'''

# Patrón flexible: buscamos desde el primer < (botón anterior) hasta el cierre del bloque Hoy
# Identificamos por la palabra "Mayo" o el h2 con id título.
# Estrategia: buscar el div que contiene "Hoy" y el título del mes.

# Intentamos varios patrones por si la estructura difiere
patterns = [
    # Patrón 1: bloque completo con flechas, título y "Hoy"
    r'<div[^>]*class="[^"]*flex[^"]*items-center[^"]*"[^>]*>\s*<button[^>]*onclick="cambiarMes\(-1\)"[\s\S]*?Hoy[\s\S]*?</div>\s*</div>',
    # Patrón 2: si "Hoy" está en un <p> o <button> separado
    r'<div[^>]*>\s*<button[^>]*cambiarMes\(-1\)[\s\S]*?cambiarMes\(1\)[\s\S]*?</button>\s*</div>(?:\s*<[^>]+>\s*Hoy\s*</[^>]+>)?',
]

replaced = False
for pat in patterns:
    new_html, n = re.subn(pat, NEW_NAV, html, count=1)
    if n > 0:
        html = new_html
        replaced = True
        print(f"✅ Navegación de mes reemplazada (patrón funcional)")
        break

if not replaced:
    # Fallback: eliminar solo el "Hoy" y dejar nota
    html = re.sub(r'<[^>]+>\s*Hoy\s*</[^>]+>\s*', '', html, count=1)
    print("⚠️  No se encontró bloque completo de navegación.")
    print("    Se eliminó solo el 'Hoy'. Revisa manualmente si quieres la nav compacta.")

# ── 2.2 Añadir soporte de swipe táctil ──────────────────
SWIPE_SCRIPT = '''
<!-- ═══════════════════════════════════════════════════════ -->
<!-- SWIPE HORIZONTAL — cambiar de mes en móvil             -->
<!-- ═══════════════════════════════════════════════════════ -->
<script>
(function() {
  const grid = document.querySelector('#calendario-grid, .calendario-grid, #calendar-grid, main');
  if (!grid) return;

  let touchStartX = 0;
  let touchStartY = 0;
  let touchEndX   = 0;
  let touchEndY   = 0;
  const MIN_SWIPE = 60;   // píxeles mínimos para considerar swipe
  const MAX_VERT  = 80;   // tolerancia vertical (evita confundir con scroll)

  grid.addEventListener('touchstart', e => {
    touchStartX = e.changedTouches[0].screenX;
    touchStartY = e.changedTouches[0].screenY;
  }, { passive: true });

  grid.addEventListener('touchend', e => {
    touchEndX = e.changedTouches[0].screenX;
    touchEndY = e.changedTouches[0].screenY;
    handleSwipe();
  }, { passive: true });

  function handleSwipe() {
    const dx = touchEndX - touchStartX;
    const dy = Math.abs(touchEndY - touchStartY);
    if (Math.abs(dx) < MIN_SWIPE) return;
    if (dy > MAX_VERT) return;  // fue más vertical que horizontal → scroll
    if (typeof cambiarMes !== 'function') return;
    if (dx > 0) cambiarMes(-1);  // swipe derecha → mes anterior
    else        cambiarMes(1);   // swipe izquierda → mes siguiente
  }
})();
</script>'''

# Inyectar antes del </body> o al final del archivo Thymeleaf
if 'SWIPE HORIZONTAL' not in html:
    if '</body>' in html:
        html = html.replace('</body>', SWIPE_SCRIPT + '\n</body>', 1)
    else:
        # Thymeleaf fragment: añadir al final del contenido
        html = html.rstrip() + '\n' + SWIPE_SCRIPT + '\n'
    print("✅ Script de swipe añadido")
else:
    print("ℹ️  Swipe ya estaba presente — no se duplica")

INDEX.write_text(html, encoding="utf-8")
print("✅ index.html actualizado")
PYEOF

# ── 3. Bump del Service Worker (PWA) ──────────────────────
if [[ -f "$SW" ]]; then
  python3 <<'PYEOF'
import re, pathlib, datetime
sw = pathlib.Path('src/main/resources/static/sw.js')
c = sw.read_text(encoding='utf-8')
v = 'fasi-v' + datetime.datetime.now().strftime('%Y%m%d%H%M%S')
patterns = [
    (r"(const\s+CACHE_NAME\s*=\s*)['\"][^'\"]+['\"]", rf"\1'{v}'"),
    (r"(const\s+VERSION\s*=\s*)['\"][^'\"]+['\"]",    rf"\1'{v}'"),
    (r"(caches\.open\(\s*)['\"][^'\"]+['\"]",          rf"\1'{v}'"),
]
changed = False
for p, r in patterns:
    c, n = re.subn(p, r, c)
    if n: changed = True
if not changed:
    c = f"const CACHE_NAME = '{v}';\n" + c
sw.write_text(c, encoding='utf-8')
print(f"✅ Service Worker versionado: {v}")
PYEOF
fi

# ── 4. Copiar a target/ si existe ─────────────────────────
[[ -f target/classes/templates/layout/base.html ]]    && cp "$BASE"  target/classes/templates/layout/base.html
[[ -f target/classes/templates/calendario/index.html ]] && cp "$INDEX" target/classes/templates/calendario/index.html
[[ -f target/classes/static/sw.js ]]                  && cp "$SW"    target/classes/static/sw.js

echo ""
echo "──────────────────────────────────────────────────"
echo "🎉 Rediseño aplicado"
echo ""
echo "📁 Backup: $BACKUP_DIR"
echo ""
echo "🖥️  En navegador:"
echo "   · F12 → Application → Service Workers → Unregister"
echo "   · Ctrl + Shift + R"
echo ""
echo "📱 Funciona en móvil: arrastra a izquierda/derecha"
echo "   sobre el calendario para cambiar de mes."
echo "──────────────────────────────────────────────────"
