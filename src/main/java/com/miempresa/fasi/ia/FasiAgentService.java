package com.miempresa.fasi.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miempresa.fasi.calendario.Evento;
import com.miempresa.fasi.calendario.EventoRepository;
import com.miempresa.fasi.calendario.EventoService;
import com.miempresa.fasi.contexto.UsuarioActualService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FasiAgentService {

    private final LlmApiClient llmClient;
    private final ObjectMapper objectMapper;
    private final EventoService eventoService;
    private final EventoRepository eventoRepo;
    private final UsuarioActualService usuarioActualService;

    public record AgentResponse(String mensaje, boolean huboMutacion) {}

    private static final String SYSTEM_PROMPT = """
        Eres FASI, el cerebro operativo del calendario familiar y personal.
        Hoy es %s (%s). Año en curso: %d.
        
        Tu misión es analizar la orden del usuario y clasificarla en una acción ejecutable en UN SOLO PASO.
        
        POSIBLES ACCIONES:
        1. "BORRAR": El usuario quiere eliminar, cancelar o borrar uno o más eventos.
        2. "CREAR": El usuario quiere agendar uno o varios eventos.
        3. "CONSULTA": El usuario solo pregunta qué tiene que hacer un día o si tiene eventos.
        
        FORMATO DE SALIDA ESTRICTO (solo JSON puro, sin explicaciones ni markdown):
        
        Si pide BORRAR:
        {
          "accion": "BORRAR",
          "criterio": {
            "fecha": "YYYY-MM-DD",   // fecha mencionada o null si no especifica
            "texto": "comida Madrid" // palabras clave para identificar el evento a borrar
          },
          "mensaje": "He eliminado la comida en Madrid de tu calendario."
        }
        
        Si pide CREAR:
        {
          "accion": "CREAR",
          "eventos": [
            {
              "titulo": "Cumpleaños de Martín",
              "fechaInicio": "2026-12-07",
              "horaInicio": "19:00",
              "tipo": "SOCIAL",
              "lugar": null,
              "personas": "Martín",
              "todoDia": false
            }
          ],
          "mensaje": "He añadido el evento a tu calendario."
        }
        
        Si es CONSULTA:
        {
          "accion": "CONSULTA",
          "mensaje": "Responde de forma concisa a lo que pregunta el usuario."
        }
        """;

    public AgentResponse ejecutarOrden(String texto) {
        LocalDate hoy = LocalDate.now();
        String prompt = SYSTEM_PROMPT.formatted(hoy, hoy.getDayOfWeek(), hoy.getYear());

        try {
            log.info("🤖 Interpretando orden en una sola pasada: '{}'", texto);
            String json = llmClient.chatJson(prompt, texto);
            JsonNode root = objectMapper.readTree(json);

            String accion = root.path("accion").asText("CONSULTA");
            String mensaje = root.path("mensaje").asText("Operación completada.");

            UUID usuarioId = usuarioActualService.obtenerUsuarioActual().getId();

            switch (accion.toUpperCase()) {
                case "BORRAR" -> {
                    JsonNode criterio = root.path("criterio");
                    String fechaStr = criterio.path("fecha").asText(null);
                    String palabrasClave = criterio.path("texto").asText("");

                    List<Evento> candidatos = new ArrayList<>();

                    if (fechaStr != null && !fechaStr.isBlank() && !fechaStr.equals("null")) {
                        try {
                            LocalDate f = LocalDate.parse(fechaStr);
                            candidatos = eventoService.findByDia(f);
                        } catch (Exception ignored) {}
                    }

                    if (candidatos.isEmpty() && !palabrasClave.isBlank()) {
                        candidatos = eventoRepo.buscarFullText(usuarioId, palabrasClave);
                    }

                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No encontré ningún evento en tu calendario que coincida con esa descripción para borrar.", false);
                    }

                    // Filtramos si hay palabras clave dentro de los candidatos del día
                    List<Evento> aBorrar = new ArrayList<>();
                    String[] tokens = palabrasClave.toLowerCase().split("\\s+");

                    for (Evento e : candidatos) {
                        String matchStr = (e.getTitulo() + " " + (e.getLugar() != null ? e.getLugar() : "")).toLowerCase();
                        boolean coincide = false;
                        for (String token : tokens) {
                            if (!token.isBlank() && matchStr.contains(token)) {
                                coincide = true;
                                break;
                            }
                        }
                        if (coincide || tokens.length == 0 || palabrasClave.isBlank()) {
                            aBorrar.add(e);
                        }
                    }

                    if (aBorrar.isEmpty()) {
                        aBorrar = candidatos;
                    }

                    for (Evento e : aBorrar) {
                        eventoService.delete(e.getId());
                        log.info("🗑️ Evento borrado con éxito: '{}' ({})", e.getTitulo(), e.getId());
                    }

                    return new AgentResponse(mensaje, true);
                }

                case "CREAR" -> {
                    JsonNode evArray = root.path("eventos");
                    if (evArray.isArray()) {
                        for (JsonNode node : evArray) {
                            Evento e = new Evento();
                            e.setTitulo(node.path("titulo").asText("Nuevo evento"));
                            e.setFechaInicio(LocalDate.parse(node.path("fechaInicio").asText(hoy.toString())));

                            if (node.hasNonNull("horaInicio") && !node.path("horaInicio").asText().isBlank()) {
                                try { e.setHoraInicio(LocalTime.parse(node.path("horaInicio").asText())); } catch (Exception ignored) {}
                            }

                            String tipoStr = node.path("tipo").asText("OTRO");
                            try { e.setTipo(Evento.TipoEvento.valueOf(tipoStr.toUpperCase())); }
                            catch (Exception ex) { e.setTipo(Evento.TipoEvento.OTRO); }

                            if (node.hasNonNull("lugar") && !node.path("lugar").asText().isBlank()) {
                                e.setLugar(node.path("lugar").asText());
                            }
                            if (node.hasNonNull("personas") && !node.path("personas").asText().isBlank()) {
                                e.setPersonasRelacionadas(node.path("personas").asText());
                            }

                            e.setTodoDia(node.path("todoDia").asBoolean(e.getHoraInicio() == null));
                            eventoService.save(e);
                            log.info("📅 Evento creado con éxito: '{}'", e.getTitulo());
                        }
                        return new AgentResponse(mensaje, true);
                    }
                }

                case "CONSULTA" -> {
                    return new AgentResponse(mensaje, false);
                }
            }

            return new AgentResponse(mensaje, false);

        } catch (Exception e) {
            log.error("Error al procesar orden agéntica", e);
            return new AgentResponse("Ocurrió un error al procesar tu solicitud: " + e.getMessage(), false);
        }
    }
}
