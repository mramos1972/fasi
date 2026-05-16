#!/usr/bin/env bash
# ════════════════════════════════════════════════════════════
#  FASI — Optimización IA: Capas 1, 2 y 3
#  - Capa 1: OllamaClient con generateJson() + format:"json"
#  - Capa 2: EventoIaService con prompt minimalista
#  - Capa 3: IaContextBuilder con filtrado inteligente
# ════════════════════════════════════════════════════════════
set -euo pipefail

STAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="backups/ia_optimization_$STAMP"
BASE="src/main/java/com/miempresa/fasi"

mkdir -p "$BACKUP_DIR"

echo "🚀 FASI — Aplicando optimización IA (Capas 1+2+3)"
echo "═══════════════════════════════════════════════════"

# ── BACKUPS ──────────────────────────────────────────────
echo ""
echo "💾 Creando backups en $BACKUP_DIR..."
cp "$BASE/ia/OllamaClient.java"               "$BACKUP_DIR/OllamaClient.java.bak"
cp "$BASE/ia/IaContextBuilder.java"           "$BACKUP_DIR/IaContextBuilder.java.bak"
cp "$BASE/calendario/EventoIaService.java"    "$BACKUP_DIR/EventoIaService.java.bak"
echo "✅ Backups OK"

# ════════════════════════════════════════════════════════════
#  CAPA 1: OllamaClient.java
# ════════════════════════════════════════════════════════════
echo ""
echo "🔧 [Capa 1/3] Reescribiendo OllamaClient.java..."

