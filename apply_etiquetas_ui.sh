#!/bin/bash
# ══════════════════════════════════════════════════════════
#  FASI — Aplicar UI de etiquetas (form + dia + controller)
#  Genera backup, aplica los 3 cambios, valida compilación
# ══════════════════════════════════════════════════════════
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_ROOT"

TS=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="backups/ui_etiquetas_${TS}"
mkdir -p "$BACKUP_DIR"

FORM="src/main/resources/templates/calendario/form.html"
DIA="src/main/resources/templates/calendario/dia.html"
CTRL="src/main/java/com/miempresa/fasi/calendario/CalendarioController.java"

echo "🗂️  [1/5] Backup en $BACKUP_DIR"
cp "$FORM" "$BACKUP_DIR/" 2>/dev/null || { echo "❌ No existe $FORM"; exit 1; }
cp "$DIA"  "$BACKUP_DIR/" 2>/dev/null || { echo "❌ No existe $DIA";  exit 1; }
cp "$CTRL" "$BACKUP_DIR/" 2>/dev/null || { echo "❌ No existe $CTRL"; exit 1; }
echo "✅ Backup hecho"

# ══════════════════════════════════════════════════════════
# 1️⃣  form.html — input de etiquetas antes del bloque "Descripción"
# ══════════════════════════════════════════════════════════
echo "📝 [2/5] Modificando form.html"

if grep -q 'name="etiquetasRaw"' "$FORM"; then
    echo "ℹ️  form.html ya contiene etiquetasRaw, lo dejo como está"
else
python3 - <<'PY_EOF'
from pathlib import Path
import re

p = Path("src/main/resources/templates/calendario/form.html")
src = p.read_text(encoding="utf-8")

bloque = '''      <!-- Etiquetas libres -->
      <div>
        <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
          🏷️ Etiquetas
          <span class="text-xs text-gray-400 font-normal">(separadas por comas)</span>
        </label>
        <input type="text" name="etiquetasRaw"
               th:value="${evento.etiquetas != null ? #strings.setJoin(evento.etiquetas, ', ') : ''}"
               placeholder="Ej: cumpleanos, amigos, jara"
               class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2
                      bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100
                      focus:outline-none focus:ring-2 focus:ring-indigo-400"/>
        <p class="text-xs text-gray-400 mt-1">
          Las etiquetas ayudan a filtrar y buscar. Ej: <em>tutoria, colegio</em>
        </p>
      </div>

'''

# Inserta antes del comentario "Descripción" o "Notas"
patron = re.compile(r'(\s*<!--\s*(Descripción|Descripcion|Notas)[^-]*-->)', re.IGNORECASE)
m = patron.search(src)
if m:
    nuevo = src[:m.start()] + "\n" + bloque + m.group(1) + src[m.end():]
    p.write_text(nuevo, encoding="utf-8")
    print("✅ form.html: bloque insertado antes de Descripción")
else:
    # Fallback: antes del último </form> o último botón submit
    patron2 = re.compile(r'(\s*<button[^>]*type="submit")', re.IGNORECASE)
    m2 = patron2.search(src)
    if m2:
        nuevo = src[:m2.start()] + "\n" + bloque + m2.group(1) + src[m2.end():]
        p.write_text(nuevo, encoding="utf-8")
        print("✅ form.html: bloque insertado antes del botón submit")
    else:
        print("⚠️  No encontré punto de inserción en form.html — revisa manualmente")
PY_EOF
fi

# ══════════════════════════════════════════════════════════
# 2️⃣  dia.html — chips de etiquetas tras el <h3> del título
# ══════════════════════════════════════════════════════════
echo "📝 [3/5] Modificando dia.html"

if grep -q 'th:each="tag : ${e.etiquetas}"' "$DIA"; then
    echo "ℹ️  dia.html ya contiene chips de etiquetas, lo dejo como está"
else
python3 - <<'PY_EOF'
from pathlib import Path
import re

p = Path("src/main/resources/templates/calendario/dia.html")
src = p.read_text(encoding="utf-8")

