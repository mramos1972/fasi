package com.miempresa.fasi.calendario;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventoService {

    private final EventoRepository repo;

    // ── Lectura ──────────────────────────────────────────────

    public List<Evento> findByMes(int year, int month) {
        LocalDate inicio = LocalDate.of(year, month, 1);
        LocalDate fin    = inicio.withDayOfMonth(inicio.lengthOfMonth());
        return repo.findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(inicio, fin);
    }

    public List<Evento> findBySemana(LocalDate lunesSemana) {
        return repo.findByFechaInicioBetweenOrderByFechaInicioAscHoraInicioAsc(
                lunesSemana, lunesSemana.plusDays(6));
    }

    public List<Evento> findByDia(LocalDate fecha) {
        return repo.findByFechaInicioOrderByHoraInicioAsc(fecha);
    }

    public List<Evento> buscar(String query) {
        if (query == null || query.isBlank()) return List.of();
        return repo.buscarFullText(query.trim());
    }

    public List<Evento> findProximos(int limite) {
        return repo.findProximos(LocalDate.now())
                   .stream().limit(limite).toList();
    }

    public Evento findById(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Evento no encontrado: " + id));
    }

    // ── Escritura ────────────────────────────────────────────

    @Transactional
    public Evento save(Evento e) {
        return repo.save(e);
    }

    @Transactional
    public Evento update(UUID id, Evento datos) {
        Evento e = findById(id);
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
        return repo.save(e);
    }

    @Transactional
    public void delete(UUID id) {
        repo.deleteById(id);
    }
}