cat > "$BASE/ia/OllamaClient.java" <<'JAVA'
package com.miempresa.fasi.ia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaClient {

    @Value("${fasi.ollama.url:http://localhost:11434}")
    private String ollamaUrl;

    @Value("${fasi.ollama.model:qwen2.5:3b}")
    private String model;

    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ════════════════════════════════════════════════════════
    //  Generación estándar (chat / consulta libre)
    // ════════════════════════════════════════════════════════
    public String generate(String prompt) {
        return generate(prompt, 1024);
    }

    public String generate(String prompt, int numCtx) {
        try {
            OllamaRequest req = new OllamaRequest();
            req.setModel(model);
            req.setPrompt(prompt);
            req.setStream(false);
            req.setKeep_alive(-1);
            req.setOptions(Map.of(
                "num_ctx",     numCtx,
                "temperature", 0,
                "top_k",       20,
                "top_p",       0.7
            ));

            return callOllama(req);

        } catch (Exception e) {
            log.error("Error llamando a Ollama", e);
            return "⚠️ Error al conectar con el asistente IA. " +
                   "Asegúrate de que Ollama está corriendo en WSL (ollama serve).";
        }
    }

    // ════════════════════════════════════════════════════════
    //  Generación JSON-mode (extracción de eventos)
    //  - format:"json" garantiza salida JSON válida
    //  - num_predict limita la salida -> más rápido
    //  - top_k=10 -> menos creatividad, más velocidad
    // ════════════════════════════════════════════════════════
    public String generateJson(String prompt, int numCtx, int maxOutputTokens) {
        try {
            OllamaRequest req = new OllamaRequest();
            req.setModel(model);
            req.setPrompt(prompt);
            req.setStream(false);
            req.setKeep_alive(-1);
            req.setFormat("json");
            req.setOptions(Map.of(
                "num_ctx",     numCtx,
                "num_predict", maxOutputTokens,
                "temperature", 0,
                "top_k",       10,
                "top_p",       0.5
            ));

            return callOllama(req);

        } catch (Exception e) {
            log.error("Error llamando a Ollama (JSON mode)", e);
            return "{}";
        }
    }

    // ════════════════════════════════════════════════════════
    //  Warmup
    // ════════════════════════════════════════════════════════
    public void warmup() {
        try {
            log.info("🔥 Precalentando modelo IA ({})...", model);
            OllamaRequest req = new OllamaRequest();
            req.setModel(model);
            req.setPrompt("");
            req.setStream(false);
            req.setKeep_alive(-1);
            req.setOptions(Map.of("num_ctx", 512, "temperature", 0));

            callOllama(req);
            log.info("✅ Modelo IA listo en RAM.");
        } catch (Exception e) {
            log.warn("⚠️ No se pudo precalentar el modelo: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    //  HTTP helper
    // ════════════════════════════════════════════════════════
    private String callOllama(OllamaRequest req) throws Exception {
        String body = objectMapper.writeValueAsString(req);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(120))
                .build();

        HttpResponse<String> resp = httpClient.send(
                httpReq, HttpResponse.BodyHandlers.ofString());

        OllamaResponse ollamaResp =
                objectMapper.readValue(resp.body(), OllamaResponse.class);
        return ollamaResp.getResponse();
    }

    // ════════════════════════════════════════════════════════
    //  DTOs internos
    // ════════════════════════════════════════════════════════
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class OllamaRequest {
        private String              model;
        private String              prompt;
        private boolean             stream;
        private int                 keep_alive;
        private String              format;     // 👈 nuevo: "json" para JSON mode
        private Map<String, Object> options;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OllamaResponse {
        private String  response;
        private boolean done;
    }
}
JAVA

echo "✅ OllamaClient.java reescrito"

# ════════════════════════════════════════════════════════════
#  CAPA 2: EventoIaService.java
# ════════════════════════════════════════════════════════════
echo ""
echo "🔧 [Capa 2/3] Reescribiendo EventoIaService.java..."

cat > "$BASE/calendario/EventoIaService.java" <<'JAVA'
package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miempresa.fasi.ia.IaContextBuilder;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class EventoIaService {

    private final OllamaClient     ollamaClient;
    private final EventoRepository repo;
    private final ObjectMapper     objectMapper;
    private final IaContextBuilder contextBuilder;

    // Parámetros optimizados
    private static final int CTX_INTERPRETAR    = 1024;
    private static final int MAX_OUTPUT_TOKENS  = 400;

    public EventoIaResponse interpretar(EventoIaRequest request) {
        long inicio = System.currentTimeMillis();
        try {
            String prompt = buildPromptInterpretarMinimo(request);
            String json   = ollamaClient.generateJson(prompt, CTX_INTERPRETAR, MAX_OUTPUT_TOKENS);
            String jsonLimpio = extraerJson(json);

            log.debug("JSON recibido de IA: {}", jsonLimpio);

            String trimmed = jsonLimpio.trim();

            // ── MÚLTIPLES EVENTOS ─────────────────────────────
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
                log.info("IA detectó {} eventos múltiples en {}ms",
                        previews.size(), System.currentTimeMillis() - inicio);
                return new EventoIaResponse(true, null, null, previews,
                        System.currentTimeMillis() - inicio);
            }

            // ── EVENTO ÚNICO ──────────────────────────────────
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

            log.info("IA detectó 1 evento en {}ms", System.currentTimeMillis() - inicio);
            return new EventoIaResponse(true, null, evento, null,
                    System.currentTimeMillis() - inicio);

        } catch (Exception e) {
            log.error("Error interpretando texto: {}", e.getMessage());
            return new EventoIaResponse(false,
                    "No pude interpretar el texto. Inténtalo de nuevo.",
                    null, System.currentTimeMillis() - inicio);
        }
    }

    private void normalizarHora(ObjectNode node, String campo) {
        if (!node.has(campo) || node.get(campo).isNull()) return;
        String val = node.get(campo).asText("").trim()
                         .replaceAll("(?i)\\s*[Hh].*$", "").trim();
        if (val.isEmpty() || val.equals("null")) { node.putNull(campo); return; }
        if (val.matches("\\d{1,2}:\\d{2}$")) val = val + ":00";
        if (val.matches("\\d{1,2}$"))        val = val + ":00:00";
        node.put(campo, val);
    }

    @Transactional(readOnly = true)
    public String consultar(String pregunta) {
        String prompt = contextBuilder.buildPrompt(pregunta, "calendario");
        return ollamaClient.generate(prompt, 1536);
    }

    // ════════════════════════════════════════════════════════
    //  Prompt MINIMALISTA — ~200 tokens vs ~800 antes
    // ════════════════════════════════════════════════════════
    private String buildPromptInterpretarMinimo(EventoIaRequest req) {
        String fechaHoy = req.getFechaContexto() != null
                ? req.getFechaContexto()
                : LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        return """
            Extrae eventos de calendario en JSON. Fecha hoy: %s
            Tipos: CUMPLEANOS, CITA_MEDICA, TUTORIA, CLAUSTRO, CURSO, EXTRAESCOLAR, VIAJE, VACACIONES, COMIDA, COMPRAS, OTRO

            Si hay varios eventos -> array. Si hay uno -> objeto.
            Campos: tipo, titulo, fechaInicio (YYYY-MM-DD), horaInicio (HH:mm:ss o null),
            todoDia (bool), lugar, personasRelacionadas, descripcion, datosExtra (objeto vacio {}).
            Para recurrentes: recurrente=true, patron="WEEKLY", diaSemana="LUNES".

            Reglas:
            - Sin hora explicita -> horaInicio:null
            - Cumpleanos sin hora -> todoDia:true
            - Tipo dudoso -> OTRO
            - titulo max 60 chars

            TEXTO: "%s"
            """.formatted(fechaHoy, req.getTexto());
    }

    private String extraerJson(String respuesta) {
        String trimmed = respuesta.trim();
        int arrInicio = trimmed.indexOf('[');
        int arrFin    = trimmed.lastIndexOf(']');
        int objInicio = trimmed.indexOf('{');
        int objFin    = trimmed.lastIndexOf('}');

        if (arrInicio >= 0 && arrFin > arrInicio &&
            (objInicio < 0 || arrInicio < objInicio)) {
            return trimmed.substring(arrInicio, arrFin + 1);
        }
        if (objInicio >= 0 && objFin > objInicio) {
            return trimmed.substring(objInicio, objFin + 1);
        }
        return trimmed;
    }
}
JAVA

echo "✅ EventoIaService.java reescrito"

# ════════════════════════════════════════════════════════════
#  CAPA 3: IaContextBuilder.java
# ════════════════════════════════════════════════════════════
echo ""
echo "🔧 [Capa 3/3] Reescribiendo IaContextBuilder.java..."

cat > "$BASE/ia/IaContextBuilder.java" <<'JAVA'
package com.miempresa.fasi.ia;

import com.miempresa.fasi.calendario.Evento;
import com.miempresa.fasi.calendario.EventoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class IaContextBuilder {

    private final EventoRepository eventoRepo;

    private static final int MAX_EVENTOS_CTX  = 15;
    private static final int RANGO_DIAS_BASE  = 30;
    private static final int RANGO_DIAS_AMPLIO = 90;

    // ════════════════════════════════════════════════════════
    //  Prompt principal — compacto y adaptativo
    // ════════════════════════════════════════════════════════
    public String buildPrompt(String preguntaUsuario, String modulo) {
        String ctx = buildContextoFiltrado(preguntaUsuario);

        return """
            Eres FASI, asistente del calendario. Responde en español, breve y claro.
            Solo usa los datos del CONTEXTO. Si falta un dato responde:
            "No tengo ese dato registrado."
            Nunca inventes información.

            %s

            Pregunta: %s
            Respuesta:
            """.formatted(ctx, preguntaUsuario);
    }

    // ════════════════════════════════════════════════════════
    //  Contexto FILTRADO según palabras clave de la pregunta
    // ════════════════════════════════════════════════════════
    private String buildContextoFiltrado(String pregunta) {
        String p = pregunta.toLowerCase();
        LocalDate hoy = LocalDate.now();

        Evento.TipoEvento tipoFiltro = detectarTipo(p);
        int dias = detectarRangoDias(p);
        LocalDate fin = hoy.plusDays(dias);

        List<Evento> eventos;
        if (tipoFiltro != null) {
            eventos = eventoRepo.findByTipoOrderByFechaInicioAsc(tipoFiltro).stream()
                    .filter(e -> !e.getFechaInicio().isBefore(hoy)
                              && !e.getFechaInicio().isAfter(fin))
                    .limit(MAX_EVENTOS_CTX)
                    .collect(Collectors.toList());
        } else {
            eventos = eventoRepo
                    .findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(hoy, fin)
                    .stream()
                    .limit(MAX_EVENTOS_CTX)
                    .collect(Collectors.toList());
        }

        StringBuilder ctx = new StringBuilder("=== CONTEXTO ===\n");
        ctx.append("Hoy: ").append(hoy).append("\n");
        if (tipoFiltro != null) {
            ctx.append("Filtro tipo: ").append(tipoFiltro.getLabel()).append("\n");
        }
        ctx.append("Rango: próximos ").append(dias).append(" días\n\n");

        if (eventos.isEmpty()) {
            ctx.append("Sin eventos en el rango consultado.\n");
        } else {
            ctx.append("Eventos:\n");
            eventos.forEach(e -> ctx
                .append("- ").append(e.getFechaInicio())
                .append(e.getHoraInicio() != null ? " " + e.getHoraInicio() : "")
                .append(" | ").append(e.getTipo().getLabel())
                .append(" | ").append(e.getTitulo())
                .append(e.getLugar() != null ? " (" + e.getLugar() + ")" : "")
                .append(e.getPersonasRelacionadas() != null
                        ? " · " + e.getPersonasRelacionadas() : "")
                .append("\n"));
        }
        return ctx.toString();
    }

    // ════════════════════════════════════════════════════════
    //  Heurísticas — sin IA, en Java puro
    // ════════════════════════════════════════════════════════
    private Evento.TipoEvento detectarTipo(String p) {
        if (p.contains("médic") || p.contains("medic") || p.contains("doctor")
         || p.contains("hospital") || p.contains("consulta"))   return Evento.TipoEvento.CITA_MEDICA;
        if (p.contains("cumple"))                               return Evento.TipoEvento.CUMPLEANOS;
        if (p.contains("tutor"))                                return Evento.TipoEvento.TUTORIA;
        if (p.contains("claustro"))                             return Evento.TipoEvento.CLAUSTRO;
        if (p.contains("curso") || p.contains("formaci"))       return Evento.TipoEvento.CURSO;
        if (p.contains("extraescolar"))                         return Evento.TipoEvento.EXTRAESCOLAR;
        if (p.contains("viaj"))                                 return Evento.TipoEvento.VIAJE;
        if (p.contains("vacacion"))                             return Evento.TipoEvento.VACACIONES;
        if (p.contains("comida") || p.contains("comer")
         || p.contains("cena") || p.contains("almuerz"))        return Evento.TipoEvento.COMIDA;
        if (p.contains("compra"))                               return Evento.TipoEvento.COMPRAS;
        return null;
    }

    private int detectarRangoDias(String p) {
        if (p.contains("hoy"))                                   return 1;
        if (p.contains("mañana"))                                return 2;
        if (p.contains("esta semana") || p.contains("semana"))   return 7;
        if (p.contains("quincena") || p.contains("15 días"))     return 15;
        if (p.contains("este mes") || p.contains("mes"))         return RANGO_DIAS_BASE;
        if (p.contains("trimestre") || p.contains("3 meses"))    return RANGO_DIAS_AMPLIO;
        if (p.contains("año") || p.contains("anual"))            return 365;
        return RANGO_DIAS_BASE;
    }
}
JAVA

echo "✅ IaContextBuilder.java reescrito"

# ════════════════════════════════════════════════════════════
#  Sincronización con target/ (evita recompilación si solo
#  cambian properties, pero aquí son .java -> hay que recompilar)
# ════════════════════════════════════════════════════════════
echo ""
echo "🔄 Sincronizando con target/ (solo recursos, los .java requieren recompilación)..."

# ════════════════════════════════════════════════════════════
#  Resumen final
# ════════════════════════════════════════════════════════════
echo ""
echo "═══════════════════════════════════════════════════"
echo "✅ OPTIMIZACIÓN IA APLICADA — 3 capas"
echo "═══════════════════════════════════════════════════"
echo ""
echo "📂 Backups guardados en:  $BACKUP_DIR"
echo ""
echo "🔄 Próximos pasos:"
echo "   1. Recompilar:   ./mvnw clean package -DskipTests"
echo "   2. Reiniciar:    ./dev-redeploy.sh   (o como tú reinicies)"
echo "   3. Probar:       Abrir FASI → '+ Nuevo evento' → dictar/escribir"
echo ""
echo "⏱️  Mide con cronómetro el primer 'Interpretar' después del warmup:"
echo "    Esperado: 2-4 segundos (antes: 8-15s)"
echo ""
echo "↩️  Si algo falla, restaurar con:"
echo "    cp $BACKUP_DIR/*.bak [destinos originales]"
echo ""