chips = '''
        <!-- Chips de etiquetas -->
        <div th:if="${e.etiquetas != null and !e.etiquetas.isEmpty()}"
             class="flex flex-wrap gap-1 mt-1">
          <span th:each="tag : ${e.etiquetas}"
                class="text-[10px] px-1.5 py-0.5 rounded-full bg-gray-100 dark:bg-gray-700
                       text-gray-600 dark:text-gray-300 font-medium"
                th:text="'#' + ${tag}"></span>
        </div>'''

# Inserta tras el primer </h3> que muestre e.titulo
patron = re.compile(r'(<h3[^>]*th:text="\$\{e\.titulo\}"[^>]*></h3>)')
m = patron.search(src)
if m:
    nuevo = src[:m.end()] + chips + src[m.end():]
    p.write_text(nuevo, encoding="utf-8")
    print("✅ dia.html: chips insertados tras <h3>")
else:
    # Fallback: tras cualquier </h3> dentro del bucle de eventos
    patron2 = re.compile(r'(</h3>)')
    m2 = patron2.search(src)
    if m2:
        nuevo = src[:m2.end()] + chips + src[m2.end():]
        p.write_text(nuevo, encoding="utf-8")
        print("✅ dia.html: chips insertados tras primer </h3> (fallback)")
    else:
        print("⚠️  No encontré <h3> en dia.html — revisa manualmente")
PY_EOF
fi

# ══════════════════════════════════════════════════════════
# 3️⃣  CalendarioController.java — añadir parseEtiquetas + parámetro
# ══════════════════════════════════════════════════════════
echo "📝 [4/5] Modificando CalendarioController.java"

if grep -q 'parseEtiquetas' "$CTRL"; then
    echo "ℹ️  CalendarioController ya tiene parseEtiquetas, lo dejo como está"
else
python3 - <<'PY_EOF'
from pathlib import Path
import re

p = Path("src/main/java/com/miempresa/fasi/calendario/CalendarioController.java")
src = p.read_text(encoding="utf-8")

# 1) Añadir import RequestParam si no está
if "import org.springframework.web.bind.annotation.RequestParam;" not in src:
    src = re.sub(
        r'(package [^\n]+;\s*\n)',
        r'\1\nimport org.springframework.web.bind.annotation.RequestParam;\n',
        src, count=1
    )

# 2) Añadir método helper parseEtiquetas justo antes del último '}'
helper = '''
    /** Convierte "tutoria, colegio, extraescolar" → Set{tutoria, colegio, extraescolar} (sin tildes) */
    private java.util.Set<String> parseEtiquetas(String raw) {
        if (raw == null || raw.isBlank()) return new java.util.HashSet<>();
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replaceAll("[áàä]", "a")
                           .replaceAll("[éèë]", "e")
                           .replaceAll("[íìï]", "i")
                           .replaceAll("[óòö]", "o")
                           .replaceAll("[úùü]", "u")
                           .replaceAll("[ñ]", "n"))
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
    }
'''
src = src.rstrip()
if src.endswith("}"):
    src = src[:-1].rstrip() + "\n" + helper + "\n}\n"

# 3) Inyectar parámetro etiquetasRaw en TODOS los @PostMapping de Evento
#    Buscamos firmas tipo:  ...@ModelAttribute Evento evento ...) {
#    Y añadimos un @RequestParam etiquetasRaw + llamada a setEtiquetas
def inyectar(match):
    firma = match.group(0)
    if "etiquetasRaw" in firma:
        return firma
    # Añadir parámetro antes del último ')'
    nueva_firma = firma[:-2] + ',\n            @RequestParam(value = "etiquetasRaw", required = false) String etiquetasRaw) {'
    # Añadir setter justo después de la apertura
    nueva_firma += '\n        evento.setEtiquetas(parseEtiquetas(etiquetasRaw));'
    return nueva_firma

# Patrón: @PostMapping(...) ... method(...@ModelAttribute...Evento evento...) {
patron_post = re.compile(
    r'@PostMapping[^\n]*\n\s*public\s+\w[\w<>]*\s+\w+\s*\([^)]*@ModelAttribute[^)]*Evento\s+evento[^)]*\)\s*\{',
    re.MULTILINE
)
src_new, n = patron_post.subn(inyectar, src)
print(f"✅ CalendarioController: {n} método(s) @PostMapping adaptado(s)")

p.write_text(src_new, encoding="utf-8")
PY_EOF
fi

# ══════════════════
