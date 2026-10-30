package com.miempresa.fasi.compra.comparador;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultadoComparativaDto {

    private String supermercado;
    private String codigoPostal;
    @Builder.Default
    private List<ItemComparadoDto> productos = new ArrayList<>();
    private BigDecimal totalEstimado;
    private int productosEncontrados;
    private int productosSinPrecio;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ItemComparadoDto {
        private String terminoBuscado;
        private String productoEncontrado;
        private BigDecimal precio;
        private String formato;
        private boolean encontrado;
        
        @Builder.Default
        private List<OpcionProductoDto> opciones = new ArrayList<>();
    }
}
