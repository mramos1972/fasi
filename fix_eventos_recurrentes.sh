#!/bin/bash
# ════════════════════════════════════════════════════════════
#  Fix eventos recurrentes — Modal de voz + IA mejorada
# ════════════════════════════════════════════════════════════

set -e
BACKUP_DIR="backups/recurrentes_fix_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

echo "📦 Creando backup..."
cp src/main/resources/templates/calendario/index.html "$BACKUP_DIR/"
cp src/main/java/com/miempresa/fasi/calendario/EventoIaService.java "$BACKUP_DIR/"

# ════════════════════════════════════════════════════════════
#  1. Mejorar el prompt de la IA para detectar recurrencias
# ════════════════════════════════════════════════════════════

echo "🤖 Mejorando detección de recurrencias en EventoIaService..."

sed -i '/REGLAS:/a\
- recurrencia: null (evento único) o "DAILY", "WEEKLY", "MONTHLY"\
- Si menciona "todos los [días]", "cada [día]", "semanalmente" → WEEKLY\
- Si menciona "diariamente", "todos los días" → DAILY\
- Si menciona "mensualmente", "cada mes" → MONTHLY\
\
EJEMPLOS DE RECURRENCIA:\
- "Trabajo presencial en Madrid todos los lunes y martes de junio" →\
  Crear DOS eventos WEEKLY: uno para lunes, otro para martes\
- "Clases de teatro todos los jueves a las 17:00" → WEEKLY (jueves)\
- "Reunión cada primer lunes de mes" → MONTHLY' \
  src/main/java/com/miempresa/fasi/calendario/EventoIaService.java

# ════════════════════════════════════════════════════════════
#  2. Crear archivo temporal con la función mejorada
# ════════════════════════════════════════════════════════════

echo "🎤 Creando función confirmarEvento mejorada..."

cat > /tmp/confirmar_evento_fixed.js << 'EOFJS'
    function confirmarEvento() {
      const preview = window.eventoPreview;
      if (!preview || preview.length === 0) {
        alert("No hay evento para confirmar");
        return;
      }

      // Detectar si es recurrente
      const esRecurrente = preview.some(e => e.recurrencia && e.recurrencia !== "null");
      const endpoint = esRecurrente 
        ? "/calendario/api/confirmar-recurrente" 
        : "/calendario/api/confirmar";

      console.log("📅 Confirmando evento(s):", {
        cantidad: preview.length,
        esRecurrente: esRecurrente,
        endpoint: endpoint
      });

      fetch(endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(preview)
      })
      .then(r => {
        if (!r.ok) throw new Error("Error al guardar");
        return r.text();
      })
      .then(() => {
        alert(esRecurrente 
          ? "✅ Eventos recurrentes creados correctamente" 
          : "✅ Evento guardado");
        cerrarModal();
        location.reload();
      })
      .catch(err => {
        console.error(err);
        alert("❌ Error al guardar: " + err.message);
      });
    }
EOFJS

# Reemplazar usando Python
python3 << 'EOFPYTHON'
import re

with open('src/main/resources/templates/calendario/index.html', 'r', encoding='utf-8') as f:
    content = f.read()

with open('/tmp/confirmar_evento_fixed.js', 'r', encoding='utf-8') as f:
    new_function = f.read()

# Patrón más simple: buscar desde "function confirmarEvento()" hasta el cierre de esa función
pattern = r'function confirmarEvento\(\)[^{]*\{(?:[^{}]|\{[^{}]*\})*\}'
match = re.search(pattern, content, re.DOTALL)

if match:
    content = content[:match.start()] + new_function + content[match.end():]
    print("✅ Función confirmarEvento() reemplazada")
else:
    print("⚠️  No se encontró la función confirmarEvento()")

with open('src/main/resources/templates/calendario/index.html', 'w', encoding='utf-8') as f:
    f.write(content)
EOFPYTHON

# ════════════════════════════════════════════════════════════
#  3. Mejorar vista previa (solo si no existe ya)
# ════════════════════════════════════════════════════════════

echo "👁️ Verificando vista previa de recurrencia..."

