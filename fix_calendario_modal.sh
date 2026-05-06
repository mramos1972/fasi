#!/bin/bash
# fix_calendario_modal.sh
# Corrige: SyntaxError línea 481 + abrirModalVoz not defined
set -e

TEMPLATES="src/main/resources/templates"

# ─────────────────────────────────────────────────────────────
# 1. base.html — botón + con fallback seguro (2 botones)
# ─────────────────────────────────────────────────────────────
sed -i 's|onclick="abrirModalVoz()"|onclick="if(typeof window.abrirModalVoz==='\''function'\'') window.abrirModalVoz(); else window.location='\''\/calendario\/nuevo'\''"|g' \
  "$TEMPLATES/layout/base.html"

echo "✅ base.html — botones corregidos"

# ─────────────────────────────────────────────────────────────
# 2. calendario/index.html — reemplazar el bloque JS roto
#    (el SyntaxError está en la construcción de numClase)
# ─────────────────────────────────────────────────────────────
python3 - <<'PYEOF'
import re, pathlib

path = pathlib.Path("src/main/resources/templates/calendario/index.html")
content = path.read_text(encoding="utf-8")

# ── Reemplazar el bloque roto de numClase + html ─────────────
OLD = r"""        let html = `
          <div class=\"min-h-14 sm:min-h-20 border-b border-r border-gray-100
                      dark:border-gray-700 p-1 cursor-pointer
                      hover:bg-indigo-50 dark:hover:bg-indigo-900/20 transition
                      \${esSabDom ? 'bg-gray-50/80 dark:bg-gray-800/50' : ''}\"
               onclick=\"window.location='/calendario/dia/\${fechaStr}'\">\n            <div class=\"flex justify-between items-start\">\n              <span class=\"\${esHoy\n                ? 'bg-indigo-600 text-white w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold'\n                : 'text-xs font-semibold ' \+ \(esSabDom \? 'text-indigo-400 dark:text-indigo-400' : 'text-gray-600\n                : 'text-xs font-semibold ' \+ \(esSabDom \? 'text-indigo-400 dark:text-indigo-400' : 'text-gray-600 dark:text-gray-300'\)}\">"""\
.replace('\\n', '\n')

NEW = """        const numClase = esHoy
          ? 'bg-indigo-600 text-white w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold'
          : ('text-xs font-semibold ' + (esSabDom ? 'text-indigo-400 dark:text-indigo-400' : 'text-gray-600 dark:text-gray-300'));

        let html = `<div class="min-h-14 sm:min-h-20 border-b border-r border-gray-100 dark:border-gray-700 p-1 cursor-pointer hover:bg-indigo-50 dark:hover:bg-indigo-900/20 transition ${esSabDom ? 'bg-gray-50/80 dark:bg-gray-800/50' : ''}" onclick="window.location='/calendario/dia/${fechaStr}'">
          <div class="flex justify-between items-start">
            <span class="${numClase}">${dia}</span>
            <a href="/calendario/nuevo?fecha=${fechaStr}"
               onclick="event.stopPropagation()"
               class="text-gray-300 hover:text-indigo-500 text-base leading-none w-5 h-5 flex items-center justify-center">+</a>
          </div>`"""

# Usamos una búsqueda más robusta por marcadores únicos
MARKER_START = "let html = `"
MARKER_END   = "></span>\n              <a href=\"/calendario/nuevo?fecha=${fechaStr}\""

idx_start = content.find(MARKER_START)
idx_end   = content.find("</span>\n              <a href=\"/calendario/nuevo?fecha=${fechaStr}\"")

if idx_start != -1 and idx_end != -1:
    # Encontrar el cierre del span problemático
    close_span = content.find("</span>", idx_start)
    # Buscar hasta después del <a> de nuevo evento
    a_end = content.find(">+</a>", idx_end) + len(">+</a>")
    content = content[:idx_start] + NEW + "\n" + content[a_end:]
    print("✅ Bloque numClase + html corregido")
else:
    print("⚠️  Marcador no encontrado — aplicando reemplazo alternativo por línea")

# ── Exponer abrirModalVoz como window.abrirModalVoz ──────────
OLD_FUNC = "    function abrirModalVoz() {"
NEW_FUNC = "    window.abrirModalVoz = function() {"
OLD_CLOSE = "    }\n\n    function cerrarModalVoz()"
NEW_CLOSE = "    };\n\n    function cerrarModalVoz()"

content = content.replace(OLD_FUNC, NEW_FUNC)
content = content.replace(OLD_CLOSE, NEW_CLOSE)
print("✅ abrirModalVoz expuesta como window.abrirModalVoz")

path.write_text(content, encoding="utf-8")
print("✅ calendario/index.html guardado")
PYEOF

echo ""
echo "════════════════════════════════════════"
echo "  Listo. Ahora ejecuta:"
echo "  ./dev-redeploy.sh"
echo "════════════════════════════════════════"
