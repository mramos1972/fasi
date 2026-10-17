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
        Eres FASI, el asistente operativo inteligente de calendario.
        Hoy es %s (%s). Año en curso: %d.
        
        Analiza la orden del usuario y clasifícala estrictamente en una de estas 4 acciones en JSON puro (sin ```json):
        
        1. ACCIÓN "MODIFICAR" / "MOVER":
        Si el usuario pide mover, cambiar de hora, posponer o cambiar la fecha de un evento existente:
        {
          "accion": "MODIFICAR",
          "buscar": {
            "fecha": "YYYY-MM-DD",     // fecha donde está actualmente o null
            "texto": "cumpleaños"      // texto para buscar el evento original
          },
          "cambios": {
            "nuevaFecha": "YYYY-MM-DD",// nueva fecha solicitada (o null si solo cambia hora)
            "nuevaHora": "HH:MM",      // nueva hora solicitada (o null si solo cambia fecha)
            "nuevoTitulo": null        // nuevo título si pide renombrarlo, o null
          },
          "mensaje": "He movido el cumpleaños al 15 de diciembre a las 18:00."
        }
        
        2. ACCIÓN "BORRAR":
        Si el usuario pide eliminar, borrar o cancelar un evento:
        {
          "accion": "BORRAR",
          "fecha": "YYYY-MM-DD",
          "texto": "comida Madrid",
          "mensaje": "He eliminado la comida en Madrid de tu calendario."
        }
        
        3. ACCIÓN "CREAR":
        Si pide agendar uno o varios eventos nuevos:
        {
          "accion": "CREAR",
          "eventos": [
            {
              "titulo": "Reunión de equipo",
              "fechaInicio": "2026-12-10",
              "horaInicio": "10:00",
              "tipo": "TRABAJO",
              "lugar": null,
              "personas": null,
              "todoDia": false
            }
          ],
          "mensaje": "He añadido la reunión de equipo el 10 de diciembre."
        }
        
        4. ACCIÓN "CONSULTA":
        Preguntas sobre qué eventos hay o saludos:
        {
          "accion": "CONSULTA",
          "mensaje": "Respuesta clara a la duda del usuario."
        }
        """;

    public AgentResponse ejecutarOrden(String texto) {
        LocalDate hoy = LocalDate.now();
        String prompt = SYSTEM_PROMPT.formatted(hoy, hoy.getDayOfWeek(), hoy.getYear());

        try {
            log.info("🤖 Interpretando orden: '{}'", texto);
            String rawJson = llmClient.chatJson(prompt, texto);
            log.info("📥 Respuesta cruda de la IA: {}", rawJson);

            String limpio = sanitizarJson(rawJson);
            JsonNode root = objectMapper.readTree(limpio);

            String accion = root.path("accion").asText("CONSULTA").toUpperCase();
            String mensaje = root.path("mensaje").asText("Operación completada.");
            UUID usuarioId = usuarioActualService.obtenerUsuarioActual().getId();

            switch (accion) {
                case "MODIFICAR" -> {
                    JsonNode buscarNode = root.path("buscar");
                    JsonNode cambiosNode = root.path("cambios");

                    String fechaBuscar = buscarNode.path("fecha").asText(null);
                    String textoBuscar = buscarNode.path("texto").asText("");

                    List<Evento> candidatos = buscarCandidatos(usuarioId, fechaBuscar, textoBuscar);
                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No encontré ningún evento en tu calendario que coincida con '" + textoBuscar + "' para modificar.", false);
                    }

                    Evento eventoAEditar = candidatos.get(0); // Tomamos la mejor coincidencia

                    if (cambiosNode.hasNonNull("nuevaFecha") && !cambiosNode.path("nuevaFecha").asText().isBlank()) {
                        try {
                            eventoAEditar.setFechaInicio(LocalDate.parse(cambiosNode.path("nuevaFecha").asText()));
                        } catch (Exception ignored) {}
                    }

                    if (cambiosNode.hasNonNull("nuevaHora") && !cambiosNode.path("nuevaHora").asText().isBlank()) {
                        try {
                            eventoAEditar.setHoraInicio(LocalTime.parse(cambiosNode.path("nuevaHora").asText()));
                            eventoAEditar.setTodoDia(false);
                        } catch (Exception ignored) {}
                    }

                    if (cambiosNode.hasNonNull("nuevoTitulo") && !cambiosNode.path("nuevoTitulo").asText().isBlank()) {
                        eventoAEditar.setTitulo(cambiosNode.path("nuevoTitulo").asText());
                    }

                    eventoService.update(eventoAEditar.getId(), eventoAEditar);
                    log.info("✏️ Evento modificado con éxito: '{}' ahora en fecha {}", eventoAEditar.getTitulo(), eventoAEditar.getFechaInicio());

                    return new AgentResponse(mensaje, true);
                }

                case "BORRAR" -> {
                    String fechaStr = root.path("fecha").asText(null);
                    String palabrasClave = root.path("texto").asText("");

                    List<Evento> candidatos = buscarCandidatos(usuarioId, fechaStr, palabrasClave);

                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No encontré ningún evento en tu calendario que coincida para borrar.", false);
                    }

                    for (Evento e : candidatos) {
                        eventoService.delete(e.getId());
                        log.info("🗑️ Evento borrado de la base de datos: '{}' (ID: {})", e.getTitulo(), e.getId());
                    }

                    String confirmacion = candidatos.size() == 1
                            ? "He eliminado '" + candidatos.get(0).getTitulo() + "' de tu calendario."
                            : "He eliminado " + candidatos.size() + " eventos de tu calendario.";

                    return new AgentResponse(mensaje.isBlank() ? confirmacion : mensaje, true);
                }

                case "CREAR" -> {
                    JsonNode evArray = root.path("eventos");
                    if (evArray.isArray() && !evArray.isEmpty()) {
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
                            log.info("📅 Evento creado: '{}' para {}", e.getTitulo(), e.getFechaInicio());
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
            return new AgentResponse("Error procesando la orden: " + e.getMessage(), false);
        }
    }

    private List<Evento> buscarCandidatos(UUID usuarioId, String fechaStr, String palabrasClave) {
        List<Evento> candidatos = new ArrayList<>();

        if (fechaStr != null && !fechaStr.isBlank() && !fechaStr.equals("null")) {
            try {
                LocalDate f = LocalDate.parse(fechaStr);
                candidatos.addAll(eventoService.findByDia(f));
            } catch (Exception ignored) {}
        }

        if (candidatos.isEmpty() && palabrasClave != null && !palabrasClave.isBlank()) {
            candidatos.addAll(eventoRepo.buscarFullText(usuarioId, palabrasClave));
        }

        if (candidatos.isEmpty()) {
            candidatos.addAll(eventoRepo.findAllOptimizado(usuarioId));
        }

        if (palabrasClave == null || palabrasClave.isBlank()) {
            return candidatos;
        }

        List<Evento> filtrados = new ArrayList<>();
        String[] tokens = palabrasClave.toLowerCase().split("\\s+");

        for (Evento e : candidatos) {
            String matchStr = (e.getTitulo() + " " + (e.getLugar() != null ? e.getLugar() : "")).toLowerCase();
            for (String token : tokens) {
                if (!token.isBlank() && matchStr.contains(token)) {
                    filtrados.add(e);
                    break;
                }
            }
        }

        return filtrados.isEmpty() ? candidatos : filtrados;
    }

    private String sanitizarJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstLine = s.indexOf('\n');
            int lastBacktick = s.lastIndexOf("```");
            if (firstLine != -1 && lastBacktick > firstLine) {
                s = s.substring(firstLine + 1, lastBacktick).trim();
            }
        }
        return s;
    }
}
