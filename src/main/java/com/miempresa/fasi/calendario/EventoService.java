package com.miempresa.fasi.calendario;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.ArrayList;
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
        return repo.findByMesConRango(inicio, fin);
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

    @Transactional
    public void deleteAll() {
        repo.deleteAll();
    }

    /**
     * Genera todos los eventos de un patrón recurrente.
     * Soporta: WEEKLY (semanal), DAILY (diario), MONTHLY (mensual).
     * Por defecto genera durante 3 meses desde fechaInicio.
     */
    @Transactional
    public List<Evento> saveRecurrente(EventoConfirmarRequest dto) {
        Evento base = dto.toEvento();

        // Leer patrón de datosExtra
        String patron = "WEEKLY";
        if (base.getDatosExtra() != null && base.getDatosExtra().containsKey("patron")) {
            patron = base.getDatosExtra().get("patron").toString().toUpperCase();
        }

        LocalDate inicio = base.getFechaInicio();
        LocalDate limite = inicio.plusMonths(3); // generar 3 meses

        List<Evento> generados = new ArrayList<>();
        LocalDate cursor = inicio;

        while (!cursor.isAfter(limite)) {
            Evento e = new Evento();
            e.setTipo(base.getTipo());
            e.setTitulo(base.getTitulo());
            e.setFechaInicio(cursor);
            e.setFechaFin(base.getFechaFin() != null
                ? cursor.plusDays(java.time.temporal.ChronoUnit.DAYS.between(inicio, base.getFechaFin()))
                : null);
            e.setHoraInicio(base.getHoraInicio());
            e.setHoraFin(base.getHoraFin());
            e.setTodoDia(base.isTodoDia());
            e.setLugar(base.getLugar());
            e.setPersonasRelacionadas(base.getPersonasRelacionadas());
            e.setDescripcion(base.getDescripcion());
            e.setRecordatorioMinutos(base.getRecordatorioMinutos());
            e.setDatosExtra(base.getDatosExtra());
            e.setTextoOriginalVoz(base.getTextoOriginalVoz());
            generados.add(repo.save(e));

            cursor = switch (patron) {
                case "DAILY"   -> cursor.plusDays(1);
                case "MONTHLY" -> cursor.plusMonths(1);
                default        -> cursor.plusWeeks(1); // WEEKLY
            };
        }

        return generados;
    }
}
