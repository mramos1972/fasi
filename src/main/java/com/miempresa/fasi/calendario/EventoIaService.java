package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miempresa.fasi.ia.OllamaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventoIaService {

    private final OllamaClient      ollamaClient;
    private final EventoRepository  repo;
    private final ObjectMapper      objectMapper;

    // ── Interpretar texto de voz → Evento (preview) ──────────
    public EventoIaResponse interpretar(EventoIaRequest request) {
        long inicio = System.currentTimeMillis();
        try {
            String prompt    = buildPromptInterpretar(request);
            String json      = ollamaClient.generate(prompt, 512);
            String jsonLimpio = extraerJson(json);

            log.debug("JSON recibido de IA: {}", jsonLimpio);

            // Parsear con ObjectMapper tolerante (ignora campos desconocidos)
            com.fasterxml.jackson.databind.node.ObjectNode node =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                objectMapper.readTree(jsonLimpio);

            // Normalizar horaInicio/horaFin: "16:00" → "16:00:00"
            normalizarHora(node, "horaInicio");
            normalizarHora(node, "horaFin");

            // Extraer flag de recurrencia ANTES de deserializar Evento
            boolean esRecurrente = node.has("recurrente") && node.get("recurrente").asBoolean(false);
            String  patron       = node.has("patron")     ? node.get("patron").asText(null) : null;

            // Eliminar campos que Evento no tiene para evitar error de deserialización
            node.remove("recurrente");
            node.remove("patron");
            // NO eliminar diaSemana — lo necesita saveRecurrente

            Evento evento = objectMapper.treeToValue(node, Evento.class);
            evento.setTextoOriginalVoz(request.getTexto());

            // Si es recurrente, guardar info en datosExtra
            if (esRecurrente && patron != null) {
                java.util.Map<String, Object> extra = evento.getDatosExtra() != null
                    ? new java.util.HashMap<>(evento.getDatosExtra())
                    : new java.util.HashMap<>();
                extra.put("recurrente", true);
                extra.put("patron", patron);
                // Guardar texto original para que el frontend pueda mostrar aviso
                extra.put("textoRecurrencia", request.getTexto());
                // Guardar diaSemana en datosExtra si la IA lo devolvió
                if (node.has("diaSemana") && !node.get("diaSemana").isNull()) {
                    extra.put("diaSemana", node.get("diaSemana").asText());
                }
                evento.setDatosExtra(extra);
            }

            return new EventoIaResponse(true, null, evento,
                    System.currentTimeMillis() - inicio);

        } catch (Exception e) {
            log.error("Error interpretando texto de voz: {}", e.getMessage());
            return new EventoIaResponse(false,
                    "No pude interpretar el texto. Inténtalo de nuevo o crea el evento manualmente.",
                    null, System.currentTimeMillis() - inicio);
        }
    }

    /** Normaliza "16:00" o "16:00 H" a "16:00:00" para que LocalTime lo acepte */
    private void normalizarHora(com.fasterxml.jackson.databind.node.ObjectNode node, String campo) {
        if (!node.has(campo) || node.get(campo).isNull()) return;
        String val = node.get(campo).asText("").trim()
                         .replaceAll("(?i)\\s*[Hh].*$", "") // quitar " H", " h", " horas"
                         .trim();
        if (val.isEmpty() || val.equals("null")) { node.putNull(campo); return; }
        // "16:00" → "16:00:00"
        if (val.matches("\\d{1,2}:\\d{2}$")) val = val + ":00";
        // "16" → "16:00:00"
        if (val.matches("\\d{1,2}$")) val = val + ":00:00";
        node.put(campo, val);
    }

    // ── Consulta en lenguaje natural sobre el calendario ─────
    @Transactional(readOnly = true)
    public String consultar(String pregunta) {
        String contexto = buildContextoCalendario();
        String prompt   = buildPromptConsulta(pregunta, contexto);
        return ollamaClient.generate(prompt);
    }

    // ── Prompt para interpretar voz → JSON evento ────────────
    private String buildPromptInterpretar(EventoIaRequest req) {
        String fechaHoy = req.getFechaContexto() != null
                ? req.getFechaContexto()
                : LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String texto = req.getTexto();

        StringBuilder sb = new StringBuilder();
        sb.append("Eres un asistente de calendario. Convierte el texto en JSON.\n\n");
        sb.append("FECHA HOY: ").append(fechaHoy).append("\n\n");
        sb.append("TIPOS (elige el mas adecuado):\n");
        sb.append("CUMPLEANOS, CITA_MEDICA, TUTORIA, CLAUSTRO, CURSO, EXTRAESCOLAR, VIAJE, VACACIONES, COMIDA, COMPRAS, OTRO\n\n");
        sb.append("REGLAS:\n");
        sb.append("1. Responde SOLO con JSON valido. Sin texto extra. Sin markdown.\n");
        sb.append("2. fechaInicio: primer dia del mes/periodo indicado. Formato YYYY-MM-DD.\n");
        sb.append("3. fechaFin: ultimo dia del mes/periodo indicado. Formato YYYY-MM-DD.\n");
        sb.append("4. horaInicio: formato HH:mm:ss. Ejemplos: 11:00:00, 16:00:00, 09:00:00\n");
        sb.append("5. Si dice sabado -> diaSemana: SABADO. Si dice martes -> MARTES. Etc.\n");
        sb.append("6. Si es recurrente (todos los X, cada semana) -> recurrente: true, patron: WEEKLY\n");
        sb.append("7. tipo: EXTRAESCOLAR para teatro, deporte, actividades. CUMPLEANOS para cumples.\n");
        sb.append("8. titulo: maximo 60 caracteres.\n\n");
        sb.append("EJEMPLOS DE INTERPRETACION:\n");
        sb.append("- todos los sabados de junio y julio -> recurrente:true, diaSemana:SABADO, fechaInicio:2026-06-01, fechaFin:2026-07-31\n");
        sb.append("- todos los martes de junio -> recurrente:true, diaSemana:MARTES, fechaInicio:2026-06-01, fechaFin:2026-06-30\n");
        sb.append("- cumpleanos de Noa el 30 de mayo -> tipo:CUMPLEANOS, fechaInicio:2026-05-30, recurrente:false\n");
        sb.append("- teatro de Alicia -> tipo:EXTRAESCOLAR\n\n");
        sb.append("ESTRUCTURA JSON:\n");
        sb.append("{\n");
        sb.append("  \"tipo\": \"EXTRAESCOLAR\",\n");
        sb.append("  \"titulo\": \"Teatro Alicia\",\n");
        sb.append("  \"fechaInicio\": \"2026-06-01\",\n");
        sb.append("  \"fechaFin\": \"2026-07-31\",\n");
        sb.append("  \"horaInicio\": \"11:00:00\",\n");
        sb.append("  \"horaFin\": null,\n");
        sb.append("  \"todoDia\": false,\n");
        sb.append("  \"lugar\": null,\n");
        sb.append("  \"personasRelacionadas\": \"Alicia\",\n");
        sb.append("  \"descripcion\": null,\n");
        sb.append("  \"recurrente\": true,\n");
        sb.append("  \"patron\": \"WEEKLY\",\n");
        sb.append("  \"diaSemana\": \"SABADO\",\n");
        sb.append("  \"datosExtra\": {}\n");
        sb.append("}\n\n");
        sb.append("TEXTO: \"").append(texto).append("\"\n\n");
        sb.append("JSON:");
        return sb.toString();
    }

        // ── Prompt para consultas sobre el calendario ─────────────
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

    // ── Construir contexto del calendario para consultas ──────
    private String buildContextoCalendario() {
        StringBuilder ctx = new StringBuilder("=== EVENTOS DEL CALENDARIO ===\n\n");

        LocalDate hoy    = LocalDate.now();
        LocalDate fin    = hoy.plusMonths(3);

        List<Evento> proximos = repo
                .findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(hoy, fin);

        if (proximos.isEmpty()) {
            ctx.append("(No hay eventos próximos registrados)\n");
        } else {
            ctx.append("Próximos 3 meses:\n");
            proximos.forEach(e -> ctx.append(String.format(
                    "  [%s] %s %s | %s%s%s\n",
                    e.getFechaInicio(),
                    e.getTipo().getEmoji(),
                    e.getTitulo(),
                    e.getHoraInicio() != null ? "a las " + e.getHoraInicio() + " " : "",
                    e.getLugar() != null ? "en " + e.getLugar() + " " : "",
                    e.getPersonasRelacionadas() != null ? "· " + e.getPersonasRelacionadas() : ""
            )));
        }

        // Resumen por tipo
        ctx.append("\nRESUMEN POR TIPO (total histórico):\n");
        for (Evento.TipoEvento tipo : Evento.TipoEvento.values()) {
            long count = repo.findByTipoOrderByFechaInicioAsc(tipo).size();
            if (count > 0) {
                ctx.append(String.format("  %s %s: %d eventos\n",
                        tipo.getEmoji(), tipo.getLabel(), count));
            }
        }

        return ctx.toString();
    }

    // ── Extraer JSON de la respuesta de la IA ────────────────
    private String extraerJson(String respuesta) {
        // Buscar primer { y último }
        int inicio = respuesta.indexOf('{');
        int fin    = respuesta.lastIndexOf('}');
        if (inicio >= 0 && fin > inicio) {
            return respuesta.substring(inicio, fin + 1);
        }
        return respuesta.trim();
    }
}
