#!/usr/bin/env bash
# ============================================================
#  FASI — Refactor header minimalista + mareas en vista día
#  Fecha: 2026-05-13
# ============================================================
set -euo pipefail

TEMPLATES="src/main/resources/templates"
BASE="$TEMPLATES/layout/base.html"
INDEX="$TEMPLATES/calendario/index.html"
DIA="$TEMPLATES/calendario/dia.html"
STAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="backups/header_refactor_$STAMP"

echo "🔧 FASI — Aplicando refactor de header minimalista"
echo "──────────────────────────────────────────────────"

# ── 0. Verificaciones previas ─────────────────────────────
for f in "$BASE" "$INDEX" "$DIA"; do
  [[ -f "$f" ]] || { echo "❌ No encuentro $f. ¿Estás en la raíz del proyecto?"; exit 1; }
done

# ── 1. Backup ─────────────────────────────────────────────
mkdir -p "$BACKUP_DIR"
cp "$BASE"  "$BACKUP_DIR/base.html.bak"
cp "$INDEX" "$BACKUP_DIR/index.html.bak"
cp "$DIA"   "$BACKUP_DIR/dia.html.bak"
echo "✅ Backup creado en $BACKUP_DIR"

# ── 2. Reescribir <header> en base.html ───────────────────
python3 <<'PYEOF'
import re, pathlib

BASE = pathlib.Path("src/main/resources/templates/layout/base.html")
html = BASE.read_text(encoding="utf-8")

# 2.1 Sustituir bloque <header>...</header>
new_header = '''  <!-- ═══════════════════════════════════════════════════════ -->
  <!-- HEADER FIJO — Minimalista                              -->
  <!-- ═══════════════════════════════════════════════════════ -->
  <header class="fixed top-0 left-0 right-0 z-50 bg-gray-900 dark:bg-gray-800
                 text-white h-14 flex items-center justify-between shadow-lg px-2">

    <!-- Logo (izquierda) — vuelve siempre al inicio -->
    <a th:href="@{/}"
       class="flex items-center justify-center w-12 h-12 text-2xl
              hover:bg-gray-700 rounded-full transition"
       title="Inicio">
      ⚡
    </a>

    <!-- Botones (derecha) -->
    <div class="flex items-center gap-1">

      <button onclick="if(typeof window.abrirModalVoz==='function') window.abrirModalVoz(); else window.location='/calendario/nuevo'"
              class="w-10 h-10 flex items-center justify-center rounded-full
                     bg-indigo-600 hover:bg-indigo-500 transition text-xl font-bold"
              title="Nuevo evento">
        +
      </button>

      <button onclick="toggleMenu()"
              class="w-10 h-10 flex items-center justify-center rounded-full
                     hover:bg-gray-700 transition text-2xl"
              title="Menú">
        ☰
      </button>

    </div>
  </header>'''

html = re.sub(
    r'<header class="fixed top-0[\s\S]*?</header>',
    new_header,
    html,
    count=1
)

# 2.2 Borrar líneas que pintan datos en el header inexistente
patterns_to_remove = [
    r"\s*document\.getElementById\('header-amanecer'\)[^\n]*\n",
    r"\s*document\.getElementById\('header-atardecer'\)[^\n]*\n",
    r"\s*document\.getElementById\('header-pleamar1'\)[^\n]*\n",
    r"\s*document\.getElementById\('header-bajamar1'\)[^\n]*\n",
    r"\s*document\.getElementById\('header-pleamar2'\)[^\n]*\n",
    r"\s*document\.getElementById\('header-bajamar2'\)[^\n]*\n",
    r"\s*document\.getElementById\('header-ciudad'\)[^\n]*\n",
]
for p in patterns_to_remove:
    html = re.sub(p, "\n", html)

# 2.3 Eliminar initFooterFecha y la línea actualizarFooterFecha
html = re.sub(
    r"\s*//\s*── Init ──+[\s\S]*?\(function initFooterFecha\(\)\s*\{[\s\S]*?\}\)\(\);\s*",
    "\n    // ── Init ─────────────────────────────────────────────────\n",
    html
)
html = re.sub(
    r"\s*window\.actualizarFooterFecha\s*=\s*cargarDatosFooter;\s*\n",
    "\n",
    html
)

BASE.write_text(html, encoding="utf-8")
print("✅ base.html actualizado")
PYEOF

# ── 3. Limpiar referencia en calendario/index.html ────────
python3 <<'PYEOF'
import re, pathlib

INDEX = pathlib.Path("src/main/resources/templates/calendario/index.html")
html = INDEX.read_text(encoding="utf-8")

# Borrar las dos líneas que actualizaban el footer del header
html = re.sub(
    r"\s*//\s*Actualizar footer\s*\n\s*var primerFecha[^\n]*\n\s*if\s*\(window\.actualizarFooterFecha\)[^\n]*\n",
    "\n",
    html
)

INDEX.write_text(html, encoding="utf-8")
print("✅ index.html actualizado")
PYEOF

