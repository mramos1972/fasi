package com.miempresa.fasi.calendario;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
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
     * Respeta fechaFin como límite si está definida; si no, usa 3 meses desde fechaInicio.
     * Para WEEKLY: avanza de 7 en 7 días desde la fechaInicio (que ya debe ser el día correcto).
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

        // Límite: usar fechaFin si existe (la IA la calcula), si no 3 meses
        LocalDate limite;
        if (base.getFechaFin() != null && base.getFechaFin().isAfter(inicio)) {
            limite = base.getFechaFin();
        } else {
            limite = inicio.plusMonths(3);
        }

        // Para WEEKLY: avanzar inicio hasta el primer día de semana correcto
        // La IA puede equivocarse en la fecha exacta — el backend la corrige
        if ("WEEKLY".equals(patron) && base.getDatosExtra() != null
                && base.getDatosExtra().containsKey("diaSemana")) {
            String diaNombre = base.getDatosExtra().get("diaSemana").toString().toUpperCase();
            DayOfWeek diaObjetivo = parseDiaSemana(diaNombre);
            if (diaObjetivo != null) {
                // Retroceder al inicio del mes para no perder el primer día
                inicio = inicio.withDayOfMonth(1);
                // Avanzar hasta el primer día correcto del mes
                while (inicio.getDayOfWeek() != diaObjetivo) {
                    inicio = inicio.plusDays(1);
                }
            }
        }

        List<Evento> generados = new ArrayList<>();
        LocalDate cursor = inicio;

        // Guardar fechaFin original para no confundirla con la del rango recurrente
        // (en eventos recurrentes fechaFin del base es el límite de la serie, no del evento)
        LocalDate fechaFinEvento = null; // cada evento individual no tiene fechaFin (es de 1 día)

        while (!cursor.isAfter(limite)) {
            Evento e = new Evento();
            e.setTipo(base.getTipo());
            e.setTitulo(base.getTitulo());
            e.setFechaInicio(cursor);
            e.setFechaFin(fechaFinEvento);   // cada ocurrencia es 1 día
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
                default        -> cursor.plusWeeks(1); // WEEKLY: exactamente +7 días
            };
        }

        log.info("Recurrencia {}: {} eventos generados entre {} y {}",
                patron, generados.size(), inicio, limite);
        return generados;
    }

    /** Convierte nombre de día en español o inglés a DayOfWeek */
    private DayOfWeek parseDiaSemana(String nombre) {
        return switch (nombre.toUpperCase().trim()) {
            case "LUNES",     "MONDAY"    -> DayOfWeek.MONDAY;
            case "MARTES",    "TUESDAY"   -> DayOfWeek.TUESDAY;
            case "MIERCOLES", "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
            case "JUEVES",    "THURSDAY"  -> DayOfWeek.THURSDAY;
            case "VIERNES",   "FRIDAY"    -> DayOfWeek.FRIDAY;
            case "SABADO",    "SATURDAY"  -> DayOfWeek.SATURDAY;
            case "DOMINGO",   "SUNDAY"    -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    public List<Evento> getEventosPendientesDatos() {
        return repo.findEventosPendientesDatos();
    }

}