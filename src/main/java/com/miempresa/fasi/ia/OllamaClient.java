package com.miempresa.fasi.ia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaClient {

    @Value("${fasi.ollama.url:http://localhost:11434}")
    private String ollamaUrl;

    @Value("${fasi.ollama.model:gemma3:4b}")
    private String model;

    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public String generate(String prompt) {
        try {
            OllamaRequest req = new OllamaRequest();
            req.setModel(model);
            req.setPrompt(prompt);
            req.setStream(false);

            String body = objectMapper.writeValueAsString(req);

            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> resp = httpClient.send(
                    httpReq, HttpResponse.BodyHandlers.ofString());

            OllamaResponse ollamaResp =
                    objectMapper.readValue(resp.body(), OllamaResponse.class);
            return ollamaResp.getResponse();

        } catch (Exception e) {
            log.error("Error llamando a Ollama", e);
            return "⚠️ Error al conectar con el asistente IA. " +
                   "Asegúrate de que Ollama está corriendo en WSL " +
                   "(ollama serve).";
        }
    }

    // ── DTOs internos ────────────────────────────────────────
    @Data
    static class OllamaRequest {
        private String  model;
        private String  prompt;
        private boolean stream;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OllamaResponse {
        private String response;
        private boolean done;
    }
}
