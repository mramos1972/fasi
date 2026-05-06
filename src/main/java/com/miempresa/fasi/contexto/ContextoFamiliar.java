package com.miempresa.fasi.contexto;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "contexto_familiar")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ContextoFamiliar extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String clave;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String valor;

    @Column(length = 300)
    private String descripcion;
}
