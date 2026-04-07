package com.miempresa.fasi.documentos;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Entity
@Table(name = "documentos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Documento extends BaseEntity {

    @NotBlank
    @Column(nullable = false)
    private String nombre;

    @Column(columnDefinition = "TEXT")
    private String descripcion;

    private String tipo;

    @Column(nullable = false)
    private String ruta;

    private Long   tamanioBytes;
    private String categoria;
}
