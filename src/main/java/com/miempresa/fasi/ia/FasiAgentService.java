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
        Eres FASI, el asistente operativo de calendario.
        Hoy es %s (%s). Año en curso: %d.
        
        Analiza la orden del usuario y clasifícala obligatoriamente en JSON puro (SIN bloques markdown ```json, solo las llaves {}):
        
        CASO 1: Si pide BORRAR o CANCELAR un evento:
        {
          "accion": "BORRAR",
          "fecha": "YYYY-MM-DD",  // fecha indicada o null si no se menciona
          "texto": "comida Madrid", // términos clave del evento a borrar
          "mensaje": "He borrado la comida en Madrid de tu calendario."
        }
        
        CASO 2: Si pide CREAR uno o más eventos:
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
          "mensaje": "He añadido el cumpleaños de Martín el 7 de diciembre a las 19:00."
        }
        
        CASO 3: CONSULTA simple o saludo:
        {
          "accion": "CONSULTA",
          "mensaje": "Respuesta clara al usuario."
        }
        """;

    public AgentResponse ejecutarOrden(String texto) {
        LocalDate hoy = LocalDate.now();
        String prompt = SYSTEM_PROMPT.formatted(hoy, hoy.getDayOfWeek(), hoy.getYear());

        try {
            log.info("🤖 Interpretando orden: '{}'", texto);
            String rawJson = llmClient.chatJson(prompt, texto);
            log.info("📥 Respuesta cruda de la IA: {}", rawJson);

            // Sanitizar posibles bloques de código markdown
            String limpio = rawJson.trim();
            if (limpio.startsWith("```")) {
                int firstLine = limpio.indexOf('\n');
                int lastBacktick = limpio.lastIndexOf("```");
                if (firstLine != -1 && lastBacktick > firstLine) {
                    limpio = limpio.substring(firstLine + 1, lastBacktick).trim();
                }
            }

            JsonNode root = objectMapper.readTree(limpio);
            String accion = root.path("accion").asText("CONSULTA").toUpperCase();
            String mensaje = root.path("mensaje").asText("Operación procesada.");

            UUID usuarioId = usuarioActualService.obtenerUsuarioActual().getId();

            switch (accion) {
                case "BORRAR" -> {
                    String fechaStr = root.hasNonNull("fecha") ? root.get("fecha").asText() : null;
                    if (root.has("criterio") && root.get("criterio").hasNonNull("fecha")) {
                        fechaStr = root.get("criterio").get("fecha").asText();
                    }

                    String palabrasClave = root.hasNonNull("texto") ? root.get("texto").asText() : "";
                    if (palabrasClave.isBlank() && root.has("criterio") && root.get("criterio").hasNonNull("texto")) {
                        palabrasClave = root.get("criterio").get("texto").asText();
                    }

                    log.info("🔍 Buscando para borrar -> Fecha: {}, Palabras: '{}'", fechaStr, palabrasClave);

                    List<Evento> candidatos = new ArrayList<>();

                    // 1. Intentar por fecha si viene dada
                    if (fechaStr != null && !fechaStr.isBlank() && !fechaStr.equals("null")) {
                        try {
                            LocalDate f = LocalDate.parse(fechaStr);
                            candidatos.addAll(eventoService.findByDia(f));
                        } catch (Exception e) {
                            log.warn("Formato de fecha inválido recibido de IA: {}", fechaStr);
                        }
                    }

                    // 2. Si no hay candidatos o no hubo fecha, buscar por texto en toda la BD del usuario
                    if (candidatos.isEmpty() && !palabrasClave.isBlank()) {
                        candidatos.addAll(eventoRepo.buscarFullText(usuarioId, palabrasClave));
                    }

                    // 3. Fallback: buscar eventos próximos o de todo el mes si aún no hay coincidencia
                    if (candidatos.isEmpty()) {
                        candidatos.addAll(eventoRepo.findAllOptimizado(usuarioId));
                    }

                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No se encontró ningún evento en el calendario que coincida con esa búsqueda.", false);
                    }

                    // Filtrar por tokens de coincidencia
                    List<Evento> aBorrar = new ArrayList<>();
                    String[] tokens = palabrasClave.toLowerCase().split("\\s+");

                    for (Evento e : candidatos) {
                        String textoEvento = (e.getTitulo() + " " + (e.getLugar() != null ? e.getLugar() : "")).toLowerCase();
                        boolean match = false;
                        for (String token : tokens) {
                            if (!token.isBlank() && textoEvento.contains(token)) {
                                match = true;
                                break;
                            }
                        }
                        if (match || tokens.length == 0 || palabrasClave.isBlank()) {
                            aBorrar.add(e);
                        }
                    }

                    if (aBorrar.isEmpty()) {
                        return new AgentResponse("Encontré eventos ese día, pero ninguno coincide con '" + palabrasClave + "'.", false);
                    }

                    for (Evento e : aBorrar) {
                        eventoService.delete(e.getId());
                        log.info("🗑️ Evento borrado de la base de datos: '{}' (ID: {})", e.getTitulo(), e.getId());
                    }

                    String confirmacion = aBorrar.size() == 1
                            ? "He eliminado '" + aBorrar.get(0).getTitulo() + "' de tu calendario."
                            : "He eliminado " + aBorrar.size() + " eventos de tu calendario.";

                    return new AgentResponse(mensaje.isBlank() ? confirmacion : mensaje, true);
                }

                case "CREAR" -> {
                    JsonNode evArray = root.path("eventos");
                    if (evArray.isArray() && !evArray.isEmpty()) {
                        int count = 0;
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
                            count++;
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
}
