package com.miempresa.fasi.calendario;

import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
public class EventoIaResponse {
    private boolean      exito;
    private String       mensajeError;
    private Evento       eventoPreview;
    private List<Evento> eventosPreview;
    private long         tiempoMs;

    // Constructor evento único — compatibilidad hacia atrás
    public EventoIaResponse(boolean exito, String mensajeError,
                             Evento eventoPreview, long tiempoMs) {
        this.exito         = exito;
        this.mensajeError  = mensajeError;
        this.eventoPreview = eventoPreview;
        this.tiempoMs      = tiempoMs;
    }

    // Constructor múltiples eventos
    public EventoIaResponse(boolean exito, String mensajeError,
                             Evento eventoPreview, List<Evento> eventosPreview,
                             long tiempoMs) {
        this.exito          = exito;
        this.mensajeError   = mensajeError;
        this.eventoPreview  = eventoPreview;
        this.eventosPreview = eventosPreview;
        this.tiempoMs       = tiempoMs;
    }

    public boolean isMultiple() {
        return eventosPreview != null && !eventosPreview.isEmpty();
    }
}
