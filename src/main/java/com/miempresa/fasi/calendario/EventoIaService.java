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
import java.util.ArrayList;
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
        Eres un asistente experto en interpretar lenguaje natural para calendarios familiares en español.
        Hoy es %s (%s). Año en curso: %d.

        INSTRUCCIONES CLAVE:
        1. Analiza el texto y detecta CUÁNTOS eventos distintos solicita el usuario (puede ser uno, dos o muchos).
        2. Para cada evento, extrae:
           - titulo: Nombre descriptivo, conciso y limpio del evento (ej: "Cumpleaños de Martín"). NO pongas la orden entera.
           - tipo: Clasifica estrictamente en uno de estos valores: PERSONAL, SOCIAL, SALUD, TRABAJO, FAMILIA, VIAJE, VACACIONES, APRENDIZAJE, OCIO, GESTION, OTRO.
           - fechaInicio: En formato YYYY-MM-DD. Si no menciona el año, asume el año en curso (%d).
           - horaInicio: Formato HH:MM en 24h (ej: 19:00 si dice '7 de la tarde'). Si no hay hora, pon null.
           - horaFin: Formato HH:MM o null.
           - fechaFin: Formato YYYY-MM-DD o null.
           - todoDia: true si no hay hora especificada, false si tiene hora.
           - lugar: Ubicación si se menciona explícitamente, o null.
           - personasRelacionadas: Personas involucradas (ej: "Martín", "Sara") o null.
           - etiquetas: Array de strings con etiquetas clave o vacío [].

        Debes responder ÚNICAMENTE con un JSON con la propiedad "eventos":
        {
          "eventos": [
            {
              "titulo": "Cumpleaños de Martín",
              "tipo": "SOCIAL",
              "fechaInicio": "2026-11-20",
              "fechaFin": null,
              "horaInicio": "19:00",
              "horaFin": null,
              "todoDia": false,
              "lugar": null,
              "personasRelacionadas": "Martín",
              "etiquetas": ["cumpleaños"]
            }
          ]
        }
        No agregues explicaciones ni markdown ```json. Solo el JSON puro.
        """;

    // ── Interpretar múltiples eventos o individual ──
    public List<Evento> interpretarLista(String texto) {
        LocalDate hoy = LocalDate.now();
        String prompt = SYSTEM_PROMPT_EVENTO.formatted(hoy, hoy.getDayOfWeek(), hoy.getYear(), hoy.getYear());
        String jsonRespuesta = llmClient.chatJson(prompt, texto);
        log.info("Respuesta JSON multi-evento de Gemini: {}", jsonRespuesta);

        List<Evento> lista = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(jsonRespuesta);
            JsonNode eventosNode = root.path("eventos");

            if (eventosNode.isArray()) {
                for (JsonNode item : eventosNode) {
                    lista.add(mapearNodoAEvento(item, texto, hoy));
                }
            } else if (root.has("titulo")) {
                // Compatibilidad por si el modelo devuelve un solo objeto suelto
                lista.add(mapearNodoAEvento(root, texto, hoy));
            }
        } catch (Exception e) {
            log.error("Fallo al parsear JSON multi-evento: '{}'", jsonRespuesta, e);
        }
        return lista;
    }

    // ── Método principal llamado por el flujo de confirmación ──
    public EventoIaResponse interpretar(EventoIaRequest request) {
        long inicioMs = System.currentTimeMillis();
        String texto = request.getTexto();
        LocalDate hoy = LocalDate.now();

        List<Evento> eventos = interpretarLista(texto);

        if (!eventos.isEmpty()) {
            Evento principal = eventos.get(0);
            long tiempoMs = System.currentTimeMillis() - inicioMs;
            return new EventoIaResponse(true, null, principal, tiempoMs);
        }

        // Fallback básico si Gemini no pudo extraer nada
        Evento fallback = new Evento();
        fallback.setTitulo(texto);
        fallback.setFechaInicio(hoy);
        fallback.setTodoDia(true);
        fallback.setTipo(Evento.TipoEvento.OTRO);
        fallback.setTextoOriginalVoz(texto);

        long tiempoMs = System.currentTimeMillis() - inicioMs;
        return new EventoIaResponse(false, "No se pudo interpretar el evento.", fallback, tiempoMs);
    }

    private Evento mapearNodoAEvento(JsonNode node, String textoOriginal, LocalDate hoy) {
        Evento evento = new Evento();
        evento.setTitulo(node.path("titulo").asText("Evento"));

        String tipoStr = node.path("tipo").asText("OTRO");
        try {
            evento.setTipo(Evento.TipoEvento.valueOf(tipoStr.toUpperCase()));
        } catch (Exception e) {
            evento.setTipo(Evento.TipoEvento.OTRO);
        }

        if (node.hasNonNull("fechaInicio")) {
            evento.setFechaInicio(LocalDate.parse(node.get("fechaInicio").asText()));
        } else {
            evento.setFechaInicio(hoy);
        }

        if (node.hasNonNull("fechaFin")) {
            evento.setFechaFin(LocalDate.parse(node.get("fechaFin").asText()));
        }

        if (node.hasNonNull("horaInicio")) {
            evento.setHoraInicio(LocalTime.parse(node.get("horaInicio").asText()));
        }

        if (node.hasNonNull("horaFin")) {
            evento.setHoraFin(LocalTime.parse(node.get("horaFin").asText()));
        }

        evento.setTodoDia(node.path("todoDia").asBoolean(evento.getHoraInicio() == null));
        evento.setLugar(node.hasNonNull("lugar") ? node.get("lugar").asText() : null);
        evento.setPersonasRelacionadas(node.hasNonNull("personasRelacionadas") ? node.get("personasRelacionadas").asText() : null);
        evento.setTextoOriginalVoz(textoOriginal);

        Set<String> tags = new HashSet<>();
        if (node.has("etiquetas") && node.get("etiquetas").isArray()) {
            node.get("etiquetas").forEach(t -> tags.add(t.asText()));
        }
        evento.setEtiquetas(tags);

        return evento;
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
