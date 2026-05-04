package com.miempresa.fasi.ia;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class IaWarmupRunner implements ApplicationRunner {

    private final OllamaClient ollamaClient;

    @Override
    public void run(ApplicationArguments args) {
        new Thread(() -> {
            try {
                Thread.sleep(3000);
                ollamaClient.warmup();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ia-warmup").start();
    }
}
