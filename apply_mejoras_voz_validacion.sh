#!/bin/bash
set -e
echo "🚀 Aplicando mejoras: voz continua + validación de datos..."

# ══════════════════════════════════════════════════════════════
# 1. EventoConfirmarRequest — añadir campo pendienteHora
# ══════════════════════════════════════════════════════════════
cat > src/main/java/com/miempresa/fasi/calendario/EventoConfirmarRequest.java << 'EOF'
package com.miempresa.fasi.calendario;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class EventoConfirmarRequest {
    private Evento.TipoEvento tipo;
    private String            titulo;
    private LocalDate         fechaInicio;
    private LocalDate         fechaFin;
    private LocalTime         horaInicio;
    private LocalTime         horaFin;
    private boolean           todoDia;
    private String            lugar;
    private String            personasRelacionadas;
    private String            descripcion;
    private Integer           recordatorioMinutos;
    private Map<String, Object> datosExtra;
    private String            textoOriginalVoz;

    public Evento toEvento() {
        Evento e = new Evento();
        e.setTipo(tipo != null ? tipo : Evento.TipoEvento.OTRO);
        e.setTitulo(titulo);
        e.setFechaInicio(fechaInicio);
        e.setFechaFin(fechaFin);
        e.setHoraInicio(horaInicio);
        e.setHoraFin(horaFin);
        e.setTodoDia(todoDia);
        e.setLugar(lugar);
        e.setPersonasRelacionadas(personasRelacionadas);
        e.setDescripcion(descripcion);
        e.setRecordatorioMinutos(recordatorioMinutos);
        e.setTextoOriginalVoz(textoOriginalVoz);

        Map<String, Object> extra = datosExtra != null
            ? new HashMap<>(datosExtra)
            : new HashMap<>();

        // Si no tiene hora y no es todo el día → marcar pendiente
        if (horaInicio == null && !todoDia) {
            extra.put("pendienteHora", true);
        }

        e.setDatosExtra(extra.isEmpty() ? null : extra);
        return e;
    }
}
EOF
echo "  ✅ EventoConfirmarRequest.java"

# ══════════════════════════════════════════════════════════════
# 2. EventoIaService — prompt actualizado con reglas de validación
# ══════════════════════════════════════════════════════════════
# Sólo actualizamos buildPromptInterpretar dentro del service
# usando python para reemplazar únicamente ese método
python3 << 'PYEOF'
path = "src/main/java/com/miempresa/fasi/calendario/EventoIaService.java"
with open(path, "r") as f:
    content = f.read()

