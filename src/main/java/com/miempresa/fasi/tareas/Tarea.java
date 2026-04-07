package com.miempresa.fasi.tareas;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "tareas")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Tarea extends BaseEntity {

    @NotBlank
    @Column(nullable = false)
    private String titulo;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EstadoTarea estado;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PrioridadTarea prioridad;

    private LocalDate fechaVencimiento;

    public enum EstadoTarea    { PENDIENTE, EN_CURSO, HECHA }
    public enum PrioridadTarea { ALTA, MEDIA, BAJA }
}
