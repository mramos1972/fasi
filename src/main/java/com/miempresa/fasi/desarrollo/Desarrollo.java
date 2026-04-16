package com.miempresa.fasi.desarrollo;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "desarrollos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Desarrollo extends BaseEntity {

    @NotBlank
    @Column(nullable = false)
    private String titulo;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoDesarrollo estado;

    @Column(columnDefinition = "TEXT")
    private String scriptGenerado;

    private LocalDateTime fechaAplicado;

    public enum EstadoDesarrollo { PENDIENTE, APLICADO, DESCARTADO }
}