old_method = '''    private String buildPromptInterpretar(EventoIaRequest req) {
        String fechaHoy = req.getFechaContexto() != null
                ? req.getFechaContexto()
                : LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        StringBuilder sb = new StringBuilder();
        sb.append("Eres un asistente de calendario. Convierte el texto en JSON.\\n\\n");
        sb.append("FECHA HOY: ").append(fechaHoy).append("\\n\\n");
        sb.append("TIPOS: CUMPLEANOS, CITA_MEDICA, TUTORIA, CLAUSTRO, CURSO, EXTRAESCOLAR, VIAJE, VACACIONES, COMIDA, COMPRAS, OTRO\\n\\n");
        sb.append("REGLAS CRITICAS:\\n");
        sb.append("1. Si el usuario pide VARIOS eventos distintos -> devuelve ARRAY JSON: [{...},{...}]\\n");
        sb.append("2. Si el usuario pide UN solo evento -> devuelve OBJETO JSON: {...}\\n");
        sb.append("3. Responde SOLO con JSON valido. Sin texto extra. Sin markdown. Sin ```\\n");
        sb.append("4. fechaInicio formato YYYY-MM-DD.\\n");
        sb.append("5. horaInicio formato HH:mm:ss. Ejemplo: 16:00:00\\n");
        sb.append("6. Si es recurrente -> recurrente: true, patron: WEEKLY\\n");
        sb.append("7. titulo: maximo 60 caracteres.\\n\\n");
        sb.append("EJEMPLO ARRAY:\\n");
        sb.append("[\\n");
        sb.append("  {\\"tipo\\":\\"CUMPLEANOS\\",\\"titulo\\":\\"Cumpleanos Carlota\\",\\"fechaInicio\\":\\"2026-05-26\\",\\"horaInicio\\":\\"16:00:00\\",\\"todoDia\\":false,\\"lugar\\":\\"Costaballena\\",\\"personasRelacionadas\\":\\"Carlota\\",\\"descripcion\\":null,\\"datosExtra\\":{}},\\n");
        sb.append("  {\\"tipo\\":\\"CUMPLEANOS\\",\\"titulo\\":\\"Cumpleanos Cristian\\",\\"fechaInicio\\":\\"2026-05-23\\",\\"horaInicio\\":\\"15:00:00\\",\\"todoDia\\":false,\\"lugar\\":\\"Playa de la Jara\\",\\"personasRelacionadas\\":\\"Cristian\\",\\"descripcion\\":null,\\"datosExtra\\":{}}\\n");
        sb.append("]\\n\\n");
        sb.append("EJEMPLO OBJETO:\\n");
        sb.append("{\\"tipo\\":\\"CITA_MEDICA\\",\\"titulo\\":\\"Cita medica\\",\\"fechaInicio\\":\\"2026-05-14\\",\\"horaInicio\\":\\"10:00:00\\",\\"todoDia\\":false,\\"lugar\\":null,\\"personasRelacionadas\\":null,\\"descripcion\\":null,\\"datosExtra\\":{}}\\n\\n");
        sb.append("TEXTO: \\"").append(req.getTexto()).append("\\"\\n\\n");
        sb.append("JSON:");
        return sb.toString();
    }'''

new_method = '''    private String buildPromptInterpretar(EventoIaRequest req) {
        String fechaHoy = req.getFechaContexto() != null
                ? req.getFechaContexto()
                : LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        StringBuilder sb = new StringBuilder();
        sb.append("Eres un asistente de calendario. Convierte el texto en JSON.\\n\\n");
        sb.append("FECHA HOY: ").append(fechaHoy).append("\\n\\n");
        sb.append("TIPOS: CUMPLEANOS, CITA_MEDICA, TUTORIA, CLAUSTRO, CURSO, EXTRAESCOLAR, VIAJE, VACACIONES, COMIDA, COMPRAS, OTRO\\n\\n");
        sb.append("REGLAS CRITICAS:\\n");
        sb.append("1. Si el usuario pide VARIOS eventos distintos -> devuelve ARRAY JSON: [{...},{...}]\\n");
        sb.append("2. Si el usuario pide UN solo evento -> devuelve OBJETO JSON: {...}\\n");
        sb.append("3. Responde SOLO con JSON valido. Sin texto extra. Sin markdown. Sin ```\\n");
        sb.append("4. fechaInicio formato YYYY-MM-DD. OBLIGATORIO siempre.\\n");
        sb.append("5. horaInicio formato HH:mm:ss. Si NO se menciona hora -> pon null. NUNCA inventes una hora.\\n");
        sb.append("6. todoDia: true SOLO si el usuario dice explicitamente todo el dia o es un cumpleanos sin hora.\\n");
        sb.append("7. Si es recurrente -> recurrente: true, patron: WEEKLY\\n");
        sb.append("8. titulo: maximo 60 caracteres.\\n\\n");
        sb.append("EJEMPLO SIN HORA:\\n");
        sb.append("{\\"tipo\\":\\"CITA_MEDICA\\",\\"titulo\\":\\"Cita medica\\",\\"fechaInicio\\":\\"2026-05-14\\",\\"horaInicio\\":null,\\"todoDia\\":false,\\"lugar\\":null,\\"personasRelacionadas\\":null,\\"descripcion\\":null,\\"datosExtra\\":{}}\\n\\n");
        sb.append("EJEMPLO CON HORA:\\n");
        sb.append("{\\"tipo\\":\\"CITA_MEDICA\\",\\"titulo\\":\\"Cita medica\\",\\"fechaInicio\\":\\"2026-05-14\\",\\"horaInicio\\":\\"10:00:00\\",\\"todoDia\\":false,\\"lugar\\":null,\\"personasRelacionadas\\":null,\\"descripcion\\":null,\\"datosExtra\\":{}}\\n\\n");
        sb.append("EJEMPLO ARRAY:\\n");
        sb.append("[\\n");
        sb.append("  {\\"tipo\\":\\"CUMPLEANOS\\",\\"titulo\\":\\"Cumpleanos Carlota\\",\\"fechaInicio\\":\\"2026-05-26\\",\\"horaInicio\\":\\"16:00:00\\",\\"todoDia\\":false,\\"lugar\\":\\"Costaballena\\",\\"personasRelacionadas\\":\\"Carlota\\",\\"descripcion\\":null,\\"datosExtra\\":{}},\\n");
        sb.append("  {\\"tipo\\":\\"CUMPLEANOS\\",\\"titulo\\":\\"Cumpleanos Cristian\\",\\"fechaInicio\\":\\"2026-05-23\\",\\"horaInicio\\":null,\\"todoDia\\":true,\\"lugar\\":\\"Playa de la Jara\\",\\"personasRelacionadas\\":\\"Cristian\\",\\"descripcion\\":null,\\"datosExtra\\":{}}\\n");
        sb.append("]\\n\\n");
        sb.append("TEXTO: \\"").append(req.getTexto()).append("\\"\\n\\n");
        sb.append("JSON:");
        return sb.toString();
    }'''

