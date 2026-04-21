package com.miempresa.fasi.ia;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "ia_mensajes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IaMensaje extends BaseEntity {

    @Column(columnDefinition = "TEXT", nullable = false)
    private String pregunta;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String respuesta;

    private String modulo;

    private Long tiempoMs;
}
