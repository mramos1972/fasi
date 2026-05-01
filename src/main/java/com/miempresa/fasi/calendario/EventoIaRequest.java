package com.miempresa.fasi.calendario;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EventoIaRequest {
    @NotBlank
    private String texto;          // texto dictado por voz o escrito
    private String fechaContexto;  // fecha actual (para resolver "el jueves")
}
