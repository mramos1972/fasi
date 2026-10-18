package com.miempresa.fasi.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Cliente Gemini (REST nativo v1beta).
 *
 *  - chat / chatJson    : llamadas simples.
 *  - generateWithTools  : function calling para el agente.
 *
 * Resiliencia:
 *  - thinking por familia de modelo (budget 2.x / level 3.x+), con fallback sin thinkingConfig
 *  - 429 de cuota diaria -> NO se reintenta en el mismo modelo; se pasa al siguiente de
 *    fasi.ai.provider.fallback-models (si lo hay). 429 por ráfaga (por minuto) -> espera y reintenta.
 *  - 500/503/timeout -> reintento con espera
 *  - Mensajes de error legibles para el usuario (LlmException.userMessage)
 */
@Slf4j
@Component
public class LlmApiClient {

    @Value("${fasi.ai.provider.url:https://generativelanguage.googleapis.com/v1beta}")
    private String apiUrl;

    @Value("${fasi.ai.provider.api-key:}")
    private String apiKey;

    @Value("${fasi.ai.provider.model:gemini-3.8-flash}")
    private String model;

    /** Modelos de respaldo separados por comas, p. ej. "gemini-3.7-flash,gemini-3.5-flash-lite". */
    @Value("${fasi.ai.provider.fallback-models:}")
    private String fallbackModels;

    @Value("${fasi.ai.provider.timeout-seconds:45}")
    private int timeoutSeconds;

    @Value("${fasi.ai.provider.thinking:auto}")
    private String thinkingMode;

    @Value("${fasi.ai.provider.thinking-level:minimal}")
    private String thinkingLevel;

    @Value("${fasi.ai.provider.max-retries:2}")
    private int maxRetries;

    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public LlmApiClient(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ══════════════════════════════════════════════════════════
    //  Tipos públicos
    // ══════════════════════════════════════════════════════════

    public record FunctionCall(String name, JsonNode args) {}

    public record LlmTurn(String text, List<FunctionCall> calls, JsonNode modelContent) {
        public boolean hasCalls() { return calls != null && !calls.isEmpty(); }
    }

    public static class LlmException extends RuntimeException {
        private final String userMessage;
        public LlmException(String technical, String userMessage) { super(technical); this.userMessage = userMessage; }
        public LlmException(String technical) { this(technical, "El asistente de IA no está disponible ahora mismo. Inténtalo de nuevo en unos segundos."); }
        public String userMessage() { return userMessage; }
    }

    private enum Thinking { BUDGET, LEVEL, OFF }

    /** Señal interna: cuota agotada en este modelo -> probar el siguiente. */
    private static final class QuotaExhausted extends Exception {
        final String body;
        QuotaExhausted(String body) { super("quota"); this.body = body; }
    }

    // ══════════════════════════════════════════════════════════
    //  API simple (compatibilidad)
    // ══════════════════════════════════════════════════════════

    public String chatJson(String systemPrompt, String userPrompt) {
        try {
            ArrayNode contents = mapper.createArrayNode();
            contents.add(userText(userPrompt));
            return extraerTexto(generateContent(systemPrompt, contents, null, true), "{}");
        } catch (Exception e) {
            log.error("❌ Fallo en la llamada a IA (json): {}", e.getMessage());
            return "{}";
        }
    }

    public String chat(String systemPrompt, String userPrompt) {
        try {
            ArrayNode contents = mapper.createArrayNode();
            contents.add(userText(userPrompt));
            return extraerTexto(generateContent(systemPrompt, contents, null, false),
                    "No he podido generar una respuesta.");
        } catch (LlmException e) {
            log.error("❌ Fallo en la llamada a IA (chat): {}", e.getMessage());
            return "⚠️ " + e.userMessage();
        } catch (Exception e) {
            log.error("❌ Fallo en la llamada a IA (chat): {}", e.getMessage());
            return "⚠️ El asistente no está disponible ahora mismo.";
        }
    }

    // ══════════════════════════════════════════════════════════
    //  API con herramientas (agente)
    // ══════════════════════════════════════════════════════════

    public LlmTurn generateWithTools(String systemInstruction, ArrayNode contents, ArrayNode functionDeclarations) {
        JsonNode resp = generateContent(systemInstruction, contents, functionDeclarations, false);

        JsonNode candidate = resp.path("candidates").path(0);
        JsonNode content   = candidate.path("content");

        if (content.isMissingNode() || !content.has("parts")) {
            String motivo = resp.path("promptFeedback").path("blockReason").asText(
                    candidate.path("finishReason").asText("sin contenido"));
            throw new LlmException("El modelo no devolvió contenido (" + motivo + ")",
                    "La IA no ha devuelto respuesta (" + motivo + "). Prueba a formular la orden de otra manera.");
        }

        List<FunctionCall> calls = new ArrayList<>();
        StringBuilder texto = new StringBuilder();
        for (JsonNode part : content.path("parts")) {
            if (part.has("functionCall")) {
                JsonNode fc = part.get("functionCall");
                calls.add(new FunctionCall(fc.path("name").asText(), fc.path("args")));
            } else if (part.has("text") && !part.path("thought").asBoolean(false)) {
                texto.append(part.get("text").asText());
            }
        }

        ObjectNode modelContent = (ObjectNode) content.deepCopy();
        modelContent.put("role", "model");
        return new LlmTurn(texto.toString().trim(), calls, modelContent);
    }

    // ══════════════════════════════════════════════════════════
    //  Constructores de "contents"
    // ══════════════════════════════════════════════════════════

    public ObjectNode userText(String texto) {
        ObjectNode c = mapper.createObjectNode();
        c.put("role", "user");
        c.putArray("parts").addObject().put("text", texto);
        return c;
    }

    public ObjectNode functionResponsePart(String nombre, JsonNode respuesta) {
        ObjectNode part = mapper.createObjectNode();
        ObjectNode fr = part.putObject("functionResponse");
        fr.put("name", nombre);
        fr.set("response", respuesta);
        return part;
    }

    // ══════════════════════════════════════════════════════════
    //  Núcleo: cadena de modelos
    // ══════════════════════════════════════════════════════════

    private JsonNode generateContent(String systemInstruction, ArrayNode contents,
                                     ArrayNode tools, boolean forzarJson) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("AI_API_KEY no configurada", "La IA no está configurada (falta la clave de API).");
        }
        if (!apiUrl.contains("googleapis.com")) {
            throw new LlmException("Este cliente solo soporta la API nativa de Gemini");
        }

