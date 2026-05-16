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
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaClient {

    @Value("${fasi.ollama.url:http://localhost:11434}")
    private String ollamaUrl;

    @Value("${fasi.ollama.model:qwen2.5:7b}")
    private String model;

    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Genera una respuesta. keep_alive=-1 mantiene el modelo en RAM indefinidamente. */
    public String generate(String prompt) {
        return generate(prompt, 1024);
    }

    public String generate(String prompt, int numCtx) {
        try {
            OllamaRequest req = new OllamaRequest();
            req.setModel(model);
            req.setPrompt(prompt);
            req.setStream(false);
            req.setKeep_alive(-1);
            req.setOptions(Map.of(
                "num_ctx",     numCtx,
                "temperature", 0
            ));

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

    /** Precalienta el modelo: lo carga en RAM sin generar texto. */
    public void warmup() {
        try {
            log.info("🔥 Precalentando modelo IA ({})...", model);
            OllamaRequest req = new OllamaRequest();
            req.setModel(model);
            req.setPrompt("");
            req.setStream(false);
            req.setKeep_alive(-1);
            req.setOptions(Map.of("num_ctx", 512, "temperature", 0));

            String body = objectMapper.writeValueAsString(req);
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl + "/api/generate"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            log.info("✅ Modelo IA listo en RAM.");
        } catch (Exception e) {
            log.warn("⚠️ No se pudo precalentar el modelo: {}", e.getMessage());
        }
    }

    // ── DTOs internos ────────────────────────────────────────
    @Data
    static class OllamaRequest {
        private String              model;
        private String              prompt;
        private boolean             stream;
        private int                 keep_alive;
        private Map<String, Object> options;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OllamaResponse {
        private String  response;
        private boolean done;
    }
}
