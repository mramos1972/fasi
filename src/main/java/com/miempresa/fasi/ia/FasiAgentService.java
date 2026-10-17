package com.miempresa.fasi.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miempresa.fasi.calendario.Evento;
import com.miempresa.fasi.calendario.EventoRepository;
import com.miempresa.fasi.calendario.EventoService;
import com.miempresa.fasi.contexto.Usuario;
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

    @Value("${fasi.ai.provider.model:gemini-3.8-flash}")
    private String model;

    private final ObjectMapper objectMapper;
    private final EventoService eventoService;
    private final EventoRepository eventoRepo;
    private final UsuarioActualService usuarioActualService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record AgentResponse(String mensaje, boolean huboMutacion) {}

    public AgentResponse ejecutarOrden(String promptUsuario) {
        LocalDate hoy = LocalDate.now();
        List<Map<String, Object>> messages = new ArrayList<>();

        String systemPrompt = """
            Eres FASI, el asistente operativo inteligente del calendario.
            Hoy es %s (%s). Año: %d.
            
            Tienes herramientas disponibles para interactuar con la base de datos:
            - 'buscar_eventos': Búscate la vida consultando eventos antes de borrar o si te preguntan por fechas.
            - 'eliminar_evento': Borra un evento por su UUID exacto (consigue primero el UUID con buscar_eventos si no lo tienes).
            - 'crear_evento': Añade un evento a la base de datos.
            
            Comportamiento obligatorio:
            - Si el usuario te pide borrar, cancelar o mover una cita, PRIMERO busca en la base de datos para verificar que existe y obtener su UUID.
            - Una vez localizado el UUID, llama a 'eliminar_evento'.
            - Si te pide crear una o varias citas, usa 'crear_evento'.
            - Cuando termines las acciones, responde con un mensaje cordial, claro y conciso confirmando lo que has hecho.
            """.formatted(hoy, hoy.getDayOfWeek(), hoy.getYear());

        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", promptUsuario));

        boolean huboMutacion = false;
        int maxIteraciones = 6; // Límite de seguridad para evitar bucles infinitos

        for (int i = 0; i < maxIteraciones; i++) {
            log.info("🤖 Agente FASI: Iteración {} ejecutando...", i + 1);
            JsonNode respuestaLlm = llamarApiConHerramientas(messages);

            if (respuestaLlm == null) {
                return new AgentResponse("Hubo un error de comunicación con el asistente.", huboMutacion);
            }

            JsonNode choice = respuestaLlm.path("choices").get(0);
            JsonNode messageNode = choice.path("message");

            // Si el modelo decide llamar a herramientas (tool_calls)
            if (messageNode.has("tool_calls") && !messageNode.path("tool_calls").isEmpty()) {
                // Añadir el mensaje del asistente con las tool_calls al historial
                messages.add(objectMapper.convertValue(messageNode, Map.class));

                for (JsonNode toolCall : messageNode.path("tool_calls")) {
                    String toolCallId = toolCall.path("id").asText();
                    String funcName = toolCall.path("function").path("name").asText();
                    String argsStr = toolCall.path("function").path("arguments").asText();

                    log.info("🛠️ Agente ejecutando herramienta: '{}' con argumentos: {}", funcName, argsStr);
                    String resultadoTool = ejecutarHerramienta(funcName, argsStr);

                    if (funcName.equals("eliminar_evento") || funcName.equals("crear_evento")) {
                        huboMutacion = true;
                    }

                    // Inyectar el resultado de la herramienta para que el modelo lo procese
                    Map<String, Object> toolMessage = new HashMap<>();
                    toolMessage.put("role", "tool");
                    toolMessage.put("tool_call_id", toolCallId);
                    toolMessage.put("name", funcName);
                    toolMessage.put("content", resultadoTool);
                    messages.add(toolMessage);
                }
            } else {
                // El modelo ha terminado de actuar y devuelve la respuesta textual final
                String respuestaFinal = messageNode.path("content").asText();
                log.info("✅ Agente completó su tarea: {}", respuestaFinal);
                return new AgentResponse(respuestaFinal, huboMutacion);
            }
        }

        return new AgentResponse("Operación completada tras varias acciones.", huboMutacion);
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

    private JsonNode llamarApiConHerramientas(List<Map<String, Object>> messages) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.1);
            body.put("tools", obtenerDefinicionHerramientas());

            String payload = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(45))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Error API Gemini: {} -> {}", response.statusCode(), response.body());
                return null;
            }

            return objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.error("Fallo comunicando con Gemini en modo agente", e);
            return null;
        }
    }

    private List<Map<String, Object>> obtenerDefinicionHerramientas() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // 1. buscar_eventos
        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "buscar_eventos",
                        "description", "Busca eventos existentes en el calendario por palabras clave o por rango de fechas (YYYY-MM-DD)",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "texto", Map.of("type", "string", "description", "Texto a buscar en el título o lugar"),
                                        "fechaDesde", Map.of("type", "string", "description", "Fecha inicial en formato YYYY-MM-DD"),
                                        "fechaHasta", Map.of("type", "string", "description", "Fecha final en formato YYYY-MM-DD")
                                )
                        )
                )
        ));

        // 2. eliminar_evento
        tools.add(Map.of(
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
        ));

        // 3. crear_evento
        tools.add(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "crear_evento",
                        "description", "Crea un nuevo evento en el calendario",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "titulo", Map.of("type", "string", "description", "Título del evento"),
                                        "fechaInicio", Map.of("type", "string", "description", "Fecha en formato YYYY-MM-DD"),
                                        "horaInicio", Map.of("type", "string", "description", "Hora en formato HH:MM (ej 16:00) o vacío"),
                                        "fechaFin", Map.of("type", "string", "description", "Fecha fin en formato YYYY-MM-DD si es de varios días"),
                                        "tipo", Map.of("type", "string", "description", "PERSONAL, SOCIAL, SALUD, TRABAJO, FAMILIA, VIAJE, VACACIONES, APRENDIZAJE, OCIO, GESTION, OTRO"),
                                        "lugar", Map.of("type", "string", "description", "Ubicación si aplica"),
                                        "personasRelacionadas", Map.of("type", "string", "description", "Personas implicadas"),
                                        "todoDia", Map.of("type", "boolean", "description", "true si dura todo el día sin hora fija")
                                ),
                                "required", List.of("titulo", "fechaInicio")
                        )
                )
        ));

        return tools;
    }
}