        List<String> cadena = new ArrayList<>();
        cadena.add(limpiar(model));
        if (fallbackModels != null && !fallbackModels.isBlank()) {
            Arrays.stream(fallbackModels.split(",")).map(this::limpiar).filter(m -> !m.isEmpty() && !cadena.contains(m)).forEach(cadena::add);
        }

        String ultimoCuerpo = "";
        for (String modelo : cadena) {
            try {
                return generarConModelo(modelo, systemInstruction, contents, tools, forzarJson);
            } catch (QuotaExhausted q) {
                ultimoCuerpo = q.body;
                log.warn("⚠️ Cuota agotada en {}; {}", modelo,
                        modelo.equals(cadena.get(cadena.size() - 1)) ? "no quedan modelos de respaldo" : "probando el siguiente modelo");
            }
        }
        throw new LlmException("HTTP 429 cuota diaria agotada: " + abreviar(ultimoCuerpo),
                "He agotado la cuota diaria gratuita de la IA de Google. Se renueva cada día; "
              + "para uso continuado hay que activar la facturación del proyecto (coste de céntimos).");
    }

    private JsonNode generarConModelo(String modelo, String systemInstruction, ArrayNode contents,
                                      ArrayNode tools, boolean forzarJson) throws QuotaExhausted {
        Thinking thinking = resolverThinking(modelo);
        ObjectNode payload = construirPayload(systemInstruction, contents, tools, forzarJson, thinking);

        int intento = 0;
        while (true) {
            intento++;
            long inicio = System.currentTimeMillis();
            try {
                HttpResponse<String> r = enviar(modelo, payload);
                long ms = System.currentTimeMillis() - inicio;
                int st = r.statusCode();
                String body = r.body() == null ? "" : r.body();

                if (st == 200) {
                    log.info("⚡ Gemini {} respondió en {} ms", modelo, ms);
                    return mapper.readTree(body);
                }

                if (st == 400 && thinking != Thinking.OFF && body.toLowerCase().contains("thinking")) {
                    log.warn("⚠️ {} rechaza thinkingConfig ({}); reintento sin él", modelo, thinking);
                    thinking = Thinking.OFF;
                    payload = construirPayload(systemInstruction, contents, tools, forzarJson, thinking);
                    intento--;
                    continue;
                }

                if (st == 429) {
                    String lower = body.toLowerCase();
                    boolean cuotaDiaria = lower.contains("per_day") || lower.contains("perday")
                            || lower.contains("free_tier_requests") || lower.contains("billing")
                            || lower.contains("current quota");
                    if (cuotaDiaria) throw new QuotaExhausted(body);
                    if (intento <= maxRetries) {
                        long espera = 2500L * intento;
                        log.warn("⚠️ {} HTTP 429 por ráfaga; esperando {} ms (intento {}/{})", modelo, espera, intento, maxRetries + 1);
                        dormir(espera);
                        continue;
                    }
                    throw new LlmException("HTTP 429 ráfaga: " + abreviar(body),
                            "Demasiadas peticiones seguidas a la IA. Espera unos segundos y vuelve a intentarlo.");
                }

                if (st == 404) {
                    throw new LlmException("HTTP 404: " + abreviar(body),
                            "El modelo de IA configurado (" + modelo + ") no está disponible. Revisa AI_MODEL.");
                }

                if ((st == 500 || st == 503) && intento <= maxRetries) {
                    log.warn("⚠️ {} HTTP {} (intento {}/{}); reintentando...", modelo, st, intento, maxRetries + 1);
                    dormir(1500L * intento);
                    continue;
                }

                log.error("❌ {} HTTP {} en {} ms: {}", modelo, st, ms, abreviar(body));
                throw new LlmException("HTTP " + st + ": " + abreviar(body));

            } catch (HttpTimeoutException e) {
                log.warn("⚠️ Timeout ({} s) con {} (intento {}/{})", timeoutSeconds, modelo, intento, maxRetries + 1);
                if (intento <= maxRetries) continue;
                throw new LlmException("timeout tras " + intento + " intentos",
                        "La IA tarda demasiado en responder. Inténtalo de nuevo.");
            } catch (QuotaExhausted | LlmException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmException(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════

    private Thinking resolverThinking(String modelo) {
        String mode = thinkingMode == null ? "auto" : thinkingMode.trim().toLowerCase();
        switch (mode) {
            case "budget": return Thinking.BUDGET;
            case "level":  return Thinking.LEVEL;
            case "off":
            case "none":   return Thinking.OFF;
            default:
                if (modelo.startsWith("gemini-2")) return Thinking.BUDGET;
                if (modelo.startsWith("gemini-"))  return Thinking.LEVEL;
                return Thinking.OFF;
        }
    }

    private String limpiar(String m) {
        return m == null ? "" : m.replace("models/", "").replace("openai/", "").trim();
    }

    private HttpResponse<String> enviar(String modelo, ObjectNode payload) throws Exception {
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        if (base.endsWith("/openai")) base = base.substring(0, base.length() - 7);
        String endpoint = base + "/models/" + modelo + ":generateContent";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private ObjectNode construirPayload(String systemInstruction, ArrayNode contents,
                                        ArrayNode tools, boolean forzarJson, Thinking thinking) {
        ObjectNode payload = mapper.createObjectNode();
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            payload.putObject("systemInstruction").putArray("parts").addObject().put("text", systemInstruction);
        }
        payload.set("contents", contents);
        if (tools != null && tools.size() > 0) {
            payload.putArray("tools").addObject().set("functionDeclarations", tools);
            payload.putObject("toolConfig").putObject("functionCallingConfig").put("mode", "AUTO");
        }
        ObjectNode gen = payload.putObject("generationConfig");
        gen.put("temperature", forzarJson ? 0.1 : 0.2);
        if (forzarJson) gen.put("responseMimeType", "application/json");
        switch (thinking) {
            case BUDGET -> gen.putObject("thinkingConfig").put("thinkingBudget", 0);
            case LEVEL  -> gen.putObject("thinkingConfig").put("thinkingLevel", thinkingLevel == null ? "minimal" : thinkingLevel.trim().toLowerCase());
            case OFF    -> { }
        }
        return payload;
    }

    private String extraerTexto(JsonNode root, String porDefecto) {
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : parts) if (p.has("text") && !p.path("thought").asBoolean(false)) sb.append(p.get("text").asText());
        String t = sb.toString().trim();
        return t.isEmpty() ? porDefecto : t;
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static String abreviar(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
