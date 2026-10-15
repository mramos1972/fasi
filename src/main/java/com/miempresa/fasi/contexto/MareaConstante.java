package com.miempresa.fasi.contexto;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "marea_constante")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MareaConstante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id; // <-- Cambiado de Long a Integer

    @Column(nullable = false, unique = true, length = 10)
    private String nombre;

    @Column(nullable = false)
    private double amplitud;   // H en metros

    @Column(nullable = false)
    private double fase;       // g en grados

    @Column(nullable = false)
    private double velocidad;  // ω en grados/hora

    @Column(nullable = false)
    private boolean activo = true;
}
