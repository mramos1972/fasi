package com.miempresa.fasi.compra.comparador;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final int HORAS_VALIDEZ_CACHE = 24;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final Map<String, Integer> cacheCategoriasMercadona = new ConcurrentHashMap<>();

    private static final Set<String> STOPWORDS = Set.of(
            "de", "del", "la", "el", "los", "las", "en", "un", "una", "unos", "unas", "para", "con", "y", "o"
    );

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

            // 1. Mirar si está en la caché local reciente
            Optional<PrecioCache> cache = cacheRepo.buscarPrecioReciente(s.getId(), terminoLimpio, limiteCache);
            if (cache.isPresent() && cache.get().getPrecio() != null) {
                PrecioCache p = cache.get();
                log.info("⚡ [Caché {}] '{}' -> {} ({} €)", s.getNombre(), termino, p.getProductoNombre(), p.getPrecio());
                items.add(ResultadoComparativaDto.ItemComparadoDto.builder()
                        .terminoBuscado(art)
                        .productoEncontrado(p.getProductoNombre())
                        .precio(p.getPrecio())
                        .formato(p.getFormatoUnidad())
                        .encontrado(true)
                        .build());
                total = total.add(p.getPrecio());
                encontrados++;
                continue;
            }

            // 2. Consultar conector en tiempo real
            ResultadoComparativaDto.ItemComparadoDto prod = consultarPrecioEnSupermercado(s, art, terminoLimpio);
            items.add(prod);

            if (prod.isEncontrado() && prod.getPrecio() != null) {
                total = total.add(prod.getPrecio());
                encontrados++;

                PrecioCache nuevo = PrecioCache.builder()
                        .supermercado(s)
                        .terminoBusqueda(terminoLimpio)
                        .productoNombre(prod.getProductoEncontrado())
                        .precio(prod.getPrecio())
                        .formatoUnidad(prod.getFormato())
                        .actualizadoEl(LocalDateTime.now())
                        .build();
                cacheRepo.save(nuevo);
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

    private ResultadoComparativaDto.ItemComparadoDto consultarPrecioEnSupermercado(Supermercado s, String original, String query) {
        try {
            String codigo = s.getCodigoInterno().toUpperCase();
            if ("MERCADONA".equals(codigo)) {
                return buscarEnMercadonaPorCategoria(original, query);
            } else if ("ALCAMPO".equals(codigo)) {
                return buscarEnAlcampo(original, query);
            }
        } catch (Exception e) {
            log.warn("⚠️ [{}] Error en consulta: {}", s.getNombre(), e.getMessage());
        }

        return buscarFallback(s.getNombre(), original);
    }

    /**
     * Búsqueda en Mercadona: Descarga de subcategoría y parseo de categories[].products[]
     */
    private ResultadoComparativaDto.ItemComparadoDto buscarEnMercadonaPorCategoria(String original, String query) {
        try {
            Integer categoriaId = resolverIdCategoriaMercadona(query);
            if (categoriaId == null) {
                log.info("ℹ️ [Mercadona] No se detectó categoría afín para '{}'", query);
                return buscarFallback("Mercadona", original);
            }

            log.info("🎯 [Mercadona] Consultando categoría ID {} para '{}'...", categoriaId, query);
            String url = "https://tienda.mercadona.es/api/v1_1/categories/" + categoriaId + "/";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                List<JsonNode> productos = new ArrayList<>();
                extraerProductosMercadona(root, productos);

                log.info("📦 [Mercadona] {} productos procesados en la categoría {}", productos.size(), categoriaId);

                String qNorm = normalizar(query);

                // 1. Coincidencia por coincidencia de texto
                for (JsonNode p : productos) {
                    String nombre = p.path("display_name").asText();
                    String nombreNorm = normalizar(nombre);

                    if (nombreNorm.contains(qNorm) || qNorm.contains(nombreNorm)) {
                        return construirItemMercadona(original, p);
                    }
                }

                // 2. Si no coincide el nombre completo, devolver el primer producto del listado
                if (!productos.isEmpty()) {
                    return construirItemMercadona(original, productos.get(0));
                }
            }
        } catch (Exception e) {
            log.error("❌ [Mercadona] Excepción al procesar categoría: {}", e.getMessage());
        }

        return buscarFallback("Mercadona", original);
    }

    private void extraerProductosMercadona(JsonNode root, List<JsonNode> productos) {
        JsonNode subCats = root.path("categories");
        if (subCats.isArray()) {
            for (JsonNode sub : subCats) {
                JsonNode prods = sub.path("products");
                if (prods.isArray()) {
                    for (JsonNode p : prods) {
                        productos.add(p);
                    }
                }
            }
        }
    }

    private ResultadoComparativaDto.ItemComparadoDto construirItemMercadona(String original, JsonNode p) {
        String display = p.path("display_name").asText();
        String formato = p.path("packaging").asText("Ud");

        JsonNode priceInst = p.path("price_instructions");
        double precio = priceInst.path("unit_price").asDouble(0.0);
        if (precio == 0.0) precio = priceInst.path("bulk_price").asDouble(0.0);

        log.info("🔥 [Mercadona EN VIVO] '{}' -> {} € ({})", display, precio, formato);

        return ResultadoComparativaDto.ItemComparadoDto.builder()
                .terminoBuscado(original)
                .productoEncontrado(display)
                .precio(BigDecimal.valueOf(precio).setScale(2, RoundingMode.HALF_UP))
                .formato(formato)
                .encontrado(true)
                .build();
    }

    private Integer resolverIdCategoriaMercadona(String query) {
        String q = normalizar(query);

        if (q.contains("harina")) return 69;   // Harina y preparado repostería
        if (q.contains("leche")) return 72;    // Leche y bebidas vegetales
        if (q.contains("huevo")) return 77;    // Huevos
        if (q.contains("aceite")) return 112;  // Aceite, vinagre y sal
        if (q.contains("arroz")) return 118;   // Arroz
        if (q.contains("pasta") || q.contains("macarron") || q.contains("espagueti")) return 120; // Pasta
        if (q.contains("detergente") || q.contains("suavizante") || q.contains("norit")) return 226; // Detergente y suavizante ropa
        if (q.contains("pan")) return 59;      // Pan de horno
        if (q.contains("azucar")) return 89;    // Azúcar y edulcorante
        if (q.contains("cafe")) return 83;     // Café molido y en grano
        if (q.contains("papel")) return 238;   // Papel higiénico y celulosa
        if (q.contains("tomate")) return 126;  // Tomate

        return cacheCategoriasMercadona.computeIfAbsent(q, this::buscarEnArbolRemotoMercadona);
    }

    private Integer buscarEnArbolRemotoMercadona(String termino) {
        try {
            String url = "https://tienda.mercadona.es/api/v1_1/categories/";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                JsonNode results = root.path("results");
                for (JsonNode catMaestra : results) {
                    for (JsonNode sub : catMaestra.path("categories")) {
                        String subName = normalizar(sub.path("name").asText());
                        if (subName.contains(termino) || termino.contains(subName)) {
                            return sub.path("id").asInt();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Búsqueda en Alcampo: API de catálogo público
     */
    private ResultadoComparativaDto.ItemComparadoDto buscarEnAlcampo(String original, String query) {
        try {
            String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.compraonline.alcampo.es/api/v1/catalog/products?term=" + q + "&limit=5";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("📡 [Alcampo API] Status: {} para query='{}'", resp.statusCode(), query);

            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                JsonNode items = root.path("entities");
                if (items.isArray() && items.size() > 0) {
                    JsonNode p = items.get(0);
                    String nombre = p.path("name").asText();
                    double precio = p.path("price").path("amount").asDouble(0.0);

                    if (precio > 0.0) {
                        log.info("🔥 [Alcampo EN VIVO] '{}' -> {} €", nombre, precio);
                        return ResultadoComparativaDto.ItemComparadoDto.builder()
                                .terminoBuscado(original)
                                .productoEncontrado(nombre)
                                .precio(BigDecimal.valueOf(precio).setScale(2, RoundingMode.HALF_UP))
                                .formato("Unidad")
                                .encontrado(true)
                                .build();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [Alcampo] Fallo en consulta: {}", e.getMessage());
        }

        return buscarFallback("Alcampo", original);
    }

    private String limpiarTerminoParaBusqueda(String texto) {
        String s = normalizar(texto)
                .replaceAll("[,.;:_-]", " ")
                .replaceAll("\\b\\d+\\s*(kg|g|gr|gramos|kilos|l|litros|ml|cl|uds|unidades|pack)\\b", " ")
                .replaceAll("[^a-z0-9\\s]", " ");

        List<String> palabras = Arrays.stream(s.split("\\s+"))
                .filter(p -> p.length() >= 2 && !STOPWORDS.contains(p) && !p.matches("\\d+"))
                .toList();

        if (palabras.isEmpty()) return texto.trim().toLowerCase();
        return String.join(" ", palabras.subList(0, Math.min(2, palabras.size())));
    }

    private String normalizar(String texto) {
        return Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .trim();
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
