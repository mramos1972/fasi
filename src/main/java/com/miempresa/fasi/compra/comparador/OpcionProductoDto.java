package com.miempresa.fasi.compra.comparador;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpcionProductoDto {
    private UUID id;
    private String nombre;
    private BigDecimal precio;
    private String formato;
    private boolean seleccionada;
}
