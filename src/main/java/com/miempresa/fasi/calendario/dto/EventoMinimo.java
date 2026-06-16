package com.miempresa.fasi.calendario.dto;

import com.miempresa.fasi.calendario.Evento;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * DTO MINIMALISTA para eventos.
 * 
 * FILOSOFÍA:
 * - Solo 3 campos OBLIGATORIOS: titulo, fechaInicio, tipo
 * - TODO LO DEMÁS es OPCIONAL
 * - Se puede guardar "incompleto" y editar después
 * 
 * VENTAJAS:
 * - Acepta eventos "pendientes de hora", "sin lugar", etc.
 * - No fuerza al usuario a completar todo
 * - Flujo conversacional más rápido
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventoMinimo {

    // ════════════════════════════════════════════════════════
    //  CAMPOS OBLIGATORIOS (3)
    // ════════════════════════════════════════════════════════
    
    @NotBlank(message = "El título es obligatorio")
    private String titulo;
    
    @NotNull(message = "La fecha es obligatoria")
    private LocalDate fechaInicio;
    
    @NotNull(message = "El tipo es obligatorio")
    private Evento.TipoEvento tipo;

    // ════════════════════════════════════════════════════════
    //  CAMPOS OPCIONALES (pueden ser null)
    // ════════════════════════════════════════════════════════
    
    private LocalTime horaInicio;      // null = "pendiente de hora"
    private LocalTime horaFin;
    private LocalDate fechaFin;        // null = evento de 1 día
    private String lugar;              // null = "sin especificar"
    private String personasRelacionadas; // null
    private String descripcion;
    private Integer recordatorioMinutos;
    private Boolean todoDia;           // default = false

    // ════════════════════════════════════════════════════════
    //  CAMPOS PARA RECURRENCIA (si aplica)
    // ════════════════════════════════════════════════════════
    
    private Boolean esRecurrente;      // true si es recurrente
    private String patron;             // "WEEKLY", "MONTHLY", "YEARLY", etc.
    private Map<String, Object> datosExtra; // Datos específicos por patrón

    // ════════════════════════════════════════════════════════
    //  CONVERSIÓN A EVENTO COMPLETO
    // ════════════════════════════════════════════════════════
    
    /**
     * Convierte EventoMinimo a Evento (entidad JPA).
     * Llena los campos opcionales con valores por defecto si es necesario.
     */
    public Evento toEvento() {
        Evento e = new Evento();
        
        // Obligatorios
        e.setTitulo(titulo);
        e.setFechaInicio(fechaInicio);
        e.setTipo(tipo != null ? tipo : Evento.TipoEvento.OTRO);
        
        // Opcionales
        e.setHoraInicio(horaInicio);
        e.setHoraFin(horaFin);
        e.setFechaFin(fechaFin);
        e.setLugar(lugar);
        e.setPersonasRelacionadas(personasRelacionadas);
        e.setDescripcion(descripcion);
        e.setRecordatorioMinutos(recordatorioMinutos);
        e.setTodoDia(todoDia != null ? todoDia : false);
        
        // Datos extra (recurrencia, etc.)
        if (esRecurrente != null && esRecurrente) {
            Map<String, Object> extra = datosExtra != null 
                ? new HashMap<>(datosExtra) 
                : new HashMap<>();
            extra.put("recurrente", true);
            if (patron != null) extra.put("patron", patron);
            e.setDatosExtra(extra.isEmpty() ? null : extra);
        } else {
            e.setDatosExtra(datosExtra);
        }
        
        return e;
    }

    // ════════════════════════════════════════════════════════
    //  FACTORY METHODS (para facilitar creación)
    // ════════════════════════════════════════════════════════
    
    /**
     * Crea un EventoMinimo desde un Evento existente.
     */
    public static EventoMinimo fromEvento(Evento evento) {
        return EventoMinimo.builder()
            .titulo(evento.getTitulo())
            .fechaInicio(evento.getFechaInicio())
            .tipo(evento.getTipo())
            .horaInicio(evento.getHoraInicio())
            .horaFin(evento.getHoraFin())
            .fechaFin(evento.getFechaFin())
            .lugar(evento.getLugar())
            .personasRelacionadas(evento.getPersonasRelacionadas())
            .descripcion(evento.getDescripcion())
            .recordatorioMinutos(evento.getRecordatorioMinutos())
            .todoDia(evento.isTodoDia())
            .datosExtra(evento.getDatosExtra())
            .esRecurrente(evento.getDatosExtra() != null && 
                         Boolean.TRUE.equals(evento.getDatosExtra().get("recurrente")))
            .patron(evento.getDatosExtra() != null ? 
                   (String) evento.getDatosExtra().get("patron") : null)
            .build();
    }

    // ════════════════════════════════════════════════════════
    //  VALIDACIONES LÓGICAS
    // ════════════════════════════════════════════════════════
    
    /**
     * Valida que el evento tenga sentido lógico.
     * Lanza excepción si hay problemas.
     */
    public void validar() throws IllegalArgumentException {
        // Ya validado por @NotNull/@NotBlank, pero aquí lógica extra
        
        if (fechaFin != null && fechaFin.isBefore(fechaInicio)) {
            throw new IllegalArgumentException("La fecha fin no puede ser antes que la fecha inicio");
        }
        
        if (horaFin != null && horaInicio != null && horaFin.isBefore(horaInicio)) {
            throw new IllegalArgumentException("La hora fin no puede ser antes que la hora inicio");
        }
        
        if (todoDia != null && todoDia && horaInicio != null) {
            throw new IllegalArgumentException("No puede ser 'todo el día' y tener hora específica");
        }
    }

    @Override
    public String toString() {
        return "EventoMinimo{" +
                "titulo='" + titulo + '\'' +
                ", fecha=" + fechaInicio +
                ", tipo=" + tipo +
                ", hora=" + horaInicio +
                ", lugar='" + lugar + '\'' +
                ", personas='" + personasRelacionadas + '\'' +
                ", recurrente=" + esRecurrente +
                '}';
    }
}
