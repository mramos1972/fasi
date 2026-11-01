package com.miempresa.fasi.compra.comparador;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Controller
@RequestMapping("/admin/mantenimiento-precios")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class MantenimientoPreciosController {

    private final SupermercadoRepository supermercadoRepo;
    private final PrecioCacheRepository precioCacheRepo;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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

    @PostMapping("/api/lote-aldi")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> registrarLoteAldi(@RequestBody List<ItemIngestaDto> lote) {
        return procesarLoteSupermercado("ALDI", lote);
    }

    @PostMapping("/api/sincronizar-mercadona")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> sincronizarMercadonaServer() {
        Supermercado mercadona = supermercadoRepo.findByActivoTrue().stream()
                .filter(s -> (s.getCodigoInterno() != null && s.getCodigoInterno().equalsIgnoreCase("MERCADONA"))
                        || s.getNombre().toUpperCase().contains("MERCADONA"))
                .findFirst()
                .orElse(null);

        if (mercadona == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Supermercado Mercadona no encontrado o inactivo"));
        }

        try {
            log.info("🟢 [Mercadona Sync Server] Obteniendo categorías raíz...");
            HttpRequest rootReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://tienda.mercadona.es/api/categories/"))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> rootResp = httpClient.send(rootReq, HttpResponse.BodyHandlers.ofString());
            if (rootResp.statusCode() != 200) {
                return ResponseEntity.status(rootResp.statusCode())
                        .body(Map.of("error", "HTTP " + rootResp.statusCode() + " al obtener categorías de Mercadona"));
            }

            JsonNode rootJson = objectMapper.readTree(rootResp.body());
            JsonNode results = rootJson.path("results");

            List<Integer> subcatIds = new ArrayList<>();
            if (results.isArray()) {
                for (JsonNode cat : results) {
                    JsonNode subs = cat.path("categories");
                    if (subs.isArray()) {
                        for (JsonNode sub : subs) {
                            int id = sub.path("id").asInt(0);
                            if (id > 0) subcatIds.add(id);
                        }
                    }
                }
            }

            log.info("🟢 [Mercadona Sync Server] Total subcategorías detectadas: {}", subcatIds.size());

            int procesados = 0;
            LocalDateTime ahora = LocalDateTime.now();

            for (Integer subId : subcatIds) {
                try {
                    HttpRequest catReq = HttpRequest.newBuilder()
                            .uri(URI.create("https://tienda.mercadona.es/api/categories/" + subId + "/"))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .header("Accept", "application/json")
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();

                    HttpResponse<String> catResp = httpClient.send(catReq, HttpResponse.BodyHandlers.ofString());
                    if (catResp.statusCode() != 200) continue;

                    JsonNode catJson = objectMapper.readTree(catResp.body());
                    String nombreSubcat = catJson.path("name").asText("General");
                    JsonNode categories = catJson.path("categories");

                    if (categories.isArray()) {
                        for (JsonNode inner : categories) {
                            JsonNode products = inner.path("products");
                            if (products.isArray()) {
                                for (JsonNode p : products) {
                                    String nombreProd = p.path("display_name").asText("").trim();
                                    double precioDbl = p.path("price_instructions").path("unit_price").asDouble(0.0);
                                    String unidad = p.path("packaging").asText("Ud").trim();

                                    if (!nombreProd.isBlank() && precioDbl > 0.0) {
                                        BigDecimal precio = BigDecimal.valueOf(precioDbl).setScale(2, RoundingMode.HALF_UP);

                                        Optional<PrecioCache> existente = precioCacheRepo.buscarPreciosRecientes(
                                                mercadona.getId(), nombreProd, ahora.minusDays(60)).stream().findFirst();

                                        if (existente.isPresent()) {
                                            PrecioCache pc = existente.get();
                                            pc.setPrecio(precio);
                                            pc.setFormatoUnidad(unidad);
                                            pc.setActualizadoEl(ahora);
                                            precioCacheRepo.save(pc);
                                        } else {
                                            PrecioCache pc = PrecioCache.builder()
                                                    .supermercado(mercadona)
                                                    .terminoBusqueda(nombreSubcat.toLowerCase())
                                                    .productoNombre(nombreProd)
                                                    .precio(precio)
                                                    .formatoUnidad(unidad)
                                                    .actualizadoEl(ahora)
                                                    .build();
                                            precioCacheRepo.save(pc);
                                        }
                                        procesados++;
                                    }
                                }
                            }
                        }
                    }

                    Thread.sleep(60);

                } catch (Exception e) {
                    log.warn("Aviso descargando categoría {} de Mercadona: {}", subId, e.getMessage());
                }
            }

            long totalBd = precioCacheRepo.count();
            log.info("🎉 [Mercadona Sync Server] Finalizado con éxito. Procesados: {} (Total BD: {})", procesados, totalBd);

            return ResponseEntity.ok(Map.of(
                    "procesados", procesados,
                    "totalEnCache", totalBd
            ));

        } catch (Exception ex) {
            log.error("❌ Error en sincronización Mercadona Server: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of("error", ex.getMessage()));
        }
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

            Optional<PrecioCache> existente = precioCacheRepo.buscarPreciosRecientes(
                    superm.getId(), nombre, ahora.minusDays(60)).stream().findFirst();

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

    @PostMapping("/api/sincronizar-aldi")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> sincronizarAldiServidor() {
        log.info("Iniciando crawling y sincronización en árbol de ALDI desde el servidor...");

        List<String> categoriasRaiz = List.of(
                "lacteos-y-huevos",
                "quesos",
                "carne",
                "pescado-y-marisco",
                "charcuteria",
                "fruta-y-verdura",
                "panaderia-y-bolleria",
                "despensa",
                "desayuno",
                "cafe-cacao-e-infusiones",
                "aperitivos",
                "chocolates-y-dulces",
                "platos-preparados-y-pizza",
                "congelados",
                "bebidas",
                "bebidas-alcoholicas",
                "limpieza-y-hogar",
                "cuidado-personal",
                "bebe-e-infantil",
                "mascotas",
                "gourmet",
                "dulces-de-navidad"
        );

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        String buildId = "Uaol5T0hC3jS0YDFqYFLA";

        try {
            HttpRequest rHome = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.aldi.es/productos.html"))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(8))
                    .GET().build();
            HttpResponse<String> respHome = client.send(rHome, HttpResponse.BodyHandlers.ofString());
            if (respHome.statusCode() == 200) {
                Pattern pBuild = Pattern.compile("\"buildId\":\"([^\"]+)\"");
                Matcher m = pBuild.matcher(respHome.body());
                if (m.find()) {
                    buildId = m.group(1);
                    log.info("BuildId dinámico detectado para ALDI: {}", buildId);
                }
            }
        } catch (Exception e) {
            log.warn("Usando buildId de fallback para ALDI: {}", e.getMessage());
        }

        Queue<String> colaExploracion = new LinkedList<>(categoriasRaiz);
        Set<String> rutasVisitadas = new HashSet<>();
        int totalProcesados = 0;
        int categoriasConProductos = 0;

        while (!colaExploracion.isEmpty()) {
            String rutaActual = colaExploracion.poll();
            if (rutasVisitadas.contains(rutaActual)) continue;
            rutasVisitadas.add(rutaActual);

            try {
                String url = "https://www.aldi.es/_next/data/" + buildId + "/es/productos/" + rutaActual + ".html.json";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .header("Accept", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    continue;
                }

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode results = root.path("pageProps").path("algoliaState").path("initialResults");

                if (!results.isMissingNode() && results.isObject()) {
                    Iterator<String> it = results.fieldNames();
                    while (it.hasNext()) {
                        String idx = it.next();
                        JsonNode resArray = results.path(idx).path("results");
                        if (resArray.isArray()) {
                            for (JsonNode r : resArray) {
                                JsonNode hits = r.path("hits");
                                if (hits.isArray()) {
                                    for (JsonNode h : hits) {
                                        String link = h.path("url").asText("");
                                        if (link.isEmpty()) link = h.path("categoryUrl").asText("");
                                        if (link.isEmpty()) link = h.path("link").asText("");

                                        if (!link.isEmpty() && link.contains("/productos/")) {
                                            int idxProd = link.indexOf("/productos/") + "/productos/".length();
                                            String slugHijo = link.substring(idxProd);
                                            if (slugHijo.endsWith(".html")) {
                                                slugHijo = slugHijo.substring(0, slugHijo.length() - 5);
                                            }
                                            if (slugHijo.startsWith("/")) {
                                                slugHijo = slugHijo.substring(1);
                                            }
                                            if (!slugHijo.isBlank() && !rutasVisitadas.contains(slugHijo)) {
                                                colaExploracion.add(slugHijo);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                List<ItemIngestaDto> lote = new ArrayList<>();
                if (!results.isMissingNode() && results.isObject()) {
                    Iterator<String> it = results.fieldNames();
                    while (it.hasNext()) {
                        String idx = it.next();
                        JsonNode resArray = results.path(idx).path("results");
                        if (resArray.isArray()) {
                            for (JsonNode r : resArray) {
                                JsonNode hits = r.path("hits");
                                if (hits.isArray()) {
                                    for (JsonNode h : hits) {
                                        double precio = h.path("currentPrice").path("priceValue").asDouble(0.0);
                                        if (precio <= 0) continue;

                                        String name = h.path("name").asText("");
                                        String brand = h.path("brandName").asText("").replaceAll("[®™]", "").trim();
                                        String displayName = brand.isEmpty() ? name : name + " " + brand;
                                        String sku = h.path("objectID").asText("");
                                        String unit = h.path("salesUnit").asText("Ud");
                                        String termino = rutaActual.contains("/") ? rutaActual.substring(rutaActual.lastIndexOf("/") + 1) : rutaActual;

                                        ItemIngestaDto dto = new ItemIngestaDto();
                                        dto.setSku_id(sku);
                                        dto.setDisplay_name(displayName);
                                        dto.setPrecio(BigDecimal.valueOf(precio));
                                        dto.setMeasure_unit(unit);
                                        dto.setTermino_busqueda(termino);
                                        lote.add(dto);
                                    }
                                }
                            }
                        }
                    }
                }

                if (!lote.isEmpty()) {
                    procesarLoteSupermercado("ALDI", lote);
                    totalProcesados += lote.size();
                    categoriasConProductos++;
                    log.info("✔ ALDI [{}]: +{} productos", rutaActual, lote.size());
                }

                Thread.sleep(60);

            } catch (Exception e) {
                log.warn("Aviso procesando rama ALDI {}: {}", rutaActual, e.getMessage());
            }
        }

        long totalEnCache = precioCacheRepo.count();
        log.info("🎉 Sincronización ALDI finalizada. Categorías indexadas: {}, Productos procesados: {} (Total BD: {})",
                categoriasConProductos, totalProcesados, totalEnCache);

        return ResponseEntity.ok(Map.of(
                "supermercado", "ALDI",
                "procesados", totalProcesados,
                "totalEnCache", totalEnCache
        ));
    }
}
