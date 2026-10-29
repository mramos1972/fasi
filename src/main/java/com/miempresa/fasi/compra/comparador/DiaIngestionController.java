package com.miempresa.fasi.compra.comparador;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/compra/api")
@RequiredArgsConstructor
public class DiaIngestionController {

    private final SupermercadoRepository supermercadoRepo;
    private final PrecioCacheRepository precioCacheRepo;
    private final ObjectMapper objectMapper;

    @PostMapping("/ingestar-dia")
    @Transactional
    public ResponseEntity<?> ingestarJsonDia(@RequestBody String jsonPayload) {
        try {
            Supermercado dia = supermercadoRepo.findAll().stream()
                    .filter(s -> s.getCodigoInterno() != null && s.getCodigoInterno().equalsIgnoreCase("DIA")
                            || s.getNombre().equalsIgnoreCase("Dia"))
                    .findFirst()
                    .orElse(null);

            if (dia == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Supermercado Dia no encontrado en BD"));
            }

            JsonNode root = objectMapper.readTree(jsonPayload);
            JsonNode items = root.path("search_items");
            if (!items.isArray() || items.isEmpty()) {
                items = root.findPath("items");
            }

            if (!items.isArray() || items.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "No se detectaron search_items en el JSON"));
            }

            int insertados = 0;
            for (JsonNode item : items) {
                String nombre = item.path("display_name").asText("");
                double precioDbl = item.path("prices").path("price").asDouble(0.0);
                if (precioDbl == 0.0) {
                    precioDbl = item.findPath("price").asDouble(0.0);
                }
                String unidad = item.path("prices").path("measure_unit").asText("Ud");

                if (!nombre.isBlank() && precioDbl > 0.0) {
                    BigDecimal precio = BigDecimal.valueOf(precioDbl).setScale(2, RoundingMode.HALF_UP);
                    
                    // Extraer término de búsqueda clave (primera palabra relevante, ej. 'arroz', 'harina')
                    String termino = extraerTerminoClave(nombre);

                    PrecioCache pc = PrecioCache.builder()
                            .supermercado(dia)
                            .terminoBusqueda(termino.toLowerCase())
                            .productoNombre(nombre)
                            .precio(precio)
                            .formatoUnidad(unidad)
                            .actualizadoEl(LocalDateTime.now())
                            .build();

                    precioCacheRepo.save(pc);
                    insertados++;
                }
            }

            log.info("✅ Sincronizados {} productos reales de Dia directamente desde el navegador.", insertados);
            return ResponseEntity.ok(Map.of("status", "ok", "productos_procesados", insertados));

        } catch (Exception e) {
            log.error("❌ Error al ingestar JSON de Dia: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private String extraerTerminoClave(String nombre) {
        String clean = nombre.toLowerCase()
                .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u");
        String[] comunes = {"arroz", "harina", "azucar", "papel higienico", "leche", "huevos", "aceite", "pasta", "cafe"};
        for (String c : comunes) {
            if (clean.contains(c)) {
                return c;
            }
        }
        return clean.split(" ")[0];
    }
}
