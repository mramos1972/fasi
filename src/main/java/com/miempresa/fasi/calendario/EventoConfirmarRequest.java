package com.miempresa.fasi.calendario;

import lombok.Data;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class EventoConfirmarRequest {
    private Evento.TipoEvento tipo;
    private String            titulo;
    private LocalDate         fechaInicio;
    private LocalDate         fechaFin;
    private LocalTime         horaInicio;
    private LocalTime         horaFin;
    private boolean           todoDia;
    private String            lugar;
    private String            personasRelacionadas;
    private String            descripcion;
    private Integer           recordatorioMinutos;
    private Map<String, Object> datosExtra;
    private String            textoOriginalVoz;

    public Evento toEvento() {
        Evento e = new Evento();
        e.setTipo(tipo != null ? tipo : Evento.TipoEvento.OTRO);
        e.setTitulo(titulo);
        e.setFechaInicio(fechaInicio);
        e.setFechaFin(fechaFin);
        e.setHoraInicio(horaInicio);
        e.setHoraFin(horaFin);
        e.setTodoDia(todoDia);
        e.setLugar(lugar);
        e.setPersonasRelacionadas(personasRelacionadas);
        e.setDescripcion(descripcion);
        e.setRecordatorioMinutos(recordatorioMinutos);
        e.setTextoOriginalVoz(textoOriginalVoz);

        Map<String, Object> extra = datosExtra != null
            ? new HashMap<>(datosExtra)
            : new HashMap<>();

        // Si no tiene hora y no es todo el día → marcar pendiente
        if (horaInicio == null && !todoDia) {
            extra.put("pendienteHora", true);
        }

        e.setDatosExtra(extra.isEmpty() ? null : extra);
        return e;
    }
}
