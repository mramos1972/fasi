package com.miempresa.fasi.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miempresa.fasi.calendario.Evento;
import com.miempresa.fasi.calendario.EventoRepository;
import com.miempresa.fasi.calendario.EventoService;
import com.miempresa.fasi.contexto.UsuarioActualService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FasiAgentService {

    @Value("${fasi.ai.provider.url:https://generativelanguage.googleapis.com/v1beta/openai}")
    private String apiUrl;

    @Value("${fasi.ai.provider.api-key:}")
    private String apiKey;

    @Value("${fasi.ai.provider.model:gemini-2.5-flash}")
    private String defaultModel;

    private final ObjectMapper objectMapper;
    private final EventoService eventoService;
    private final EventoRepository eventoRepo;
    private final UsuarioActualService usuarioActualService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public record AgentResponse(String mensaje, boolean huboMutacion) {}

    public AgentResponse ejecutarOrden(String promptUsuario) {
        LocalDate hoy = LocalDate.now();
        List<Map<String, Object>> messages = new ArrayList<>();

        String systemPrompt = """
            Eres FASI, el asistente operativo inteligente del calendario.
            Hoy es %s (%s). Año en curso: %d.
            
            Tienes herramientas disponibles para interactuar con la base de datos:
            - 'buscar_eventos': Consulta eventos antes de borrar o cuando pregunten por fechas.
            - 'eliminar_evento': Borra un evento por su UUID exacto (obtenlo primero con buscar_eventos si no lo tienes).
            - 'crear_evento': Añade un evento a la base de datos.
            
            Reglas de actuación:
            1. Si el usuario pide borrar o cancelar un evento, PRIMERO llama a 'buscar_eventos' para localizar su UUID exacto.
            2. Con el UUID obtenido, llama a 'eliminar_evento'.
            3. Si pide crear uno o varios eventos, llama a 'crear_evento' para cada uno.
            4. Al terminar todas las acciones, responde con un mensaje claro y conciso confirmando lo realizado.
            """.formatted(hoy, hoy.getDayOfWeek(), hoy.getYear());

        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", promptUsuario));

        boolean huboMutacion = false;
        int maxIteraciones = 6;

        for (int i = 0; i < maxIteraciones; i++) {
            log.info("🤖 Agente FASI: Iteración {} ejecutando...", i + 1);
            JsonNode respuestaLlm = llamarApiConReintento(messages);

            if (respuestaLlm == null) {
                return new AgentResponse("Los servidores de IA están saturados temporalmente (503). Por favor, repite la orden en unos segundos.", huboMutacion);
            }

            JsonNode choices = respuestaLlm.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return new AgentResponse("No se obtuvo respuesta válida del modelo.", huboMutacion);
            }

            JsonNode messageNode = choices.get(0).path("message");

            // Si el modelo solicita ejecutar herramientas
            if (messageNode.has("tool_calls") && !messageNode.path("tool_calls").isEmpty()) {
                messages.add(objectMapper.convertValue(messageNode, Map.class));

                for (JsonNode toolCall : messageNode.path("tool_calls")) {
                    String toolCallId = toolCall.path("id").asText();
                    String funcName = toolCall.path("function").path("name").asText();
                    String argsStr = toolCall.path("function").path("arguments").asText();

                    log.info("🛠️ Ejecutando herramienta: '{}' con argumentos: {}", funcName, argsStr);
                    String resultadoTool = ejecutarHerramienta(funcName, argsStr);

                    if ("eliminar_evento".equals(funcName) || "crear_evento".equals(funcName)) {
                        huboMutacion = true;
                    }

                    Map<String, Object> toolMessage = new HashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", toolCallId);
                    toolMessage.put("name", funcName);
                    toolMessage.put("content", resultadoTool);
                    messages.add(toolMessage);
                }
            } else {
                // Respuesta final completada
                String respuestaFinal = messageNode.path("content").asText();
                log.info("✅ Agente completó su tarea: {}", respuestaFinal);
                return new AgentResponse(respuestaFinal, huboMutacion);
            }
        }

        return new AgentResponse("Operación completada con éxito.", huboMutacion);
    }

    private String ejecutarHerramienta(String nombre, String argumentosJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentosJson);
            UUID usuarioId = usuarioActualService.obtenerUsuarioActual().getId();

            switch (nombre) {
                case "buscar_eventos" -> {
                    String query = args.path("texto").asText("");
                    String desdeStr = args.path("fechaDesde").asText("");
                    String hastaStr = args.path("fechaHasta").asText("");

                    List<Evento> encontrados;
                    if (!query.isBlank()) {
                        encontrados = eventoRepo.buscarFullText(usuarioId, query);
                    } else if (!desdeStr.isBlank() && !hastaStr.isBlank()) {
                        LocalDate d = LocalDate.parse(desdeStr);
                        LocalDate h = LocalDate.parse(hastaStr);
                        encontrados = eventoRepo.findByRango(usuarioId, d, h);
                    } else {
                        encontrados = eventoRepo.findProximosOptimizado(usuarioId, LocalDate.now());
                    }

                    List<Map<String, Object>> lista = new ArrayList<>();
                    for (Evento e : encontrados) {
                        lista.add(Map.of(
                                "id", e.getId().toString(),
                                "titulo", e.getTitulo(),
                                "fechaInicio", e.getFechaInicio().toString(),
                                "horaInicio", e.getHoraInicio() != null ? e.getHoraInicio().toString() : "",
                                "lugar", e.getLugar() != null ? e.getLugar() : ""
                        ));
                    }
                    return objectMapper.writeValueAsString(Map.of("total", lista.size(), "eventos", lista));
                }

                case "eliminar_evento" -> {
                    String idStr = args.path("id").asText();
                    UUID id = UUID.fromString(idStr);
                    eventoService.delete(id);
                    log.info("🗑️ Evento {} eliminado con éxito por el Agente", id);
                    return objectMapper.writeValueAsString(Map.of("status", "ok", "eliminadoId", idStr));
                }

                case "crear_evento" -> {
                    Evento e = new Evento();
                    e.setTitulo(args.path("titulo").asText("Nuevo evento"));
                    e.setFechaInicio(LocalDate.parse(args.path("fechaInicio").asText()));

                    if (args.hasNonNull("horaInicio") && !args.path("horaInicio").asText().isBlank()) {
                        e.setHoraInicio(LocalTime.parse(args.path("horaInicio").asText()));
                    }

                    if (args.hasNonNull("fechaFin") && !args.path("fechaFin").asText().isBlank()) {
                        e.setFechaFin(LocalDate.parse(args.path("fechaFin").asText()));
                    }

                    String tipoStr = args.path("tipo").asText("OTRO");
                    try {
                        e.setTipo(Evento.TipoEvento.valueOf(tipoStr.toUpperCase()));
                    } catch (Exception ex) {
                        e.setTipo(Evento.TipoEvento.OTRO);
                    }

                    if (args.hasNonNull("lugar") && !args.path("lugar").asText().isBlank()) {
                        e.setLugar(args.path("lugar").asText());
                    }

                    if (args.hasNonNull("personasRelacionadas") && !args.path("personasRelacionadas").asText().isBlank()) {
                        e.setPersonasRelacionadas(args.path("personasRelacionadas").asText());
                    }

                    e.setTodoDia(args.path("todoDia").asBoolean(e.getHoraInicio() == null));
                    Evento guardado = eventoService.save(e);
                    return objectMapper.writeValueAsString(Map.of("status", "ok", "creadoId", guardado.getId().toString()));
                }

                default -> {
                    return "{\"error\": \"Herramienta desconocida\"}";
                }
            }
        } catch (Exception e) {
            log.error("Error al ejecutar herramienta {}", nombre, e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private JsonNode llamarApiConReintento(List<Map<String, Object>> messages) {
        // Modelos a probar en cascada si hay 503 por sobrecarga
        List<String> modelosFallback = List.of(defaultModel, "gemini-2.5-flash", "gemini-1.5-flash");

        for (String modelo : modelosFallback) {
            for (int intento = 1; intento <= 2; intento++) {
                try {
                    Map<String, Object> body = new HashMap<>();
                    body.put("model", modelo);
                    body.put("messages", messages);
                    body.put("temperature", 0.1);
                    body.put("tools", obtenerDefinicionHerramientas());

                    String payload = objectMapper.writeValueAsString(body);

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(apiUrl + "/chat/completions"))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + apiKey)
                            .POST(HttpRequest.BodyPublishers.ofString(payload))
                            .timeout(Duration.ofSeconds(30))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                    if (response.statusCode() == 200) {
                        return objectMapper.readTree(response.body());
                    }

                    if (response.statusCode() == 503 || response.statusCode() == 429) {
                        log.warn("⚠️ Modelo {} sobrecargado (HTTP {}). Intento {}/2. Esperando 1.5s...", modelo, response.statusCode(), intento);
                        Thread.sleep(1500);
                        continue;
                    }

                    log.error("Error API Gemini ({}): {}", response.statusCode(), response.body());
                    break; // Error no recuperable con reintento inmediato

                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                } catch (Exception e) {
                    log.error("Fallo de red comunicando con modelo {}", modelo, e);
                }
            }
        }

        return null;
    }

    private List<Map<String, Object>> obtenerDefinicionHerramientas() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "buscar_eventos",
                                "description", "Busca eventos existentes en el calendario por palabras clave o rango de fechas (YYYY-MM-DD)",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "texto", Map.of("type", "string", "description", "Texto a buscar en el título o lugar"),
                                                "fechaDesde", Map.of("type", "string", "description", "Fecha inicial YYYY-MM-DD"),
                                                "fechaHasta", Map.of("type", "string", "description", "Fecha final YYYY-MM-DD")
                                        )
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "eliminar_evento",
                                "description", "Elimina de forma permanente un evento de la base de datos usando su ID UUID",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "id", Map.of("type", "string", "description", "UUID exacto del evento a eliminar")
                                        ),
                                        "required", List.of("id")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "crear_evento",
                                "description", "Crea un nuevo evento en el calendario",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "titulo", Map.of("type", "string", "description", "Título del evento"),
                                                "fechaInicio", Map.of("type", "string", "description", "Fecha YYYY-MM-DD"),
                                                "horaInicio", Map.of("type", "string", "description", "Hora HH:MM o vacío"),
                                                "fechaFin", Map.of("type", "string", "description", "Fecha fin YYYY-MM-DD si es de varios días"),
                                                "tipo", Map.of("type", "string", "description", "PERSONAL, SOCIAL, SALUD, TRABAJO, FAMILIA, VIAJE, VACACIONES, APRENDIZAJE, OCIO, GESTION, OTRO"),
                                                "lugar", Map.of("type", "string", "description", "Ubicación si aplica"),
                                                "personasRelacionadas", Map.of("type", "string", "description", "Personas implicadas"),
                                                "todoDia", Map.of("type", "boolean", "description", "true si no tiene hora fija")
                                        ),
                                        "required", List.of("titulo", "fechaInicio")
                                )
                        )
                )
        );
    }
}
