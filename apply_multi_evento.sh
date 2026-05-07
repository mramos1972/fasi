#!/bin/bash
set -e
BASE="src/main/java/com/miempresa/fasi"
TMPL="src/main/resources/templates/calendario"

echo "🚀 Aplicando cambios multi-evento..."

# ══════════════════════════════════════════════════════════════
# 1. EventoIaResponse — añadir lista + helper isMultiple
# ══════════════════════════════════════════════════════════════
cat > "$BASE/calendario/EventoIaResponse.java" << 'EOF'
package com.miempresa.fasi.calendario;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventoIaResponse {
    private boolean      exito;
    private String       mensajeError;
    private Evento       eventoPreview;
    private List<Evento> eventosPreview;
    private long         tiempoMs;

    // Constructor compatibilidad hacia atrás (evento único)
    public EventoIaResponse(boolean exito, String mensajeError, Evento eventoPreview, long tiempoMs) {
        this.exito         = exito;
        this.mensajeError  = mensajeError;
        this.eventoPreview = eventoPreview;
        this.tiempoMs      = tiempoMs;
    }

    public boolean isMultiple() {
        return eventosPreview != null && !eventosPreview.isEmpty();
    }
}
EOF
echo "  ✅ EventoIaResponse.java"

# ══════════════════════════════════════════════════════════════
# 2. EventoIaService — detectar array vs objeto
# ══════════════════════════════════════════════════════════════
cat > "$BASE/calendario/EventoIaService.java" << 'EOF'
package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miempresa.fasi.ia.OllamaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventoIaService {

    private final OllamaClient     ollamaClient;
    private final EventoRepository repo;
    private final ObjectMapper     objectMapper;

    // ── Interpretar texto → Evento/s (preview) ───────────────
    public EventoIaResponse interpretar(EventoIaRequest request) {
        long inicio = System.currentTimeMillis();
        try {
            String prompt    = buildPromptInterpretar(request);
            String json      = ollamaClient.generate(prompt, 768);
            String jsonLimpio = extraerJson(json);

            log.debug("JSON recibido de IA: {}", jsonLimpio);

            String trimmed = jsonLimpio.trim();

            // ── MÚLTIPLES EVENTOS (array JSON) ────────────────
            if (trimmed.startsWith("[")) {
                List<EventoConfirmarRequest> dtos = objectMapper.readValue(
                    trimmed,
                    objectMapper.getTypeFactory()
                        .constructCollectionType(List.class, EventoConfirmarRequest.class)
                );

                List<Evento> previews = new ArrayList<>();
                for (EventoConfirmarRequest dto : dtos) {
                    Evento e = dto.toEvento();
                    e.setTextoOriginalVoz(request.getTexto());
                    previews.add(e);
                }

                log.info("IA detectó {} eventos múltiples", previews.size());
                return new EventoIaResponse(true, null, null, previews,
                        System.currentTimeMillis() - inicio);
            }

            // ── EVENTO ÚNICO (objeto JSON) ────────────────────
            ObjectNode node = (ObjectNode) objectMapper.readTree(trimmed);

            normalizarHora(node, "horaInicio");
            normalizarHora(node, "horaFin");

            boolean esRecurrente = node.has("recurrente") && node.get("recurrente").asBoolean(false);
            String  patron       = node.has("patron")     ? node.get("patron").asText(null) : null;

            node.remove("recurrente");
            node.remove("patron");

            Evento evento = objectMapper.treeToValue(node, Evento.class);
            evento.setTextoOriginalVoz(request.getTexto());

            if (esRecurrente && patron != null) {
                java.util.Map<String, Object> extra = evento.getDatosExtra() != null
                    ? new HashMap<>(evento.getDatosExtra())
                    : new HashMap<>();
                extra.put("recurrente", true);
                extra.put("patron", patron);
                extra.put("textoRecurrencia", request.getTexto());
                if (node.has("diaSemana") && !node.get("diaSemana").isNull()) {
                    extra.put("diaSemana", node.get("diaSemana").asText());
                }
                evento.setDatosExtra(extra);
            }

            return new EventoIaResponse(true, null, evento, null,
                    System.currentTimeMillis() - inicio);

        } catch (Exception e) {
            log.error("Error interpretando texto: {}", e.getMessage());
            return new EventoIaResponse(false,
                    "No pude interpretar el texto. Inténtalo de nuevo o crea el evento manualmente.",
                    null, null, System.currentTimeMillis() - inicio);
        }
    }

    private void normalizarHora(ObjectNode node, String campo) {
        if (!node.has(campo) || node.get(campo).isNull()) return;
        String val = node.get(campo).asText("").trim()
                         .replaceAll("(?i)\\s*[Hh].*$", "").trim();
        if (val.isEmpty() || val.equals("null")) { node.putNull(campo); return; }
        if (val.matches("\\d{1,2}:\\d{2}$"))     val = val + ":00";
        if (val.matches("\\d{1,2}$"))             val = val + ":00:00";
        node.put(campo, val);
    }

    // ── Consulta en lenguaje natural sobre el calendario ─────
    @Transactional(readOnly = true)
    public String consultar(String pregunta) {
        String contexto = buildContextoCalendario();
        String prompt   = buildPromptConsulta(pregunta, contexto);
        return ollamaClient.generate(prompt);
    }

    // ── Prompt interpretar ────────────────────────────────────
    private String buildPromptInterpretar(EventoIaRequest req) {
        String fechaHoy = req.getFechaContexto() != null
                ? req.getFechaContexto()
                : LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String texto = req.getTexto();

        StringBuilder sb = new StringBuilder();
        sb.append("Eres un asistente de calendario. Convierte el texto en JSON.\n\n");
        sb.append("FECHA HOY: ").append(fechaHoy).append("\n\n");
        sb.append("TIPOS: CUMPLEANOS, CITA_MEDICA, TUTORIA, CLAUSTRO, CURSO, EXTRAESCOLAR, VIAJE, VACACIONES, COMIDA, COMPRAS, OTRO\n\n");

        sb.append("REGLAS CRITICAS:\n");
        sb.append("1. Si el usuario pide VARIOS eventos distintos -> devuelve un ARRAY JSON: [ {...}, {...} ]\n");
        sb.append("2. Si el usuario pide UN solo evento -> devuelve un OBJETO JSON: { ... }\n");
        sb.append("3. Responde SOLO con JSON valido. Sin texto extra. Sin markdown. Sin ```\n");
        sb.append("4. fechaInicio formato YYYY-MM-DD.\n");
        sb.append("5. horaInicio formato HH:mm:ss. Ejemplo: 16:00:00\n");
        sb.append("6. Si es recurrente -> recurrente: true, patron: WEEKLY\n");
        sb.append("7. titulo: maximo 60 caracteres.\n\n");

        sb.append("EJEMPLOS DE ARRAY (varios eventos):\n");
        sb.append("Texto: \"Cumpleanos de Ana el 3 de junio y cita medica el 10 de junio a las 9\"\n");
        sb.append("Respuesta:\n");
        sb.append("[\n");
        sb.append("  {\"tipo\":\"CUMPLEANOS\",\"titulo\":\"Cumpleanos Ana\",\"fechaInicio\":\"2026-06-03\",\"horaInicio\":null,\"todoDia\":true,\"lugar\":null,\"personasRelacionadas\":\"Ana\",\"descripcion\":null,\"datosExtra\":{}},\n");
        sb.append("  {\"tipo\":\"CITA_MEDICA\",\"titulo\":\"Cita medica\",\"fechaInicio\":\"2026-06-10\",\"horaInicio\":\"09:00:00\",\"todoDia\":false,\"lugar\":null,\"personasRelacionadas\":null,\"descripcion\":null,\"datosExtra\":{}}\n");
        sb.append("]\n\n");

        sb.append("EJEMPLO OBJETO (un evento):\n");
        sb.append("Texto: \"Cita medica el jueves a las 10\"\n");
        sb.append("Respuesta:\n");
        sb.append("{\"tipo\":\"CITA_MEDICA\",\"titulo\":\"Cita medica\",\"fechaInicio\":\"2026-05-14\",\"horaInicio\":\"10:00:00\",\"todoDia\":false,\"lugar\":null,\"personasRelacionadas\":null,\"descripcion\":null,\"datosExtra\":{}}\n\n");

        sb.append("TEXTO: \"").append(texto).append("\"\n\n");
        sb.append("JSON:");
        return sb.toString();
    }

    private String buildPromptConsulta(String pregunta, String contexto) {
        return """
            Eres FASI-IA, asistente inteligente del calendario FASI.
            Tienes acceso a los eventos REALES del calendario que se muestran a continuación.

            REGLAS:
            1. Responde ÚNICAMENTE con datos del contexto proporcionado.
            2. Si un dato no está, responde: 'No tengo ese dato en el calendario.'
            3. NUNCA inventes información.
            4. Responde en español, de forma clara y estructurada.
            5. Usa emojis con moderación.

            %s

            PREGUNTA: %s

            RESPUESTA:
            """.formatted(contexto, pregunta);
    }

    private String buildContextoCalendario() {
        StringBuilder ctx = new StringBuilder("=== EVENTOS DEL CALENDARIO ===\n\n");
        LocalDate hoy = LocalDate.now();
        LocalDate fin = hoy.plusMonths(3);
        List<Evento> proximos = repo
                .findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(hoy, fin);
        if (proximos.isEmpty()) {
            ctx.append("(No hay eventos próximos registrados)\n");
        } else {
            ctx.append("Próximos 3 meses:\n");
            proximos.forEach(e -> ctx.append(String.format(
                    "  [%s] %s %s | %s%s%s\n",
                    e.getFechaInicio(), e.getTipo().getEmoji(), e.getTitulo(),
                    e.getHoraInicio() != null ? "a las " + e.getHoraInicio() + " " : "",
                    e.getLugar() != null ? "en " + e.getLugar() + " " : "",
                    e.getPersonasRelacionadas() != null ? "· " + e.getPersonasRelacionadas() : ""
            )));
        }
        ctx.append("\nRESUMEN POR TIPO:\n");
        for (Evento.TipoEvento tipo : Evento.TipoEvento.values()) {
            long count = repo.findByTipoOrderByFechaInicioAsc(tipo).size();
            if (count > 0) ctx.append(String.format("  %s %s: %d eventos\n",
                    tipo.getEmoji(), tipo.getLabel(), count));
        }
        return ctx.toString();
    }

    private String extraerJson(String respuesta) {
        String trimmed = respuesta.trim();
        // Intentar extraer array primero
        int arrInicio = trimmed.indexOf('[');
        int arrFin    = trimmed.lastIndexOf(']');
        int objInicio = trimmed.indexOf('{');
        int objFin    = trimmed.lastIndexOf('}');

        // Si hay array Y empieza antes que el objeto → es array
        if (arrInicio >= 0 && arrFin > arrInicio &&
            (objInicio < 0 || arrInicio < objInicio)) {
            return trimmed.substring(arrInicio, arrFin + 1);
        }
        // Si hay objeto
        if (objInicio >= 0 && objFin > objInicio) {
            return trimmed.substring(objInicio, objFin + 1);
        }
        return trimmed;
    }
}
EOF
echo "  ✅ EventoIaService.java"

