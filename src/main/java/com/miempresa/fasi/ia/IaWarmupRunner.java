package com.miempresa.fasi.ia;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class IaWarmupRunner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        log.info("🚀 Módulo IA (Gemini Flash API) listo para peticiones.");
    }
}
