package com.miempresa.fasi.ia;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    @Value("${fasi.ollama.model:qwen2.5:3b}")
    private String model;

    private final ObjectMapper objectMapper;
    private final HttpClient   httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ════════════════════════════════════════════════════════
    //  Generación estándar (chat / consulta libre)
    // ════════════════════════════════════════════════════════
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
                "temperature", 0,
                "top_k",       20,
                "top_p",       0.7
            ));

            return callOllama(req);

        } catch (Exception e) {
            log.error("Error llamando a Ollama", e);
            return "⚠️ Error al conectar con el asistente IA. " +
                   "Asegúrate de que Ollama está corriendo en WSL (ollama serve).";
        }
    }

    // ════════════════════════════════════════════════════════
    //  Generación JSON-mode (extracción de eventos)
    //  - format:"json" garantiza salida JSON válida
    //  - num_predict limita la salida -> más rápido
    //  - top_k=10 -> menos creatividad, más velocidad
    // ════════════════════════════════════════════════════════
    public String generateJson(String prompt, int numCtx, int maxOutputTokens) {
        try {
            OllamaRequest req = new OllamaRequest();
            req.setModel(model);
            req.setPrompt(prompt);
            req.setStream(false);
            req.setKeep_alive(-1);
            req.setFormat("json");
            req.setOptions(Map.of(
                "num_ctx",     numCtx,
                "num_predict", maxOutputTokens,
                "temperature", 0,
                "top_k",       10,
                "top_p",       0.5
            ));

            return callOllama(req);

        } catch (Exception e) {
            log.error("Error llamando a Ollama (JSON mode)", e);
            return "{}";
        }
    }

    // ════════════════════════════════════════════════════════
    //  Warmup
    // ════════════════════════════════════════════════════════
    public void warmup() {
        try {
            log.info("🔥 Precalentando modelo IA ({})...", model);
            OllamaRequest req = new OllamaRequest();
            req.setModel(model);
            req.setPrompt("");
            req.setStream(false);
            req.setKeep_alive(-1);
            req.setOptions(Map.of("num_ctx", 512, "temperature", 0));

            callOllama(req);
            log.info("✅ Modelo IA listo en RAM.");
        } catch (Exception e) {
            log.warn("⚠️ No se pudo precalentar el modelo: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════
    //  HTTP helper
    // ════════════════════════════════════════════════════════
    private String callOllama(OllamaRequest req) throws Exception {
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
    }

    // ════════════════════════════════════════════════════════
    //  DTOs internos
    // ════════════════════════════════════════════════════════
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class OllamaRequest {
        private String              model;
        private String              prompt;
        private boolean             stream;
        private int                 keep_alive;
        private String              format;     // 👈 nuevo: "json" para JSON mode
        private Map<String, Object> options;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OllamaResponse {
        private String  response;
        private boolean done;
    }
}
