package com.miempresa.fasi.compra.comparador;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "supermercados")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Supermercado extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String nombre;

    @Column(name = "codigo_interno", nullable = false, unique = true, length = 50)
    private String codigoInterno;

    @Column(name = "codigo_postal", nullable = false, length = 10)
    @Builder.Default
    private String codigoPostal = "11540";

    @Column(nullable = false)
    @Builder.Default
    private boolean activo = true;
}
