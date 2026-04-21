package com.miempresa.fasi.ia;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IaChatResponse {
    private String respuesta;
    private String modulo;
    private long   tiempoMs;
}