# ══════════════════════════════════════════════════════════════
# 3. index.html — actualizar JS del modal
# ══════════════════════════════════════════════════════════════
# Usamos python3 para hacer el reemplazo limpio del bloque JS
python3 - << 'PYEOF'
import re

path = "src/main/resources/templates/calendario/index.html"
with open(path, "r") as f:
    content = f.read()

# ── Reemplazar función interpretarTexto ──────────────────────
old_interpretar = r'async function interpretarTexto\(\) \{.*?^\s*\}'
new_interpretar = '''async function interpretarTexto() {
      var texto = document.getElementById('voz-texto').value.trim();
      if (!texto) { alert('Escribe o dicta algo primero.'); return; }
      document.getElementById('ia-spinner').style.display     = 'flex';
      document.getElementById('preview-evento').style.display = 'none';
      try {
        var resp = await fetch('/calendario/api/interpretar', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ texto: texto, fechaContexto: HOY })
        });
        var data = await resp.json();
        if (data.exito) {
          if (data.multiple && data.eventosPreview && data.eventosPreview.length > 0) {
            eventoActual = data.eventosPreview;
            mostrarPreviewMultiple(data.eventosPreview);
          } else if (data.eventoPreview) {
            eventoActual = data.eventoPreview;
            mostrarPreview(data.eventoPreview);
          } else {
            alert('La IA no devolvió ningún evento.');
          }
        } else {
          alert('Error: ' + (data.mensajeError || 'No se pudo interpretar'));
        }
      } catch (err) {
        alert('Error: ' + err.message);
      } finally {
        document.getElementById('ia-spinner').style.display = 'none';
      }
    }'''

