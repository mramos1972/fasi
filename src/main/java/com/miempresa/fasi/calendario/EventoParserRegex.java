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
//  PATRONES REGEX — EVENTOS ÚNICOS (PRIORIDAD MÁXIMA)
// ══════════════════════════════════════════════════════════

// "tutoría el lunes" / "claustro el viernes"
private static final Pattern EVENTO_UNICO_DIA = Pattern.compile(
    "(?i)(.+?)\\s+el\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)(?:\\s+a\\s+las\\s+(\\d{1,2})(?::(\\d{2}))?)?"
);

// "cita médica el 5 de junio a las 15" / "tutoría el 23 de marzo"
private static final Pattern EVENTO_UNICO_DIA_MES = Pattern.compile(
    "(?i)(.+?)\\s+el\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)(?:\\s+a\\s+las\\s+(\\d{1,2})(?::(\\d{2}))?)?"
);

// "cumpleaños de X el 15 de marzo"
private static final Pattern CUMPLE = Pattern.compile(
    "(?i)cumple(?:años)?\\s+(?:de\\s+)?([\\wáéíóúñ]+)\\s+el\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)(?:\\s+(cada\\s+año|todos\\s+los\\s+años|anual|anualmente))?"
);

// ══════════════════════════════════════════════════════════
//  🆕 PATRONES NUEVOS — FECHAS RELATIVAS
// ══════════════════════════════════════════════════════════

// "pasado mañana", "este finde", "el lunes que viene", "el próximo martes"
private static final Pattern FECHA_RELATIVA = Pattern.compile(
    "(?i)(.+?)\\s+(mañana|pasado\\s+mañana|este\\s+(?:fin\\s+de\\s+semana|finde)|el\\s+(?:lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)\\s+que\\s+viene|el\\s+próximo\\s+(?:lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo))(?:\\s+a\\s+las\\s+(\\d{1,2})(?::(\\d{2}))?)?"
);

// "mañana a las 10" (más simple)
private static final Pattern MANANA_SIMPLE = Pattern.compile(
    "(?i)(.+?)\\s+mañana(?:\\s+a\\s+las\\s+(\\d{1,2})(?::(\\d{2}))?)?"
);

// ══════════════════════════════════════════════════════════
//  🆕 PATRONES NUEVOS — RANGOS FLEXIBLES
// ══════════════════════════════════════════════════════════

// "vacaciones del 23 al 31" (mes implícito → usar mes actual o siguiente)
private static final Pattern RANGO_SIN_MES = Pattern.compile(
    "(?i)(.+?)\\s+del\\s+(\\d{1,2})\\s+al\\s+(\\d{1,2})$"
);

