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
import java.util.List;

/**
 * Cliente Gemini (REST nativo v1beta).
 *
 *  - chat / chatJson        : llamadas simples (compatibilidad con EventoIaService, IaService).
 *  - generateWithTools      : function calling para el agente (FasiAgentService).
 *
 * Mejoras respecto a la versión anterior:
 *  - timeout configurable (45 s por defecto) en lugar de 12 s fijos
 *  - thinkingBudget=0 (sin "pensamiento") para latencias de 1-4 s; si el modelo
 *    no admite el parámetro se reintenta automáticamente sin él
 *  - reintentos con espera ante timeout / 429 / 503
 *  - systemInstruction nativo (el prompt de sistema ya no va concatenado al del usuario)
 *  - API key en cabecera, no en la URL
 */
@Slf4j
@Component
public class LlmApiClient {

    @Value("${fasi.ai.provider.url:https://generativelanguage.googleapis.com/v1beta}")
    private String apiUrl;

    @Value("${fasi.ai.provider.api-key:}")
    private String apiKey;

    @Value("${fasi.ai.provider.model:gemini-2.5-flash}")
    private String model;

    @Value("${fasi.ai.provider.timeout-seconds:45}")
    private int timeoutSeconds;

    /** 0 = sin pensamiento (rápido). -1 = no enviar el parámetro (dejar el valor por defecto del modelo). */
    @Value("${fasi.ai.provider.thinking-budget:0}")
    private int thinkingBudget;

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
        public LlmException(String msg) { super(msg); }
        public LlmException(String msg, Throwable t) { super(msg, t); }
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
        } catch (Exception e) {
            log.error("❌ Fallo en la llamada a IA (chat): {}", e.getMessage());
            return "⚠️ El asistente no está disponible ahora mismo (" + e.getMessage() + ").";
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
            throw new LlmException("El modelo no devolvió contenido (" + motivo + ")");
        }

        List<FunctionCall> calls = new ArrayList<>();
        StringBuilder texto = new StringBuilder();
        for (JsonNode part : content.path("parts")) {
            if (part.has("functionCall")) {
                JsonNode fc = part.get("functionCall");
                calls.add(new FunctionCall(fc.path("name").asText(), fc.path("args")));
            } else if (part.has("text")) {
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
        ArrayNode parts = c.putArray("parts");
        parts.addObject().put("text", texto);
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
    //  Núcleo HTTP
    // ══════════════════════════════════════════════════════════

    private JsonNode generateContent(String systemInstruction, ArrayNode contents,
                                     ArrayNode tools, boolean forzarJson) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("AI_API_KEY no configurada");
        }
        if (!apiUrl.contains("googleapis.com")) {
            throw new LlmException("Este cliente solo soporta la API nativa de Gemini (googleapis.com)");
        }

        int budget = thinkingBudget;
        ObjectNode payload = construirPayload(systemInstruction, contents, tools, forzarJson, budget);

        int intento = 0;
        while (true) {
            intento++;
            long inicio = System.currentTimeMillis();
            try {
                HttpResponse<String> r = enviar(payload);
                long ms = System.currentTimeMillis() - inicio;
                int st = r.statusCode();

                if (st == 200) {
                    log.info("⚡ Gemini {} respondió en {} ms", model, ms);
                    return mapper.readTree(r.body());
                }

                // El modelo no admite thinkingConfig -> reintentar sin él (no cuenta como intento)
                if (st == 400 && budget >= 0 && r.body().toLowerCase().contains("thinking")) {
                    log.warn("⚠️ El modelo {} rechaza thinkingConfig; reintento sin él", model);
                    budget = -1;
                    payload = construirPayload(systemInstruction, contents, tools, forzarJson, budget);
                    intento--;
                    continue;
                }

                if ((st == 429 || st == 500 || st == 503) && intento <= maxRetries) {
                    log.warn("⚠️ Gemini HTTP {} (intento {}/{}); reintentando...", st, intento, maxRetries + 1);
                    dormir(1500L * intento);
                    continue;
                }

                log.error("❌ Gemini HTTP {} en {} ms: {}", st, ms, abreviar(r.body()));
                throw new LlmException("HTTP " + st + ": " + abreviar(r.body()));

            } catch (HttpTimeoutException e) {
                log.warn("⚠️ Timeout ({} s) llamando a Gemini (intento {}/{})", timeoutSeconds, intento, maxRetries + 1);
                if (intento <= maxRetries) continue;
                throw new LlmException("timeout tras " + intento + " intentos");
            } catch (LlmException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmException(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), e);
            }
        }
    }

    private HttpResponse<String> enviar(ObjectNode payload) throws Exception {
        String base = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        if (base.endsWith("/openai")) base = base.substring(0, base.length() - 7);
        String modelo = model.replace("models/", "").replace("openai/", "").trim();
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
                                        ArrayNode tools, boolean forzarJson, int budget) {
        ObjectNode payload = mapper.createObjectNode();

        if (systemInstruction != null && !systemInstruction.isBlank()) {
            ObjectNode si = payload.putObject("systemInstruction");
            si.putArray("parts").addObject().put("text", systemInstruction);
        }

        payload.set("contents", contents);

        if (tools != null && tools.size() > 0) {
            ArrayNode toolsArr = payload.putArray("tools");
            toolsArr.addObject().set("functionDeclarations", tools);
            payload.putObject("toolConfig").putObject("functionCallingConfig").put("mode", "AUTO");
        }

        ObjectNode gen = payload.putObject("generationConfig");
        gen.put("temperature", forzarJson ? 0.1 : 0.2);
        if (forzarJson) gen.put("responseMimeType", "application/json");
        if (budget >= 0) gen.putObject("thinkingConfig").put("thinkingBudget", budget);

        return payload;
    }

    private String extraerTexto(JsonNode root, String porDefecto) {
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : parts) if (p.has("text")) sb.append(p.get("text").asText());
        String t = sb.toString().trim();
        return t.isEmpty() ? porDefecto : t;
    }

    private static void dormir(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private static String abreviar(String s) {
        if (s == null) return "";
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }
}