content = re.sub(old_interpretar, new_interpretar, content, flags=re.DOTALL | re.MULTILINE)

# ── Reemplazar función confirmarEvento ───────────────────────
old_confirmar = r'async function confirmarEvento\(\) \{.*?^\s*\}'
new_confirmar = '''async function confirmarEvento() {
      if (!eventoActual) return;

      // ── Lista de eventos ────────────────────────────────────
      if (Array.isArray(eventoActual)) {
        var guardados = 0;
        var errores   = 0;
        for (var i = 0; i < eventoActual.length; i++) {
          var ev     = eventoActual[i];
          var esRec  = ev.datosExtra && ev.datosExtra.recurrente === true;
          var ep     = esRec ? '/calendario/api/confirmar-recurrente' : '/calendario/api/confirmar';
          try {
            var r = await fetch(ep, {
              method: 'POST',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify(ev)
            });
            if (r.ok) guardados++; else errores++;
          } catch(e) { errores++; }
        }
        var msg = guardados + ' evento' + (guardados !== 1 ? 's' : '') + ' creado' + (guardados !== 1 ? 's' : '');
        if (errores > 0) msg += ' (' + errores + ' con error)';
        alert(msg);
        cerrarModalVoz();
        window.location.reload();
        return;
      }

      // ── Evento único ────────────────────────────────────────
      var esRec    = eventoActual.datosExtra && eventoActual.datosExtra.recurrente === true;
      var endpoint = esRec ? '/calendario/api/confirmar-recurrente' : '/calendario/api/confirmar';
      try {
        var resp = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(eventoActual)
        });
        if (resp.ok) {
          var data = await resp.json();
          if (esRec && Array.isArray(data)) alert(data.length + ' eventos creados.');
          cerrarModalVoz();
          window.location.reload();
        } else {
          alert('Error al guardar.');
        }
      } catch (err) {
        alert('Error: ' + err.message);
      }
    }'''

