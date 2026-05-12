#!/usr/bin/env bash
# ============================================================
#  FASI — fix_footer_y_logo.sh
#  1) El logo "⚡ FASI" se vuelve clicable → redirige a "/"
#  2) El footer carga los datos del día que se está viendo
#     (detectando /calendario/dia/YYYY-MM-DD en la URL)
# ============================================================
set -euo pipefail

FILE="src/main/resources/templates/layout/base.html"

if [[ ! -f "$FILE" ]]; then
  echo "❌ No encuentro $FILE — ejecuta el script desde la raíz del proyecto."
  exit 1
fi

# Backup
cp "$FILE" "${FILE}.bak.$(date +%Y%m%d_%H%M%S)"
echo "✅ Backup creado: ${FILE}.bak.*"

# ─────────────────────────────────────────────────────────────
# 1) Logo clicable
#    Reemplazamos:
#      <span class="text-xl font-bold tracking-wide flex-shrink-0">⚡ FASI</span>
#    por un <a th:href="@{/}"> ... </a>
# ─────────────────────────────────────────────────────────────
python3 - "$FILE" <<'PYEOF'
import re, sys, pathlib
p = pathlib.Path(sys.argv[1])
html = p.read_text(encoding="utf-8")

# --- 1) Logo clicable ----------------------------------------
old_logo = '<span class="text-xl font-bold tracking-wide flex-shrink-0">⚡ FASI</span>'
new_logo = ('<a th:href="@{/}" '
            'class="text-xl font-bold tracking-wide flex-shrink-0 '
            'hover:text-indigo-300 transition cursor-pointer" '
            'title="Inicio">⚡ FASI</a>')

if old_logo in html:
    html = html.replace(old_logo, new_logo)
    print("✅ Logo convertido en enlace a /")
else:
    print("⚠️  No se encontró el logo original (quizá ya está modificado).")

# --- 2) Footer dinámico según URL ----------------------------
# Buscamos la línea:    cargarDatosFooter();
# y la sustituimos por una versión que extrae la fecha de la URL.
old_init = "cargarDatosFooter();"
new_init = """// Detectar fecha desde URL /calendario/dia/YYYY-MM-DD
    (function initFooterFecha() {
      const m = window.location.pathname.match(/\\/calendario\\/dia\\/(\\d{4}-\\d{2}-\\d{2})/);
      cargarDatosFooter(m ? m[1] : null);
    })();"""

if old_init in html:
    html = html.replace(old_init, new_init, 1)
    print("✅ Footer ahora detecta la fecha desde la URL")
else:
    print("⚠️  No se encontró la llamada inicial cargarDatosFooter();")

p.write_text(html, encoding="utf-8")
PYEOF

echo ""
echo "──────────────────────────────────────────────"
echo "✅ Cambios aplicados en $FILE"
echo "🔁 Recompila y reinicia:"
echo "   ./mvnw clean package -DskipTests && ./dev-redeploy.sh"
echo "──────────────────────────────────────────────"
