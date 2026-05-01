package com.miempresa.fasi.calendario;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventoIaResponse {
    private boolean exito;
    private String  mensajeError;
    private Evento  eventoPreview;  // evento parseado, pendiente de confirmación
    private long    tiempoMs;
}