// "vacaciones 23-31 agosto" / "vacaciones 23/08 al 31/08"
private static final Pattern RANGO_COMPACTO = Pattern.compile(
    "(?i)(.+?)\\s+(\\d{1,2})[-/](\\d{1,2})\\s+(?:de\\s+)?(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// "del 23/08 al 31/08 vacaciones"
private static final Pattern RANGO_NUMERICO = Pattern.compile(
    "(?i)del\\s+(\\d{1,2})/(\\d{1,2})\\s+al\\s+(\\d{1,2})/(\\d{1,2})\\s+(.+)"
);

// ══════════════════════════════════════════════════════════
//  PATRONES REGEX — RANGOS DE FECHAS (EXISTENTES)
// ══════════════════════════════════════════════════════════

// "Vacaciones de Rocío del 10 al 20 de agosto"
private static final Pattern RANGO_CON_PERSONA = Pattern.compile(
    "(?i)(.+?)\\s+de\\s+([A-ZÁÉÍÓÚÑ][\\wáéíóúñ]+)\\s+del\\s+(\\d{1,2})\\s+al\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// "Viaje a Estoril del 1 al 5 de septiembre"
private static final Pattern RANGO_CON_LUGAR = Pattern.compile(
    "(?i)(.+?)\\s+a\\s+([A-ZÁÉÍÓÚÑ][\\wáéíóúñ]+)\\s+del\\s+(\\d{1,2})\\s+al\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// "Feria de la manzanilla del 3 al 8 de Junio"
private static final Pattern RANGO_GENERICO = Pattern.compile(
    "(?i)(.+?)\\s+del\\s+(\\d{1,2})\\s+al\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// "Vacaciones de Rocío del 1 de julio al 31 de agosto"
private static final Pattern RANGO_CON_PERSONA_MESES_DIFERENTES = Pattern.compile(
    "(?i)(.+?)\\s+de\\s+([A-ZÁÉÍÓÚÑ][\\wáéíóúñ]+)\\s+del\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)\\s+al\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// "Viaje a Estoril del 1 de julio al 5 de agosto"
private static final Pattern RANGO_CON_LUGAR_MESES_DIFERENTES = Pattern.compile(
    "(?i)(.+?)\\s+a\\s+([A-ZÁÉÍÓÚÑ][\\wáéíóúñ]+)\\s+del\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)\\s+al\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// "Feria de la manzanilla del 3 de junio al 8 de julio"
private static final Pattern RANGO_GENERICO_MESES_DIFERENTES = Pattern.compile(
    "(?i)(.+?)\\s+del\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)\\s+al\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// ══════════════════════════════════════════════════════════
//  PATRONES REGEX — RECURRENCIA CON LÍMITE
// ══════════════════════════════════════════════════════════

// "teatro todos los miércoles a las 17:00 hasta el 1 de agosto"
private static final Pattern REC_TITULO_HASTA_MES = Pattern.compile(
    "(?i)(.+?)\\s+(?:todos|cada)\\s+(?:los\\s+)?(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)(?:\\s+a\\s+las\\s+(\\d{1,2})(?::(\\d{2}))?)?\\s+hasta\\s+(?:el\\s+)?(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// "todos los lunes hasta agosto"
private static final Pattern REC_HASTA_MES = Pattern.compile(
    "(?i)(?:todos\\s+)?los\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)(?:\\s+y\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?))?\\s+hasta\\s+(?:el\\s+)?(\\d{1,2}\\s+de\\s+)?(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// "desde enero hasta marzo los lunes"
private static final Pattern REC_DESDE_HASTA = Pattern.compile(
    "(?i)desde\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)\\s+hasta\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)(?:\\s+los\\s+)?(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)?"
);

// ══════════════════════════════════════════════════════════
//  PATRONES REGEX — RECURRENCIA SEMANAL
// ══════════════════════════════════════════════════════════

// "todos los lunes y miércoles del mes de agosto"
private static final Pattern REC_SEMANAL_MULTIPLE_MES = Pattern.compile(
    "(?i)(?:todos\\s+los?|cada)\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)\\s+y\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)\\s+(?:del\\s+mes\\s+)?de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// "en agosto todos los lunes y miércoles"
private static final Pattern REC_SEMANAL_MULTIPLE_MES_INVERSO = Pattern.compile(
    "(?i)en\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)\\s+(?:todos\\s+los?|cada)\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)(?:\\s+y\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?))?"
);

// "todos los lunes de agosto"
private static final Pattern REC_SEMANAL_MES = Pattern.compile(
    "(?i)(?:todos\\s+los?|cada)\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)\\s+(?:del\\s+mes\\s+)?de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// "en agosto todos los lunes"
private static final Pattern REC_SEMANAL_MES_INVERSO = Pattern.compile(
    "(?i)en\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)\\s+(?:todos\\s+los?|cada)\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)"
);

// "los lunes y miércoles"
private static final Pattern REC_SEMANAL_MULTIPLE_PLURAL = Pattern.compile(
    "(?i)los\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)\\s+y\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)"
);

// "los lunes"
private static final Pattern REC_SEMANAL_PLURAL = Pattern.compile(
    "(?i)los\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)"
);

// "todos los lunes"
private static final Pattern REC_SEMANAL_TODOS = Pattern.compile(
    "(?i)(?:todos|cada)\\s+los?\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)"
);

// "todos los días de agosto"
private static final Pattern REC_DIARIA_MES = Pattern.compile(
    "(?i)(?:todos|cada)\\s+los?\\s+días?\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
);

// ══════════════════════════════════════════════════════════
//  PATRONES REGEX — RECURRENCIA MENSUAL
// ══════════════════════════════════════════════════════════

// "el día 15 de cada mes"
private static final Pattern REC_MENSUAL_SIMPLE = Pattern.compile(
    "(?i)(?:el\\s+día\\s+|todos\\s+los\\s+días?\\s+)(\\d{1,2})\\s+de\\s+cada\\s+mes"
);

// "cada primer lunes de cada mes"
private static final Pattern REC_MENSUAL_RELATIVO = Pattern.compile(
    "(?i)cada\\s+(primer|primero|segundo|tercer|tercero|cuarto|último|ultima|ultimo)\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábado|sabado|domingo)\\s+(?:de\\s+cada\\s+mes|del\\s+mes)"
);

// ══════════════════════════════════════════════════════════
//  PATRONES REGEX — RECURRENCIA ANUAL
// ══════════════════════════════════════════════════════════

// "todos los 15 de agosto"
private static final Pattern REC_ANUAL = Pattern.compile(
    "(?i)(?:todos|cada)\\s+los?\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
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
//  MÉTODO PRINCIPAL (ACTUALIZADO CON PRIORIDADES)
// ══════════════════════════════════════════════════════════

public Optional<EventoParseResult> parse(String texto, LocalDate fechaContexto) {
    if (texto == null || texto.isBlank()) return Optional.empty();
    String textoNorm = texto.trim().toLowerCase();
    log.debug("🔍 Parseando: {}", textoNorm);

    Optional<EventoParseResult> resultado;

    // ── PRIORIDAD 1: FECHAS RELATIVAS (NUEVO) ─────────────
    if ((resultado = parseMañanaSimple(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseFechaRelativa(textoNorm, fechaContexto)).isPresent()) return resultado;

    // ── PRIORIDAD 2: EVENTOS ÚNICOS ───────────────────────
    if ((resultado = parseEventoUnicoDia(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseEventoUnicoDiaMes(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseCumpleanos(textoNorm, fechaContexto)).isPresent()) return resultado;

    // ── PRIORIDAD 3: RANGOS FLEXIBLES (NUEVO) ─────────────
    if ((resultado = parseRangoNumerico(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRangoCompacto(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRangoSinMes(textoNorm, fechaContexto)).isPresent()) return resultado;

    // ── PRIORIDAD 4: RANGOS CON MESES DIFERENTES ──────────
    if ((resultado = parseRangoConPersonaMesesDiferentes(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRangoConLugarMesesDiferentes(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRangoGenericoMesesDiferentes(textoNorm, fechaContexto)).isPresent()) return resultado;
    
    // ── PRIORIDAD 5: RANGOS MISMO MES ─────────────────────
    if ((resultado = parseRangoConPersona(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRangoConLugar(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRangoGenerico(textoNorm, fechaContexto)).isPresent()) return resultado;

    // ── PRIORIDAD 6: RECURRENCIA CON TÍTULO Y LÍMITE ──────
    if ((resultado = parseRecTituloHastaMes(textoNorm, fechaContexto)).isPresent()) return resultado;

    // ── PRIORIDAD 7: RECURRENCIA CON LÍMITE ───────────────
    if ((resultado = parseRecHastaMes(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRecDesdeHasta(textoNorm, fechaContexto)).isPresent()) return resultado;

    // ── PRIORIDAD 8: RECURRENCIA SEMANAL ──────────────────
    if ((resultado = parseRecSemanalMultipleMes(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRecSemanalMultipleMesInverso(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRecSemanalMes(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRecSemanalMesInverso(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRecSemanalMultiplePlural(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRecSemanalPlural(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRecSemanalTodos(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRecDiariaMes(textoNorm, fechaContexto)).isPresent()) return resultado;

    // ── PRIORIDAD 9: RECURRENCIA MENSUAL ──────────────────
    if ((resultado = parseRecMensualRelativo(textoNorm, fechaContexto)).isPresent()) return resultado;
    if ((resultado = parseRecMensualSimple(textoNorm, fechaContexto)).isPresent()) return resultado;

    // ── PRIORIDAD 10: RECURRENCIA ANUAL ───────────────────
    if ((resultado = parseRecAnual(textoNorm, fechaContexto)).isPresent()) return resultado;

    log.debug("⏩ Ningún patrón REGEX coincidió");
    return Optional.empty();
}

// ══════════════════════════════════════════════════════════
//  🆕 PARSERS NUEVOS — FECHAS RELATIVAS
// ══════════════════════════════════════════════════════════

private Optional<EventoParseResult> parseMañanaSimple(String texto, LocalDate hoy) {
    Matcher m = MANANA_SIMPLE.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    String horaStr = m.group(2);
    String minStr = m.group(3);

    LocalDate fecha = hoy.plusDays(1);

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo);
    e.setFechaInicio(fecha);
    e.setTextoOriginalVoz(texto);

    if (horaStr != null) {
        int hora = Integer.parseInt(horaStr);
        int min = minStr != null ? Integer.parseInt(minStr) : 0;
        e.setHoraInicio(LocalTime.of(hora, min));
    }

    String lugar = extraerLugar(texto);
    String persona = extraerPersona(texto);
    if (lugar != null) e.setLugar(lugar);
    if (persona != null) e.setPersonasRelacionadas(persona);

    log.info("✅ REGEX: Mañana → {} el {}", titulo, fecha);
    return Optional.of(new EventoParseResult(e, false));
}

private Optional<EventoParseResult> parseFechaRelativa(String texto, LocalDate hoy) {
    Matcher m = FECHA_RELATIVA.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    String expresion = m.group(2).toLowerCase();
    String horaStr = m.group(3);
    String minStr = m.group(4);

    LocalDate fecha = calcularFechaRelativa(expresion, hoy);
    if (fecha == null) return Optional.empty();

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo);
    e.setFechaInicio(fecha);
    e.setTextoOriginalVoz(texto);

    if (horaStr != null) {
        int hora = Integer.parseInt(horaStr);
        int min = minStr != null ? Integer.parseInt(minStr) : 0;
        e.setHoraInicio(LocalTime.of(hora, min));
    }

    String lugar = extraerLugar(texto);
    String persona = extraerPersona(texto);
    if (lugar != null) e.setLugar(lugar);
    if (persona != null) e.setPersonasRelacionadas(persona);

    log.info("✅ REGEX: Fecha relativa → {} el {}", titulo, fecha);
    return Optional.of(new EventoParseResult(e, false));
}

private LocalDate calcularFechaRelativa(String expresion, LocalDate hoy) {
    if (expresion.equals("mañana")) return hoy.plusDays(1);
    if (expresion.equals("pasado mañana")) return hoy.plusDays(2);
    if (expresion.equals("este fin de semana") || expresion.equals("este finde")) {
        return hoy.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
    }
    
    // "el lunes que viene", "el próximo martes"
    for (Map.Entry<String, DayOfWeek> entry : DIAS_SEMANA.entrySet()) {
        if (expresion.contains(entry.getKey())) {
            return hoy.with(TemporalAdjusters.next(entry.getValue()));
        }
    }
    
    return null;
}

// ══════════════════════════════════════════════════════════
//  🆕 PARSERS NUEVOS — RANGOS FLEXIBLES
// ══════════════════════════════════════════════════════════

private Optional<EventoParseResult> parseRangoSinMes(String texto, LocalDate hoy) {
    Matcher m = RANGO_SIN_MES.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    int diaInicio = Integer.parseInt(m.group(2));
    int diaFin = Integer.parseInt(m.group(3));

    // Asumir mes actual o siguiente si ya pasó
    int mes = hoy.getMonthValue();
    int anio = hoy.getYear();
    
    LocalDate fechaInicio = LocalDate.of(anio, mes, diaInicio);
    if (fechaInicio.isBefore(hoy)) {
        // Si ya pasó, usar el mes siguiente
        fechaInicio = fechaInicio.plusMonths(1);
    }
    
    LocalDate fechaFin = LocalDate.of(fechaInicio.getYear(), fechaInicio.getMonthValue(), diaFin);

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo);
    e.setFechaInicio(fechaInicio);
    e.setFechaFin(fechaFin);
    e.setTodoDia(true);
    e.setTextoOriginalVoz(texto);

    String persona = extraerPersona(texto);
    String lugar = extraerLugar(texto);
    if (persona != null) e.setPersonasRelacionadas(persona);
    if (lugar != null) e.setLugar(lugar);

    log.info("✅ REGEX: Rango sin mes explícito → {} del {} al {}", titulo, fechaInicio, fechaFin);
    return Optional.of(new EventoParseResult(e, false));
}

private Optional<EventoParseResult> parseRangoCompacto(String texto, LocalDate hoy) {
    Matcher m = RANGO_COMPACTO.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    int diaInicio = Integer.parseInt(m.group(2));
    int diaFin = Integer.parseInt(m.group(3));
    String mesNombre = m.group(4).toLowerCase();

    Integer mes = MESES.get(mesNombre);
    if (mes == null) return Optional.empty();

    int anio = hoy.getYear();
    LocalDate fechaInicio = LocalDate.of(anio, mes, diaInicio);
    LocalDate fechaFin = LocalDate.of(anio, mes, diaFin);

    if (fechaInicio.isBefore(hoy)) {
        fechaInicio = fechaInicio.plusYears(1);
        fechaFin = fechaFin.plusYears(1);
    }

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo);
    e.setFechaInicio(fechaInicio);
    e.setFechaFin(fechaFin);
    e.setTodoDia(true);
    e.setTextoOriginalVoz(texto);

    String persona = extraerPersona(texto);
    if (persona != null) e.setPersonasRelacionadas(persona);

    log.info("✅ REGEX: Rango compacto → {} del {} al {}", titulo, fechaInicio, fechaFin);
    return Optional.of(new EventoParseResult(e, false));
}

private Optional<EventoParseResult> parseRangoNumerico(String texto, LocalDate hoy) {
    Matcher m = RANGO_NUMERICO.matcher(texto);
    if (!m.find()) return Optional.empty();

    int diaInicio = Integer.parseInt(m.group(1));
    int mesInicio = Integer.parseInt(m.group(2));
    int diaFin = Integer.parseInt(m.group(3));
    int mesFin = Integer.parseInt(m.group(4));
    String titulo = capitalize(m.group(5).trim());

    int anio = hoy.getYear();
    LocalDate fechaInicio = LocalDate.of(anio, mesInicio, diaInicio);
    LocalDate fechaFin = LocalDate.of(anio, mesFin, diaFin);

    if (fechaInicio.isBefore(hoy)) {
        fechaInicio = fechaInicio.plusYears(1);
        fechaFin = fechaFin.plusYears(1);
    }

    if (mesFin < mesInicio) {
        fechaFin = fechaFin.plusYears(1);
    }

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo);
    e.setFechaInicio(fechaInicio);
    e.setFechaFin(fechaFin);
    e.setTodoDia(true);
    e.setTextoOriginalVoz(texto);

    String persona = extraerPersona(texto);
    if (persona != null) e.setPersonasRelacionadas(persona);

    log.info("✅ REGEX: Rango numérico → {} del {} al {}", titulo, fechaInicio, fechaFin);
    return Optional.of(new EventoParseResult(e, false));
}

// ══════════════════════════════════════════════════════════
//  PARSERS DE EVENTOS ÚNICOS (SIN CAMBIOS)
// ══════════════════════════════════════════════════════════

private Optional<EventoParseResult> parseEventoUnicoDia(String texto, LocalDate hoy) {
    Matcher m = EVENTO_UNICO_DIA.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    String diaNombre = m.group(2).toLowerCase();
    String horaStr = m.group(3);
    String minStr = m.group(4);

    DayOfWeek dia = DIAS_SEMANA.get(diaNombre);
    if (dia == null) return Optional.empty();

    LocalDate fecha = hoy.with(TemporalAdjusters.nextOrSame(dia));

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo);
    e.setFechaInicio(fecha);
    e.setTextoOriginalVoz(texto);

    if (horaStr != null) {
        int hora = Integer.parseInt(horaStr);
        int min = minStr != null ? Integer.parseInt(minStr) : 0;
        e.setHoraInicio(LocalTime.of(hora, min));
    }

    String lugar = extraerLugar(texto);
    String persona = extraerPersona(texto);
    if (lugar != null) e.setLugar(lugar);
    if (persona != null) e.setPersonasRelacionadas(persona);

    Set<String> etiquetas = new HashSet<>();
    if (texto.contains("tutor")) etiquetas.addAll(List.of("tutoria", "colegio"));
    if (texto.contains("médic") || texto.contains("medic")) etiquetas.add("medico");
    if (texto.contains("claustro")) etiquetas.add("claustro");
    if (!etiquetas.isEmpty()) e.setEtiquetas(etiquetas);

    log.info("✅ REGEX: Evento ÚNICO → {} el {}", titulo, fecha);
    return Optional.of(new EventoParseResult(e, false));
}

private Optional<EventoParseResult> parseEventoUnicoDiaMes(String texto, LocalDate hoy) {
    if (texto.contains("todos") || texto.contains("cada") ||
        texto.matches("(?i).*\\blos\\s+(lunes|martes|miércoles|miercoles|jueves|viernes|sábados?|sabados?|domingos?)\\b.*")) {
        log.debug("⏩ parseEventoUnicoDiaMes rechazado por contener recurrencia");
        return Optional.empty();
    }

    Matcher m = EVENTO_UNICO_DIA_MES.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    int dia = Integer.parseInt(m.group(2));
    String mesNombre = m.group(3).toLowerCase();
    String horaStr = m.group(4);
    String minStr = m.group(5);

    Integer mes = MESES.get(mesNombre);
    if (mes == null) return Optional.empty();

    int anio = hoy.getYear();
    LocalDate fecha = LocalDate.of(anio, mes, dia);

    if (fecha.isBefore(hoy)) {
        fecha = fecha.plusYears(1);
    }

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo);
    e.setFechaInicio(fecha);
    e.setTextoOriginalVoz(texto);

    if (horaStr != null) {
        int hora = Integer.parseInt(horaStr);
        int min = minStr != null ? Integer.parseInt(minStr) : 0;
        e.setHoraInicio(LocalTime.of(hora, min));
    }

    String lugar = extraerLugar(texto);
    String persona = extraerPersona(texto);
    if (lugar != null) e.setLugar(lugar);
    if (persona != null) e.setPersonasRelacionadas(persona);

    Set<String> etiquetas = new HashSet<>();
    if (texto.contains("médic") || texto.contains("medic")) etiquetas.add("medico");
    if (texto.contains("tutor")) etiquetas.addAll(List.of("tutoria", "colegio"));
    if (!etiquetas.isEmpty()) e.setEtiquetas(etiquetas);

    log.info("✅ REGEX: Evento ÚNICO → {} el {}", titulo, fecha);
    return Optional.of(new EventoParseResult(e, false));
}

private Optional<EventoParseResult> parseCumpleanos(String texto, LocalDate hoy) {
    Matcher m = CUMPLE.matcher(texto);
    if (!m.find()) return Optional.empty();

    String persona = capitalize(m.group(1));
    int dia = Integer.parseInt(m.group(2));
    String mesNombre = m.group(3).toLowerCase();
    String marcadorAnual = m.group(4);

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

    boolean esRecurrente = (marcadorAnual != null && !marcadorAnual.isBlank());

    if (esRecurrente) {
        LocalDate fin = fecha.plusYears(10);
        e.setFechaFin(fin);

        Map<String, Object> datosExtra = new HashMap<>();
        datosExtra.put("recurrente", true);
        datosExtra.put("patron", "YEARLY");
        datosExtra.put("diaDelMes", dia);
        datosExtra.put("mes", mes);
        e.setDatosExtra(datosExtra);

        log.info("✅ REGEX: Cumpleaños RECURRENTE ANUAL → {}", e.getTitulo());
        return Optional.of(new EventoParseResult(e, true));
    } else {
        log.info("✅ REGEX: Cumpleaños ÚNICO → {} el {}", e.getTitulo(), fecha);
        return Optional.of(new EventoParseResult(e, false));
    }
}

// ══════════════════════════════════════════════════════════
//  PARSERS DE RANGOS DE FECHAS (SIN CAMBIOS)
// ══════════════════════════════════════════════════════════

private Optional<EventoParseResult> parseRangoConPersona(String texto, LocalDate hoy) {
    Matcher m = RANGO_CON_PERSONA.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    String persona = capitalize(m.group(2).trim());
    int diaInicio = Integer.parseInt(m.group(3));
    int diaFin = Integer.parseInt(m.group(4));
    String mesNombre = m.group(5).toLowerCase();

    Integer mes = MESES.get(mesNombre);
    if (mes == null) return Optional.empty();

    int anio = hoy.getYear();
    LocalDate fechaInicio = LocalDate.of(anio, mes, diaInicio);
    LocalDate fechaFin = LocalDate.of(anio, mes, diaFin);

    if (fechaInicio.isBefore(hoy)) {
        fechaInicio = fechaInicio.plusYears(1);
        fechaFin = fechaFin.plusYears(1);
    }

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo + " de " + persona);
    e.setFechaInicio(fechaInicio);
    e.setFechaFin(fechaFin);
    e.setTodoDia(true);
    e.setPersonasRelacionadas(persona);
    e.setTextoOriginalVoz(texto);

    Set<String> etiquetas = new HashSet<>();
    if (texto.contains("vacacion")) etiquetas.add("vacaciones");
    if (texto.contains("viaje")) etiquetas.add("viaje");
    if (!etiquetas.isEmpty()) e.setEtiquetas(etiquetas);

    log.info("✅ REGEX: Rango con persona → {} de {} del {} al {}", titulo, persona, fechaInicio, fechaFin);
    return Optional.of(new EventoParseResult(e, false));
}

private Optional<EventoParseResult> parseRangoConLugar(String texto, LocalDate hoy) {
    Matcher m = RANGO_CON_LUGAR.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    String lugar = capitalize(m.group(2).trim());
    int diaInicio = Integer.parseInt(m.group(3));
    int diaFin = Integer.parseInt(m.group(4));
    String mesNombre = m.group(5).toLowerCase();

    Integer mes = MESES.get(mesNombre);
    if (mes == null) return Optional.empty();

    int anio = hoy.getYear();
    LocalDate fechaInicio = LocalDate.of(anio, mes, diaInicio);
    LocalDate fechaFin = LocalDate.of(anio, mes, diaFin);

    if (fechaInicio.isBefore(hoy)) {
        fechaInicio = fechaInicio.plusYears(1);
        fechaFin = fechaFin.plusYears(1);
    }

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo + " a " + lugar);
    e.setFechaInicio(fechaInicio);
    e.setFechaFin(fechaFin);
    e.setTodoDia(true);
    e.setLugar(lugar);
    e.setTextoOriginalVoz(texto);

    Set<String> etiquetas = new HashSet<>();
    if (texto.contains("viaje")) etiquetas.add("viaje");
    if (!etiquetas.isEmpty()) e.setEtiquetas(etiquetas);

    log.info("✅ REGEX: Rango con lugar → {} a {} del {} al {}", titulo, lugar, fechaInicio, fechaFin);
    return Optional.of(new EventoParseResult(e, false));
}

private Optional<EventoParseResult> parseRangoGenerico(String texto, LocalDate hoy) {
    Matcher m = RANGO_GENERICO.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    int diaInicio = Integer.parseInt(m.group(2));
    int diaFin = Integer.parseInt(m.group(3));
    String mesNombre = m.group(4).toLowerCase();

    Integer mes = MESES.get(mesNombre);
    if (mes == null) return Optional.empty();

    int anio = hoy.getYear();
    LocalDate fechaInicio = LocalDate.of(anio, mes, diaInicio);
    LocalDate fechaFin = LocalDate.of(anio, mes, diaFin);

    if (fechaInicio.isBefore(hoy)) {
        fechaInicio = fechaInicio.plusYears(1);
        fechaFin = fechaFin.plusYears(1);
    }

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo);
    e.setFechaInicio(fechaInicio);
    e.setFechaFin(fechaFin);
    e.setTodoDia(true);
    e.setTextoOriginalVoz(texto);

    String lugar = extraerLugar(texto);
    if (lugar != null) e.setLugar(lugar);

    Set<String> etiquetas = new HashSet<>();
    if (texto.contains("feria")) etiquetas.add("feria");
    if (texto.contains("semana santa")) etiquetas.add("semana-santa");
    if (!etiquetas.isEmpty()) e.setEtiquetas(etiquetas);

    log.info("✅ REGEX: Rango genérico → {} del {} al {}", titulo, fechaInicio, fechaFin);
    return Optional.of(new EventoParseResult(e, false));
}

private Optional<EventoParseResult> parseRangoConPersonaMesesDiferentes(String texto, LocalDate hoy) {
    Matcher m = RANGO_CON_PERSONA_MESES_DIFERENTES.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    String persona = capitalize(m.group(2).trim());
    int diaInicio = Integer.parseInt(m.group(3));
    String mesInicioNombre = m.group(4).toLowerCase();
    int diaFin = Integer.parseInt(m.group(5));
    String mesFinNombre = m.group(6).toLowerCase();

    Integer mesInicio = MESES.get(mesInicioNombre);
    Integer mesFin = MESES.get(mesFinNombre);
    if (mesInicio == null || mesFin == null) return Optional.empty();

    int anio = hoy.getYear();
    LocalDate fechaInicio = LocalDate.of(anio, mesInicio, diaInicio);
    LocalDate fechaFin = LocalDate.of(anio, mesFin, diaFin);

    if (fechaInicio.isBefore(hoy)) {
        fechaInicio = fechaInicio.plusYears(1);
        fechaFin = fechaFin.plusYears(1);
    }

    if (mesFin < mesInicio) {
        fechaFin = fechaFin.plusYears(1);
    }

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo + " de " + persona);
    e.setFechaInicio(fechaInicio);
    e.setFechaFin(fechaFin);
    e.setTodoDia(true);
    e.setPersonasRelacionadas(persona);
    e.setTextoOriginalVoz(texto);

    Set<String> etiquetas = new HashSet<>();
    if (texto.contains("vacacion")) etiquetas.add("vacaciones");
    if (texto.contains("viaje")) etiquetas.add("viaje");
    if (!etiquetas.isEmpty()) e.setEtiquetas(etiquetas);

    log.info("✅ REGEX: Rango con persona (meses diferentes) → {} de {} del {} al {}", 
             titulo, persona, fechaInicio, fechaFin);
    return Optional.of(new EventoParseResult(e, false));
}

private Optional<EventoParseResult> parseRangoConLugarMesesDiferentes(String texto, LocalDate hoy) {
    Matcher m = RANGO_CON_LUGAR_MESES_DIFERENTES.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    String lugar = capitalize(m.group(2).trim());
    int diaInicio = Integer.parseInt(m.group(3));
    String mesInicioNombre = m.group(4).toLowerCase();
    int diaFin = Integer.parseInt(m.group(5));
    String mesFinNombre = m.group(6).toLowerCase();

    Integer mesInicio = MESES.get(mesInicioNombre);
    Integer mesFin = MESES.get(mesFinNombre);
    if (mesInicio == null || mesFin == null) return Optional.empty();

    int anio = hoy.getYear();
    LocalDate fechaInicio = LocalDate.of(anio, mesInicio, diaInicio);
    LocalDate fechaFin = LocalDate.of(anio, mesFin, diaFin);

    if (fechaInicio.isBefore(hoy)) {
        fechaInicio = fechaInicio.plusYears(1);
        fechaFin = fechaFin.plusYears(1);
    }

    if (mesFin < mesInicio) {
        fechaFin = fechaFin.plusYears(1);
    }

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo + " a " + lugar);
    e.setFechaInicio(fechaInicio);
    e.setFechaFin(fechaFin);
    e.setTodoDia(true);
    e.setLugar(lugar);
    e.setTextoOriginalVoz(texto);

    Set<String> etiquetas = new HashSet<>();
    if (texto.contains("viaje")) etiquetas.add("viaje");
    if (!etiquetas.isEmpty()) e.setEtiquetas(etiquetas);

    log.info("✅ REGEX: Rango con lugar (meses diferentes) → {} a {} del {} al {}", 
             titulo, lugar, fechaInicio, fechaFin);
    return Optional.of(new EventoParseResult(e, false));
}

private Optional<EventoParseResult> parseRangoGenericoMesesDiferentes(String texto, LocalDate hoy) {
    Matcher m = RANGO_GENERICO_MESES_DIFERENTES.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    int diaInicio = Integer.parseInt(m.group(2));
    String mesInicioNombre = m.group(3).toLowerCase();
    int diaFin = Integer.parseInt(m.group(4));
    String mesFinNombre = m.group(5).toLowerCase();

    Integer mesInicio = MESES.get(mesInicioNombre);
    Integer mesFin = MESES.get(mesFinNombre);
    if (mesInicio == null || mesFin == null) return Optional.empty();

    int anio = hoy.getYear();
    LocalDate fechaInicio = LocalDate.of(anio, mesInicio, diaInicio);
    LocalDate fechaFin = LocalDate.of(anio, mesFin, diaFin);

    if (fechaInicio.isBefore(hoy)) {
        fechaInicio = fechaInicio.plusYears(1);
        fechaFin = fechaFin.plusYears(1);
    }

    if (mesFin < mesInicio) {
        fechaFin = fechaFin.plusYears(1);
    }

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento e = new Evento();
    e.setTipo(tipo);
    e.setTitulo(titulo);
    e.setFechaInicio(fechaInicio);
    e.setFechaFin(fechaFin);
    e.setTodoDia(true);
    e.setTextoOriginalVoz(texto);

    String lugar = extraerLugar(texto);
    if (lugar != null) e.setLugar(lugar);

    Set<String> etiquetas = new HashSet<>();
    if (texto.contains("feria")) etiquetas.add("feria");
    if (texto.contains("vacacion")) etiquetas.add("vacaciones");
    if (!etiquetas.isEmpty()) e.setEtiquetas(etiquetas);

    log.info("✅ REGEX: Rango genérico (meses diferentes) → {} del {} al {}", 
             titulo, fechaInicio, fechaFin);
    return Optional.of(new EventoParseResult(e, false));
}

private Optional<EventoParseResult> parseRecTituloHastaMes(String texto, LocalDate hoy) {
    Matcher m = REC_TITULO_HASTA_MES.matcher(texto);
    if (!m.find()) return Optional.empty();

    String titulo = capitalize(m.group(1).trim());
    String diaNombre = m.group(2).toLowerCase();
    String horaStr = m.group(3);
    String minStr = m.group(4);
    int diaFin = Integer.parseInt(m.group(5));
    String mesFin = m.group(6).toLowerCase();

    DayOfWeek dow = DIAS_SEMANA.get(diaNombre);
    Integer mesFinalNum = MESES.get(mesFin);

    if (dow == null || mesFinalNum == null) return Optional.empty();

    LocalDate inicio = hoy;
    
    int anio = hoy.getYear();
    if (mesFinalNum < hoy.getMonthValue()) anio++;
    LocalDate fin = LocalDate.of(anio, mesFinalNum, diaFin);

    Evento.TipoEvento tipo = inferirTipo(texto);

    Evento plantilla = new Evento();
    plantilla.setTipo(tipo);
    plantilla.setTitulo(titulo);
    plantilla.setFechaInicio(inicio);
    plantilla.setFechaFin(fin);
    plantilla.setTextoOriginalVoz(texto);

    if (horaStr != null) {
        int hora = Integer.parseInt(horaStr);
        int min = minStr != null ? Integer.parseInt(minStr) : 0;
        plantilla.setHoraInicio(LocalTime.of(hora, min));
    }

    String lugar = extraerLugar(texto);
    String persona = extraerPersona(texto);
    if (lugar != null) plantilla.setLugar(lugar);
    if (persona != null) plantilla.setPersonasRelacionadas(persona);

    Map<String, Object> datosExtra = new HashMap<>();
    datosExtra.put("recurrente", true);
    datosExtra.put("patron", "WEEKLY");
    datosExtra.put("diasSemana", List.of(dow.name()));
    plantilla.setDatosExtra(datosExtra);

    log.info("✅ REGEX: Recurrente {} '{}' hasta {}", diaNombre, titulo, fin);
    return Optional.of(new EventoParseResult(plantilla, true));
}

// ══════════════════════════════════════════════════════════
//  PARSERS DE RECURRENCIA (SIN CAMBIOS - MANTENER TODOS)
// ══════════════════════════════════════════════════════════

private Optional<EventoParseResult> parseRecHastaMes(String texto, LocalDate hoy) {
    Matcher m = REC_HASTA_MES.matcher(texto);
    if (!m.find()) return Optional.empty();

    String dia1 = normalizarDia(m.group(1));
    String dia2 = m.group(2) != null ? normalizarDia(m.group(2)) : null;
    String diaNumStr = m.group(3);
    String mesNombre = m.group(4).toLowerCase();

    DayOfWeek dow1 = DIAS_SEMANA.get(dia1);
    Integer mesNum = MESES.get(mesNombre);

    if (dow1 == null || mesNum == null) return Optional.empty();

    YearMonth ym = YearMonth.of(hoy.getYear(), mesNum);
    if (ym.isBefore(YearMonth.from(hoy))) ym = ym.plusYears(1);

    LocalDate inicio = hoy;
    LocalDate fin;

    if (diaNumStr != null) {
        int diaNum = Integer.parseInt(diaNumStr.trim().split("\\s+")[0]);
        fin = LocalDate.of(ym.getYear(), mesNum, diaNum);
    } else {
        fin = ym.atEndOfMonth();
    }

    List<DayOfWeek> dias = new ArrayList<>();
    dias.add(dow1);
    if (dia2 != null) {
        DayOfWeek dow2 = DIAS_SEMANA.get(dia2);
        if (dow2 != null) dias.add(dow2);
    }

    log.info("✅ REGEX: Recurrente {} hasta {}", dia1, mesNombre);
    return crearRecurrenteSemanal(texto, inicio, fin, dias, true);
}

private Optional<EventoParseResult> parseRecDesdeHasta(String texto, LocalDate hoy) {
    Matcher m = REC_DESDE_HASTA.matcher(texto);
    if (!m.find()) return Optional.empty();

    String mesInicio = m.group(1).toLowerCase();
    String mesFin = m.group(2).toLowerCase();
    String diaNombre = m.group(3) != null ? m.group(3).toLowerCase() : null;

    Integer mesInicioNum = MESES.get(mesInicio);
    Integer mesFinNum = MESES.get(mesFin);

    if (mesInicioNum == null || mesFinNum == null) return Optional.empty();

    YearMonth ymInicio = YearMonth.of(hoy.getYear(), mesInicioNum);
    if (ymInicio.isBefore(YearMonth.from(hoy))) ymInicio = ymInicio.plusYears(1);

    YearMonth ymFin = YearMonth.of(ymInicio.getYear(), mesFinNum);
    if (mesFinNum < mesInicioNum) ymFin = ymFin.plusYears(1);

    LocalDate inicio = ymInicio.atDay(1);
    LocalDate fin = ymFin.atEndOfMonth();

    if (diaNombre != null) {
        DayOfWeek dow = DIAS_SEMANA.get(normalizarDia(diaNombre));
        if (dow == null) return Optional.empty();
        log.info("✅ REGEX: Recurrente {} desde {} hasta {}", diaNombre, mesInicio, mesFin);
        return crearRecurrenteSemanal(texto, inicio, fin, List.of(dow), true);
    } else {
        log.info("✅ REGEX: Recurrente DIARIA desde {} hasta {}", mesInicio, mesFin);
        return crearRecurrenteDiaria(texto, inicio, fin, true);
    }
}

private Optional<EventoParseResult> parseRecSemanalMultipleMes(String texto, LocalDate hoy) {
    Matcher m = REC_SEMANAL_MULTIPLE_MES.matcher(texto);
    if (!m.find()) return Optional.empty();

    String dia1 = normalizarDia(m.group(1));
    String dia2 = normalizarDia(m.group(2));
    String mes = m.group(3).toLowerCase();

    DayOfWeek dow1 = DIAS_SEMANA.get(dia1);
    DayOfWeek dow2 = DIAS_SEMANA.get(dia2);
    Integer mesNum = MESES.get(mes);

    if (dow1 == null || dow2 == null || mesNum == null) return Optional.empty();

    YearMonth ym = YearMonth.of(hoy.getYear(), mesNum);
    if (ym.isBefore(YearMonth.from(hoy))) ym = ym.plusYears(1);

    LocalDate inicio = ym.atDay(1);
    LocalDate fin = ym.atEndOfMonth();

    log.info("✅ REGEX: Recurrente {} y {} de {}", dia1, dia2, mes);
    return crearRecurrenteSemanal(texto, inicio, fin, List.of(dow1, dow2), true);
}

private Optional<EventoParseResult> parseRecSemanalMultipleMesInverso(String texto, LocalDate hoy) {
    Matcher m = REC_SEMANAL_MULTIPLE_MES_INVERSO.matcher(texto);
    if (!m.find()) return Optional.empty();

    String mes = m.group(1).toLowerCase();
    String dia1 = normalizarDia(m.group(2));
    String dia2 = m.group(3) != null ? normalizarDia(m.group(3)) : null;

    Integer mesNum = MESES.get(mes);
    DayOfWeek dow1 = DIAS_SEMANA.get(dia1);

    if (mesNum == null || dow1 == null) return Optional.empty();

    YearMonth ym = YearMonth.of(hoy.getYear(), mesNum);
    if (ym.isBefore(YearMonth.from(hoy))) ym = ym.plusYears(1);

    LocalDate inicio = ym.atDay(1);
    LocalDate fin = ym.atEndOfMonth();

    List<DayOfWeek> dias = new ArrayList<>();
    dias.add(dow1);

    if (dia2 != null) {
        DayOfWeek dow2 = DIAS_SEMANA.get(dia2);
        if (dow2 != null) dias.add(dow2);
    }

    log.info("✅ REGEX: Recurrente en {} {} {}", mes, dia1, dia2 != null ? "y " + dia2 : "");
    return crearRecurrenteSemanal(texto, inicio, fin, dias, true);
}

private Optional<EventoParseResult> parseRecSemanalMes(String texto, LocalDate hoy) {
    Matcher m = REC_SEMANAL_MES.matcher(texto);
    if (!m.find()) return Optional.empty();

    String dia = normalizarDia(m.group(1));
    String mes = m.group(2).toLowerCase();

    DayOfWeek dow = DIAS_SEMANA.get(dia);
    Integer mesNum = MESES.get(mes);

    if (dow == null || mesNum == null) return Optional.empty();

    YearMonth ym = YearMonth.of(hoy.getYear(), mesNum);
    if (ym.isBefore(YearMonth.from(hoy))) ym = ym.plusYears(1);

    LocalDate inicio = ym.atDay(1);
    LocalDate fin = ym.atEndOfMonth();

    log.info("✅ REGEX: Recurrente {} de {}", dia, mes);
    return crearRecurrenteSemanal(texto, inicio, fin, List.of(dow), true);
}

private Optional<EventoParseResult> parseRecSemanalMesInverso(String texto, LocalDate hoy) {
    Matcher m = REC_SEMANAL_MES_INVERSO.matcher(texto);
    if (!m.find()) return Optional.empty();

    String mes = m.group(1).toLowerCase();
    String dia = normalizarDia(m.group(2));

    Integer mesNum = MESES.get(mes);
    DayOfWeek dow = DIAS_SEMANA.get(dia);

    if (mesNum == null || dow == null) return Optional.empty();

    YearMonth ym = YearMonth.of(hoy.getYear(), mesNum);
    if (ym.isBefore(YearMonth.from(hoy))) ym = ym.plusYears(1);

    LocalDate inicio = ym.atDay(1);
    LocalDate fin = ym.atEndOfMonth();

    log.info("✅ REGEX: Recurrente en {} {}", mes, dia);
    return crearRecurrenteSemanal(texto, inicio, fin, List.of(dow), true);
}

private Optional<EventoParseResult> parseRecSemanalMultiplePlural(String texto, LocalDate hoy) {
    Matcher m = REC_SEMANAL_MULTIPLE_PLURAL.matcher(texto);
    if (!m.find()) return Optional.empty();

    String dia1 = normalizarDia(m.group(1));
    String dia2 = normalizarDia(m.group(2));

    DayOfWeek dow1 = DIAS_SEMANA.get(dia1);
    DayOfWeek dow2 = DIAS_SEMANA.get(dia2);

    if (dow1 == null || dow2 == null) return Optional.empty();

    log.info("✅ REGEX: Recurrente {} y {} (NECESITA LÍMITE)", dia1, dia2);
    return crearRecurrenteSemanal(texto, hoy, null, List.of(dow1, dow2), false);
}

private Optional<EventoParseResult> parseRecSemanalPlural(String texto, LocalDate hoy) {
    Matcher m = REC_SEMANAL_PLURAL.matcher(texto);
    if (!m.find()) return Optional.empty();

    String dia = normalizarDia(m.group(1));
    DayOfWeek dow = DIAS_SEMANA.get(dia);

    if (dow == null) return Optional.empty();

    log.info("✅ REGEX: Recurrente {} (NECESITA LÍMITE)", dia);
    return crearRecurrenteSemanal(texto, hoy, null, List.of(dow), false);
}

private Optional<EventoParseResult> parseRecSemanalTodos(String texto, LocalDate hoy) {
    Matcher m = REC_SEMANAL_TODOS.matcher(texto);
    if (!m.find()) return Optional.empty();

    String dia = normalizarDia(m.group(1));
    DayOfWeek dow = DIAS_SEMANA.get(dia);

    if (dow == null) return Optional.empty();

    log.info("✅ REGEX: Recurrente todos los {} (NECESITA LÍMITE)", dia);
    return crearRecurrenteSemanal(texto, hoy, null, List.of(dow), false);
}

private Optional<EventoParseResult> parseRecDiariaMes(String texto, LocalDate hoy) {
    Matcher m = REC_DIARIA_MES.matcher(texto);
    if (!m.find()) return Optional.empty();

    String mes = m.group(1).toLowerCase();
    Integer mesNum = MESES.get(mes);

    if (mesNum == null) return Optional.empty();

    YearMonth ym = YearMonth.of(hoy.getYear(), mesNum);
    if (ym.isBefore(YearMonth.from(hoy))) ym = ym.plusYears(1);

    LocalDate inicio = ym.atDay(1);
    LocalDate fin = ym.atEndOfMonth();

    log.info("✅ REGEX: Recurrente DIARIA de {}", mes);
    return crearRecurrenteDiaria(texto, inicio, fin, true);
}

private Optional<EventoParseResult> parseRecMensualSimple(String texto, LocalDate hoy) {
    Matcher m = REC_MENSUAL_SIMPLE.matcher(texto);
    if (!m.find()) return Optional.empty();

    int dia = Integer.parseInt(m.group(1));

    log.info("✅ REGEX: Recurrente MENSUAL día {} (NECESITA LÍMITE)", dia);
    return crearRecurrenteMensual(texto, hoy, null, dia, false);
}

private Optional<EventoParseResult> parseRecMensualRelativo(String texto, LocalDate hoy) {
    Matcher m = REC_MENSUAL_RELATIVO.matcher(texto);
    if (!m.find()) return Optional.empty();

    String posStr = m.group(1).toLowerCase();
    String diaStr = m.group(2).toLowerCase();

    Integer posicion = POSICIONES.get(posStr);
    DayOfWeek dow = DIAS_SEMANA.get(diaStr);

    if (posicion == null || dow == null) return Optional.empty();

    log.info("✅ REGEX: Recurrente MENSUAL {} {} (NECESITA LÍMITE)", posStr, diaStr);
    return crearRecurrenteMensualRelativo(texto, hoy, null, posicion, dow, false);
}

private Optional<EventoParseResult> parseRecAnual(String texto, LocalDate hoy) {
    Matcher m = REC_ANUAL.matcher(texto);
    if (!m.find()) return Optional.empty();

    int dia = Integer.parseInt(m.group(1));
    String mes = m.group(2).toLowerCase();
    Integer mesNum = MESES.get(mes);

    if (mesNum == null) return Optional.empty();

    LocalDate inicio = LocalDate.of(hoy.getYear(), mesNum, dia);
    if (inicio.isBefore(hoy)) inicio = inicio.plusYears(1);
    LocalDate fin = inicio.plusYears(5);

    log.info("✅ REGEX: Recurrente ANUAL {} de {}", dia, mes);
    return crearRecurrenteAnual(texto, inicio, fin, dia, mesNum);
}

// ══════════════════════════════════════════════════════════
//  CREADORES DE EVENTOS RECURRENTES
// ══════════════════════════════════════════════════════════

private Optional<EventoParseResult> crearRecurrenteSemanal(String texto, LocalDate inicio,
                                                             LocalDate fin, List<DayOfWeek> dias,
                                                             boolean tieneLimite) {
    LocalTime hora = extraerHora(texto);
    String lugar = extraerLugar(texto);
    String persona = extraerPersona(texto);

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
    datosExtra.put("necesitaLimite", !tieneLimite);
    plantilla.setDatosExtra(datosExtra);

    return Optional.of(new EventoParseResult(plantilla, true));
}

private Optional<EventoParseResult> crearRecurrenteDiaria(String texto, LocalDate inicio,
                                                            LocalDate fin, boolean tieneLimite) {
    LocalTime hora = extraerHora(texto);
    String lugar = extraerLugar(texto);

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
    datosExtra.put("necesitaLimite", !tieneLimite);
    plantilla.setDatosExtra(datosExtra);

    return Optional.of(new EventoParseResult(plantilla, true));
}

private Optional<EventoParseResult> crearRecurrenteMensual(String texto, LocalDate inicio,
                                                             LocalDate fin, int dia, boolean tieneLimite) {
    LocalTime hora = extraerHora(texto);
    String lugar = extraerLugar(texto);

    Evento.TipoEvento tipo = inferirTipo(texto);
    String titulo = inferirTitulo(texto, tipo, null);

    Evento plantilla = new Evento();
    plantilla.setTipo(tipo);
    plantilla.setTitulo(titulo);
    plantilla.setFechaInicio(inicio != null ? inicio : LocalDate.now());
    plantilla.setFechaFin(fin);
    plantilla.setHoraInicio(hora);
    plantilla.setLugar(lugar);
    plantilla.setTextoOriginalVoz(texto);

    Map<String, Object> datosExtra = new HashMap<>();
    datosExtra.put("recurrente", true);
    datosExtra.put("patron", "MONTHLY");
    datosExtra.put("diaDelMes", dia);
    datosExtra.put("necesitaLimite", !tieneLimite);
    plantilla.setDatosExtra(datosExtra);

    return Optional.of(new EventoParseResult(plantilla, true));
}

private Optional<EventoParseResult> crearRecurrenteMensualRelativo(String texto, LocalDate inicio,
                                                                     LocalDate fin, int posicion,
                                                                     DayOfWeek dow, boolean tieneLimite) {
    LocalTime hora = extraerHora(texto);
    String lugar = extraerLugar(texto);

    Evento.TipoEvento tipo = inferirTipo(texto);
    String titulo = inferirTitulo(texto, tipo, null);

    Evento plantilla = new Evento();
    plantilla.setTipo(tipo);
    plantilla.setTitulo(titulo);
    plantilla.setFechaInicio(inicio != null ? inicio : LocalDate.now());
    plantilla.setFechaFin(fin);
    plantilla.setHoraInicio(hora);
    plantilla.setLugar(lugar);
    plantilla.setTextoOriginalVoz(texto);

    Map<String, Object> datosExtra = new HashMap<>();
    datosExtra.put("recurrente", true);
    datosExtra.put("patron", "MONTHLY_RELATIVE");
    datosExtra.put("diaSemana", dow.name());
    datosExtra.put("posicion", posicion);
    datosExtra.put("necesitaLimite", !tieneLimite);
    plantilla.setDatosExtra(datosExtra);

    return Optional.of(new EventoParseResult(plantilla, true));
}

private Optional<EventoParseResult> crearRecurrenteAnual(String texto, LocalDate inicio,
                                                           LocalDate fin, int dia, int mes) {
    LocalTime hora = extraerHora(texto);
    String lugar = extraerLugar(texto);

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

// ══════════════════════════════════════════════════════════
//  UTILIDADES
// ══════════════════════════════════════════════════════════

private String normalizarDia(String dia) {
    if (dia == null) return null;
    dia = dia.toLowerCase().trim();

    Set<String> diasSingulares = Set.of("lunes", "martes", "miércoles", "miercoles", "jueves", "viernes");

    if (diasSingulares.contains(dia)) return dia;
    if (dia.endsWith("s")) return dia.substring(0, dia.length() - 1);

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
    
    // ── VACACIONES (prioridad alta) ──────────────────────
    if (texto.contains("vacacion")) return Evento.TipoEvento.VACACIONES;
    
    // ── VIAJE (solo si menciona destino/transporte) ──────
    if (texto.contains("viaje")) {
        if (texto.contains("vacacion")) return Evento.TipoEvento.VACACIONES;
        return Evento.TipoEvento.VIAJE;
    }
    
    // ── RESTO DE TIPOS ────────────────────────────────────
    if (texto.contains("feria")) return Evento.TipoEvento.OCIO;
    if (texto.contains("teatro")) return Evento.TipoEvento.OCIO;
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
    // ── Títulos fijos por palabras clave (máxima prioridad) ──
    if (texto.contains("trabajo presencial") || texto.contains("presencial"))
        return "Trabajo presencial" + (persona != null ? " de " + persona : "");
    if (texto.contains("cita médica") || texto.contains("cita medica"))
        return "Cita médica" + (persona != null ? " de " + persona : "");
    if (texto.contains("médico") || texto.contains("medico"))
        return "Médico" + (persona != null ? " de " + persona : "");
    if (texto.contains("dentista"))
        return "Dentista" + (persona != null ? " de " + persona : "");
    if (texto.contains("fisio"))
        return "Fisioterapia" + (persona != null ? " de " + persona : "");
    if (texto.contains("gimnasio"))
        return "Gimnasio" + (persona != null ? " de " + persona : "");
    if (texto.contains("tutor"))
        return "Tutoría" + (persona != null ? " de " + persona : "");
    if (texto.contains("claustro"))
        return "Claustro";
    if (texto.contains("teatro"))
        return "Teatro" + (persona != null ? " de " + persona : "");
    if (texto.contains("reunion") || texto.contains("reunión"))
        return "Reunión";
    if (texto.contains("cumple"))
        return "Cumpleaños" + (persona != null ? " de " + persona : "");
    if (texto.contains("vacacion"))
        return "Vacaciones" + (persona != null ? " de " + persona : "");
    if (texto.contains("viaje"))
        return "Viaje" + (persona != null ? " de " + persona : "");
    if (texto.contains("feria"))
        return "Feria";
    if (texto.contains("curso"))
        return "Curso";
    if (texto.contains("clase"))
        return "Clase";

    // ── Fallback: reconstruir desde las primeras palabras significativas ──
    String[] stopWords = {
        "todos", "cada", "los", "las", "el", "la", "un", "una",
        "lunes", "martes", "miercoles", "miércoles", "jueves", "viernes",
        "sabado", "sábado", "domingo",
        "enero", "febrero", "marzo", "abril", "mayo", "junio",
        "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
        "hasta", "desde", "del", "al", "de", "a", "en", "para",
        "manana", "mañana", "pasado", "proximo", "próximo",
        "todos", "este", "esta"
    };
    Set<String> stop = new java.util.HashSet<>(java.util.Arrays.asList(stopWords));

    StringBuilder titulo = new StringBuilder();
    for (String p : texto.split("\\s+")) {
        String pLimpio = p.replaceAll("[^a-záéíóúüñ]", "").toLowerCase();
        if (pLimpio.length() > 2 && !stop.contains(pLimpio)) {
            if (titulo.length() > 0) titulo.append(" ");
            titulo.append(capitalize(p.replaceAll("[^a-záéíóúüñA-ZÁÉÍÓÚÜÑ]", "")));
            if (titulo.length() > 30) break; // máximo ~3 palabras
        }
    }

    if (titulo.length() > 0) {
        return titulo.toString() + (persona != null ? " de " + persona : "");
    }

    return tipo.getLabel() + (persona != null ? " de " + persona : "");
}

private String capitalize(String str) {
    if (str == null || str.isEmpty()) return str;
    return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
}

public record EventoParseResult(Evento evento, boolean esRecurrente) {}

}
