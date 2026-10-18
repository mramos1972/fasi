package com.miempresa.fasi.contexto;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "usuario_permisos_calendario")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
@IdClass(UsuarioPermiso.UsuarioPermisoId.class)
public class UsuarioPermiso {

    @Id
    @Column(name = "propietario_id", nullable = false)
    private UUID propietarioId;

    @Id
    @Column(name = "lector_id", nullable = false)
    private UUID lectorId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UsuarioPermisoId implements Serializable {
        private UUID propietarioId;
        private UUID lectorId;
    }
}
