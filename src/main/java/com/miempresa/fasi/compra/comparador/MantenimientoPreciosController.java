package com.miempresa.fasi.compra.comparador;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Controller
@RequestMapping("/admin/mantenimiento-precios")
@RequiredArgsConstructor
public class MantenimientoPreciosController {

    private final SupermercadoRepository supermercadoRepo;
    private final PrecioCacheRepository precioCacheRepo;

    @GetMapping
    public String vistaMantenimiento(Model model) {
        List<Supermercado> supers = supermercadoRepo.findAll();
        long totalPreciosCache = precioCacheRepo.count();

        model.addAttribute("supermercados", supers);
        model.addAttribute("totalPrecios", totalPreciosCache);
        return "admin/mantenimiento-precios";
    }

    @PostMapping("/api/lote-dia")
    @ResponseBody
    public ResponseEntity<?> procesarLoteDia(@RequestBody List<ProductoLoteDto> items) {
        if (items == null || items.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "El lote recibido está vacío."));
        }

        Supermercado dia = supermercadoRepo.findAll().stream()
                .filter(s -> (s.getCodigoInterno() != null && s.getCodigoInterno().equalsIgnoreCase("DIA"))
                        || s.getNombre().equalsIgnoreCase("Dia"))
                .findFirst()
                .orElse(null);

        if (dia == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Supermercado DIA no configurado en la base de datos."));
        }

        int procesados = 0;
        List<PrecioCache> entidadesAGuardar = new ArrayList<>();

        for (ProductoLoteDto dto : items) {
            if (dto.getNombre() == null || dto.getNombre().isBlank() || dto.getPrecio() == null) {
                continue;
            }

            String termino = extraerTerminoBusqueda(dto.getNombre());
            BigDecimal precioNorm = dto.getPrecio().setScale(2, RoundingMode.HALF_UP);
            String unidad = (dto.getUnidad() != null && !dto.getUnidad().isBlank()) ? dto.getUnidad() : "Ud";

            PrecioCache pc = PrecioCache.builder()
                    .supermercado(dia)
                    .terminoBusqueda(termino.toLowerCase())
                    .productoNombre(dto.getNombre().trim())
                    .precio(precioNorm)
                    .formatoUnidad(unidad)
                    .actualizadoEl(LocalDateTime.now())
                    .build();

            entidadesAGuardar.add(pc);
            procesados++;
        }

        if (!entidadesAGuardar.isEmpty()) {
            precioCacheRepo.saveAll(entidadesAGuardar);
            log.info("📦 [Mantenimiento] Guardado lote de {} productos para {}", procesados, dia.getNombre());
        }

        long nuevoTotal = precioCacheRepo.count();
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "procesadosEnLote", procesados,
                "totalEnCache", nuevoTotal
        ));
    }

    private String extraerTerminoBusqueda(String nombre) {
        String clean = nombre.toLowerCase()
                .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        String[] comunes = {
                "papel higienico", "harina de trigo", "azucar blanco", "arroz redondo",
                "leche entera", "leche semidesnatada", "leche desnatada",
                "aceite de oliva", "aceite de girasol", "huevos", "pan de molde",
                "arroz", "harina", "azucar", "leche", "aceite", "pasta", "macarrones",
                "espaguetis", "tomate", "atun", "cafe", "detergente", "suavizante",
                "galletas", "yogur", "patatas", "cebollas"
        };

        for (String c : comunes) {
            if (clean.contains(c)) {
                return c;
            }
        }

        String[] palabras = clean.split(" ");
        return palabras.length > 0 ? palabras[0] : clean;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductoLoteDto {
        @JsonProperty("display_name")
        private String nombre;

        private BigDecimal precio;

        @JsonProperty("measure_unit")
        private String unidad;
    }
}
