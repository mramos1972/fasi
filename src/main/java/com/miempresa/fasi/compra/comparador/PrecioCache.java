package com.miempresa.fasi.compra.comparador;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "precios_cache")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrecioCache {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supermercado_id", nullable = false)
    private Supermercado supermercado;

    @Column(name = "termino_busqueda", nullable = false, length = 150)
    private String terminoBusqueda;

    @Column(name = "producto_nombre", nullable = false)
    private String productoNombre;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal precio;

    @Column(name = "formato_unidad", length = 50)
    private String formatoUnidad;

    @Column(name = "url_producto", length = 500)
    private String urlProducto;

    @Column(name = "actualizado_el", nullable = false)
    @Builder.Default
    private LocalDateTime actualizadoEl = LocalDateTime.now();
}
