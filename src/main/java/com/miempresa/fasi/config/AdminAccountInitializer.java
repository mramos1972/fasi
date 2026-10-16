package com.miempresa.fasi.config;

import com.miempresa.fasi.contexto.Usuario;
import com.miempresa.fasi.contexto.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminAccountInitializer implements CommandLineRunner {

    private final UsuarioRepository usuarioRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        String username = "admin";
        String rawPassword = "admin";

        Usuario admin = usuarioRepository.findByUsername(username).orElseGet(() -> {
            log.info("Creando usuario '{}' nuevo...", username);
            return Usuario.builder()
                    .username(username)
                    .nombre("Administrador")
                    .rol("ADMIN")
                    .activo(true)
                    .build();
        });

        // Asegurar campos críticos y codificar contraseña con el encoder activo
        admin.setPassword(passwordEncoder.encode(rawPassword));
        admin.setActivo(true);
        admin.setRol("ADMIN");

        usuarioRepository.save(admin);
        log.info("✅ Credenciales de '{}' inicializadas correctamente: usuario='{}', password='{}'", username, username, rawPassword);
    }
}
