#!/bin/bash
# ════════════════════════════════════════════════════════════
#  Sistema de etiquetas con chips + autocompletado
# ════════════════════════════════════════════════════════════

set -e
BACKUP_DIR="backups/etiquetas_chips_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

echo "📦 Creando backup en $BACKUP_DIR..."
cp src/main/resources/templates/calendario/form.html "$BACKUP_DIR/"
cp src/main/java/com/miempresa/fasi/calendario/CalendarioController.java "$BACKUP_DIR/"

# ════════════════════════════════════════════════════════════
#  1. API para obtener etiquetas existentes
# ════════════════════════════════════════════════════════════

echo "🔧 Añadiendo endpoint API para etiquetas..."

# Insertar antes del último } del CalendarioController
sed -i '/^}$/i\
\
    // ── API: Obtener todas las etiquetas existentes ──────────\
    @GetMapping("/api/etiquetas")\
    @ResponseBody\
    public ResponseEntity<List<String>> getEtiquetas() {\
        return ResponseEntity.ok(eventoService.getAllEtiquetas());\
    }' src/main/java/com/miempresa/fasi/calendario/CalendarioController.java

# ════════════════════════════════════════════════════════════
#  2. Método en EventoService
# ════════════════════════════════════════════════════════════

echo "🔧 Añadiendo método getAllEtiquetas() en EventoService..."

# Insertar antes del último } del EventoService
sed -i '/^}$/i\
\
    // ── Obtener todas las etiquetas únicas usadas ────────────\
    public List<String> getAllEtiquetas() {\
        return repo.findAll().stream()\
                .flatMap(e -> e.getEtiquetas().stream())\
                .distinct()\
                .sorted()\
                .toList();\
    }' src/main/java/com/miempresa/fasi/calendario/EventoService.java

# ════════════════════════════════════════════════════════════
#  3. Nuevo HTML con chips (archivo temporal)
# ════════════════════════════════════════════════════════════

echo "🎨 Generando nuevo HTML de etiquetas..."