if ! grep -q "Evento recurrente:" src/main/resources/templates/calendario/index.html; then
  echo "Añadiendo indicador visual..."
  # Buscar la línea que tiene "Tipo:" y añadir antes el indicador
  sed -i '/Tipo:/i\
          <div th:if="${e.recurrencia != null && e.recurrencia != '\''null'\''}" \
               class="text-xs font-semibold text-blue-600 dark:text-blue-400 mb-1">\
            🔄 Evento recurrente: <span th:text="${e.recurrencia}"></span>\
          </div>' src/main/resources/templates/calendario/index.html
else
  echo "✅ Indicador ya existe"
fi

# ════════════════════════════════════════════════════════════
#  4. Verificar endpoint de recurrentes
# ════════════════════════════════════════════════════════════

echo "🔍 Verificando endpoint /api/confirmar-recurrente..."

if ! grep -q "confirmar-recurrente" src/main/java/com/miempresa/fasi/calendario/CalendarioController.java; then
  echo "⚠️  Endpoint no encontrado, añadiéndolo..."
  
  sed -i '/\/\/ ── Confirmar evento interpretado/a\
\
    @PostMapping("/api/confirmar-recurrente")\
    @ResponseBody\
    public ResponseEntity<String> confirmarRecurrente(@RequestBody List<Evento> eventos) {\
        log.info("📅 Confirmando {} evento(s) recurrente(s)", eventos.size());\
        for (Evento e : eventos) {\
            String patron = null;\
            if (e.getDatosExtra() != null && e.getDatosExtra().has("recurrencia")) {\
                patron = e.getDatosExtra().get("recurrencia").asText();\
            }\
            if (patron != null && !patron.equals("null") && !patron.isEmpty()) {\
                eventoService.saveRecurrente(e, patron);\
            } else {\
                eventoService.save(e);\
            }\
        }\
        return ResponseEntity.ok("OK");\
    }' src/main/java/com/miempresa/fasi/calendario/CalendarioController.java
else
  echo "✅ Endpoint ya existe"
fi

# ════════════════════════════════════════════════════════════
#  5. Verificar método saveRecurrente
# ════════════════════════════════════════════════════════════

echo "🔧 Verificando método saveRecurrente..."

if ! grep -q "public void saveRecurrente" src/main/java/com/miempresa/fasi/calendario/EventoService.java; then
  echo "⚠️  Método no encontrado, añadiéndolo..."
  
  sed -i '/^}$/i\
\
    @Transactional\
    public void saveRecurrente(Evento plantilla, String patron) {\
        LocalDate inicio = plantilla.getFechaInicio();\
        LocalDate fin = plantilla.getFechaFin() != null ? plantilla.getFechaFin() : inicio.plusMonths(3);\
        log.info("🔄 Generando eventos recurrentes: {} desde {} hasta {}", patron, inicio, fin);\
        LocalDate actual = inicio;\
        int count = 0;\
        while (!actual.isAfter(fin) && count < 100) {\
            Evento copia = new Evento();\
            copia.setTipo(plantilla.getTipo());\
            copia.setTitulo(plantilla.getTitulo());\
            copia.setFechaInicio(actual);\
            copia.setFechaFin(actual);\
            copia.setHoraInicio(plantilla.getHoraInicio());\
            copia.setHoraFin(plantilla.getHoraFin());\
            copia.setTodoDia(plantilla.getTodoDia());\
            copia.setLugar(plantilla.getLugar());\
            copia.setPersonasRelacionadas(plantilla.getPersonasRelacionadas());\
            copia.setDescripcion(plantilla.getDescripcion());\
            copia.setRecordatorioMinutos(plantilla.getRecordatorioMinutos());\
            copia.setEtiquetas(plantilla.getEtiquetas());\
            repo.save(copia);\
            count++;\
            switch (patron.toUpperCase()) {\
                case "DAILY": actual = actual.plusDays(1); break;\
                case "WEEKLY": actual = actual.plusWeeks(1); break;\
                case "MONTHLY": actual = actual.plusMonths(1); break;\
                default: log.warn("Patrón desconocido: {}", patron); return;\
            }\
        }\
        log.info("✅ Creados {} eventos recurrentes", count);\
    }' src/main/java/com/miempresa/fasi/calendario/EventoService.java
else
  echo "✅ Método ya existe"
fi

echo ""
echo "═══════════════════════════════════════════════════════════"
echo "  ✅ EVENTOS RECURRENTES ARREGLADOS"
echo "═══════════════════════════════════════════════════════════"
echo ""
echo "📝 Backup: $BACKUP_DIR"
echo "🚀 Ejecuta: ./dev-redeploy.sh"