# ── 4. Inyectar barra contextual en dia.html ──────────────
python3 <<'PYEOF'
import re, pathlib

DIA = pathlib.Path("src/main/resources/templates/calendario/dia.html")
html = DIA.read_text(encoding="utf-8")

bloque = '''
  <!-- ═══════════════════════════════════════════════════════ -->
  <!-- BARRA CONTEXTUAL DEL DÍA — mareas, sol, ubicación      -->
  <!-- ═══════════════════════════════════════════════════════ -->
  <div id="contexto-dia"
       class="mb-6 bg-gray-900 dark:bg-gray-800 text-white rounded-xl shadow
              overflow-x-auto scrollbar-hide">
    <div class="flex items-stretch divide-x divide-gray-700 min-w-max">

      <div class="flex flex-col items-center justify-center px-4 py-2 min-w-[80px]">
        <span class="text-base">🌅</span>
        <span class="text-xs text-amber-300 font-semibold" id="dia-amanecer">--:--</span>
        <span class="text-[10px] text-gray-400">Amanecer</span>
      </div>

      <div class="flex flex-col items-center justify-center px-4 py-2 min-w-[80px]">
        <span class="text-base">🌇</span>
        <span class="text-xs text-orange-300 font-semibold" id="dia-atardecer">--:--</span>
        <span class="text-[10px] text-gray-400">Atardecer</span>
      </div>

      <div class="flex flex-col items-center justify-center px-4 py-2 min-w-[80px]">
        <span class="text-base">🌊</span>
        <span class="text-xs text-blue-300 font-semibold" id="dia-pleamar1">--:--</span>
        <span class="text-[10px] text-gray-400">Pleamar</span>
      </div>

      <div class="flex flex-col items-center justify-center px-4 py-2 min-w-[80px]">
        <span class="text-base">🏖️</span>
        <span class="text-xs text-cyan-300 font-semibold" id="dia-bajamar1">--:--</span>
        <span class="text-[10px] text-gray-400">Bajamar</span>
      </div>

      <div class="flex flex-col items-center justify-center px-4 py-2 min-w-[80px]">
        <span class="text-base">🌊</span>
        <span class="text-xs text-blue-300 font-semibold" id="dia-pleamar2">--:--</span>
        <span class="text-[10px] text-gray-400">Pleamar 2</span>
      </div>

      <div class="flex flex-col items-center justify-center px-4 py-2 min-w-[80px]">
        <span class="text-base">🏖️</span>
        <span class="text-xs text-cyan-300 font-semibold" id="dia-bajamar2">--:--</span>
        <span class="text-[10px] text-gray-400">Bajamar 2</span>
      </div>

      <div class="flex flex-col items-center justify-center px-4 py-2 min-w-[100px]">
        <span class="text-base">📍</span>
        <span class="text-xs text-gray-300 font-semibold" id="dia-ciudad">Sanlúcar</span>
      </div>

    </div>
  </div>

  <script th:inline="javascript">
    /*<![CDATA[*/
    (async function() {
      const fechaStr = /*[[${#temporals.format(fecha, 'yyyy-MM-dd')}]]*/ '';
      try {
        const resp = await fetch('/contexto/api/datos?fecha=' + fechaStr);
        const d    = await resp.json();
        document.getElementById('dia-amanecer').textContent  = d.amanecer  || '--:--';
        document.getElementById('dia-atardecer').textContent = d.atardecer || '--:--';
        document.getElementById('dia-pleamar1').textContent  = d.pleamar1  || '--:--';
        document.getElementById('dia-bajamar1').textContent  = d.bajamar1  || '--:--';
        document.getElementById('dia-pleamar2').textContent  = d.pleamar2  || '--:--';
        document.getElementById('dia-bajamar2').textContent  = d.bajamar2  || '--:--';
        document.getElementById('dia-ciudad').textContent    = (d.ciudad || 'Sanlúcar').split(' ')[0];
      } catch(e) {
        console.warn('Contexto del día no disponible:', e.message);
      }
    })();
    /*]]>*/
  </script>
'''

# Insertar antes del comentario "<!-- Lista de eventos del día -->"
# (solo si aún no se ha insertado)
if 'id="contexto-dia"' not in html:
    html = html.replace(
        "<!-- Lista de eventos del día -->",
        bloque.strip() + "\n\n  <!-- Lista de eventos del día -->",
        1
    )
    DIA.write_text(html, encoding="utf-8")
    print("✅ dia.html actualizado")
else:
    print("ℹ️  dia.html ya tenía la barra contextual — no se toca")
PYEOF

# ── 5. Final ──────────────────────────────────────────────
echo ""
echo "──────────────────────────────────────────────────"
echo "🎉 Refactor completado"
echo ""
echo "📁 Backup en:  $BACKUP_DIR"
echo "📝 Modificados:"
echo "   · $BASE"
echo "   · $INDEX"
echo "   · $DIA"
echo ""
echo "▶️  Recarga el navegador (Spring DevTools recompila en caliente)"
echo "   o ejecuta:  ./dev-redeploy.sh"
echo "──────────────────────────────────────────────────"
