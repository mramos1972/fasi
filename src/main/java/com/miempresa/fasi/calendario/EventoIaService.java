package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miempresa.fasi.calendario.dto.IaAccionResultado;
import com.miempresa.fasi.ia.IaContextBuilder;
import com.miempresa.fasi.ia.LlmApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventoIaService {

    private final LlmApiClient llmClient;
    private final ObjectMapper objectMapper;
    private final IaContextBuilder contextBuilder;

    private static final String SYSTEM_PROMPT = """
        Eres FASI, el asistente inteligente de calendario.
        Hoy es %s (%s). Año en curso: %d.
        
        INSTRUCCIONES CLAVE:
        1. Comprende cualquier instrucción del usuario, ya sea para uno o múltiples eventos.
        2. Puedes interpretar solicitudes de CREAR eventos o BORRAR eventos.
        3. Para cada evento a crear, extrae:
           - titulo: Nombre descriptivo, conciso y limpio (ej: "Cumpleaños de Martín", "Comida en Madrid").
           - tipo: PERSONAL, SOCIAL, SALUD, TRABAJO, FAMILIA, VIAJE, VACACIONES, APRENDIZAJE, OCIO, GESTION, OTRO.
           - fechaInicio: YYYY-MM-DD.
           - horaInicio: HH:MM en 24h (ej: 16:00, 19:00). Si no hay hora explícita, pon null.
           - horaFin: HH:MM o null.
           - todoDia: true si no se indicó hora, false si tiene hora específica.
           - lugar: Ciudad o local si se menciona (ej: "Madrid", "Restaurante Pepe"), o null.
           - personasRelacionadas: Personas nombradas (ej: "Martín", "Noe"), o null.
           - etiquetas: Array de strings o vacío [].
        4. Redacta en 'mensajeUsuario' una respuesta natural, cordial y breve resumiendo lo que has interpretado y avisando de cualquier dato pendiente (por ejemplo, si falta la hora en alguno).
        
        Debes responder ÚNICAMENTE con esta estructura JSON:
        {
          "mensajeUsuario": "He preparado 2 eventos: el cumpleaños de Martín a las 19:00 y la comida en Madrid a las 16:00.",
          "crear": [
            {
              "titulo": "Cumpleaños de Martín",
              "tipo": "SOCIAL",
              "fechaInicio": "2026-12-07",
              "horaInicio": "19:00",
              "todoDia": false,
              "personasRelacionadas": "Martín",
              "lugar": null,
              "etiquetas": ["cumpleaños"]
            }
          ],
          "eliminarIds": []
        }
        No agregues markdown ```json ni explicaciones adicionales. Solo el JSON puro.
        """;

    public IaAccionResultado interpretarAccion(String texto) {
        long inicio = System.currentTimeMillis();
        LocalDate hoy = LocalDate.now();
        String prompt = SYSTEM_PROMPT.formatted(hoy, hoy.getDayOfWeek(), hoy.getYear());

        log.info("🤖 Interpretando con Gemini Flash: '{}'", texto);
        String jsonRespuesta = llmClient.chatJson(prompt, texto);
        log.info("Respuesta cruda de Gemini: {}", jsonRespuesta);

        List<Evento> eventosCrear = new ArrayList<>();
        List<UUID> idsBorrar = new ArrayList<>();
        String mensajeUsuario = "He procesado tu solicitud.";

        try {
            JsonNode root = objectMapper.readTree(jsonRespuesta);

            if (root.hasNonNull("mensajeUsuario")) {
                mensajeUsuario = root.get("mensajeUsuario").asText();
            }

            JsonNode crearNode = root.path("crear");
            if (crearNode.isArray()) {
                for (JsonNode item : crearNode) {
                    eventosCrear.add(mapearNodoAEvento(item, texto, hoy));
                }
            } else if (root.path("eventos").isArray()) { // Retrocompatibilidad
                for (JsonNode item : root.path("eventos")) {
                    eventosCrear.add(mapearNodoAEvento(item, texto, hoy));
                }
            }

            JsonNode borrarNode = root.path("eliminarIds");
            if (borrarNode.isArray()) {
                for (JsonNode item : borrarNode) {
                    try {
                        idsBorrar.add(UUID.fromString(item.asText()));
                    } catch (Exception ignored) {}
                }
            }

            long elapsed = System.currentTimeMillis() - inicio;
            return IaAccionResultado.builder()
                    .exito(!eventosCrear.isEmpty() || !idsBorrar.isEmpty())
                    .mensajeUsuario(mensajeUsuario)
                    .crear(eventosCrear)
                    .eliminarIds(idsBorrar)
                    .tiempoMs(elapsed)
                    .build();

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - inicio;
            log.error("Fallo al procesar respuesta de Gemini", e);
            return IaAccionResultado.builder()
                    .exito(false)
                    .mensajeUsuario("No pude interpretar los datos correctamente.")
                    .tiempoMs(elapsed)
                    .build();
        }
    }

    private Evento mapearNodoAEvento(JsonNode node, String textoOriginal, LocalDate hoy) {
        Evento evento = new Evento();
        evento.setTitulo(node.path("titulo").asText("Nuevo Evento"));

        String tipoStr = node.path("tipo").asText("OTRO");
        try {
            evento.setTipo(Evento.TipoEvento.valueOf(tipoStr.toUpperCase()));
        } catch (Exception e) {
            evento.setTipo(Evento.TipoEvento.OTRO);
        }

        if (node.hasNonNull("fechaInicio")) {
            try {
                evento.setFechaInicio(LocalDate.parse(node.get("fechaInicio").asText()));
            } catch (Exception e) {
                evento.setFechaInicio(hoy);
            }
        } else {
            evento.setFechaInicio(hoy);
        }

        if (node.hasNonNull("fechaFin")) {
            try { evento.setFechaFin(LocalDate.parse(node.get("fechaFin").asText())); } catch (Exception ignored) {}
        }

        if (node.hasNonNull("horaInicio")) {
            try { evento.setHoraInicio(LocalTime.parse(node.get("horaInicio").asText())); } catch (Exception ignored) {}
        }

        if (node.hasNonNull("horaFin")) {
            try { evento.setHoraFin(LocalTime.parse(node.get("horaFin").asText())); } catch (Exception ignored) {}
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

    // Retrocompatibilidad con EventoParserService
    public EventoIaResponse interpretar(EventoIaRequest request) {
        IaAccionResultado res = interpretarAccion(request.getTexto());
        if (res.isExito() && !res.getCrear().isEmpty()) {
            return new EventoIaResponse(true, null, res.getCrear().get(0), res.getCrear(), res.getTiempoMs());
        }
        return new EventoIaResponse(false, res.getMensajeUsuario(), null, res.getTiempoMs());
    }

    // Retrocompatibilidad para límites de recurrencia en EventoParserService
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

    public String consultar(String pregunta) {
        String systemPrompt = contextBuilder.buildPrompt(pregunta, "calendario");
        return llmClient.chat(systemPrompt, pregunta);
    }
}
