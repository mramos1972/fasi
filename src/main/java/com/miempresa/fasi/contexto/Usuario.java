package com.miempresa.fasi.contexto;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "usuarios")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Usuario extends BaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, length = 200)
    private String password;

    @Column(length = 100)
    private String nombre;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String rol = "USER";

    @Column(nullable = false)
    @Builder.Default
    private boolean activo = true;
}
