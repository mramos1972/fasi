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

    private static final int CTX_INTERPRETAR    = 1536;
    private static final int MAX_OUTPUT_TOKENS  = 800;

    public EventoIaResponse interpretar(EventoIaRequest request) {
        long inicio = System.currentTimeMillis();
        try {
            String prompt = buildPromptInterpretarMinimo(request);
            String json   = ollamaClient.generateJson(prompt, CTX_INTERPRETAR, MAX_OUTPUT_TOKENS);

            log.info("📥 IA RAW: {}", json);

            String jsonLimpio = extraerJson(json);
            log.info("📋 IA LIMPIO: {}", jsonLimpio);

            // ── Parseamos el envoltorio {"eventos":[...]} ────
            JsonNode root = objectMapper.readTree(jsonLimpio);
            JsonNode eventosNode = root.has("eventos") ? root.get("eventos") : root;

            // Convertir SIEMPRE a array para uniformar
            List<ObjectNode> nodos = new ArrayList<>();
            if (eventosNode.isArray()) {
                eventosNode.forEach(n -> { if (n.isObject()) nodos.add((ObjectNode) n); });
            } else if (eventosNode.isObject()) {
                nodos.add((ObjectNode) eventosNode);
            }

            if (nodos.isEmpty()) {
                throw new IllegalStateException("La IA no devolvió eventos en el JSON.");
            }

            // ── Procesar cada nodo: normalizaciones y mapping ─
            List<Evento> eventos = new ArrayList<>();
            for (ObjectNode node : nodos) {
                normalizarHora(node, "horaInicio");
                normalizarHora(node, "horaFin");
                normalizarPersonas(node);

                boolean esRecurrente = node.has("recurrente")
                        && node.get("recurrente").asBoolean(false);
                String  patron = node.has("patron")
                        ? node.get("patron").asText(null) : null;

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

                eventos.add(evento);
            }

            long elapsed = System.currentTimeMillis() - inicio;

            // ── Devolver según cantidad ──────────────────────
            if (eventos.size() == 1) {
                log.info("✅ IA detectó 1 evento en {}ms", elapsed);
                return new EventoIaResponse(true, null, eventos.get(0), null, elapsed);
            } else {
                log.info("✅ IA detectó {} eventos múltiples en {}ms", eventos.size(), elapsed);
                return new EventoIaResponse(true, null, null, eventos, elapsed);
            }

        } catch (Exception e) {
            log.error("❌ Error interpretando texto: {}", e.getMessage(), e);
            return new EventoIaResponse(false,
                    "No pude interpretar el texto. Inténtalo de nuevo.",
                    null, System.currentTimeMillis() - inicio);
        }
    }

    /** Normaliza horas tipo "10h", "10:00", "10" -> "HH:mm:ss". */
    private void normalizarHora(ObjectNode node, String campo) {
        if (!node.has(campo) || node.get(campo).isNull()) return;
        String val = node.get(campo).asText("").trim()
                         .replaceAll("(?i)\\s*[Hh].*$", "").trim();
        if (val.isEmpty() || val.equals("null")) { node.putNull(campo); return; }
        if (val.matches("\\d{1,2}:\\d{2}$")) val = val + ":00";
        if (val.matches("\\d{1,2}$"))        val = val + ":00:00";
        node.put(campo, val);
    }

    /** Si la IA devuelve personasRelacionadas como array, lo convierte a string. */
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

    // ════════════════════════════════════════════════════════
    //  Prompt con ENVOLTORIO obligatorio: {"eventos":[...]}
    // ════════════════════════════════════════════════════════
    private String buildPromptInterpretarMinimo(EventoIaRequest req) {
        String fechaHoy = req.getFechaContexto() != null
                ? req.getFechaContexto()
                : LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        return """
            Eres un extractor de eventos de calendario. Devuelve SIEMPRE este formato:
            {"eventos":[ {evento1}, {evento2}, ... ]}

            La clave "eventos" SIEMPRE es un array, aunque haya un solo evento.

            Cada evento tiene estos campos exactos:
            - tipo: uno de [CUMPLEANOS, CITA_MEDICA, TUTORIA, CLAUSTRO, CURSO, EXTRAESCOLAR, VIAJE, VACACIONES, COMIDA, COMPRAS, OTRO]
            - titulo: string corto (max 60 chars)
            - fechaInicio: string "YYYY-MM-DD"
            - horaInicio: string "HH:mm:ss" o null si no hay hora
            - todoDia: boolean (true para cumpleaños sin hora)
            - lugar: string o null
            - personasRelacionadas: STRING separado por comas (ej "Miguel, Rocio") o null. NUNCA array.
            - descripcion: string o null
            - datosExtra: {} (objeto vacio)

            Reglas:
            - Fecha actual: %s
            - Sin hora explicita -> horaInicio: null
            - Cumpleanos sin hora -> todoDia: true, horaInicio: null
            - Tipo dudoso -> "OTRO"
            - personasRelacionadas: SIEMPRE STRING, jamás array

            Ejemplo entrada: "cita medica de Miguel el 30 junio a las 12:20 en Jerez, cumple de Carlota el 26 mayo en Costaballena"
            Ejemplo salida:
            {"eventos":[
              {"tipo":"CITA_MEDICA","titulo":"Cita medica Miguel","fechaInicio":"2026-06-30","horaInicio":"12:20:00","todoDia":false,"lugar":"Jerez","personasRelacionadas":"Miguel","descripcion":null,"datosExtra":{}},
              {"tipo":"CUMPLEANOS","titulo":"Cumpleaños Carlota","fechaInicio":"2026-05-26","horaInicio":null,"todoDia":true,"lugar":"Costaballena","personasRelacionadas":"Carlota","descripcion":null,"datosExtra":{}}
            ]}

            TEXTO USUARIO: "%s"
            """.formatted(fechaHoy, req.getTexto());
    }

    private String extraerJson(String respuesta) {
        String trimmed = respuesta.trim();
        int objInicio = trimmed.indexOf('{');
        int objFin    = trimmed.lastIndexOf('}');
        if (objInicio >= 0 && objFin > objInicio) {
            return trimmed.substring(objInicio, objFin + 1);
        }
        return trimmed;
    }
}
