package com.miempresa.fasi.compra;

import com.miempresa.fasi.common.BaseEntity;
import com.miempresa.fasi.contexto.Usuario;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "lista_compra")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemCompra extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(nullable = false, length = 200)
    private String nombre;

    @Builder.Default
    @Column(nullable = false)
    private boolean comprado = false;
}
