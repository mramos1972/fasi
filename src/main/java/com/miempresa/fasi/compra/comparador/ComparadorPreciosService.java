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

    // Cache TTL: 24 horas
    private static final int HORAS_VALIDEZ_CACHE = 24;

    /**
     * Compara una lista de nombres de productos en todos los supermercados activos.
     */
    @Transactional
    public List<ResultadoComparativaDto> compararLista(List<String> articulos) {
        List<Supermercado> supers = supermercadoRepo.findByActivoTrue();
        List<ResultadoComparativaDto> comparativas = new ArrayList<>();

        for (Supermercado s : supers) {
            ResultadoComparativaDto res = compararEnSupermercado(s, articulos);
            comparativas.add(res);
        }

        // Ordenar del más barato al más caro (poniendo al final los que tienen menos items encontrados)
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
            String termino = art.trim().toLowerCase();
            if (termino.isBlank()) continue;

            // 1. Mirar si está en caché reciente
            Optional<PrecioCache> cache = cacheRepo.buscarPrecioReciente(s.getId(), termino, limiteCache);
            if (cache.isPresent()) {
                PrecioCache p = cache.get();
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

            // 2. Si no está en caché, consultar al conector
            ResultadoComparativaDto.ItemComparadoDto prod = consultarPrecioEnSupermercado(s, termino);
            items.add(prod);

            if (prod.isEncontrado() && prod.getPrecio() != null) {
                total = total.add(prod.getPrecio());
                encontrados++;

                // Guardar en caché
                PrecioCache nuevo = PrecioCache.builder()
                        .supermercado(s)
                        .terminoBusqueda(termino)
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

    private ResultadoComparativaDto.ItemComparadoDto consultarPrecioEnSupermercado(Supermercado s, String termino) {
        try {
            return switch (s.getCodigoInterno().toUpperCase()) {
                case "MERCADONA" -> buscarEnMercadona(termino, s.getCodigoPostal());
                case "DIA"       -> buscarEnDia(termino, s.getCodigoPostal());
                default          -> buscarFallback(s.getNombre(), termino);
            };
        } catch (Exception e) {
            log.warn("⚠️ Error consultando '{}' en {}: {}", termino, s.getNombre(), e.getMessage());
            return ResultadoComparativaDto.ItemComparadoDto.builder()
                    .terminoBuscado(termino)
                    .productoEncontrado(termino + " (no localizado)")
                    .precio(null)
                    .encontrado(false)
                    .build();
        }
    }

    /**
     * Conector directo a la API oficial de Mercadona.
     */
    private ResultadoComparativaDto.ItemComparadoDto buscarEnMercadona(String termino, String postal) {
        try {
            String q = URLEncoder.encode(termino, StandardCharsets.UTF_8);
            String url = "https://tienda.mercadona.es/api/v1_1/search/?q=" + q;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                JsonNode results = root.path("results");
                if (results.isArray() && results.size() > 0) {
                    JsonNode primerProd = results.get(0);
                    String display = primerProd.path("display_name").asText();
                    String formato = primerProd.path("packaging").asText("");
                    
                    JsonNode priceInst = primerProd.path("price_instructions");
                    double precioUnitario = priceInst.path("unit_price").asDouble(0.0);
                    if (precioUnitario == 0.0) {
                        precioUnitario = priceInst.path("bulk_price").asDouble(0.0);
                    }

                    if (precioUnitario > 0.0) {
                        return ResultadoComparativaDto.ItemComparadoDto.builder()
                                .terminoBuscado(termino)
                                .productoEncontrado(display)
                                .precio(BigDecimal.valueOf(precioUnitario).setScale(2, RoundingMode.HALF_UP))
                                .formato(formato)
                                .encontrado(true)
                                .build();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Mercadona query fallo: {}", e.getMessage());
        }

        return buscarFallback("Mercadona", termino);
    }

    /**
     * Conector a la API de búsqueda de Dia.
     */
    private ResultadoComparativaDto.ItemComparadoDto buscarEnDia(String termino, String postal) {
        try {
            String q = URLEncoder.encode(termino, StandardCharsets.UTF_8);
            String url = "https://www.dia.es/api/v1/search-products?q=" + q;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = mapper.readTree(resp.body());
                JsonNode items = root.path("items");
                if (items.isArray() && items.size() > 0) {
                    JsonNode prod = items.get(0);
                    String nombre = prod.path("name").asText();
                    double precio = prod.path("prices").path("price").asDouble(0.0);

                    if (precio > 0.0) {
                        return ResultadoComparativaDto.ItemComparadoDto.builder()
                                .terminoBuscado(termino)
                                .productoEncontrado(nombre)
                                .precio(BigDecimal.valueOf(precio).setScale(2, RoundingMode.HALF_UP))
                                .formato("Unidad")
                                .encontrado(true)
                                .build();
                    }
                }
            }
        } catch (Exception ignored) {}

        return buscarFallback("Dia", termino);
    }

    /**
     * Estimación de seguridad para artículos no resueltos por red.
     */
    private ResultadoComparativaDto.ItemComparadoDto buscarFallback(String superNombre, String termino) {
        return ResultadoComparativaDto.ItemComparadoDto.builder()
                .terminoBuscado(termino)
                .productoEncontrado(termino + " (precio no disponible en " + superNombre + ")")
                .precio(null)
                .formato(null)
                .encontrado(false)
                .build();
    }
}
