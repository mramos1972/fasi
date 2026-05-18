package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miempresa.fasi.ia.IaContextBuilder;
import com.miempresa.fasi.ia.OllamaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventoIaService {

    private final OllamaClient           ollamaClient;
    private final EventoRepository       repo;
    private final ObjectMapper           objectMapper;
    private final IaContextBuilder       contextBuilder;
    private final EventoParserRegex      parserRegex;

    private static final int CTX_INTERPRETAR   = 512;
    private static final int MAX_OUTPUT_TOKENS = 600;

    // ══════════════════════════════════════════════════════════
    //  SISTEMA HÍBRIDO: REGEX → IA
    // ══════════════════════════════════════════════════════════

    public EventoIaResponse interpretar(EventoIaRequest request) {
        long inicio = System.currentTimeMillis();
        LocalDate fechaContexto = request.getFechaContexto() != null && !request.getFechaContexto().isBlank()
            ? LocalDate.parse(request.getFechaContexto())
            : LocalDate.now();

        try {
            // ── PASO 1: Intentar REGEX (< 50ms) ──────────────────
            Optional<EventoParserRegex.EventoParseResult> resultado =
                parserRegex.parse(request.getTexto(), fechaContexto);

            if (resultado.isPresent()) {
                long elapsed = System.currentTimeMillis() - inicio;
                Evento evento = resultado.get().evento();
                boolean esRecurrente = resultado.get().esRecurrente();

                log.info("⚡ REGEX exitoso en {}ms (recurrente: {})", elapsed, esRecurrente);

                return new EventoIaResponse(true, null, evento, null, elapsed);
            }

            log.info("⏩ REGEX no coincide → usando IA");

            // ── PASO 2: Usar IA para casos complejos ─────────────
            return interpretarConIA(request, fechaContexto, inicio);

        } catch (Exception e) {
            log.error("❌ Error interpretando texto: {}", e.getMessage(), e);
            return new EventoIaResponse(false,
                    "No pude interpretar el texto. Inténtalo de nuevo.",
                    null, System.currentTimeMillis() - inicio);
        }
    }

    private EventoIaResponse interpretarConIA(EventoIaRequest request, LocalDate fechaContexto, long inicio) throws Exception {
        String prompt = construirPrompt(request.getTexto(), fechaContexto);
        String json   = ollamaClient.generateJson(prompt, CTX_INTERPRETAR, MAX_OUTPUT_TOKENS);

        log.info("📥 IA RAW: {}", json);

        String jsonLimpio = extraerJson(json);
        log.info("📋 IA LIMPIO: {}", jsonLimpio);

        JsonNode root = objectMapper.readTree(jsonLimpio);
        JsonNode eventosNode = root.has("eventos") ? root.get("eventos") : root;

        List<ObjectNode> nodos = new ArrayList<>();
        if (eventosNode.isArray()) {
            eventosNode.forEach(n -> { if (n.isObject()) nodos.add((ObjectNode) n); });
        } else if (eventosNode.isObject()) {
            nodos.add((ObjectNode) eventosNode);
        }

        if (nodos.isEmpty()) {
            throw new IllegalStateException("La IA no devolvió eventos en el JSON.");
        }

        List<Evento> eventos = new ArrayList<>();
        for (ObjectNode node : nodos) {
            normalizarHora(node, "horaInicio");
            normalizarHora(node, "horaFin");
            normalizarPersonas(node);

            boolean esRecurrente = node.has("recurrente") && node.get("recurrente").asBoolean(false);
            String  patron = node.has("patron") ? node.get("patron").asText(null) : null;

            node.remove("recurrente");
            node.remove("patron");

            Evento evento = objectMapper.treeToValue(node, Evento.class);
            evento.setTextoOriginalVoz(request.getTexto());

            if (esRecurrente && patron != null) {
                Map<String, Object> extra = evento.getDatosExtra() != null
                    ? new HashMap<>(evento.getDatosExtra())
                    : new HashMap<>();
                extra.put("recurrente", true);
                extra.put("patron", patron);
                extra.put("textoRecurrencia", request.getTexto());
                if (node.has("diaSemana") && !node.get("diaSemana").isNull()) {
                    extra.put("diaSemana", node.get("diaSemana").asText());
                }
                if (node.has("diasSemana") && node.get("diasSemana").isArray()) {
                    List<String> dias = new ArrayList<>();
                    node.get("diasSemana").forEach(d -> dias.add(d.asText()));
                    extra.put("diasSemana", dias);
                }
                evento.setDatosExtra(extra);
            }

            eventos.add(evento);
        }

        long elapsed = System.currentTimeMillis() - inicio;

        if (eventos.size() == 1) {
            log.info("✅ IA detectó 1 evento en {}ms", elapsed);
            return new EventoIaResponse(true, null, eventos.get(0), null, elapsed);
        } else {
            log.info("✅ IA detectó {} eventos múltiples en {}ms", eventos.size(), elapsed);
            return new EventoIaResponse(true, null, null, eventos, elapsed);
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

    private void normalizarPersonas(ObjectNode node) {
        if (!node.has("personasRelacionadas")) return;
        JsonNode pr = node.get("personasRelacionadas");
        if (pr.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pr.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(pr.get(i).asText());
            }
            String unido = sb.toString().trim();
            if (unido.isEmpty()) node.putNull("personasRelacionadas");
            else                 node.put("personasRelacionadas", unido);
        } else if (pr.isNull() || pr.asText().isBlank()) {
            node.putNull("personasRelacionadas");
        }
    }

    @Transactional(readOnly = true)
    public String consultar(String pregunta) {
        String prompt = contextBuilder.buildPrompt(pregunta, "calendario");
        return ollamaClient.generate(prompt, 1536);
    }

    private String construirPrompt(String texto, LocalDate fechaContexto) {
        return """
            Eres extractor de eventos de calendario. Devuelve SOLO JSON válido:
            {"eventos":[{"tipo":"TRABAJO","titulo":"...","fechaInicio":"YYYY-MM-DD","fechaFin":"YYYY-MM-DD","horaInicio":"HH:mm:ss",...}]}

            TIPOS: PERSONAL, SOCIAL, SALUD, TRABAJO, FAMILIA, VIAJE, APRENDIZAJE, OCIO, GESTION, OTRO

            RECURRENCIA:
            - "todos los lunes" → "recurrente":true, "patron":"WEEKLY", "diasSemana":["MONDAY"], fechaFin: 3 meses después
            - "cada día de julio" → "recurrente":true, "patron":"DAILY", fechaInicio:"2026-07-01", fechaFin:"2026-07-31"
            - "primer lunes de cada mes" → "recurrente":true, "patron":"MONTHLY", "diaSemana":"MONDAY", "posicion":"primero"
            - Si NO es recurrente → NO incluyas estos campos

            FECHA CONTEXTO: %s
            TEXTO: "%s"

            Responde SOLO con JSON, sin explicaciones.
            """.formatted(fechaContexto, texto);
    }

    private String extraerJson(String respuesta) {
        if (respuesta == null) return "{}";
        respuesta = respuesta.trim();
        int objInicio = respuesta.indexOf('{');
        int objFin    = respuesta.lastIndexOf('}');
        if (objInicio >= 0 && objFin > objInicio) {
            return respuesta.substring(objInicio, objFin + 1);
        }
        return respuesta;
    }
}