cat > /tmp/etiquetas_nuevo_bloque.html << 'EOFHTML'
      <!-- Etiquetas con chips + autocompletado -->
      <div>
        <label class="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
          Etiquetas
        </label>
        
        <!-- Contenedor de chips -->
        <div id="chips-container" 
             class="flex flex-wrap gap-1.5 mb-2 min-h-[32px] p-2 border border-gray-300 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-700">
        </div>
        
        <!-- Input con autocompletado -->
        <div class="relative">
          <input type="text" 
                 id="etiqueta-input"
                 placeholder="Escribe y pulsa Enter o coma..."
                 autocomplete="off"
                 class="w-full border border-gray-300 dark:border-gray-600 rounded-lg px-3 py-2 bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-400 text-sm"/>
          
          <div id="etiquetas-dropdown" 
               class="hidden absolute z-10 w-full mt-1 bg-white dark:bg-gray-700 border border-gray-300 dark:border-gray-600 rounded-lg shadow-lg max-h-48 overflow-y-auto">
          </div>
        </div>
        
        <input type="hidden" name="etiquetasRaw" id="etiquetas-hidden"/>
        
        <p class="text-xs text-gray-400 mt-1">
          💡 Escribe y pulsa Enter o coma para añadir
        </p>
      </div>

      <script th:inline="javascript">
        (function() {
          const chipsContainer = document.getElementById('chips-container');
          const input = document.getElementById('etiqueta-input');
          const hidden = document.getElementById('etiquetas-hidden');
          const dropdown = document.getElementById('etiquetas-dropdown');
          
          let etiquetas = [];
          let sugerencias = [];
          let selectedIndex = -1;

          const etiquetasIniciales = /*[[${evento.etiquetas}]]*/ [];
          if (etiquetasIniciales && etiquetasIniciales.length > 0) {
            etiquetas = [...etiquetasIniciales];
            renderChips();
          }

          fetch('/calendario/api/etiquetas')
            .then(r => r.json())
            .then(data => { sugerencias = data; })
            .catch(e => console.warn('Sugerencias no disponibles:', e));

          function renderChips() {
            chipsContainer.innerHTML = etiquetas.map(tag => `
              <span class="inline-flex items-center gap-1 px-2 py-1 rounded-full text-xs font-medium bg-indigo-100 dark:bg-indigo-900/50 text-indigo-700 dark:text-indigo-300 border border-indigo-200 dark:border-indigo-700">
                ${escapeHtml(tag)}
                <button type="button" onclick="eliminarEtiqueta('${escapeHtml(tag)}')" class="hover:bg-indigo-200 dark:hover:bg-indigo-800 rounded-full w-4 h-4 flex items-center justify-center text-indigo-600 dark:text-indigo-300">×</button>
              </span>
            `).join('');
            hidden.value = etiquetas.join(', ');
          }

          function anadirEtiqueta(texto) {
            const tag = normalizar(texto.trim());
            if (!tag || etiquetas.includes(tag)) return;
            etiquetas.push(tag);
            renderChips();
            input.value = '';
            cerrarDropdown();
          }

          window.eliminarEtiqueta = function(tag) {
            etiquetas = etiquetas.filter(t => t !== tag);
            renderChips();
          };

          function normalizar(str) {
            return str.toLowerCase()
              .replace(/[áàä]/g, 'a').replace(/[éèë]/g, 'e').replace(/[íìï]/g, 'i')
              .replace(/[óòö]/g, 'o').replace(/[úùü]/g, 'u').replace(/ñ/g, 'n')
              .replace(/[^a-z0-9]/g, '');
          }

          function mostrarSugerencias(query) {
            if (!query) { cerrarDropdown(); return; }
            const filtradas = sugerencias.filter(s => normalizar(s).includes(normalizar(query))).filter(s => !etiquetas.includes(s)).slice(0, 8);
            if (filtradas.length === 0) { cerrarDropdown(); return; }
            dropdown.innerHTML = filtradas.map((s, i) => `
              <div class="px-3 py-2 hover:bg-indigo-50 dark:hover:bg-indigo-900/30 cursor-pointer text-sm text-gray-700 dark:text-gray-200 ${i === selectedIndex ? 'bg-indigo-100 dark:bg-indigo-900/50' : ''}" onclick="anadirEtiqueta('${escapeHtml(s)}')" data-index="${i}">${escapeHtml(s)}</div>
            `).join('');
            dropdown.classList.remove('hidden');
          }

          function cerrarDropdown() {
            dropdown.classList.add('hidden');
            selectedIndex = -1;
          }

          input.addEventListener('input', e => mostrarSugerencias(e.target.value));

          input.addEventListener('keydown', e => {
            const valor = input.value.trim();
            if (e.key === 'Enter' || e.key === ',') {
              e.preventDefault();
              if (selectedIndex >= 0) {
                const items = dropdown.querySelectorAll('[data-index]');
                if (items[selectedIndex]) anadirEtiqueta(items[selectedIndex].textContent);
              } else if (valor) {
                anadirEtiqueta(valor);
              }
            }
            if (e.key === 'ArrowDown') {
              e.preventDefault();
              const items = dropdown.querySelectorAll('[data-index]');
              selectedIndex = Math.min(selectedIndex + 1, items.length - 1);
              mostrarSugerencias(input.value);
            }
            if (e.key === 'ArrowUp') {
              e.preventDefault();
              selectedIndex = Math.max(selectedIndex - 1, 0);
              mostrarSugerencias(input.value);
            }
            if (e.key === 'Escape') cerrarDropdown();
            if (e.key === 'Backspace' && !valor && etiquetas.length > 0) {
              etiquetas.pop();
              renderChips();
            }
          });

          document.addEventListener('click', e => {
            if (!input.contains(e.target) && !dropdown.contains(e.target)) cerrarDropdown();
          });

          function escapeHtml(text) {
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
          }
        })();
      </script>
EOFHTML

# ════════════════════════════════════════════════════════════
#  4. Reemplazar en form.html
# ════════════════════════════════════════════════════════════

echo "🔄 Reemplazando bloque de etiquetas en form.html..."

# Buscar línea que contiene "Etiquetas libres" y su número
LINEA_INICIO=$(grep -n "Etiquetas libres" src/main/resources/templates/calendario/form.html | cut -d: -f1)

if [ -z "$LINEA_INICIO" ]; then
  echo "❌ No se encontró el bloque de etiquetas en form.html"
  exit 1
fi

# Buscar el </div> que cierra ese bloque (contar 2 niveles)
LINEA_FIN=$((LINEA_INICIO + 20))

# Crear archivo temporal con el reemplazo
head -n $((LINEA_INICIO - 1)) src/main/resources/templates/calendario/form.html > /tmp/form_new.html
cat /tmp/etiquetas_nuevo_bloque.html >> /tmp/form_new.html

# Saltar hasta después del bloque viejo y copiar el resto
tail -n +$LINEA_FIN src/main/resources/templates/calendario/form.html | \
  awk '/<!-- Descripción -->/{found=1} found' >> /tmp/form_new.html

mv /tmp/form_new.html src/main/resources/templates/calendario/form.html

echo ""
echo "✅ Sistema de chips instalado correctamente"
echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✨ CAMBIOS APLICADOS"
echo "═══════════════════════════════════════════════════════════"
echo "✅ Endpoint GET /calendario/api/etiquetas"
echo "✅ Método getAllEtiquetas() en EventoService"
echo "✅ Sistema de chips con autocompletado en form.html"
echo ""
echo "📝 Backup: $BACKUP_DIR"
echo "🚀 Ejecuta: ./dev-redeploy.sh"
