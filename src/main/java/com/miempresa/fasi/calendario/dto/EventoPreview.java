package com.miempresa.fasi.calendario.dto;

import com.miempresa.fasi.calendario.Evento;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * DTO para mostrar PREVIEW de evento al usuario.
 * 
 * Contiene:
 * - Datos del evento (formateados para mostrar)
 * - Información sobre recurrencia (si aplica)
 * - Advertencias (campos pendientes, etc.)
 * - Sugerencias de edición
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EventoPreview {

    // ════════════════════════════════════════════════════════
    //  DATOS DEL EVENTO (para mostrar)
    // ════════════════════════════════════════════════════════
    
    private String titulo;
    private Evento.TipoEvento tipo;
    private String tipoLabel;           // "Salud", "Trabajo", etc.
    private String tipoEmoji;           // "⚕️", "💼", etc.
    private String tipoColor;           // "#ef4444", etc.
    
    private LocalDate fechaInicio;
    private String fechaInicioFormato;  // "Jueves, 5 de junio"
    
    private LocalDate fechaFin;
    private String fechaFinFormato;     // "Viernes, 6 de junio"
    
    private LocalTime horaInicio;
    private String horaInicioFormato;   // "15:00"
    
    private LocalTime horaFin;
    private String horaFinFormato;      // "16:00"
    
    private Boolean todoDia;
    private String lugar;
    private String personasRelacionadas;
    private String descripcion;
    private Integer recordatorioMinutos;

    // ════════════════════════════════════════════════════════
    //  INFORMACIÓN DE RECURRENCIA
    // ════════════════════════════════════════════════════════
    
    private Boolean esRecurrente;
    private String patron;              // "WEEKLY", "MONTHLY", etc.
    private String patronDescripcion;   // "Todos los lunes"
    private Integer totalEventosGenerados; // 52 (si es anual)
    private List<LocalDate> primerasFechas; // Preview de fechas
    private String descripcionPreview;  // Vista previa formateada

    // ════════════════════════════════════════════════════════
    //  ADVERTENCIAS Y SUGERENCIAS
    // ════════════════════════════════════════════════════════
    
    private Boolean faltaHora;          // true si horaInicio == null
    private Boolean faltaLugar;         // true si lugar == null
    private Boolean faltaPersonas;      // true si personasRelacionadas == null
    private String advertencia;         // Mensaje de advertencia (si aplica)
    private String sugerencia;          // Sugerencia de edición

    // ════════════════════════════════════════════════════════
    //  FACTORY: Crear desde EventoMinimo
    // ════════════════════════════════════════════════════════
    
    /**
     * Crea un EventoPreview desde un EventoMinimo.
     * Formatea los datos para mostrar al usuario.
     */
    public static EventoPreview fromEventoMinimo(EventoMinimo evento) {
        EventoPreview preview = new EventoPreview();
        
        // Datos básicos
        preview.setTitulo(evento.getTitulo());
        preview.setTipo(evento.getTipo());
        preview.setTipoLabel(evento.getTipo().getLabel());
        preview.setTipoEmoji(evento.getTipo().getEmoji());
        preview.setTipoColor(evento.getTipo().getColor());
        
        // Fechas
        preview.setFechaInicio(evento.getFechaInicio());
        preview.setFechaInicioFormato(formatearFecha(evento.getFechaInicio()));
        
        if (evento.getFechaFin() != null) {
            preview.setFechaFin(evento.getFechaFin());
            preview.setFechaFinFormato(formatearFecha(evento.getFechaFin()));
        }
        
        // Horas
        if (evento.getHoraInicio() != null) {
            preview.setHoraInicio(evento.getHoraInicio());
            preview.setHoraInicioFormato(evento.getHoraInicio().format(DateTimeFormatter.ofPattern("HH:mm")));
        }
        
        if (evento.getHoraFin() != null) {
            preview.setHoraFin(evento.getHoraFin());
            preview.setHoraFinFormato(evento.getHoraFin().format(DateTimeFormatter.ofPattern("HH:mm")));
        }
        
        // Otros campos
        preview.setTodoDia(evento.getTodoDia() != null ? evento.getTodoDia() : false);
        preview.setLugar(evento.getLugar());
        preview.setPersonasRelacionadas(evento.getPersonasRelacionadas());
        preview.setDescripcion(evento.getDescripcion());
        preview.setRecordatorioMinutos(evento.getRecordatorioMinutos());
        
        // Recurrencia
        preview.setEsRecurrente(evento.getEsRecurrente() != null && evento.getEsRecurrente());
        if (preview.getEsRecurrente()) {
            preview.setPatron(evento.getPatron());
            preview.setPatronDescripcion(describirPatron(evento.getPatron()));
        }
        
        // Advertencias
        preview.setFaltaHora(evento.getHoraInicio() == null && !preview.getTodoDia());
        preview.setFaltaLugar(evento.getLugar() == null);
        preview.setFaltaPersonas(evento.getPersonasRelacionadas() == null);
        
        // Construir advertencia
        StringBuilder adv = new StringBuilder();
        if (preview.getFaltaHora()) adv.append("⚠️ Sin hora especificada\n");
        if (preview.getFaltaLugar()) adv.append("⚠️ Sin lugar\n");
        if (preview.getFaltaPersonas() && evento.getTipo() == Evento.TipoEvento.FAMILIA) 
            adv.append("⚠️ Sin personas relacionadas\n");
        
        if (adv.length() > 0) {
            preview.setAdvertencia(adv.toString().trim());
        }
        
        return preview;
    }

    // ════════════════════════════════════════════════════════
    //  UTILIDADES DE FORMATEO
    // ════════════════════════════════════════════════════════
    
    /**
     * Formatea una fecha como "Jueves, 5 de junio".
     */
    private static String formatearFecha(LocalDate fecha) {
        if (fecha == null) return null;
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", 
            java.util.Locale.forLanguageTag("es-ES"));
        return fecha.format(formatter)
            .substring(0, 1).toUpperCase() + 
            fecha.format(formatter).substring(1);
    }

    /**
     * Describe un patrón de recurrencia en lenguaje natural.
     */
    private static String describirPatron(String patron) {
        if (patron == null) return "Recurrente";
        
        return switch (patron.toUpperCase()) {
            case "DAILY" -> "Todos los días";
            case "WEEKLY" -> "Todas las semanas";
            case "MONTHLY" -> "Todos los meses";
            case "MONTHLY_RELATIVE" -> "Cada mes (día relativo)";
            case "YEARLY" -> "Todos los años";
            default -> "Recurrente (" + patron + ")";
        };
    }

    @Override
    public String toString() {
        return "EventoPreview{" +
                "titulo='" + titulo + '\'' +
                ", tipo=" + tipo +
                ", fecha=" + fechaInicioFormato +
                ", hora=" + horaInicioFormato +
                ", lugar='" + lugar + '\'' +
                ", recurrente=" + esRecurrente +
                '}';
    }
}