if old_method in content:
    content = content.replace(old_method, new_method)
    print("  ✅ buildPromptInterpretar actualizado")
else:
    print("  ⚠️  No se encontró buildPromptInterpretar exacto — revisa manualmente")

with open(path, "w") as f:
    f.write(content)
PYEOF

# ══════════════════════════════════════════════════════════════
# 3. EventoRepository — añadir query para pendientes de datos
# ══════════════════════════════════════════════════════════════
python3 << 'PYEOF'
path = "src/main/java/com/miempresa/fasi/calendario/EventoRepository.java"
with open(path, "r") as f:
    content = f.read()

nueva_query = '''
    // Eventos con datos pendientes (sin hora, sin lugar, etc.)
    @Query(value = "SELECT * FROM eventos WHERE datos_extra @> '{\\\"pendienteHora\\\": true}'::jsonb ORDER BY fecha_inicio ASC", nativeQuery = true)
    List<Evento> findEventosPendientesHora();

    @Query(value = "SELECT * FROM eventos WHERE datos_extra @> '{\\\"pendienteHora\\\": true}'::jsonb OR datos_extra @> '{\\\"pendienteLugar\\\": true}'::jsonb ORDER BY fecha_inicio ASC", nativeQuery = true)
    List<Evento> findEventosPendientesDatos();
'''

# Insertar antes del último }
last_brace = content.rfind('}')
content = content[:last_brace] + nueva_query + '\n}'

with open(path, "w") as f:
    f.write(content)
print("  ✅ EventoRepository.java — queries pendientes añadidas")
PYEOF

# ══════════════════════════════════════════════════════════════
# 4. CalendarioController — endpoint búsqueda pendientes
# ══════════════════════════════════════════════════════════════
python3 << 'PYEOF'
path = "src/main/java/com/miempresa/fasi/calendario/CalendarioController.java"
with open(path, "r") as f:
    content = f.read()

nuevo_endpoint = '''
    @GetMapping("/api/pendientes-datos")
    @ResponseBody
    public List<Evento> getPendientesDatos() {
        return eventoService.getEventosPendientesDatos();
    }
'''

