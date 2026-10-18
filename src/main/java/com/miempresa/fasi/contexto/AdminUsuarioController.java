package com.miempresa.fasi.contexto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@Controller
@RequestMapping("/admin/usuarios")
@RequiredArgsConstructor
public class AdminUsuarioController {

    private final UsuarioRepository usuarioRepo;
    private final UsuarioPermisoRepository permisoRepo;
    private final PermisosCalendarioService permisosService;
    private final PasswordEncoder passwordEncoder;

    @GetMapping
    public String listaUsuarios(Model model) {
        List<Usuario> usuarios = usuarioRepo.findAllByOrderByNombreAsc();
        
        // Mapeamos para cada usuario qué calendarios de otros puede ver
        Map<UUID, List<UUID>> permisosMap = new HashMap<>();
        for (Usuario u : usuarios) {
            permisosMap.put(u.getId(), permisoRepo.findPropietariosVisiblesPorLector(u.getId()));
        }

        model.addAttribute("usuarios", usuarios);
        model.addAttribute("permisosMap", permisosMap);
        model.addAttribute("nuevoUsuario", new Usuario());
        return "admin/usuarios";
    }

    @PostMapping("/guardar")
    public String guardarUsuario(@ModelAttribute Usuario u) {
        if (u.getUsername() != null) {
            u.setUsername(u.getUsername().trim().toLowerCase());
        }
        if (u.getId() == null) {
            // Alta de usuario nuevo
            u.setPassword(passwordEncoder.encode(u.getPassword().trim()));
            u.setActivo(true);
            if (u.getRol() == null || u.getRol().isBlank()) u.setRol("USER");
            usuarioRepo.save(u);
            log.info("👤 Usuario creado: '{}' ({})", u.getUsername(), u.getRol());
        } else {
            // Modificación
            Usuario existente = usuarioRepo.findById(u.getId()).orElseThrow();
            existente.setNombre(u.getNombre());
            existente.setRol(u.getRol());
            if (u.getPassword() != null && !u.getPassword().isBlank()) {
                existente.setPassword(passwordEncoder.encode(u.getPassword().trim()));
            }
            usuarioRepo.save(existente);
            log.info("✏️ Usuario modificado: '{}'", existente.getUsername());
        }
        return "redirect:/admin/usuarios";
    }

    @PostMapping("/{id}/toggle-activo")
    public String toggleActivo(@PathVariable UUID id) {
        Usuario u = usuarioRepo.findById(id).orElseThrow();
        if ("admin".equalsIgnoreCase(u.getUsername())) {
            return "redirect:/admin/usuarios"; // no permitir desactivar al superadmin principal
        }
        u.setActivo(!u.isActivo());
        usuarioRepo.save(u);
        return "redirect:/admin/usuarios";
    }

    @PostMapping("/{id}/permisos")
    public String actualizarPermisos(@PathVariable UUID id, @RequestParam(required = false) List<UUID> verCalendariosDe) {
        permisosService.actualizarPermisosLector(id, verCalendariosDe != null ? verCalendariosDe : List.of());
        log.info("🔐 Permisos actualizados para usuario ID: {}", id);
        return "redirect:/admin/usuarios";
    }
}
