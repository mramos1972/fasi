package com.miempresa.fasi.calendario;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventoService {

    private final EventoRepository repo;

    // ══════════════════════════════════════════════════════════
    //  LECTURA
    // ══════════════════════════════════════════════════════════

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
        return repo.findProximosOptimizado(LocalDate.now())
                   .stream().limit(limite).toList();
    }

    public Evento findById(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Evento no encontrado: " + id));
    }

    public List<String> getAllEtiquetas() {
        return repo.findAllOptimizado().stream()
                .flatMap(e -> e.getEtiquetas().stream())
                .distinct()
                .sorted()
                .toList();
    }

    public List<Evento> getEventosPendientesDatos() {
        return repo.findEventosPendientesDatos();
    }

    // ══════════════════════════════════════════════════════════
    //  ESCRITURA
    // ══════════════════════════════════════════════════════════

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
        e.setEtiquetas(datos.getEtiquetas());
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

    // ══════════════════════════════════════════════════════════
    //  RECURRENCIA
    // ══════════════════════════════════════════════════════════

    public List<LocalDate> generarVistaPrevia(Evento plantilla) {
        Map<String, Object> datosExtra = plantilla.getDatosExtra();
        if (datosExtra == null || !datosExtra.containsKey("patron")) {
            return List.of(plantilla.getFechaInicio());
        }

        String patron = datosExtra.get("patron").toString().toUpperCase();
        LocalDate inicio = plantilla.getFechaInicio();
        LocalDate fin = plantilla.getFechaFin() != null
                ? plantilla.getFechaFin()
                : inicio.plusMonths(3);

        List<LocalDate> fechas = new ArrayList<>();

        switch (patron) {
            case "WEEKLY" -> {
                List<DayOfWeek> dias = extraerDiasSemana(datosExtra);
                LocalDate cursor = inicio.withDayOfMonth(1);
                while (!cursor.isAfter(fin) && fechas.size() < 100) {
                    if (dias.contains(cursor.getDayOfWeek())) fechas.add(cursor);
                    cursor = cursor.plusDays(1);
                }
            }
            case "DAILY" -> {
                LocalDate cursor = inicio;
                while (!cursor.isAfter(fin) && fechas.size() < 100) {
                    fechas.add(cursor);
                    cursor = cursor.plusDays(1);
                }
            }
            case "MONTHLY" -> {
                int diaDelMes = (int) datosExtra.getOrDefault("diaDelMes", inicio.getDayOfMonth());
                LocalDate cursor = inicio;
                while (!cursor.isAfter(fin) && fechas.size() < 100) {
                    int diaAjustado = Math.min(diaDelMes, cursor.lengthOfMonth());
                    fechas.add(cursor.withDayOfMonth(diaAjustado));
                    cursor = cursor.plusMonths(1);
                }
            }
            case "MONTHLY_RELATIVE" -> {
                String diaSemana = datosExtra.get("diaSemana").toString();
                int posicion = (int) datosExtra.get("posicion");
                DayOfWeek dow = parseDiaSemana(diaSemana);
                LocalDate cursor = inicio;
                while (!cursor.isAfter(fin) && fechas.size() < 100) {
                    LocalDate fecha = calcularDiaRelativo(cursor, dow, posicion);
                    if (fecha != null && !fecha.isAfter(fin)) fechas.add(fecha);
                    cursor = cursor.plusMonths(1);
                }
            }
            case "YEARLY" -> {
                int diaDelMes = (int) datosExtra.get("diaDelMes");
                int mes = (int) datosExtra.get("mes");
                LocalDate cursor = LocalDate.of(inicio.getYear(), mes, diaDelMes);
                if (cursor.isBefore(inicio)) cursor = cursor.plusYears(1);
                while (!cursor.isAfter(fin) && fechas.size() < 10) {
                    fechas.add(cursor);
                    cursor = cursor.plusYears(1);
                }
            }
            default -> fechas.add(inicio);
        }

        log.info("📅 Vista previa: {} eventos para patrón {}", fechas.size(), patron);
        return fechas;
    }

    /**
     * Construye el datosExtra limpio para persistir en cada evento individual.
     * Elimina claves de control del flujo conversacional que no deben guardarse en BD.
     */
    private Map<String, Object> limpiarDatosExtraParaPersistir(Map<String, Object> datosExtra) {
        if (datosExtra == null) return null;
        Map<String, Object> limpio = new HashMap<>(datosExtra);
        // Claves de control del flujo — no tienen sentido en BD
        limpio.remove("necesitaLimite");
        limpio.remove("esperandoLimite");
        limpio.remove("textoRecurrencia");
        return limpio.isEmpty() ? null : limpio;
    }

    @Transactional
    public List<Evento> saveRecurrente(EventoConfirmarRequest dto) {
        Evento base = dto.toEvento();
        List<LocalDate> fechas = generarVistaPrevia(base);

        // datosExtra limpio — sin claves de control del flujo conversacional
        Map<String, Object> extraLimpio = limpiarDatosExtraParaPersistir(base.getDatosExtra());

        List<Evento> generados = new ArrayList<>();
        for (LocalDate fecha : fechas) {
            Evento e = new Evento();
            e.setTipo(base.getTipo());
            e.setTitulo(base.getTitulo());
            e.setFechaInicio(fecha);
            e.setFechaFin(null);          // cada ocurrencia es de 1 día
            e.setHoraInicio(base.getHoraInicio());
            e.setHoraFin(base.getHoraFin());
            e.setTodoDia(base.isTodoDia());
            e.setLugar(base.getLugar());
            e.setPersonasRelacionadas(base.getPersonasRelacionadas());
            e.setDescripcion(base.getDescripcion());
            e.setRecordatorioMinutos(base.getRecordatorioMinutos());
            e.setDatosExtra(extraLimpio);
            e.setEtiquetas(base.getEtiquetas());
            e.setTextoOriginalVoz(base.getTextoOriginalVoz());
            generados.add(repo.save(e));
        }

        log.info("✅ Guardados {} eventos recurrentes", generados.size());
        return generados;
    }

    // ══════════════════════════════════════════════════════════
    //  UTILIDADES PRIVADAS
    // ══════════════════════════════════════════════════════════

    private List<DayOfWeek> extraerDiasSemana(Map<String, Object> datosExtra) {
        List<DayOfWeek> dias = new ArrayList<>();

        if (datosExtra.containsKey("diasSemana")) {
            Object obj = datosExtra.get("diasSemana");
            if (obj instanceof List<?> lista) {
                for (Object item : lista) {
                    DayOfWeek dow = parseDiaSemana(item.toString());
                    if (dow != null) dias.add(dow);
                }
            }
        }

        if (dias.isEmpty() && datosExtra.containsKey("diaSemana")) {
            DayOfWeek dow = parseDiaSemana(datosExtra.get("diaSemana").toString());
            if (dow != null) dias.add(dow);
        }

        return dias;
    }

    private DayOfWeek parseDiaSemana(String nombre) {
        return switch (nombre.toUpperCase().trim()) {
            case "LUNES",     "MONDAY"    -> DayOfWeek.MONDAY;
            case "MARTES",    "TUESDAY"   -> DayOfWeek.TUESDAY;
            case "MIERCOLES", "MIÉRCOLES", "WEDNESDAY" -> DayOfWeek.WEDNESDAY;
            case "JUEVES",    "THURSDAY"  -> DayOfWeek.THURSDAY;
            case "VIERNES",   "FRIDAY"    -> DayOfWeek.FRIDAY;
            case "SABADO",    "SÁBADO",   "SATURDAY"  -> DayOfWeek.SATURDAY;
            case "DOMINGO",   "SUNDAY"    -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    private LocalDate calcularDiaRelativo(LocalDate mes, DayOfWeek dow, int posicion) {
        if (posicion == -1) {
            return mes.with(TemporalAdjusters.lastInMonth(dow));
        } else {
            return mes.with(TemporalAdjusters.dayOfWeekInMonth(posicion, dow));
        }
    }

    // ══════════════════════════════════════════════════════════
    //  MÉTODO LEGACY (mantener por compatibilidad)
    // ══════════════════════════════════════════════════════════

    @Transactional
    public void saveRecurrente(Evento plantilla, String patron) {
        LocalDate inicio = plantilla.getFechaInicio();
        LocalDate fin = plantilla.getFechaFin() != null
                ? plantilla.getFechaFin()
                : inicio.plusMonths(3);
        log.info("🔄 Generando eventos recurrentes legacy: {} desde {} hasta {}", patron, inicio, fin);
        LocalDate actual = inicio;
        int count = 0;
        while (!actual.isAfter(fin) && count < 100) {
            Evento copia = new Evento();
            copia.setTipo(plantilla.getTipo());
            copia.setTitulo(plantilla.getTitulo());
            copia.setFechaInicio(actual);
            copia.setFechaFin(actual);
            copia.setHoraInicio(plantilla.getHoraInicio());
            copia.setHoraFin(plantilla.getHoraFin());
            copia.setTodoDia(plantilla.isTodoDia());
            copia.setLugar(plantilla.getLugar());
            copia.setPersonasRelacionadas(plantilla.getPersonasRelacionadas());
            copia.setDescripcion(plantilla.getDescripcion());
            copia.setRecordatorioMinutos(plantilla.getRecordatorioMinutos());
            copia.setEtiquetas(plantilla.getEtiquetas());
            repo.save(copia);
            count++;
            switch (patron.toUpperCase()) {
                case "DAILY"   -> actual = actual.plusDays(1);
                case "WEEKLY"  -> actual = actual.plusWeeks(1);
                case "MONTHLY" -> actual = actual.plusMonths(1);
                default -> { log.warn("Patrón desconocido: {}", patron); return; }
            }
        }
        log.info("✅ Creados {} eventos recurrentes (legacy)", count);
    }
}
