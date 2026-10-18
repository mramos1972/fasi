package com.miempresa.fasi.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class LlmApiClient {

    @Value("${fasi.ai.provider.url:https://generativelanguage.googleapis.com/v1beta}")
    private String apiUrl;

    @Value("${fasi.ai.provider.api-key:}")
    private String apiKey;

    @Value("${fasi.ai.provider.model:gemini-2.5-flash}")
    private String model;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmApiClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // Evita cuelgues de negociación HTTP/2 con proxies
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String chatJson(String systemPrompt, String userPrompt) {
        return ejecutarLlamada(systemPrompt, userPrompt, true);
    }

    public String chat(String systemPrompt, String userPrompt) {
        return ejecutarLlamada(systemPrompt, userPrompt, false);
    }

    private String ejecutarLlamada(String systemPrompt, String userPrompt, boolean forzarJson) {
        try {
            // Verificamos si estamos apuntando a la API de Google Gemini
            boolean esGoogle = apiUrl.contains("googleapis.com");

            if (esGoogle) {
                return llamarGeminiNativo(systemPrompt, userPrompt, forzarJson);
            } else {
                return llamarOpenAiCompatible(systemPrompt, userPrompt, forzarJson);
            }

        } catch (Exception e) {
            log.error("❌ Fallo en la llamada a IA: {}", e.getMessage());
            return "{}";
        }
    }

    private String llamarGeminiNativo(String systemPrompt, String userPrompt, boolean forzarJson) throws Exception {
        // Normalizar nombre de modelo
        String modeloLimpio = model.replace("models/", "").replace("openai/", "");
        if (modeloLimpio.isBlank() || modeloLimpio.equals("gemini-1.5-flash")) {
            modeloLimpio = "gemini-2.5-flash";
        }

        // Endpoint nativo REST v1beta: /v1beta/models/{model}:generateContent?key={apiKey}
        String baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        if (baseUrl.endsWith("/openai")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 7);
        }
        String endpoint = baseUrl + "/models/" + modeloLimpio + ":generateContent?key=" + apiKey;

        Map<String, Object> contents = Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", systemPrompt + "\n\nSolicitud del usuario:\n" + userPrompt))
        );

        Map<String, Object> generationConfig = forzarJson
                ? Map.of("responseMimeType", "application/json", "temperature", 0.1)
                : Map.of("temperature", 0.2);

        Map<String, Object> payload = Map.of(
                "contents", List.of(contents),
                "generationConfig", generationConfig
        );

        String jsonPayload = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        long inicio = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long demora = System.currentTimeMillis() - inicio;

        if (response.statusCode() != 200) {
            log.error("❌ Error de Google Gemini Nativo [HTTP {} en {}ms]: {}", response.statusCode(), demora, response.body());
            return "{}";
        }

        log.info("⚡ Respuesta de Gemini Nativo recibida en {} ms", demora);
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode candidates = root.path("candidates");
        if (candidates.isArray() && !candidates.isEmpty()) {
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts.isArray() && !parts.isEmpty()) {
                return parts.get(0).path("text").asText("{}");
            }
        }
        return "{}";
    }

    private String llamarOpenAiCompatible(String systemPrompt, String userPrompt, boolean forzarJson) throws Exception {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.1);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        if (forzarJson) {
            payload.put("response_format", Map.of("type", "json_object"));
        }

        String jsonPayload = objectMapper.writeValueAsString(payload);
        String endpoint = apiUrl.endsWith("/") ? apiUrl + "chat/completions" : apiUrl + "/chat/completions";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").get(0).path("message").path("content").asText("{}");
        }
        log.error("Error API OpenAI-compatible [HTTP {}]: {}", response.statusCode(), response.body());
        return "{}";
    }
}
