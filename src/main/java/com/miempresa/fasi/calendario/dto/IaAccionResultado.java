package com.miempresa.fasi.calendario.dto;

import com.miempresa.fasi.calendario.Evento;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IaAccionResultado {
    private boolean exito;
    private String mensajeUsuario;
    @Builder.Default
    private List<Evento> crear = new ArrayList<>();
    @Builder.Default
    private List<UUID> eliminarIds = new ArrayList<>();
    private long tiempoMs;
}
