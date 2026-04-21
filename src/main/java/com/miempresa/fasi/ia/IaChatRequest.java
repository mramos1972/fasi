package com.miempresa.fasi.ia;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class IaChatRequest {
    @NotBlank
    private String mensaje;
    private String modulo; // opcional: "contabilidad", "tareas", "agenda", "general"
}