# Insertar antes del último }
last_brace = content.rfind('}')
content = content[:last_brace] + nuevo_endpoint + '\n}'

with open(path, "w") as f:
    f.write(content)
print("  ✅ CalendarioController.java — endpoint /api/pendientes-datos añadido")
PYEOF

# ══════════════════════════════════════════════════════════════
# 5. EventoService — método getEventosPendientesDatos
# ══════════════════════════════════════════════════════════════
python3 << 'PYEOF'
path = "src/main/java/com/miempresa/fasi/calendario/EventoService.java"
with open(path, "r") as f:
    content = f.read()

nuevo_metodo = '''
    public List<Evento> getEventosPendientesDatos() {
        return repo.findEventosPendientesDatos();
    }
'''

last_brace = content.rfind('}')
content = content[:last_brace] + nuevo_metodo + '\n}'

with open(path, "w") as f:
    f.write(content)
print("  ✅ EventoService.java — getEventosPendientesDatos añadido")
PYEOF

# ══════════════════════════════════════════════════════════════
# 6. index.html — voz continua + badge pendiente + búsqueda predefinida
# ══════════════════════════════════════════════════════════════
python3 << 'PYEOF'
import re
path = "src/main/resources/templates/calendario/index.html"
with open(path, "r") as f:
    content = f.read()

# ── 6a. Reemplazar bloque de SpeechRecognition ───────────────
old_voz = """      recognition.continuous    = false;
      recognition.interimResults = false;
      recognition.lang           = 'es-ES';"""

new_voz = """      recognition.continuous    = true;
      recognition.interimResults = true;
      recognition.lang           = 'es-ES';"""

if old_voz in content:
    content = content.replace(old_voz, new_voz)
    print("  ✅ recognition params actualizados")
else:
    # Intentar variantes
    old_voz2 = "recognition.continuous    = false;"
    if old_voz2 in content:
        content = content.replace(old_voz2, "recognition.continuous    = true;")
        content = content.replace("recognition.interimResults = false;", "recognition.interimResults = true;")
        print("  ✅ recognition params actualizados (variante)")
    else:
        print("  ⚠️  No se encontró recognition.continuous — revisa manualmente")

# ── 6b. Reemplazar onresult para acumular texto ──────────────
old_onresult = """      recognition.onresult = function(event) {
        var texto = '';
        for (var i = event.resultIndex; i < event.results.length; i++) {
          texto += event.results[i][0].transcript;
        }
        document.getElementById('voz-texto').value = texto;
      };"""

new_onresult = """      var textoAcumulado = '';
      recognition.onresult = function(event) {
        var interino = '';
        for (var i = event.resultIndex; i < event.results.length; i++) {
          if (event.results[i].isFinal) {
            textoAcumulado += event.results[i][0].transcript + ' ';
          } else {
            interino += event.results[i][0].transcript;
          }
        }
        // Mostrar texto final acumulado + interino en gris
        var textarea = document.getElementById('voz-texto');
        textarea.value = textoAcumulado + interino;
      };
      recognition.onend = function() {
        // Si estaba grabando, reiniciar para mantener continuo
        if (grabando) {
          try { recognition.start(); } catch(e) {}
        }
      };"""

if old_onresult in content:
    content = content.replace(old_onresult, new_onresult)
    print("  ✅ recognition.onresult actualizado con acumulación")
else:
    print("  ⚠️  No se encontró recognition.onresult exacto — aplicando reemplazo parcial")
    # Reemplazo más tolerante
    content = re.sub(
        r'recognition\.onresult\s*=\s*function\(event\)\s*\{.*?^\s*\};',
        new_onresult,
        content,
        flags=re.DOTALL | re.MULTILINE
    )

