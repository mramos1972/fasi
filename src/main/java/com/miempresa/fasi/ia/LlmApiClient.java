package com.miempresa.fasi.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class LlmApiClient {

    @Value("${fasi.ai.provider.url:https://generativelanguage.googleapis.com/v1beta/openai}")
    private String apiUrl;

    @Value("${fasi.ai.provider.api-key:}")
    private String apiKey;

    @Value("${fasi.ai.provider.model:gemini-3.8-flash}")
    private String model;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public String chat(String systemPrompt, String userMessage) {
        return callApi(systemPrompt, userMessage, false);
    }

    public String chatJson(String systemPrompt, String userMessage) {
        return callApi(systemPrompt, userMessage, true);
    }

    private String callApi(String systemPrompt, String userMessage, boolean jsonMode) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("PEGA_AQUI")) {
            log.warn("⚠️ AI_API_KEY no configurada.");
            return jsonMode ? "{}" : "⚠️ La API de Gemini no está configurada. Introduce tu API KEY.";
        }

        try {
            Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userMessage)
                ),
                "temperature", 0.1,
                "response_format", jsonMode ? Map.of("type", "json_object") : Map.of("type", "text")
            );

            String payload = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("❌ Error de API Gemini (HTTP {}): {}", response.statusCode(), response.body());
                return jsonMode ? "{}" : "Error comunicando con Gemini (" + response.statusCode() + ").";
            }

            JsonNode root = objectMapper.readTree(response.body());
            return root.path("choices").get(0).path("message").path("content").asText();

        } catch (Exception e) {
            log.error("❌ Fallo en la llamada a Gemini", e);
            return jsonMode ? "{}" : "Error procesando con Gemini: " + e.getMessage();
        }
    }
}
