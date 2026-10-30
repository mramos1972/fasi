package com.miempresa.fasi.compra.comparador;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class MantenimientoPreciosController {

    private final SupermercadoRepository supermercadoRepo;
    private final PrecioCacheRepository precioCacheRepo;

    @GetMapping
    public String index(Model model) {
        long totalPrecios = precioCacheRepo.count();
        List<Supermercado> supers = supermercadoRepo.findAll();
        model.addAttribute("totalPrecios", totalPrecios);
        model.addAttribute("supermercados", supers);
        return "admin/mantenimiento-precios";
    }

    @PostMapping("/api/lote-dia")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> registrarLoteDia(@RequestBody List<ItemIngestaDto> lote) {
        return procesarLoteSupermercado("DIA", lote);
    }

    @PostMapping("/api/lote-mercadona")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> registrarLoteMercadona(@RequestBody List<ItemIngestaDto> lote) {
        return procesarLoteSupermercado("MERCADONA", lote);
    }

    private ResponseEntity<Map<String, Object>> procesarLoteSupermercado(String codigoSuper, List<ItemIngestaDto> lote) {
        if (lote == null || lote.isEmpty()) {
            return ResponseEntity.ok(Map.of("procesadosEnLote", 0, "totalEnCache", precioCacheRepo.count()));
        }

        Supermercado superm = supermercadoRepo.findByActivoTrue().stream()
                .filter(s -> (s.getCodigoInterno() != null && s.getCodigoInterno().equalsIgnoreCase(codigoSuper))
                        || s.getNombre().toUpperCase().contains(codigoSuper))
                .findFirst()
                .orElse(null);

        if (superm == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Supermercado " + codigoSuper + " no encontrado o inactivo"));
        }

        LocalDateTime ahora = LocalDateTime.now();
        int nuevosOActualizados = 0;

        for (ItemIngestaDto item : lote) {
            if (item.getDisplay_name() == null || item.getDisplay_name().isBlank()) continue;
            if (item.getPrecio() == null || item.getPrecio().compareTo(BigDecimal.ZERO) <= 0) continue;

            String nombre = item.getDisplay_name().trim();
            BigDecimal precio = item.getPrecio().setScale(2, RoundingMode.HALF_UP);
            String unidad = item.getMeasure_unit() != null ? item.getMeasure_unit().trim() : "Ud";
            String termino = item.getTermino_busqueda() != null ? item.getTermino_busqueda().trim() : nombre;

            // UPSERT por coincidencia de nombre exacto reciente
            Optional<PrecioCache> existente = precioCacheRepo.buscarPrecioReciente(superm.getId(), nombre, ahora.minusDays(60));

            if (existente.isPresent()) {
                PrecioCache pc = existente.get();
                pc.setPrecio(precio);
                pc.setFormatoUnidad(unidad);
                pc.setActualizadoEl(ahora);
                precioCacheRepo.save(pc);
            } else {
                PrecioCache pc = PrecioCache.builder()
                        .supermercado(superm)
                        .terminoBusqueda(termino.toLowerCase())
                        .productoNombre(nombre)
                        .precio(precio)
                        .formatoUnidad(unidad)
                        .actualizadoEl(ahora)
                        .build();
                precioCacheRepo.save(pc);
            }
            nuevosOActualizados++;
        }

        long total = precioCacheRepo.count();
        log.info("📥 [{} Sync] Procesados {} productos (Total BD: {})", codigoSuper, nuevosOActualizados, total);

        return ResponseEntity.ok(Map.of(
                "procesadosEnLote", nuevosOActualizados,
                "totalEnCache", total
        ));
    }

    @Data
    public static class ItemIngestaDto {
        private String sku_id;
        private String display_name;
        private BigDecimal precio;
        private String measure_unit;
        private String termino_busqueda;
    }
}
