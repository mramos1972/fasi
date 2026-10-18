package com.miempresa.fasi.contexto;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PermisosCalendarioService {

    private final UsuarioRepository usuarioRepo;
    private final UsuarioPermisoRepository permisoRepo;
    private final UsuarioActualService usuarioActualService;

    /**
     * Devuelve la lista de usuarios cuyos calendarios PUEDE VER el usuario logueado.
     * - Si es ADMIN: ve a todos los usuarios del sistema.
     * - Si es USER normal: se ve a sí mismo + los que el Admin le haya autorizado.
     */
    public List<Usuario> obtenerUsuariosVisibles() {
        Usuario actual = usuarioActualService.obtenerUsuarioActual();

        if ("ADMIN".equalsIgnoreCase(actual.getRol())) {
            return usuarioRepo.findAllByOrderByNombreAsc();
        }

        List<UUID> autorizados = permisoRepo.findPropietariosVisiblesPorLector(actual.getId());
        List<UUID> idsFinales = new ArrayList<>(autorizados);
        if (!idsFinales.contains(actual.getId())) {
            idsFinales.add(actual.getId());
        }

        return usuarioRepo.findAllById(idsFinales);
    }

    /**
     * Resuelve los UUIDs efectivos para las consultas SQL del calendario.
     * @param targetUserId null/"todos"/UUID específico
     */
    public List<UUID> resolverIdsConsultables(String targetUserId) {
        Usuario actual = usuarioActualService.obtenerUsuarioActual();
        List<Usuario> visibles = obtenerUsuariosVisibles();
        List<UUID> idsPermitidos = visibles.stream().map(Usuario::getId).toList();

        if (targetUserId == null || targetUserId.isBlank() || "mi".equalsIgnoreCase(targetUserId)) {
            return List.of(actual.getId());
        }

        if ("todos".equalsIgnoreCase(targetUserId)) {
            return idsPermitidos;
        }

        try {
            UUID pedido = UUID.fromString(targetUserId);
            if (idsPermitidos.contains(pedido)) {
                return List.of(pedido);
            }
        } catch (Exception ignored) {}

        return List.of(actual.getId());
    }

    @Transactional
    public void actualizarPermisosLector(UUID lectorId, List<UUID> propietariosIds) {
        permisoRepo.deleteByLectorId(lectorId);
        if (propietariosIds != null && !propietariosIds.isEmpty()) {
            for (UUID propId : propietariosIds) {
                if (!propId.equals(lectorId)) {
                    permisoRepo.save(new UsuarioPermiso(propId, lectorId));
                }
            }
        }
    }
}
