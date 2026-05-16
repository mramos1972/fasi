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

@Slf4j
@Service
@RequiredArgsConstructor
public class EventoIaService {

    private final OllamaClient     ollamaClient;
    private final EventoRepository repo;
    private final ObjectMapper     objectMapper;

    public EventoIaResponse interpretar(EventoIaRequest request) {
        long inicio = System.currentTimeMillis();
        try {
            String prompt     = buildPromptInterpretar(request);
            String json       = ollamaClient.generate(prompt, 768);
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
                log.info("IA detectó {} eventos múltiples", previews.size());
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
        return ollamaClient.generate(buildPromptConsulta(pregunta, buildContextoCalendario()));
    }

    private String buildPromptInterpretar(EventoIaRequest req) {
        String fechaHoy = req.getFechaContexto() != null
                ? req.getFechaContexto()
                : LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        StringBuilder sb = new StringBuilder();
        sb.append("Eres un asistente de calendario. Convierte el texto en JSON.\n\n");
        sb.append("FECHA HOY: ").append(fechaHoy).append("\n\n");
        sb.append("TIPOS: CUMPLEANOS, CITA_MEDICA, TUTORIA, CLAUSTRO, CURSO, EXTRAESCOLAR, VIAJE, VACACIONES, COMIDA, COMPRAS, OTRO\n\n");
        sb.append("REGLAS CRITICAS:\n");
        sb.append("1. Si el usuario pide VARIOS eventos distintos -> devuelve ARRAY JSON: [{...},{...}]\n");
        sb.append("2. Si el usuario pide UN solo evento -> devuelve OBJETO JSON: {...}\n");
        sb.append("3. Responde SOLO con JSON valido. Sin texto extra. Sin markdown. Sin ```\n");
        sb.append("4. fechaInicio formato YYYY-MM-DD. OBLIGATORIO siempre.\n");
        sb.append("5. horaInicio formato HH:mm:ss. Si NO se menciona hora -> pon null. NUNCA inventes una hora.\n");
        sb.append("6. todoDia: true SOLO si el usuario dice explicitamente todo el dia o es un cumpleanos sin hora.\n");
        sb.append("7. Si es recurrente -> recurrente: true, patron: WEEKLY\n");
        sb.append("8. titulo: maximo 60 caracteres.\n\n");
        sb.append("EJEMPLO SIN HORA:\n");
        sb.append("{\"tipo\":\"CITA_MEDICA\",\"titulo\":\"Cita medica\",\"fechaInicio\":\"2026-05-14\",\"horaInicio\":null,\"todoDia\":false,\"lugar\":null,\"personasRelacionadas\":null,\"descripcion\":null,\"datosExtra\":{}}\n\n");
        sb.append("EJEMPLO CON HORA:\n");
        sb.append("{\"tipo\":\"CITA_MEDICA\",\"titulo\":\"Cita medica\",\"fechaInicio\":\"2026-05-14\",\"horaInicio\":\"10:00:00\",\"todoDia\":false,\"lugar\":null,\"personasRelacionadas\":null,\"descripcion\":null,\"datosExtra\":{}}\n\n");
        sb.append("EJEMPLO ARRAY:\n");
        sb.append("[\n");
        sb.append("  {\"tipo\":\"CUMPLEANOS\",\"titulo\":\"Cumpleanos Carlota\",\"fechaInicio\":\"2026-05-26\",\"horaInicio\":\"16:00:00\",\"todoDia\":false,\"lugar\":\"Costaballena\",\"personasRelacionadas\":\"Carlota\",\"descripcion\":null,\"datosExtra\":{}},\n");
        sb.append("  {\"tipo\":\"CUMPLEANOS\",\"titulo\":\"Cumpleanos Cristian\",\"fechaInicio\":\"2026-05-23\",\"horaInicio\":null,\"todoDia\":true,\"lugar\":\"Playa de la Jara\",\"personasRelacionadas\":\"Cristian\",\"descripcion\":null,\"datosExtra\":{}}\n");
        sb.append("]\n\n");
        sb.append("TEXTO: \"").append(req.getTexto()).append("\"\n\n");
        sb.append("JSON:");
        return sb.toString();
    }

    private String buildPromptConsulta(String pregunta, String contexto) {
        return """
            Eres FASI-IA, asistente del calendario FASI.
            REGLAS: Responde SOLO con datos del contexto. Si no está, di 'No tengo ese dato'.
            NUNCA inventes. Responde en español.

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
            ctx.append("(No hay eventos próximos)\n");
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
        int arrInicio = trimmed.indexOf('[');
        int arrFin    = trimmed.lastIndexOf(']');
        int objInicio = trimmed.indexOf('{');
        int objFin    = trimmed.lastIndexOf('}');

        // Array tiene prioridad si empieza antes que el objeto
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
