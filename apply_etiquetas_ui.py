#!/usr/bin/env python3
"""
FASI - Aplicar UI de etiquetas (form + dia + controller)
Backup automatico, idempotente, sin heredocs anidados.
"""
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).parent.resolve()
TS = datetime.now().strftime("%Y%m%d_%H%M%S")
BACKUP = ROOT / "backups" / f"ui_etiquetas_{TS}"

FORM = ROOT / "src/main/resources/templates/calendario/form.html"
DIA  = ROOT / "src/main/resources/templates/calendario/dia.html"
CTRL = ROOT / "src/main/java/com/miempresa/fasi/calendario/CalendarioController.java"

def step(n, msg):
    print(f"\n{'='*60}\n  [{n}/5] {msg}\n{'='*60}")

def backup_files():
    BACKUP.mkdir(parents=True, exist_ok=True)
    for f in (FORM, DIA, CTRL):
        if not f.exists():
            print(f"FATAL: no existe {f}")
            sys.exit(1)
        shutil.copy2(f, BACKUP / f.name)
    print(f"OK Backup en {BACKUP}")

# ----------------------------------------------------------
def patch_form():
    src = FORM.read_text(encoding="utf-8")
    if 'name="etiquetasRaw"' in src:
        print("INFO form.html ya tiene etiquetasRaw, omitido")
        return
    bloque = '''      <!-- Etiquetas libres -->
      <div>
        <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
          Etiquetas
          <span class="text-xs text-gray-400 font-normal">(separadas por comas)</span>
        </label>
        <input type="text" name="etiquetasRaw"
               th:value="${evento.etiquetas != null ? #strings.setJoin(evento.etiquetas, ', ') : ''}"
               placeholder="Ej: cumpleanos, amigos, jara"
               class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2 bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-400"/>
        <p class="text-xs text-gray-400 mt-1">
          Las etiquetas ayudan a filtrar y buscar.
        </p>
      </div>

'''
    # Intento 1: antes del comentario "Descripcion" / "Notas"
    pat = re.compile(r'(\s*<!--\s*(Descripci[oó]n|Notas)[^-]*-->)', re.IGNORECASE)
    m = pat.search(src)
    if m:
        new = src[:m.start()] + "\n" + bloque + m.group(1) + src[m.end():]
        FORM.write_text(new, encoding="utf-8")
        print("OK form.html: insertado antes de Descripcion")
        return
    # Intento 2: antes del primer button submit
    pat2 = re.compile(r'(\s*<button[^>]*type="submit")', re.IGNORECASE)
    m2 = pat2.search(src)
    if m2:
        new = src[:m2.start()] + "\n" + bloque + m2.group(1) + src[m2.end():]
        FORM.write_text(new, encoding="utf-8")
        print("OK form.html: insertado antes de submit (fallback)")
        return
    print("WARN no encontre punto de insercion en form.html")

# ----------------------------------------------------------
def patch_dia():
    src = DIA.read_text(encoding="utf-8")
    if 'th:each="tag : ${e.etiquetas}"' in src:
        print("INFO dia.html ya tiene chips, omitido")
        return
    chips = '''
        <!-- Chips de etiquetas -->
        <div th:if="${e.etiquetas != null and !e.etiquetas.isEmpty()}"
             class="flex flex-wrap gap-1 mt-1">
          <span th:each="tag : ${e.etiquetas}"
                class="text-[10px] px-1.5 py-0.5 rounded-full bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300 font-medium"
                th:text="'#' + ${tag}"></span>
        </div>'''
    # Intento 1: tras h3 con e.titulo
    pat = re.compile(r'(<h3[^>]*th:text="\$\{e\.titulo\}"[^>]*></h3>)')
    m = pat.search(src)
    if m:
        new = src[:m.end()] + chips + src[m.end():]
        DIA.write_text(new, encoding="utf-8")
        print("OK dia.html: chips insertados tras <h3>")
        return
    # Intento 2: tras primer </h3>
    pat2 = re.compile(r'(</h3>)')
    m2 = pat2.search(src)
    if m2:
        new = src[:m2.end()] + chips + src[m2.end():]
        DIA.write_text(new, encoding="utf-8")
        print("OK dia.html: chips insertados tras primer </h3>")
        return
    print("WARN no encontre <h3> en dia.html")

# ----------------------------------------------------------
def patch_controller():
    src = CTRL.read_text(encoding="utf-8")
    if "parseEtiquetas" in src:
        print("INFO CalendarioController ya tiene parseEtiquetas, omitido")
        return

    # 1) Import RequestParam
    if "import org.springframework.web.bind.annotation.RequestParam;" not in src:
        src = re.sub(
            r'(package [^\n]+;\s*\n)',
            r'\1\nimport org.springframework.web.bind.annotation.RequestParam;\n',
            src, count=1
        )

    # 2) Helper parseEtiquetas antes del ultimo '}'
    helper = '''
    /** Convierte "tutoria, colegio" en Set sin tildes ni mayusculas */
    private java.util.Set<String> parseEtiquetas(String raw) {
        if (raw == null || raw.isBlank()) return new java.util.HashSet<>();
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replaceAll("[\u00e1\u00e0\u00e4]", "a")
                           .replaceAll("[\u00e9\u00e8\u00eb]", "e")
                           .replaceAll("[\u00ed\u00ec\u00ef]", "i")
                           .replaceAll("[\u00f3\u00f2\u00f6]", "o")
                           .replaceAll("[\u00fa\u00f9\u00fc]", "u")
                           .replaceAll("[\u00f1]", "n"))
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
    }
'''
    src = src.rstrip()
    if src.endswith("}"):
        src = src[:-1].rstrip() + "\n" + helper + "\n}\n"

    # 3) Inyectar parametro etiquetasRaw en cada @PostMapping de Evento
    pat = re.compile(
        r'(@PostMapping[^\n]*\n\s*public\s+\S+\s+\w+\s*\([^)]*@ModelAttribute[^)]*Evento\s+evento[^)]*)\)\s*\{',
        re.MULTILINE
    )
    def add_param(m):
        firma = m.group(1)
        if "etiquetasRaw" in firma:
            return m.group(0)
        return firma + ',\n            @RequestParam(value = "etiquetasRaw", required = false) String etiquetasRaw) {\n        evento.setEtiquetas(parseEtiquetas(etiquetasRaw));'
    src, n = pat.subn(add_param, src)
    print(f"OK CalendarioController: {n} metodo(s) @PostMapping adaptado(s)")
    CTRL.write_text(src, encoding="utf-8")

# ----------------------------------------------------------
def compile_check():
    print("Compilando con ./mvnw compile -q ...")
    r = subprocess.run(["./mvnw", "compile", "-q"], cwd=ROOT)
    return r.returncode == 0

# ----------------------------------------------------------
if __name__ == "__main__":
    step(1, "Backup")
    backup_files()
    step(2, "Parchear form.html")
    patch_form()
    step(3, "Parchear dia.html")
    patch_dia()
    step(4, "Parchear CalendarioController.java")
    patch_controller()
    step(5, "Compilar")
    if compile_check():
        print("\n" + "="*60)
        print("  TODO OK - cambios aplicados y compila limpio")
        print("="*60)
        print("\nSiguiente: ./dev-redeploy.sh")
        print(f"Backup en: {BACKUP}")
    else:
        print("\nERROR compilacion. Para revertir:")
        print(f"  cp {BACKUP}/form.html {FORM}")
        print(f"  cp {BACKUP}/dia.html  {DIA}")
        print(f"  cp {BACKUP}/CalendarioController.java {CTRL}")
        sys.exit(1)
