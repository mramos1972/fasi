package com.miempresa.fasi.calendario;

import com.miempresa.fasi.contexto.PermisosCalendarioService;
import com.miempresa.fasi.contexto.Usuario;
import com.miempresa.fasi.contexto.UsuarioActualService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventoService {

    private final EventoRepository repo;
    private final UsuarioActualService usuarioActualService;
    private final PermisosCalendarioService permisosService;

    // ══════════════════════════════════════════════════════════
    //  LECTURA
    // ══════════════════════════════════════════════════════════

    public List<Evento> findByMes(int year, int month, String targetUserId) {
        List<UUID> uids = permisosService.resolverIdsConsultables(targetUserId);
        LocalDate inicio = LocalDate.of(year, month, 1);
        LocalDate fin    = inicio.withDayOfMonth(inicio.lengthOfMonth());
        return repo.findByMesConRangoMulti(uids, inicio, fin);
    }

    public List<Evento> findByDia(LocalDate fecha, String targetUserId) {
        List<UUID> uids = permisosService.resolverIdsConsultables(targetUserId);
        return repo.findByFechaMulti(uids, fecha);
    }

    public List<Evento> findProximos(int limite, String targetUserId) {
        List<UUID> uids = permisosService.resolverIdsConsultables(targetUserId);
        return repo.findProximosMulti(uids, LocalDate.now())
                   .stream().limit(limite).toList();
    }

    public List<Evento> buscar(String query, String targetUserId) {
        if (query == null || query.isBlank()) return List.of();
        List<UUID> uids = permisosService.resolverIdsConsultables(targetUserId);
        return repo.buscarFullTextMulti(uids, query.trim());
    }

    public Evento findById(UUID id) {
        List<UUID> uids = permisosService.resolverIdsConsultables("todos");
        return repo.findByIdAndPermitidos(id, uids)
            .orElseThrow(() -> new EntityNotFoundException("Evento no encontrado o sin permisos: " + id));
    }

    public List<String> getAllEtiquetas() {
        UUID usuarioId = usuarioActualService.obtenerUsuarioActual().getId();
        return repo.findAllOptimizado(usuarioId).stream()
                .flatMap(e -> e.getEtiquetas().stream())
                .distinct()
                .sorted()
                .toList();
    }

    // ══════════════════════════════════════════════════════════
    //  ESCRITURA
    // ══════════════════════════════════════════════════════════

    @Transactional
    public Evento save(Evento e) {
        if (e.getUsuario() == null) {
            e.setUsuario(usuarioActualService.obtenerUsuarioActual());
        }
        return repo.save(e);
    }

    @Transactional
    public Evento update(UUID id, Evento datos) {
        Evento e = findById(id);
        Usuario actual = usuarioActualService.obtenerUsuarioActual();

        if (!"ADMIN".equalsIgnoreCase(actual.getRol()) && !e.getUsuario().getId().equals(actual.getId())) {
            throw new IllegalStateException("Solo el propietario del evento o un Administrador pueden modificarlo.");
        }

        e.setTipo(datos.getTipo());
        e.setTitulo(datos.getTitulo());
        e.setFechaInicio(datos.getFechaInicio());
        e.setFechaFin(datos.getFechaFin());
        e.setHoraInicio(datos.getHoraInicio());
        e.setHoraFin(datos.getHoraFin());
        e.setTodoDia(datos.isTodoDia());
        e.setLugar(datos.getLugar());
        e.setPersonasRelacionadas(datos.getPersonasRelacionadas());
        e.setDescripcion(datos.getDescripcion());
        e.setRecordatorioMinutos(datos.getRecordatorioMinutos());
        e.setDatosExtra(datos.getDatosExtra());
        e.setEtiquetas(datos.getEtiquetas());
        e.setColorPersonalizado(datos.getColorPersonalizado());
        return repo.save(e);
    }

    @Transactional
    public void delete(UUID id) {
        Evento e = findById(id);
        Usuario actual = usuarioActualService.obtenerUsuarioActual();
        if (!"ADMIN".equalsIgnoreCase(actual.getRol()) && !e.getUsuario().getId().equals(actual.getId())) {
            throw new IllegalStateException("No tienes permiso para borrar este evento.");
        }
        repo.delete(e);
    }

    @Transactional
    public void deleteAll() {
        UUID usuarioId = usuarioActualService.obtenerUsuarioActual().getId();
        repo.deleteAllByUsuarioId(usuarioId);
    }
}
