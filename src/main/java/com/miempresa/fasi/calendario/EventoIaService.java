package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miempresa.fasi.ia.OllamaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
            String prompt = buildPromptInterpretar(request);
            String json   = ollamaClient.generate(prompt);

            // Extraer el JSON de la respuesta (la IA puede añadir texto extra)
            String jsonLimpio = extraerJson(json);
            Evento evento     = objectMapper.readValue(jsonLimpio, Evento.class);
            evento.setTextoOriginalVoz(request.getTexto());

            return new EventoIaResponse(true, null, evento,
                    System.currentTimeMillis() - inicio);

        } catch (Exception e) {
            log.error("Error interpretando texto de voz", e);
            return new EventoIaResponse(false,
                    "No pude interpretar el texto. Inténtalo de nuevo o crea el evento manualmente.",
                    null, System.currentTimeMillis() - inicio);
        }
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
            Eres un asistente de calendario inteligente. Tu tarea es convertir texto en lenguaje natural a un evento de calendario en formato JSON.
            
            FECHA DE HOY: %s
            
            TIPOS DE EVENTO DISPONIBLES (usa exactamente estos valores):
            CUMPLEANOS, CITA_MEDICA, TUTORIA, CLAUSTRO, CURSO, EXTRAESCOLAR, VIAJE, VACACIONES, COMIDA, COMPRAS, OTRO
            
            REGLAS ESTRICTAS:
            1. Responde ÚNICAMENTE con el JSON, sin texto adicional, sin markdown, sin explicaciones.
            2. Las fechas deben estar en formato ISO: YYYY-MM-DD
            3. Las horas en formato HH:mm:ss
            4. Si no se menciona hora, deja horaInicio como null
            5. Si no se menciona lugar, deja lugar como null
            6. Infiere el tipo de evento del contexto
            7. El título debe ser descriptivo y conciso (máximo 60 caracteres)
            8. En personasRelacionadas pon los nombres mencionados separados por coma
            9. Si hay cancelación o cambio, refléjalo en la descripción
            10. datosExtra debe contener campos específicos del tipo de evento
            
            EJEMPLOS DE datosExtra por tipo:
            - CITA_MEDICA: {"especialidad":"cabecera","medico":null,"centro":"consultas externas","paciente":"Rocio"}
            - CUMPLEANOS: {"nombre_persona":"Alberto de Martín","lugar_celebracion":"Costaballena"}
            - EXTRAESCOLAR: {"actividad":"teatro","alumno":"Alicia","motivo_cancelacion":"tiene que estudiar"}
            
            TEXTO A INTERPRETAR: "%s"
            
            JSON DE RESPUESTA (solo el JSON, nada más):
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
