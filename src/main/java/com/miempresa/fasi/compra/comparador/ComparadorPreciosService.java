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

            // 1. Mirar si está en caché
            Optional<PrecioCache> cache = cacheRepo.buscarPrecioReciente(s.getId(), termino, limiteCache);
            if (cache.isPresent()) {
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

            // 2. Si no, consultar online
            ResultadoComparativaDto.ItemComparadoDto prod = consultarPrecioEnSupermercado(s, termino);
            items.add(prod);

            if (prod.isEncontrado() && prod.getPrecio() != null) {
                total = total.add(prod.getPrecio());
                encontrados++;

                PrecioCache nuevo = PrecioCache.builder()
                        .supermercado(s)
                        .terminoBusqueda(termino.toLowerCase())
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

    private ResultadoComparativaDto.ItemComparadoDto consultarPrecioEnSupermercado(Supermercado s, String terminoOriginal) {
        String keywords = simplificarParaBusqueda(terminoOriginal);
        log.info("🔎 [{}] Buscando '{}' (simplificado de '{}')...", s.getNombre(), keywords, terminoOriginal);

        try {
            return switch (s.getCodigoInterno().toUpperCase()) {
                case "MERCADONA" -> buscarEnMercadona(terminoOriginal, keywords, s.getCodigoPostal());
                case "DIA"       -> buscarEnDia(terminoOriginal, keywords, s.getCodigoPostal());
                default          -> buscarFallback(s.getNombre(), terminoOriginal);
            };
        } catch (Exception e) {
            log.error("❌ [{}] Error buscando '{}': {}", s.getNombre(), terminoOriginal, e.getMessage());
            return buscarFallback(s.getNombre(), terminoOriginal);
        }
    }

    /**
     * Limpia frases como "detergente de lavadora norit complet" a palabras clave clave: "norit lavadora"
     */
    private String simplificarParaBusqueda(String texto) {
        String limpio = Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9\\s]", " ");

        List<String> palabras = Arrays.stream(limpio.split("\\s+"))
                .filter(p -> p.length() >= 3 && !STOPWORDS.contains(p))
                .toList();

        if (palabras.isEmpty()) return texto.trim();
        // Si hay una marca reconocida como norit, ariel, fairy, hacendado, etc., priorizarla
        return String.join(" ", palabras.subList(0, Math.min(3, palabras.size())));
    }

    private ResultadoComparativaDto.ItemComparadoDto buscarEnMercadona(String original, String query, String postal) {
        try {
            String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://tienda.mercadona.es/api/v1_1/search/?q=" + q;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "es-ES,es;q=0.9")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("📡 [Mercadona API] HTTP Status: {} para query='{}'", resp.statusCode(), query);

            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                JsonNode results = root.path("results");
                if (results.isArray() && results.size() > 0) {
                    JsonNode p = results.get(0);
                    String display = p.path("display_name").asText();
                    String formato = p.path("packaging").asText("");
                    
                    JsonNode priceInst = p.path("price_instructions");
                    double precio = priceInst.path("unit_price").asDouble(0.0);
                    if (precio == 0.0) precio = priceInst.path("bulk_price").asDouble(0.0);

                    log.info("🎯 [Mercadona] ¡Coincidencia! '{}' -> {} € ({})", display, precio, formato);

                    return ResultadoComparativaDto.ItemComparadoDto.builder()
                            .terminoBuscado(original)
                            .productoEncontrado(display)
                            .precio(BigDecimal.valueOf(precio).setScale(2, RoundingMode.HALF_UP))
                            .formato(formato)
                            .encontrado(true)
                            .build();
                } else {
                    log.warn("⚠️ [Mercadona] 0 productos encontrados para query='{}'", query);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [Mercadona] Falló petición: {}", e.getMessage());
        }
        return buscarFallback("Mercadona", original);
    }

    private ResultadoComparativaDto.ItemComparadoDto buscarEnDia(String original, String query, String postal) {
        try {
            String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.dia.es/api/v1/search-products?q=" + q;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "application/json")
                    .header("Accept-Language", "es-ES,es;q=0.9")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            log.info("📡 [Dia API] HTTP Status: {} para query='{}'", resp.statusCode(), query);

            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                JsonNode items = root.path("items");
                if (items.isArray() && items.size() > 0) {
                    JsonNode prod = items.get(0);
                    String nombre = prod.path("name").asText();
                    double precio = prod.path("prices").path("price").asDouble(0.0);

                    log.info("🎯 [Dia] ¡Coincidencia! '{}' -> {} €", nombre, precio);

                    return ResultadoComparativaDto.ItemComparadoDto.builder()
                            .terminoBuscado(original)
                            .productoEncontrado(nombre)
                            .precio(BigDecimal.valueOf(precio).setScale(2, RoundingMode.HALF_UP))
                            .formato("Ud")
                            .encontrado(true)
                            .build();
                } else {
                    log.warn("⚠️ [Dia] 0 productos encontrados para query='{}'", query);
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [Dia] Falló petición: {}", e.getMessage());
        }
        return buscarFallback("Dia", original);
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
