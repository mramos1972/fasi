package com.miempresa.fasi.calendario;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/**
 * DTO para recibir el evento interpretado por la IA desde el frontend.
 * El campo 'tipo' puede llegar como String ("CUMPLEANOS") o como objeto
 * con subcampos {label, emoji, color} — lo normalizamos aquí.
 */
@Data
public class EventoConfirmarRequest {

    private Object tipo;   // puede ser String o Map<String,Object>

    private String    titulo;
    private LocalDate fechaInicio;
    private LocalDate fechaFin;
    private LocalTime horaInicio;
    private LocalTime horaFin;
    private boolean   todoDia;
    private String    lugar;
    private String    personasRelacionadas;
    private String    descripcion;
    private Integer   recordatorioMinutos;
    private Map<String, Object> datosExtra;
    private String    textoOriginalVoz;

    /**
     * Resuelve el tipo sea cual sea el formato que mande el frontend.
     */
    public Evento.TipoEvento resolverTipo() {
        if (tipo == null) return Evento.TipoEvento.OTRO;

        // Caso 1: llega como String puro → "CUMPLEANOS"
        if (tipo instanceof String s) {
            try { return Evento.TipoEvento.valueOf(s.trim().toUpperCase()); }
            catch (IllegalArgumentException e) { return Evento.TipoEvento.OTRO; }
        }

        // Caso 2: llega como objeto {label:"Cumpleaños", emoji:"🎂", color:"#f59e0b"}
        // Jackson lo deserializa como LinkedHashMap
        if (tipo instanceof Map<?,?> m) {
            // Intentar por nombre del enum si viene en el mapa
            Object nombre = m.get("name");
            if (nombre == null) nombre = m.get("nombre");
            if (nombre instanceof String s) {
                try { return Evento.TipoEvento.valueOf(s.trim().toUpperCase()); }
                catch (IllegalArgumentException ignored) {}
            }
            // Intentar por label
            Object label = m.get("label");
            if (label instanceof String lbl) {
                for (Evento.TipoEvento t : Evento.TipoEvento.values()) {
                    if (t.getLabel().equalsIgnoreCase(lbl.trim())) return t;
                }
            }
        }

        return Evento.TipoEvento.OTRO;
    }

    /** Convierte el DTO a entidad lista para persistir. */
    public Evento toEvento() {
        Evento e = new Evento();
        e.setTipo(resolverTipo());
        e.setTitulo(titulo != null ? titulo : "Sin título");
        e.setFechaInicio(fechaInicio != null ? fechaInicio : LocalDate.now());
        e.setFechaFin(fechaFin);
        e.setHoraInicio(horaInicio);
        e.setHoraFin(horaFin);
        e.setTodoDia(todoDia);
        e.setLugar(lugar);
        e.setPersonasRelacionadas(personasRelacionadas);
        e.setDescripcion(descripcion);
        e.setRecordatorioMinutos(recordatorioMinutos);
        e.setDatosExtra(datosExtra);
        e.setTextoOriginalVoz(textoOriginalVoz);
        return e;
    }
}
