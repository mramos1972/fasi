package com.miempresa.fasi.agenda;

import com.miempresa.fasi.common.BaseEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Entity
@Table(name = "contactos")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Contacto extends BaseEntity {

    @NotBlank
    @Column(nullable = false)
    private String nombre;

    private String apellidos;

    @Email
    private String email;

    private String telefono;

    @Column(columnDefinition = "TEXT")
    private String notas;
}
