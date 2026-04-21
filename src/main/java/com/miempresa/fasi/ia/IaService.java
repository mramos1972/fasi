package com.miempresa.fasi.ia;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IaService {

    private final OllamaClient       ollamaClient;
    private final IaContextBuilder   contextBuilder;
    private final IaMensajeRepository repo;

    @Transactional
    public IaChatResponse chat(IaChatRequest request) {
        long inicio = System.currentTimeMillis();

        String prompt    = contextBuilder.buildPrompt(request.getMensaje(), request.getModulo());
        String respuesta = ollamaClient.generate(prompt);
        long   tiempoMs  = System.currentTimeMillis() - inicio;

        // Persistir en historial
        repo.save(IaMensaje.builder()
                .pregunta(request.getMensaje())
                .respuesta(respuesta)
                .modulo(request.getModulo() != null ? request.getModulo() : "general")
                .tiempoMs(tiempoMs)
                .build());

        log.info("IA respondió en {}ms para módulo '{}'", tiempoMs, request.getModulo());
        return new IaChatResponse(respuesta, request.getModulo(), tiempoMs);
    }

    public List<IaMensaje> historial() {
        return repo.findTop20ByOrderByCreatedAtDesc();
    }
}
