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
import java.util.stream.Collectors;

@Slf4j
@Component
public class EventoParserRegex {

    // ══════════════════════════════════════════════════════════
    //  PATRONES REGEX — RECURRENCIA SEMANAL
    // ══════════════════════════════════════════════════════════

    // "todos los lunes y martes de junio"
    private static final Pattern REC_SEMANAL_MULTIPLE_MES = Pattern.compile(
        "(?i)(todos|cada)\\s+los?\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)\\s+y\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
    );

    // "todos los lunes de junio"
    private static final Pattern REC_SEMANAL_MES = Pattern.compile(
        "(?i)(todos|cada)\\s+los?\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
    );

    // "todos los lunes y viernes"
    private static final Pattern REC_SEMANAL_MULTIPLE = Pattern.compile(
        "(?i)(todos|cada)\\s+los?\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)\\s+y\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)"
    );

    // "todos los lunes"
    private static final Pattern REC_SEMANAL_SIMPLE = Pattern.compile(
        "(?i)(todos|cada)\\s+los?\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)"
    );

    // "todos los días de julio"
    private static final Pattern REC_DIARIA_MES = Pattern.compile(
        "(?i)(todos|cada)\\s+los?\\s+días?\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
    );

    // ══════════════════════════════════════════════════════════
    //  PATRONES REGEX — RECURRENCIA MENSUAL
    // ══════════════════════════════════════════════════════════

    // "el día 15 de cada mes"
    private static final Pattern REC_MENSUAL_SIMPLE = Pattern.compile(
        "(?i)(?:el\\s+día\\s+|todos\\s+los\\s+días?\\s+)(\\d{1,2})\\s+de\\s+cada\\s+mes"
    );

    // "el día 10 de enero, marzo y mayo" o "el día 5 de junio, julio y agosto"
    private static final Pattern REC_MENSUAL_LISTA = Pattern.compile(
        "(?i)(?:el\\s+día\\s+|todos\\s+los\\s+días?\\s+)(\\d{1,2})\\s+de\\s+([\\wáéíóúñ,\\s]+)"
    );

    // "cada primer lunes de cada mes" o "cada último viernes de marzo"
    private static final Pattern REC_MENSUAL_RELATIVO = Pattern.compile(
        "(?i)cada\\s+(primer|primero|segundo|tercer|tercero|cuarto|último|ultima|ultimo)\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)\\s+de\\s+(?:cada\\s+mes|(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre))"
    );

    // ══════════════════════════════════════════════════════════
    //  PATRONES REGEX — RECURRENCIA ANUAL
    // ══════════════════════════════════════════════════════════

    // "todos los 25 de diciembre de cada año" o "el 15 de marzo de cada año"
    private static final Pattern REC_ANUAL = Pattern.compile(
        "(?i)(?:todos\\s+los\\s+|el\\s+)(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)\\s+de\\s+cada\\s+año"
    );

    // ══════════════════════════════════════════════════════════
    //  PATRONES REGEX — RECURRENCIA CON RANGO
    // ══════════════════════════════════════════════════════════

    // "los lunes desde junio hasta agosto" o "todos los días desde el 1 de junio hasta el 30 de agosto"
    private static final Pattern REC_RANGO_MESES = Pattern.compile(
        "(?i)(?:los\\s+)?(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?|días?)\\s+desde\\s+(?:el\\s+\\d{1,2}\\s+de\\s+)?(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)\\s+hasta\\s+(?:el\\s+\\d{1,2}\\s+de\\s+)?(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
    );

    // ══════════════════════════════════════════════════════════
    //  PATRONES REGEX — EVENTOS ÚNICOS
    // ══════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════
    //  MAPAS DE CONVERSIÓN
    // ══════════════════════════════════════════════════════════

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

    private static final Map<String, Integer> POSICIONES = Map.of(
        "primer", 1, "primero", 1,
        "segundo", 2,
        "tercer", 3, "tercero", 3,
        "cuarto", 4,
        "último", -1, "ultima", -1, "ultimo", -1
    );

    // ══════════════════════════════════════════════════════════
    //  MÉTODO PRINCIPAL
    // ══════════════════════════════════════════════════════════

