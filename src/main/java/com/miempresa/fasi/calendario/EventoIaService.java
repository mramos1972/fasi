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
            String json      = ollamaClient.generate(prompt);
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
            node.remove("diasSemana");

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
                         .replaceAll("(?i)\s*[Hh].*$", "") // quitar " H", " h", " horas"
                         .trim();
        if (val.isEmpty() || val.equals("null")) { node.putNull(campo); return; }
        // "16:00" → "16:00:00"
        if (val.matches("\d{1,2}:\d{2}$")) val = val + ":00";
        // "16" → "16:00:00"
        if (val.matches("\d{1,2}$")) val = val + ":00:00";
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

        return """
            Eres un asistente de calendario. Convierte texto en lenguaje natural a JSON de evento de calendario.

            FECHA DE HOY: %s

            TIPOS DISPONIBLES — elige el más adecuado al contexto:
            - CUMPLEANOS   → cumpleaños, aniversario, celebración de nacimiento
            - CITA_MEDICA  → médico, consulta, hospital, dentista, revisión
            - TUTORIA      → tutoría, reunión con profesor, entrevista escolar
            - CLAUSTRO     → claustro, reunión de profesores, junta escolar
            - CURSO        → curso, formación, clase, taller
            - EXTRAESCOLAR → extraescolar, actividad, teatro, deporte, música, ballet
            - VIAJE        → viaje, excursión, desplazamiento
            - VACACIONES   → vacaciones, días libres, puente
            - COMIDA       → comida, cena, almuerzo, restaurante
            - COMPRAS      → compras, supermercado, recado
            - OTRO         → cualquier otra cosa

            REGLAS CRÍTICAS:
            1. Responde SOLO con JSON válido, sin texto extra, sin markdown, sin ```
            2. fechaInicio y fechaFin: formato YYYY-MM-DD obligatorio
            3. horaInicio y horaFin: formato HH:mm:ss (ej: "16:00:00"). Si no hay hora → null
            4. "16:00 H", "las 4 de la tarde", "a las 16h" → "16:00:00"
            5. "las 7" sin contexto AM/PM en evento de tarde → "19:00:00"
            6. Si el evento es recurrente (todos los martes, cada semana...) → recurrente:true, patron:"WEEKLY", diasSemana:["TUESDAY"] o similar
            7. Si no hay recurrencia → recurrente:false
            8. tipo: usa EXACTAMENTE uno de los valores en mayúsculas de la lista anterior
            9. titulo: máximo 60 caracteres, descriptivo
            10. lugar: null si no se menciona
            11. personasRelacionadas: nombres separados por coma, null si no hay

            ESTRUCTURA JSON REQUERIDA:
            {
              "tipo": "CUMPLEANOS",
              "titulo": "Cumpleaños de Noa",
              "fechaInicio": "2026-05-30",
              "fechaFin": null,
              "horaInicio": "16:00:00",
              "horaFin": "19:00:00",
              "todoDia": false,
              "lugar": "Costaballena",
              "personasRelacionadas": "Noa",
              "descripcion": null,
              "recurrente": false,
              "patron": null,
              "diasSemana": null,
              "datosExtra": {"nombre_persona": "Noa", "lugar_celebracion": "Costaballena"}
            }

            EJEMPLOS DE TIPOS CORRECTOS:
            - "cumpleaños de Noa" → tipo: "CUMPLEANOS"
            - "teatro de Alicia" / "extraescolar" → tipo: "EXTRAESCOLAR"
            - "claustro de Rocío" / "reunión profesores" → tipo: "CLAUSTRO"
            - "cita médica" / "médico" → tipo: "CITA_MEDICA"
            - "vacaciones" → tipo: "VACACIONES"

            TEXTO: "%s"

            JSON:
            """.formatted(fechaHoy, req.getTexto());
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
