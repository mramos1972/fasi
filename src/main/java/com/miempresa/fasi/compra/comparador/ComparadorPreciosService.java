package com.miempresa.fasi.compra.comparador;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComparadorPreciosService {

    private final SupermercadoRepository supermercadoRepo;
    private final PrecioCacheRepository cacheRepo;
    private final ObjectMapper mapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final int HORAS_VALIDEZ_CACHE = 48;
    private final Map<String, String> almacenPorCodigoPostal = new ConcurrentHashMap<>();

    private static final Set<String> STOPWORDS = Set.of(
            "de", "del", "la", "el", "los", "las", "en", "un", "una", "unos", "unas", "para", "con", "y", "o"
    );

    @PostConstruct
    public void precargarCestaBasicaSiFalta() {
        try {
            Optional<Supermercado> carrefour = supermercadoRepo.findByCodigoInterno("CARREFOUR");
            Optional<Supermercado> dia       = supermercadoRepo.findByCodigoInterno("DIA");
            Optional<Supermercado> mercadona = supermercadoRepo.findByCodigoInterno("MERCADONA");

            if (carrefour.isEmpty() || dia.isEmpty() || mercadona.isEmpty()) return;

            // Asegurar que Carrefour y Dia tengan datos semilla para contrastar con Mercadona
            List<Object[]> semillas = List.of(
                new Object[]{"harina trigo", "Harina de trigo clásica", 0.79, 0.85, 0.78, "1 kg"},
                new Object[]{"harina", "Harina tradicional", 0.82, 0.89, 0.80, "1 kg"},
                new Object[]{"leche", "Leche entera", 0.95, 0.98, 0.93, "1 L"},
                new Object[]{"leche semidesnatada", "Leche semidesnatada", 0.95, 0.97, 0.93, "1 L"},
                new Object[]{"huevos", "Huevos clase L (docena)", 2.45, 2.55, 2.39, "12 uds"},
                new Object[]{"aceite oliva", "Aceite de oliva virgen extra", 7.95, 8.20, 7.85, "1 L"},
                new Object[]{"aceite girasol", "Aceite de girasol", 1.45, 1.49, 1.42, "1 L"},
                new Object[]{"arroz", "Arroz redondo", 1.30, 1.35, 1.25, "1 kg"},
                new Object[]{"detergente", "Detergente lavadora líquido", 4.85, 5.20, 4.75, "Botella"},
                new Object[]{"norit", "Norit lavado a máquina", 5.20, 5.45, 4.99, "1.5 L"},
                new Object[]{"detergente norit", "Norit Ropa Completa", 5.20, 5.45, 4.99, "1.5 L"},
                new Object[]{"pan", "Barra de pan", 0.55, 0.60, 0.50, "250 g"},
                new Object[]{"azucar", "Azúcar blanco", 1.15, 1.20, 1.10, "1 kg"},
                new Object[]{"pasta", "Macarrones pluma", 0.90, 0.95, 0.88, "1 kg"},
                new Object[]{"tomate frito", "Tomate frito casero", 1.10, 1.15, 1.05, "350 g"},
                new Object[]{"cafe", "Café molido natural", 2.60, 2.75, 2.50, "250 g"}
            );

            for (Object[] row : semillas) {
                String term = (String) row[0];
                String nom  = (String) row[1];
                double pMer = (Double) row[2];
                double pCar = (Double) row[3];
                double pDia = (Double) row[4];
                String fmt  = (String) row[5];

                asegurarSemilla(mercadona.get(), term, nom + " Hacendado", pMer, fmt);
                asegurarSemilla(carrefour.get(), term, nom + " Carrefour Classic", pCar, fmt);
                asegurarSemilla(dia.get(),       term, nom + " Dia Al Punto", pDia, fmt);
            }
            log.info("✅ Catálogo de referencia verificado y sincronizado.");
        } catch (Exception e) {
            log.warn("⚠️ No se pudo verificar catálogo semilla: {}", e.getMessage());
        }
    }

    private void asegurarSemilla(Supermercado s, String termino, String nombre, double precio, String fmt) {
        LocalDateTime limite = LocalDateTime.now().minusHours(HORAS_VALIDEZ_CACHE);
        if (cacheRepo.buscarPrecioReciente(s.getId(), termino, limite).isEmpty()) {
            PrecioCache p = PrecioCache.builder()
                    .supermercado(s)
                    .terminoBusqueda(termino.toLowerCase())
                    .productoNombre(nombre)
                    .precio(BigDecimal.valueOf(precio).setScale(2, RoundingMode.HALF_UP))
                    .formatoUnidad(fmt)
                    .actualizadoEl(LocalDateTime.now())
                    .build();
            cacheRepo.save(p);
        }
    }

    @Transactional
    public List<ResultadoComparativaDto> compararLista(List<String> articulos) {
        log.info("🛒 Iniciando comparativa para {} artículos: {}", articulos.size(), articulos);
        List<Supermercado> supers = supermercadoRepo.findByActivoTrue();
        List<ResultadoComparativaDto> comparativas = new ArrayList<>();

        for (Supermercado s : supers) {
            ResultadoComparativaDto res = compararEnSupermercado(s, articulos);
            comparativas.add(res);
        }

        comparativas.sort((a, b) -> {
            if (a.getProductosEncontrados() != b.getProductosEncontrados()) {
                return Integer.compare(b.getProductosEncontrados(), a.getProductosEncontrados());
            }
            return a.getTotalEstimado().compareTo(b.getTotalEstimado());
        });

        return comparativas;
    }

    private ResultadoComparativaDto compararEnSupermercado(Supermercado s, List<String> articulos) {
        BigDecimal total = BigDecimal.ZERO;
        List<ResultadoComparativaDto.ItemComparadoDto> items = new ArrayList<>();
        int encontrados = 0;
        int noEncontrados = 0;

        LocalDateTime limiteCache = LocalDateTime.now().minusHours(HORAS_VALIDEZ_CACHE);

        for (String art : articulos) {
            String termino = art.trim();
            if (termino.isBlank()) continue;

            String terminoLimpio = limpiarTerminoParaBusqueda(termino);

            // 1. Si es Mercadona, intentamos SIEMPRE consultar su API en vivo primero para precio exacto hoy
            ResultadoComparativaDto.ItemComparadoDto prod = null;
            if ("MERCADONA".equalsIgnoreCase(s.getCodigoInterno())) {
                prod = buscarEnMercadonaConAlmacen(art, terminoLimpio, s.getCodigoPostal());
            }

            // 2. Si no es Mercadona o la API no devolvió resultado, buscar en BD local
            if (prod == null || !prod.isEncontrado()) {
                Optional<PrecioCache> cache = cacheRepo.buscarPrecioReciente(s.getId(), terminoLimpio, limiteCache);
                if (cache.isEmpty()) {
                    String primeraPalabra = terminoLimpio.split("\\s+")[0];
                    cache = cacheRepo.buscarPrecioReciente(s.getId(), primeraPalabra, limiteCache);
                }

                if (cache.isPresent()) {
                    PrecioCache p = cache.get();
                    log.info("⚡ [Catálogo {}] '{}' -> {} ({} €)", s.getNombre(), termino, p.getProductoNombre(), p.getPrecio());
                    prod = ResultadoComparativaDto.ItemComparadoDto.builder()
                            .terminoBuscado(art)
                            .productoEncontrado(p.getProductoNombre())
                            .precio(p.getPrecio())
                            .formato(p.getFormatoUnidad())
                            .encontrado(true)
                            .build();
                }
            }

            // 3. Si aún así no se encuentra, marcar como no disponible
            if (prod == null) {
                prod = buscarFallback(s.getNombre(), art);
            }

            items.add(prod);

            if (prod.isEncontrado() && prod.getPrecio() != null) {
                total = total.add(prod.getPrecio());
                encontrados++;

                // Si vino de la API remota, guardarlo en caché
                if ("MERCADONA".equalsIgnoreCase(s.getCodigoInterno())) {
                    PrecioCache nuevo = PrecioCache.builder()
                            .supermercado(s)
                            .terminoBusqueda(terminoLimpio)
                            .productoNombre(prod.getProductoEncontrado())
                            .precio(prod.getPrecio())
                            .formatoUnidad(prod.getFormato())
                            .actualizadoEl(LocalDateTime.now())
                            .build();
                    cacheRepo.save(nuevo);
                }
            } else {
                noEncontrados++;
            }
        }

        return ResultadoComparativaDto.builder()
                .supermercado(s.getNombre())
                .codigoPostal(s.getCodigoPostal())
                .totalEstimado(total.setScale(2, RoundingMode.HALF_UP))
                .productos(items)
                .productosEncontrados(encontrados)
                .productosSinPrecio(noEncontrados)
                .build();
    }

    /**
     * Búsqueda en Mercadona con extracción exhaustiva de productos en árbol JSON.
     */
    private ResultadoComparativaDto.ItemComparadoDto buscarEnMercadonaConAlmacen(String original, String query, String postal) {
        try {
            String cp = (postal != null && !postal.isBlank()) ? postal : "11540";
            String warehouseId = almacenPorCodigoPostal.computeIfAbsent(cp, this::resolverAlmacenMercadona);

            String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://tienda.mercadona.es/api/v1_1/categories/?search=" + q;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "application/json")
                    .header("Accept-Language", "es-ES,es;q=0.9")
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                
                // Extraer el primer producto buscando recursivamente en la estructura
                JsonNode producto = encontrarPrimerProducto(root);
                if (producto != null) {
                    String display = producto.path("display_name").asText();
                    String formato = producto.path("packaging").asText("Ud");
                    
                    JsonNode priceInst = producto.path("price_instructions");
                    double precio = priceInst.path("unit_price").asDouble(0.0);
                    if (precio == 0.0) precio = priceInst.path("bulk_price").asDouble(0.0);

                    if (precio > 0.0) {
                        log.info("🎯 [Mercadona EN VIVO] Coincidencia: '{}' -> {} € ({})", display, precio, formato);
                        return ResultadoComparativaDto.ItemComparadoDto.builder()
                                .terminoBuscado(original)
                                .productoEncontrado(display)
                                .precio(BigDecimal.valueOf(precio).setScale(2, RoundingMode.HALF_UP))
                                .formato(formato)
                                .encontrado(true)
                                .build();
                    }
                } else {
                    log.info("ℹ️ [Mercadona API] 200 OK pero sin productos para query='{}'", query);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [Mercadona] Error parseando respuesta: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Recorre el árbol JSON de Mercadona buscando el primer objeto con 'display_name' y 'price_instructions'
     */
    private JsonNode encontrarPrimerProducto(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;

        if (node.has("display_name") && node.has("price_instructions")) {
            return node;
        }

        if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = encontrarPrimerProducto(child);
                if (found != null) return found;
            }
        } else if (node.isObject()) {
            // Inspeccionar campos habituales primero
            if (node.has("products")) {
                JsonNode found = encontrarPrimerProducto(node.get("products"));
                if (found != null) return found;
            }
            if (node.has("categories")) {
                JsonNode found = encontrarPrimerProducto(node.get("categories"));
                if (found != null) return found;
            }
            if (node.has("results")) {
                JsonNode found = encontrarPrimerProducto(node.get("results"));
                if (found != null) return found;
            }

            Iterator<JsonNode> it = node.elements();
            while (it.hasNext()) {
                JsonNode found = encontrarPrimerProducto(it.next());
                if (found != null) return found;
            }
        }
        return null;
    }

    private String resolverAlmacenMercadona(String cp) {
        try {
            String url = "https://tienda.mercadona.es/api/v1_1/postal-codes/" + cp + "/";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(Duration.ofSeconds(3))
                    .PUT(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode n = mapper.readTree(resp.body());
                return n.path("warehouse").asText("");
            }
        } catch (Exception ignored) {}
        return "cadiz_1";
    }

    private String limpiarTerminoParaBusqueda(String texto) {
        String s = Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[,.;:_-]", " ")
                .replaceAll("\\b\\d+\\s*(kg|g|gr|gramos|kilos|l|litros|ml|cl|uds|unidades|pack)\\b", " ")
                .replaceAll("[^a-z0-9\\s]", " ");

        List<String> palabras = Arrays.stream(s.split("\\s+"))
                .filter(p -> p.length() >= 2 && !STOPWORDS.contains(p) && !p.matches("\\d+"))
                .toList();

        if (palabras.isEmpty()) return texto.trim().toLowerCase();
        return String.join(" ", palabras.subList(0, Math.min(3, palabras.size())));
    }

    private ResultadoComparativaDto.ItemComparadoDto buscarFallback(String superNombre, String original) {
        return ResultadoComparativaDto.ItemComparadoDto.builder()
                .terminoBuscado(original)
                .productoEncontrado(original + " (no localizado en " + superNombre + ")")
                .precio(null)
                .formato(null)
                .encontrado(false)
                .build();
    }
}
