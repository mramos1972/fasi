package com.miempresa.fasi.contabilidad;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "movimientos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Movimiento extends BaseEntity {

    @NotBlank
    @Column(nullable = false)
    private String concepto;

    @NotNull @Positive
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal importe;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TipoMovimiento tipo;

    private String categoria;

    @NotNull
    @Column(nullable = false)
    private LocalDate fecha;

    @Column(columnDefinition = "TEXT")
    private String notas;

    public enum TipoMovimiento { INGRESO, GASTO }
}