# ── 6c. Resetear textoAcumulado al abrir modal ───────────────
old_abrir = "function abrirModalVoz() {"
new_abrir = """function abrirModalVoz() {
      textoAcumulado = '';"""

content = content.replace(old_abrir, new_abrir, 1)

# ── 6d. Badge ⚠️ en pills de eventos con pendienteHora ───────
# Buscar donde se renderizan los pills y añadir badge
old_pill = "var pill = document.createElement('div');"
new_pill = """var pill = document.createElement('div');
          // Badge pendiente de datos
          var tienePendiente = ev.datosExtra && ev.datosExtra.pendienteHora;"""

content = content.replace(old_pill, new_pill, 1)

# Buscar donde se pone el innerHTML del pill para añadir el badge
old_pill_inner = "pill.innerHTML = tipoInfo.emoji + ' ' + ev.titulo;"
new_pill_inner = """var badgePendiente = tienePendiente ? ' <span class=\"text-yellow-400 text-xs\">⚠️</span>' : '';
          pill.innerHTML = tipoInfo.emoji + ' ' + ev.titulo + badgePendiente;"""

if old_pill_inner in content:
    content = content.replace(old_pill_inner, new_pill_inner, 1)
    print("  ✅ Badge ⚠️ en pills añadido")
else:
    print("  ⚠️  No se encontró pill.innerHTML exacto — badge pendiente requiere ajuste manual")

# ── 6e. Preview — mostrar aviso si falta hora ────────────────
old_preview_fn = "function mostrarPreview(evento) {"
new_preview_fn = """function mostrarPreview(evento) {
      // Aviso si no tiene hora
      var avisoHora = (!evento.horaInicio && !evento.todoDia)
        ? '<div class="mt-2 p-2 bg-yellow-50 dark:bg-yellow-900/30 border border-yellow-300 rounded text-xs text-yellow-700 dark:text-yellow-300">⚠️ No se especificó hora — se guardará como <strong>pendiente de hora</strong></div>'
        : '';"""

content = content.replace(old_preview_fn, new_preview_fn, 1)

# Añadir avisoHora al contenido del preview
old_preview_contenido = "document.getElementById('preview-contenido').innerHTML = html;"
new_preview_contenido = "document.getElementById('preview-contenido').innerHTML = html + avisoHora;"

content = content.replace(old_preview_contenido, new_preview_contenido, 1)

# ── 6f. Búsqueda predefinida — añadir "Pendientes de datos" ──
old_sugerencias = "var sugerencias = ["
new_sugerencias = """var sugerencias = [
        { label: '⚠️ Pendientes de datos', query: 'pendientes-datos', tipo: 'especial' },"""

if old_sugerencias in content:
    content = content.replace(old_sugerencias, new_sugerencias, 1)
    print("  ✅ Sugerencia 'Pendientes de datos' añadida")
else:
    print("  ⚠️  No se encontró array sugerencias — revisa manualmente")

# ── 6g. Manejar búsqueda especial pendientes-datos ───────────
old_buscar = "async function buscarEventos(query) {"
new_buscar = """async function buscarEventos(query) {
      // Búsqueda especial: pendientes de datos
      if (query === 'pendientes-datos') {
        var resp = await fetch('/calendario/api/pendientes-datos');
        var eventos = await resp.json();
        mostrarResultadosBusqueda(eventos, '⚠️ Eventos pendientes de datos (' + eventos.length + ')');
        return;
      }"""

if old_buscar in content:
    content = content.replace(old_buscar, new_buscar, 1)
    print("  ✅ Lógica búsqueda pendientes-datos añadida")
else:
    print("  ⚠️  No se encontró buscarEventos — revisa manualmente")

with open(path, "w") as f:
    f.write(content)

print("  ✅ calendario/index.html actualizado")
PYEOF

echo ""
echo "✅ Todos los cambios aplicados."
echo ""
echo "📦 Recompila con:"
echo "   ./mvnw clean package -DskipTests && docker compose up -d --build"