content = re.sub(old_confirmar, new_confirmar, content, flags=re.DOTALL | re.MULTILINE)

# ── Añadir función mostrarPreviewMultiple antes de confirmarEvento ──
if 'mostrarPreviewMultiple' not in content:
    nueva_funcion = '''
    function mostrarPreviewMultiple(eventos) {
      var c    = document.getElementById('preview-contenido');
      var html = '<p class="font-semibold text-sm mb-2 text-indigo-600">' + eventos.length + ' eventos detectados:</p>';
      eventos.forEach(function(e, i) {
        var emoji = (e.tipo && e.tipo.emoji) ? e.tipo.emoji : '';
        var color = (e.tipo && e.tipo.color) ? e.tipo.color : '#6366f1';
        var hora  = e.horaInicio ? ' · ' + e.horaInicio.substring(0,5) : '';
        var lugar = e.lugar ? ' · ' + e.lugar : '';
        var pers  = e.personasRelacionadas ? ' · ' + e.personasRelacionadas : '';
        html += '<div class="border-l-2 pl-2 mb-2 py-0.5" style="border-color:' + color + '">'
              + '<p class="text-xs font-semibold text-gray-800 dark:text-gray-100">' + emoji + ' ' + e.titulo + '</p>'
              + '<p class="text-xs text-gray-400">' + (e.fechaInicio || '') + hora + lugar + pers + '</p>'
              + '</div>';
      });
      c.innerHTML = html;

      var btn = document.getElementById('btn-confirmar');
      if (btn) {
        btn.textContent = '✅ Confirmar ' + eventos.length + ' eventos';
        btn.className = btn.className
          .replace(/bg-green-\\d+/g, '').replace(/hover:bg-green-\\d+/g, '')
          .replace(/bg-indigo-\\d+/g, '').replace(/hover:bg-indigo-\\d+/g, '');
        btn.className += ' bg-indigo-600 hover:bg-indigo-700';
      }
      document.getElementById('preview-evento').style.display = 'block';
    }

    '''
    # Insertar antes de confirmarEvento
    content = content.replace('    async function confirmarEvento()', nueva_funcion + '    async function confirmarEvento()')

with open(path, "w") as f:
    f.write(content)

print("  ✅ calendario/index.html")
PYEOF

echo ""
echo "✅ Todos los cambios aplicados."
echo ""
echo "📦 Recompila con:"
echo "   ./mvnw clean package -DskipTests && docker compose up -d --build"
