package com.miempresa.fasi.calendario;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class EventoParserRegex {

    // ══════════════════════════════════════════════════════════
    //  PATRONES REGEX — EVENTOS RECURRENTES (CORREGIDOS)
    // ══════════════════════════════════════════════════════════

    // Patrón maestro: captura TODOS los elementos de recurrencia
    private static final Pattern RECURRENCIA_MASTER = Pattern.compile(
        "(?i)(todos|cada)\\s+los?\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?|días?|dias?)(?:\\s+y\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?))?(?:\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre))?"
    );

    // Patrones simples para eventos únicos
    private static final Pattern CUMPLE = Pattern.compile(
        "(?i)cumple(?:años)?\\s+(?:de\\s+)?([\\wáéíóúñ]+)\\s+el\\s+(\\d{1,2})\\s+de\\s+(\\w+)"
    );

    private static final Pattern CITA_MEDICA = Pattern.compile(
        "(?i)cita\\s+(?:medica|médica)(?:\\s+de\\s+([\\wáéíóúñ]+))?\\s+el\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)(?:\\s+a\\s+las\\s+(\\d{1,2})(?::(\\d{2}))?)?"
    );

    private static final Pattern TUTORIA = Pattern.compile(
        "(?i)tutor[ií]a(?:\\s+de\\s+([\\wáéíóúñ]+))?\\s+el\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)(?:\\s+a\\s+las\\s+(\\d{1,2})(?::(\\d{2}))?)?"
    );

    private static final Pattern CLAUSTRO = Pattern.compile(
        "(?i)claustro\\s+el\\s+(lunes|martes|miércoles|miercoles|jueves|viernes)(?:\\s+a\\s+las\\s+(\\d{1,2}):(\\d{2}))?"
    );

    private static final Map<String, Integer> MESES = Map.ofEntries(
        Map.entry("enero", 1), Map.entry("febrero", 2), Map.entry("marzo", 3),
        Map.entry("abril", 4), Map.entry("mayo", 5), Map.entry("junio", 6),
        Map.entry("julio", 7), Map.entry("agosto", 8), Map.entry("septiembre", 9),
        Map.entry("octubre", 10), Map.entry("noviembre", 11), Map.entry("diciembre", 12)
    );

    private static final Map<String, DayOfWeek> DIAS_SEMANA = Map.of(
        "lunes", DayOfWeek.MONDAY,
        "martes", DayOfWeek.TUESDAY,
        "miércoles", DayOfWeek.WEDNESDAY,
        "miercoles", DayOfWeek.WEDNESDAY,
        "jueves", DayOfWeek.THURSDAY,
        "viernes", DayOfWeek.FRIDAY,
        "sábado", DayOfWeek.SATURDAY,
        "sabado", DayOfWeek.SATURDAY,
        "domingo", DayOfWeek.SUNDAY
    );

    public Optional<EventoParseResult> parse(String texto, LocalDate fechaContexto) {
        if (texto == null || texto.isBlank()) return Optional.empty();
        String textoNorm = texto.trim().toLowerCase();
        log.debug("🔍 Parseando: {}", textoNorm);

        // ── PRIORIDAD 1: Eventos recurrentes ──────────────────
        Optional<EventoParseResult> resultado = parseRecurrencia(textoNorm, fechaContexto);
        if (resultado.isPresent()) return resultado;

        // ── PRIORIDAD 2: Eventos únicos ───────────────────────
        if ((resultado = parseCumpleanos(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseCitaMedica(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseTutoria(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseClaustro(textoNorm, fechaContexto)).isPresent()) return resultado;

        log.debug("⏩ Ningún patrón REGEX coincidió");
        return Optional.empty();
    }

    // ══════════════════════════════════════════════════════════
    //  PARSER UNIFICADO DE RECURRENCIA
    // ══════════════════════════════════════════════════════════

    private Optional<EventoParseResult> parseRecurrencia(String texto, LocalDate hoy) {
        Matcher m = RECURRENCIA_MASTER.matcher(texto);
        if (!m.find()) return Optional.empty();

        String cuantificador = m.group(1); // todos|cada
        String dia1Nombre = m.group(2).toLowerCase().replaceAll("s$", "");
        String dia2Nombre = m.group(3) != null ? m.group(3).toLowerCase().replaceAll("s$", "") : null;
        String mesNombre = m.group(4) != null ? m.group(4).toLowerCase() : null;

        log.debug("✅ REGEX MATCH: cuantificador={}, dia1={}, dia2={}, mes={}", 
                  cuantificador, dia1Nombre, dia2Nombre, mesNombre);

        // ── Caso: "todos los días" ────────────────────────────
        if (dia1Nombre.equals("día") || dia1Nombre.equals("dia")) {
            Integer mes = mesNombre != null ? MESES.get(mesNombre) : null;
            log.info("✅ REGEX: Recurrente diario" + (mes != null ? " de " + mesNombre : ""));
            return crearRecurrenteDiario(texto, hoy, mes);
        }

        // ── Caso: días de la semana ───────────────────────────
        DayOfWeek dia1 = DIAS_SEMANA.get(dia1Nombre);
        if (dia1 == null) {
            log.warn("⚠️ Día no reconocido: {}", dia1Nombre);
            return Optional.empty();
        }

        List<DayOfWeek> dias = new ArrayList<>();
        dias.add(dia1);

        if (dia2Nombre != null) {
            DayOfWeek dia2 = DIAS_SEMANA.get(dia2Nombre);
            if (dia2 != null) {
                dias.add(dia2);
                log.info("✅ REGEX: Recurrente semanal múltiple ({} y {})" + 
                         (mesNombre != null ? " de " + mesNombre : ""), dia1Nombre, dia2Nombre);
            }
        } else {
            log.info("✅ REGEX: Recurrente semanal simple ({})" + 
                     (mesNombre != null ? " de " + mesNombre : ""), dia1Nombre);
        }

        Integer mes = mesNombre != null ? MESES.get(mesNombre) : null;
        return crearRecurrenteSemanal(texto, hoy, dias, mes);
    }

    // ══════════════════════════════════════════════════════════
    //  CREADORES DE EVENTOS RECURRENTES
    // ══════════════════════════════════════════════════════════

    private Optional<EventoParseResult> crearRecurrenteSemanal(String texto, LocalDate hoy, 
                                                                 List<DayOfWeek> dias, Integer mes) {
        LocalTime hora = extraerHora(texto);
        String lugar = extraerLugar(texto);
        String persona = extraerPersona(texto);

        LocalDate inicio, fin;
        if (mes != null) {
            YearMonth ym = YearMonth.of(hoy.getYear(), mes);
            if (ym.isBefore(YearMonth.from(hoy))) ym = ym.plusYears(1);
            inicio = ym.atDay(1);
            fin = ym.atEndOfMonth();
        } else {
            inicio = hoy;
            fin = hoy.plusMonths(3);
        }

        Evento.TipoEvento tipo = inferirTipo(texto);
        String titulo = inferirTitulo(texto, tipo, persona);

        Evento plantilla = new Evento();
        plantilla.setTipo(tipo);
        plantilla.setTitulo(titulo);
        plantilla.setFechaInicio(inicio);
        plantilla.setFechaFin(fin);
        plantilla.setHoraInicio(hora);
        plantilla.setLugar(lugar);
        plantilla.setPersonasRelacionadas(persona);
        plantilla.setTextoOriginalVoz(texto);

        Map<String, Object> datosExtra = new HashMap<>();
        datosExtra.put("recurrente", true);
        datosExtra.put("patron", "WEEKLY");
        datosExtra.put("diasSemana", dias.stream().map(DayOfWeek::name).toList());
        plantilla.setDatosExtra(datosExtra);

        log.info("✅ REGEX: Evento recurrente semanal → {} (días: {}, lugar: {})", 
                 titulo, dias, lugar);
        return Optional.of(new EventoParseResult(plantilla, true));
    }

    private Optional<EventoParseResult> crearRecurrenteDiario(String texto, LocalDate hoy, Integer mes) {
        LocalTime hora = extraerHora(texto);
        String lugar = extraerLugar(texto);

        LocalDate inicio, fin;
        if (mes != null) {
            YearMonth ym = YearMonth.of(hoy.getYear(), mes);
            if (ym.isBefore(YearMonth.from(hoy))) ym = ym.plusYears(1);
            inicio = ym.atDay(1);
            fin = ym.atEndOfMonth();
        } else {
            inicio = hoy;
            fin = hoy.plusMonths(1);
        }

        Evento.TipoEvento tipo = inferirTipo(texto);
        String titulo = inferirTitulo(texto, tipo, null);

        Evento plantilla = new Evento();
        plantilla.setTipo(tipo);
        plantilla.setTitulo(titulo);
        plantilla.setFechaInicio(inicio);
        plantilla.setFechaFin(fin);
        plantilla.setHoraInicio(hora);
        plantilla.setLugar(lugar);
        plantilla.setTextoOriginalVoz(texto);

        Map<String, Object> datosExtra = new HashMap<>();
        datosExtra.put("recurrente", true);
        datosExtra.put("patron", "DAILY");
        plantilla.setDatosExtra(datosExtra);

        log.info("✅ REGEX: Evento recurrente diario → {}", titulo);
        return Optional.of(new EventoParseResult(plantilla, true));
    }

    // ══════════════════════════════════════════════════════════
    //  PARSERS DE EVENTOS ÚNICOS
    // ══════════════════════════════════════════════════════════

    private Optional<EventoParseResult> parseCumpleanos(String texto, LocalDate hoy) {
        Matcher m = CUMPLE.matcher(texto);
        if (!m.find()) return Optional.empty();
        String persona = capitalize(m.group(1));
        int dia = Integer.parseInt(m.group(2));
        String mesNombre = m.group(3).toLowerCase();
        Integer mes = MESES.get(mesNombre);
        if (mes == null) return Optional.empty();
        LocalDate fecha = LocalDate.of(hoy.getYear(), mes, dia);
        if (fecha.isBefore(hoy)) fecha = fecha.plusYears(1);
        Evento e = new Evento();
        e.setTipo(Evento.TipoEvento.SOCIAL);
        e.setTitulo("Cumpleaños de " + persona);
        e.setFechaInicio(fecha);
        e.setTodoDia(true);
        e.setPersonasRelacionadas(persona);
        e.setEtiquetas(Set.of("cumpleanos"));
        e.setTextoOriginalVoz(texto);
        log.info("✅ REGEX: Cumpleaños detectado → {}", e.getTitulo());
        return Optional.of(new EventoParseResult(e, false));
    }

    private Optional<EventoParseResult> parseCitaMedica(String texto, LocalDate hoy) {
        Matcher m = CITA_MEDICA.matcher(texto);
        if (!m.find()) return Optional.empty();
        String persona = m.group(1) != null ? capitalize(m.group(1)) : null;
        String diaNombre = m.group(2).toLowerCase();
        String horaStr = m.group(3);
        String minStr = m.group(4);
        DayOfWeek dia = DIAS_SEMANA.get(diaNombre);
        if (dia == null) return Optional.empty();
        LocalDate fecha = hoy.with(TemporalAdjusters.nextOrSame(dia));
        Evento e = new Evento();
        e.setTipo(Evento.TipoEvento.SALUD);
        e.setTitulo("Cita médica" + (persona != null ? " de " + persona : ""));
        e.setFechaInicio(fecha);
        e.setPersonasRelacionadas(persona);
        e.setEtiquetas(Set.of("medico"));
        e.setTextoOriginalVoz(texto);
        if (horaStr != null) {
            int hora = Integer.parseInt(horaStr);
            int min = minStr != null ? Integer.parseInt(minStr) : 0;
            e.setHoraInicio(LocalTime.of(hora, min));
        }
        log.info("✅ REGEX: Cita médica detectada → {}", e.getTitulo());
        return Optional.of(new EventoParseResult(e, false));
    }

    private Optional<EventoParseResult> parseTutoria(String texto, LocalDate hoy) {
        Matcher m = TUTORIA.matcher(texto);
        if (!m.find()) return Optional.empty();
        String persona = m.group(1) != null ? capitalize(m.group(1)) : null;
        String diaNombre = m.group(2).toLowerCase();
        String horaStr = m.group(3);
        String minStr = m.group(4);
        DayOfWeek dia = DIAS_SEMANA.get(diaNombre);
        if (dia == null) return Optional.empty();
        LocalDate fecha = hoy.with(TemporalAdjusters.nextOrSame(dia));
        Evento e = new Evento();
        e.setTipo(Evento.TipoEvento.FAMILIA);
        e.setTitulo("Tutoría" + (persona != null ? " de " + persona : ""));
        e.setFechaInicio(fecha);
        e.setPersonasRelacionadas(persona);
        e.setEtiquetas(Set.of("tutoria", "colegio"));
        e.setTextoOriginalVoz(texto);
        if (horaStr != null) {
            int hora = Integer.parseInt(horaStr);
            int min = minStr != null ? Integer.parseInt(minStr) : 0;
            e.setHoraInicio(LocalTime.of(hora, min));
        }
        log.info("✅ REGEX: Tutoría detectada → {}", e.getTitulo());
        return Optional.of(new EventoParseResult(e, false));
    }

    private Optional<EventoParseResult> parseClaustro(String texto, LocalDate hoy) {
        Matcher m = CLAUSTRO.matcher(texto);
        if (!m.find()) return Optional.empty();
        String diaNombre = m.group(1).toLowerCase();
        String horaStr = m.group(2);
        String minStr = m.group(3);
        DayOfWeek dia = DIAS_SEMANA.get(diaNombre);
        if (dia == null) return Optional.empty();
        LocalDate fecha = hoy.with(TemporalAdjusters.nextOrSame(dia));
        Evento e = new Evento();
        e.setTipo(Evento.TipoEvento.TRABAJO);
        e.setTitulo("Claustro");
        e.setFechaInicio(fecha);
        e.setEtiquetas(Set.of("claustro"));
        e.setTextoOriginalVoz(texto);
        if (horaStr != null && minStr != null) {
            int hora = Integer.parseInt(horaStr);
            int min = Integer.parseInt(minStr);
            e.setHoraInicio(LocalTime.of(hora, min));
        }
        log.info("✅ REGEX: Claustro detectado → {}", e.getTitulo());
        return Optional.of(new EventoParseResult(e, false));
    }

    // ══════════════════════════════════════════════════════════
    //  EXTRACTORES INTELIGENTES
    // ══════════════════════════════════════════════════════════

    private LocalTime extraerHora(String texto) {
        Pattern p = Pattern.compile("(?i)a\\s+las\\s+(\\d{1,2})(?::(\\d{2}))?");
        Matcher m = p.matcher(texto);
        if (m.find()) {
            int hora = Integer.parseInt(m.group(1));
            int min = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
            return LocalTime.of(hora, min);
        }
        return null;
    }

    private String extraerLugar(String texto) {
        Pattern p = Pattern.compile("(?i)(?:en|de)\\s+([A-ZÁÉÍÓÚÑ][\\wáéíóúñ]+)(?:[,\\s]|$)");
        Matcher m = p.matcher(texto);
        if (m.find()) {
            String lugar = m.group(1);
            if (!lugar.matches("(?i)(todos|cada|lunes|martes|miércoles|jueves|viernes|sábado|domingo|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)")) {
                return capitalize(lugar);
            }
        }
        return null;
    }

    private String extraerPersona(String texto) {
        Pattern p = Pattern.compile("(?i)(?:de|para)\\s+([A-ZÁÉÍÓÚÑ][\\wáéíóúñ]+)(?:[,\\s]|$)");
        Matcher m = p.matcher(texto);
        if (m.find()) {
            String persona = m.group(1);
            if (!persona.matches("(?i)(todos|cada|lunes|martes|miércoles|jueves|viernes|sábado|domingo|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)")) {
                return capitalize(persona);
            }
        }
        return null;
    }

    private Evento.TipoEvento inferirTipo(String texto) {
        texto = texto.toLowerCase();
        if (texto.contains("trabajo") || texto.contains("presencial") || texto.contains("claustro") || texto.contains("reunión"))
            return Evento.TipoEvento.TRABAJO;
        if (texto.contains("médic") || texto.contains("medic") || texto.contains("doctor"))
            return Evento.TipoEvento.SALUD;
        if (texto.contains("tutoría") || texto.contains("tutoria") || texto.contains("colegio"))
            return Evento.TipoEvento.FAMILIA;
        if (texto.contains("curso") || texto.contains("clase"))
            return Evento.TipoEvento.APRENDIZAJE;
        if (texto.contains("gimnasio") || texto.contains("deporte"))
            return Evento.TipoEvento.OCIO;
        return Evento.TipoEvento.PERSONAL;
    }

    private String inferirTitulo(String texto, Evento.TipoEvento tipo, String persona) {
        if (texto.contains("trabajo presencial") || texto.contains("presencial")) {
            return "Trabajo presencial" + (persona != null ? " de " + persona : "");
        }
        String[] palabras = texto.split("\\s+");
        for (String p : palabras) {
            if (p.length() > 4 && !p.matches("(?i)(todos|cada|lunes|martes|miércoles|jueves|viernes|sábado|domingo|enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)")) {
                return capitalize(p) + (persona != null ? " de " + persona : "");
            }
        }
        return tipo.getLabel();
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    public record EventoParseResult(Evento evento, boolean esRecurrente) {}
}
