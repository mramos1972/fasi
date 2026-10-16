package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miempresa.fasi.ia.IaContextBuilder;
import com.miempresa.fasi.ia.LlmApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventoIaService {

    private final LlmApiClient llmClient;
    private final ObjectMapper objectMapper;
    private final IaContextBuilder contextBuilder;

    private static final String SYSTEM_PROMPT_EVENTO = """
        Eres un asistente experto en analizar peticiones de calendario en español.
        Hoy es %s (%s).

        Debes responder ÚNICAMENTE con un objeto JSON válido con los siguientes campos:
        {
          "titulo": "descripción breve del evento",
          "tipo": "PERSONAL|SOCIAL|SALUD|TRABAJO|FAMILIA|VIAJE|VACACIONES|APRENDIZAJE|OCIO|GESTION|OTRO",
          "fechaInicio": "YYYY-MM-DD",
          "fechaFin": "YYYY-MM-DD o null",
          "horaInicio": "HH:MM o null",
          "horaFin": "HH:MM o null",
          "todoDia": true/false,
          "lugar": "lugar o null",
          "personasRelacionadas": "nombres o null",
          "etiquetas": ["etiqueta1", "etiqueta2"]
        }
        No incluyas explicaciones ni bloques markdown ```json. Solo el JSON puro.
        """;

    // ── Método principal llamado por EventoParserService ──
    public EventoIaResponse interpretar(EventoIaRequest request) {
        long inicioMs = System.currentTimeMillis();
        String texto = request.getTexto();
        LocalDate hoy = LocalDate.now();

        String prompt = SYSTEM_PROMPT_EVENTO.formatted(hoy, hoy.getDayOfWeek());
        String jsonRespuesta = llmClient.chatJson(prompt, texto);
        log.info("Respuesta JSON recibida de Gemini: {}", jsonRespuesta);

        try {
            JsonNode root = objectMapper.readTree(jsonRespuesta);

            Evento evento = new Evento();
            evento.setTitulo(root.path("titulo").asText(texto));

            String tipoStr = root.path("tipo").asText("OTRO");
            try {
                evento.setTipo(Evento.TipoEvento.valueOf(tipoStr.toUpperCase()));
            } catch (Exception e) {
                evento.setTipo(Evento.TipoEvento.OTRO);
            }

            if (root.hasNonNull("fechaInicio")) {
                evento.setFechaInicio(LocalDate.parse(root.get("fechaInicio").asText()));
            } else {
                evento.setFechaInicio(hoy);
            }

            if (root.hasNonNull("fechaFin")) {
                evento.setFechaFin(LocalDate.parse(root.get("fechaFin").asText()));
            }

            if (root.hasNonNull("horaInicio")) {
                evento.setHoraInicio(LocalTime.parse(root.get("horaInicio").asText()));
            }

            if (root.hasNonNull("horaFin")) {
                evento.setHoraFin(LocalTime.parse(root.get("horaFin").asText()));
            }

            evento.setTodoDia(root.path("todoDia").asBoolean(evento.getHoraInicio() == null));
            evento.setLugar(root.hasNonNull("lugar") ? root.get("lugar").asText() : null);
            evento.setPersonasRelacionadas(root.hasNonNull("personasRelacionadas") ? root.get("personasRelacionadas").asText() : null);
            evento.setTextoOriginalVoz(texto);

            Set<String> tags = new HashSet<>();
            if (root.has("etiquetas") && root.get("etiquetas").isArray()) {
                root.get("etiquetas").forEach(t -> tags.add(t.asText()));
            }
            evento.setEtiquetas(tags);

            long tiempoMs = System.currentTimeMillis() - inicioMs;
            return new EventoIaResponse(true, null, evento, tiempoMs);

        } catch (Exception e) {
            log.error("Fallo al parsear respuesta JSON de Gemini: '{}'", jsonRespuesta, e);
            Evento fallback = new Evento();
            fallback.setTitulo(texto);
            fallback.setFechaInicio(hoy);
            fallback.setTodoDia(true);
            fallback.setTipo(Evento.TipoEvento.OTRO);
            fallback.setTextoOriginalVoz(texto);

            long tiempoMs = System.currentTimeMillis() - inicioMs;
            return new EventoIaResponse(false, "No se pudo interpretar con precisión, se creó borrador básico.", fallback, tiempoMs);
        }
    }

    // ── Método puente que devuelve EventoConfirmarRequest ──
    public EventoConfirmarRequest parsearTexto(String texto) {
        EventoIaRequest req = new EventoIaRequest();
        req.setTexto(texto);
        EventoIaResponse resp = interpretar(req);
        Evento e = resp.getEventoPreview();

        EventoConfirmarRequest confirmarReq = new EventoConfirmarRequest();
        confirmarReq.setTextoOriginalVoz(texto);
        confirmarReq.setTitulo(e.getTitulo());
        confirmarReq.setTipo(e.getTipo());
        confirmarReq.setFechaInicio(e.getFechaInicio());
        confirmarReq.setFechaFin(e.getFechaFin());
        confirmarReq.setHoraInicio(e.getHoraInicio());
        confirmarReq.setHoraFin(e.getHoraFin());
        confirmarReq.setTodoDia(e.isTodoDia());
        confirmarReq.setLugar(e.getLugar());
        confirmarReq.setPersonasRelacionadas(e.getPersonasRelacionadas());
        confirmarReq.setEtiquetas(e.getEtiquetas());
        return confirmarReq;
    }

    // ── Consultas en lenguaje natural desde el Calendario ──
    public String consultar(String pregunta) {
        String systemPrompt = contextBuilder.buildPrompt(pregunta, "calendario");
        return llmClient.chat(systemPrompt, pregunta);
    }

    // ── Procesar límite de recurrencia devolviendo EventoIaResponse ──
    public EventoIaResponse procesarLimite(Evento eventoExistente, String textoLimite) {
        long inicioMs = System.currentTimeMillis();
        LocalDate hoy = LocalDate.now();

        String prompt = """
            Eres un asistente de fechas. A partir de una instrucción como '%s', deduce la fecha fin.
            Hoy es %s.
            Responde ÚNICAMENTE con JSON: {"fechaFin": "YYYY-MM-DD"}
            """.formatted(textoLimite, hoy);

        LocalDate fechaFin = null;
        try {
            String json = llmClient.chatJson(prompt, textoLimite);
            JsonNode root = objectMapper.readTree(json);
            if (root.hasNonNull("fechaFin")) {
                fechaFin = LocalDate.parse(root.get("fechaFin").asText());
            }
        } catch (Exception e) {
            log.warn("No se pudo extraer fechaFin de '{}'", textoLimite);
        }

        if (fechaFin != null) {
            eventoExistente.setFechaFin(fechaFin);
        }

        long tiempoMs = System.currentTimeMillis() - inicioMs;
        return new EventoIaResponse(true, null, eventoExistente, tiempoMs);
    }
}
