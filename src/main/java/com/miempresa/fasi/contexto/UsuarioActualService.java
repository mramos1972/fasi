package com.miempresa.fasi.contexto;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UsuarioActualService {

    private final UsuarioRepository usuarioRepository;

    public Usuario obtenerUsuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new IllegalStateException("No hay un usuario autenticado en la sesión");
        }
        return usuarioRepository.findByUsernameAndActivoTrue(auth.getName())
            .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado: " + auth.getName()));
    }
}