    public Optional<EventoParseResult> parse(String texto, LocalDate fechaContexto) {
        if (texto == null || texto.isBlank()) return Optional.empty();
        String textoNorm = texto.trim().toLowerCase();
        log.debug("🔍 Parseando: {}", textoNorm);

        Optional<EventoParseResult> resultado;

        // ── PRIORIDAD 1: Recurrencia Semanal ──────────────────
        if ((resultado = parseRecSemanalMultipleMes(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseRecSemanalMes(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseRecSemanalMultiple(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseRecSemanalSimple(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseRecDiariaMes(textoNorm, fechaContexto)).isPresent()) return resultado;

        // ── PRIORIDAD 2: Recurrencia Mensual ──────────────────
        if ((resultado = parseRecMensualRelativo(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseRecMensualLista(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseRecMensualSimple(textoNorm, fechaContexto)).isPresent()) return resultado;

        // ── PRIORIDAD 3: Recurrencia Anual ────────────────────
        if ((resultado = parseRecAnual(textoNorm, fechaContexto)).isPresent()) return resultado;

        // ── PRIORIDAD 4: Recurrencia con Rango ────────────────
        if ((resultado = parseRecRangoMeses(textoNorm, fechaContexto)).isPresent()) return resultado;

        // ── PRIORIDAD 5: Eventos Únicos ───────────────────────
        if ((resultado = parseCumpleanos(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseCitaMedica(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseTutoria(textoNorm, fechaContexto)).isPresent()) return resultado;
        if ((resultado = parseClaustro(textoNorm, fechaContexto)).isPresent()) return resultado;

        log.debug("⏩ Ningún patrón REGEX coincidió");
        return Optional.empty();
    }

    // ══════════════════════════════════════════════════════════
    //  PARSERS DE RECURRENCIA SEMANAL
    // ══════════════════════════════════════════════════════════

    private Optional<EventoParseResult> parseRecSemanalMultipleMes(String texto, LocalDate hoy) {
        Matcher m = REC_SEMANAL_MULTIPLE_MES.matcher(texto);
        if (!m.find()) return Optional.empty();

        String dia1 = normalizarDia(m.group(2));
        String dia2 = normalizarDia(m.group(3));
        String mes = m.group(4).toLowerCase();

        DayOfWeek dow1 = DIAS_SEMANA.get(dia1);
        DayOfWeek dow2 = DIAS_SEMANA.get(dia2);
        Integer mesNum = MESES.get(mes);

        if (dow1 == null || dow2 == null || mesNum == null) return Optional.empty();

        log.info("✅ REGEX: Recurrente semanal múltiple ({} y {}) de {}", dia1, dia2, mes);
        return crearRecurrenteSemanal(texto, hoy, List.of(dow1, dow2), mesNum);
    }

    private Optional<EventoParseResult> parseRecSemanalMes(String texto, LocalDate hoy) {
        Matcher m = REC_SEMANAL_MES.matcher(texto);
        if (!m.find()) return Optional.empty();

        String dia = normalizarDia(m.group(2));
        String mes = m.group(3).toLowerCase();

        DayOfWeek dow = DIAS_SEMANA.get(dia);
        Integer mesNum = MESES.get(mes);

        if (dow == null || mesNum == null) return Optional.empty();

        log.info("✅ REGEX: Recurrente semanal ({}) de {}", dia, mes);
        return crearRecurrenteSemanal(texto, hoy, List.of(dow), mesNum);
    }

    private Optional<EventoParseResult> parseRecSemanalMultiple(String texto, LocalDate hoy) {
        Matcher m = REC_SEMANAL_MULTIPLE.matcher(texto);
        if (!m.find()) return Optional.empty();

        String dia1 = normalizarDia(m.group(2));
        String dia2 = normalizarDia(m.group(3));

        DayOfWeek dow1 = DIAS_SEMANA.get(dia1);
        DayOfWeek dow2 = DIAS_SEMANA.get(dia2);

        if (dow1 == null || dow2 == null) return Optional.empty();

        log.info("✅ REGEX: Recurrente semanal múltiple ({} y {})", dia1, dia2);
        return crearRecurrenteSemanal(texto, hoy, List.of(dow1, dow2), null);
    }

    private Optional<EventoParseResult> parseRecSemanalSimple(String texto, LocalDate hoy) {
        Matcher m = REC_SEMANAL_SIMPLE.matcher(texto);
        if (!m.find()) return Optional.empty();

        String dia = normalizarDia(m.group(2));
        DayOfWeek dow = DIAS_SEMANA.get(dia);

        if (dow == null) return Optional.empty();

        log.info("✅ REGEX: Recurrente semanal ({})", dia);
        return crearRecurrenteSemanal(texto, hoy, List.of(dow), null);
    }

    private Optional<EventoParseResult> parseRecDiariaMes(String texto, LocalDate hoy) {
        Matcher m = REC_DIARIA_MES.matcher(texto);
        if (!m.find()) return Optional.empty();

        String mes = m.group(2).toLowerCase();
        Integer mesNum = MESES.get(mes);

        if (mesNum == null) return Optional.empty();

        log.info("✅ REGEX: Recurrente diaria de {}", mes);
        return crearRecurrenteDiaria(texto, hoy, mesNum);
    }

    // ══════════════════════════════════════════════════════════
    //  PARSERS DE RECURRENCIA MENSUAL
    // ══════════════════════════════════════════════════════════

    private Optional<EventoParseResult> parseRecMensualSimple(String texto, LocalDate hoy) {
        Matcher m = REC_MENSUAL_SIMPLE.matcher(texto);
        if (!m.find()) return Optional.empty();

        int dia = Integer.parseInt(m.group(1));

        log.info("✅ REGEX: Recurrente mensual (día {})", dia);
        return crearRecurrenteMensual(texto, hoy, dia, null);
    }

    private Optional<EventoParseResult> parseRecMensualLista(String texto, LocalDate hoy) {
        Matcher m = REC_MENSUAL_LISTA.matcher(texto);
        if (!m.find()) return Optional.empty();

        int dia = Integer.parseInt(m.group(1));
        String mesesStr = m.group(2).toLowerCase();

        // Extraer meses: "enero, marzo y mayo" → [1, 3, 5]
        List<Integer> meses = Arrays.stream(mesesStr.split("[,\\sy]+"))
            .map(String::trim)
            .filter(s -> !s.isEmpty() && !s.equals("y"))
            .map(MESES::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        if (meses.isEmpty()) return Optional.empty();

        log.info("✅ REGEX: Recurrente mensual (día {} de meses {})", dia, meses);
        return crearRecurrenteMensual(texto, hoy, dia, meses);
    }

    private Optional<EventoParseResult> parseRecMensualRelativo(String texto, LocalDate hoy) {
        Matcher m = REC_MENSUAL_RELATIVO.matcher(texto);
        if (!m.find()) return Optional.empty();

        String posStr = m.group(1).toLowerCase();
        String diaStr = m.group(2).toLowerCase();
        String mesStr = m.group(3) != null ? m.group(3).toLowerCase() : null;

        Integer posicion = POSICIONES.get(posStr);
        DayOfWeek dow = DIAS_SEMANA.get(diaStr);
        Integer mes = mesStr != null ? MESES.get(mesStr) : null;

        if (posicion == null || dow == null) return Optional.empty();

        log.info("✅ REGEX: Recurrente mensual relativo ({} {} de {})", 
                 posStr, diaStr, mes != null ? mesStr : "cada mes");
        return crearRecurrenteMensualRelativo(texto, hoy, posicion, dow, mes);
    }

    // ══════════════════════════════════════════════════════════
    //  PARSERS DE RECURRENCIA ANUAL
    // ══════════════════════════════════════════════════════════

    private Optional<EventoParseResult> parseRecAnual(String texto, LocalDate hoy) {
        Matcher m = REC_ANUAL.matcher(texto);
        if (!m.find()) return Optional.empty();

        int dia = Integer.parseInt(m.group(1));
        String mes = m.group(2).toLowerCase();
        Integer mesNum = MESES.get(mes);

        if (mesNum == null) return Optional.empty();

        log.info("✅ REGEX: Recurrente anual ({} de {})", dia, mes);
        return crearRecurrenteAnual(texto, hoy, dia, mesNum);
    }

    // ══════════════════════════════════════════════════════════
    //  PARSERS DE RECURRENCIA CON RANGO
    // ══════════════════════════════════════════════════════════

    private Optional<EventoParseResult> parseRecRangoMeses(String texto, LocalDate hoy) {
        Matcher m = REC_RANGO_MESES.matcher(texto);
        if (!m.find()) return Optional.empty();

        String diaStr = normalizarDia(m.group(1));
        String mesInicio = m.group(2).toLowerCase();
        String mesFin = m.group(3).toLowerCase();

        Integer mesInicioNum = MESES.get(mesInicio);
        Integer mesFinNum = MESES.get(mesFin);

        if (mesInicioNum == null || mesFinNum == null) return Optional.empty();

        boolean esDiario = diaStr.equals("día") || diaStr.equals("dia");
        DayOfWeek dow = esDiario ? null : DIAS_SEMANA.get(diaStr);

        if (!esDiario && dow == null) return Optional.empty();

        log.info("✅ REGEX: Recurrente {} desde {} hasta {}", 
                 esDiario ? "diaria" : diaStr, mesInicio, mesFin);
        return crearRecurrenteRango(texto, hoy, dow, mesInicioNum, mesFinNum);
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

        return Optional.of(new EventoParseResult(plantilla, true));
    }

    private Optional<EventoParseResult> crearRecurrenteDiaria(String texto, LocalDate hoy, Integer mes) {
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

        return Optional.of(new EventoParseResult(plantilla, true));
    }

    private Optional<EventoParseResult> crearRecurrenteMensual(String texto, LocalDate hoy, 
                                                                 int dia, List<Integer> meses) {
        LocalTime hora = extraerHora(texto);
        String lugar = extraerLugar(texto);

        LocalDate inicio, fin;
        if (meses != null && !meses.isEmpty()) {
            // Primer mes de la lista
            int primerMes = meses.get(0);
            YearMonth ym = YearMonth.of(hoy.getYear(), primerMes);
            if (ym.isBefore(YearMonth.from(hoy))) ym = ym.plusYears(1);
            inicio = ym.atDay(Math.min(dia, ym.lengthOfMonth()));

            // Último mes de la lista
            int ultimoMes = meses.get(meses.size() - 1);
            YearMonth ymFin = YearMonth.of(inicio.getYear(), ultimoMes);
            fin = ymFin.atDay(Math.min(dia, ymFin.lengthOfMonth()));
        } else {
            inicio = hoy;
            fin = hoy.plusYears(1);
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
        datosExtra.put("patron", "MONTHLY");
        datosExtra.put("diaDelMes", dia);
        if (meses != null) {
            datosExtra.put("meses", meses);
        }
        plantilla.setDatosExtra(datosExtra);

        return Optional.of(new EventoParseResult(plantilla, true));
    }

    private Optional<EventoParseResult> crearRecurrenteMensualRelativo(String texto, LocalDate hoy, 
                                                                         int posicion, DayOfWeek dow, Integer mes) {
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
            fin = hoy.plusYears(1);
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
        datosExtra.put("patron", "MONTHLY_RELATIVE");
        datosExtra.put("diaSemana", dow.name());
        datosExtra.put("posicion", posicion);
        if (mes != null) {
            datosExtra.put("mes", mes);
        }
        plantilla.setDatosExtra(datosExtra);

        return Optional.of(new EventoParseResult(plantilla, true));
    }

    private Optional<EventoParseResult> crearRecurrenteAnual(String texto, LocalDate hoy, int dia, int mes) {
        LocalTime hora = extraerHora(texto);
        String lugar = extraerLugar(texto);

        LocalDate inicio = LocalDate.of(hoy.getYear(), mes, dia);
        if (inicio.isBefore(hoy)) inicio = inicio.plusYears(1);
        LocalDate fin = inicio.plusYears(5);

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
        datosExtra.put("patron", "YEARLY");
        datosExtra.put("diaDelMes", dia);
        datosExtra.put("mes", mes);
        plantilla.setDatosExtra(datosExtra);

        return Optional.of(new EventoParseResult(plantilla, true));
    }

    private Optional<EventoParseResult> crearRecurrenteRango(String texto, LocalDate hoy, 
                                                               DayOfWeek dow, int mesInicio, int mesFin) {
        LocalTime hora = extraerHora(texto);
        String lugar = extraerLugar(texto);

        YearMonth ymInicio = YearMonth.of(hoy.getYear(), mesInicio);
        if (ymInicio.isBefore(YearMonth.from(hoy))) ymInicio = ymInicio.plusYears(1);
        
        YearMonth ymFin = YearMonth.of(ymInicio.getYear(), mesFin);
        if (mesFin < mesInicio) ymFin = ymFin.plusYears(1);

        LocalDate inicio = ymInicio.atDay(1);
        LocalDate fin = ymFin.atEndOfMonth();

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
        if (dow != null) {
            datosExtra.put("patron", "WEEKLY");
            datosExtra.put("diasSemana", List.of(dow.name()));
        } else {
            datosExtra.put("patron", "DAILY");
        }
        plantilla.setDatosExtra(datosExtra);

        return Optional.of(new EventoParseResult(plantilla, true));
    }

    // ══════════════════════════════════════════════════════════
    //  PARSERS DE EVENTOS ÚNICOS (igual que antes)
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
    //  UTILIDADES
    // ══════════════════════════════════════════════════════════

    private String normalizarDia(String dia) {
        if (dia == null) return null;
        dia = dia.toLowerCase().trim();
        
        Set<String> diasSingulares = Set.of("lunes", "martes", "miércoles", "miercoles", "jueves", "viernes");
        
        if (diasSingulares.contains(dia)) {
            return dia;
        }
        
        if (dia.endsWith("s")) {
            return dia.substring(0, dia.length() - 1);
        }
        
        return dia;
    }

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
