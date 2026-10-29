package com.miempresa.fasi.compra.comparador;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class ComparadorPreciosService {

    private static final Logger log = LoggerFactory.getLogger(ComparadorPreciosService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // Mapa de categorías reales de Mercadona
    private static final Map<String, Integer> MERCADONA_CATEGORIAS = new HashMap<>();

    static {
        MERCADONA_CATEGORIAS.put("harina", 69);
        MERCADONA_CATEGORIAS.put("leche", 72);
        MERCADONA_CATEGORIAS.put("huevos", 77);
        MERCADONA_CATEGORIAS.put("azucar", 89);
        MERCADONA_CATEGORIAS.put("aceite", 112);
        MERCADONA_CATEGORIAS.put("arroz", 118);
        MERCADONA_CATEGORIAS.put("pasta", 119);
        MERCADONA_CATEGORIAS.put("cafe", 94);
        MERCADONA_CATEGORIAS.put("papel higienico", 238);
        MERCADONA_CATEGORIAS.put("detergente", 233);
        MERCADONA_CATEGORIAS.put("tomate frito", 110);
        MERCADONA_CATEGORIAS.put("atun", 113);
        MERCADONA_CATEGORIAS.put("pan de molde", 60);
        MERCADONA_CATEGORIAS.put("galletas", 84);
        MERCADONA_CATEGORIAS.put("patatas", 28);
        MERCADONA_CATEGORIAS.put("yogur", 75);
    }

    public ComparadorPreciosService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<ComparativaSupermercadoDTO> compararCesta(List<String> articulos) {
        if (articulos == null || articulos.isEmpty()) {
            return Collections.emptyList();
        }

        log.info("🛒 Iniciando comparativa para {} artículos: {}", articulos.size(), articulos);

        ComparativaSupermercadoDTO mercadonaDTO = new ComparativaSupermercadoDTO("Mercadona");
        ComparativaSupermercadoDTO diaDTO = new ComparativaSupermercadoDTO("Dia");
        ComparativaSupermercadoDTO carrefourDTO = new ComparativaSupermercadoDTO("Carrefour");

        for (String art : articulos) {
            String artLimpio = art.trim();

            // 1. Mercadona (EN VIVO)
            ProductoPrecioDTO pMercadona = obtenerPrecioConCache("Mercadona", artLimpio, this::consultarMercadonaEnVivo);
            if (pMercadona != null) mercadonaDTO.getProductos().add(pMercadona);

            // 2. Dia (EN VIVO vía API reducida)
            ProductoPrecioDTO pDia = obtenerPrecioConCache("Dia", artLimpio, this::consultarDiaEnVivo);
            if (pDia != null) diaDTO.getProductos().add(pDia);

            // 3. Carrefour (Pendiente de endpoint verificado: no mostramos ficticios)
            ProductoPrecioDTO pCarrefour = obtenerPrecioConCache("Carrefour", artLimpio, (term) -> null);
            if (pCarrefour != null) carrefourDTO.getProductos().add(pCarrefour);
        }

        List<ComparativaSupermercadoDTO> resultado = new ArrayList<>();
        resultado.add(mercadonaDTO);
        resultado.add(diaDTO);
        resultado.add(carrefourDTO);

        marcarMasBarato(resultado);
        return resultado;
    }

    // ==========================================
    // 🟡 DIA EN VIVO (search/reduced)
    // ==========================================
    private ProductoPrecioDTO consultarDiaEnVivo(String termino) {
        try {
            String queryLimpia = termino.toLowerCase()
                    .replace("de", "")
                    .replace("la", "")
                    .replace("el", "")
                    .replaceAll("\\s+", " ")
                    .trim();

            String encodedQuery = URLEncoder.encode(queryLimpia, StandardCharsets.UTF_8);
            String url = "https://www.dia.es/api/v1/search-back/search/reduced?q=" + encodedQuery + "&page=1";

            log.info("🎯 [Dia] Buscando término '{}' en URL: {}", queryLimpia, url);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "application/json")
                    .header("Accept-Language", "es-ES,es;q=0.9")
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode items = root.path("search_items");

                if (items.isArray() && items.size() > 0) {
                    JsonNode seleccionado = null;
                    String[] palabrasTermino = queryLimpia.split("\\s+");

                    // 1. Priorizar producto de marca blanca Dia que contenga los términos
                    for (JsonNode item : items) {
                        String dName = item.path("display_name").asText("").toLowerCase();
                        boolean diaBrand = item.path("dia_brand").asBoolean(false);
                        boolean contieneTodas = Arrays.stream(palabrasTermino).allMatch(dName::contains);

                        if (contieneTodas && diaBrand) {
                            seleccionado = item;
                            break;
                        }
                    }

                    // 2. Si no hay marca blanca exacta, tomar el primero que contenga los términos
                    if (seleccionado == null) {
                        for (JsonNode item : items) {
                            String dName = item.path("display_name").asText("").toLowerCase();
                            if (Arrays.stream(palabrasTermino).allMatch(dName::contains)) {
                                seleccionado = item;
                                break;
                            }
                        }
                    }

                    // 3. Fallback al primer resultado de la búsqueda
                    if (seleccionado == null) {
                        seleccionado = items.get(0);
                    }

                    String nombre = seleccionado.path("display_name").asText();
                    double precio = seleccionado.path("prices").path("price").asDouble(0.0);
                    String unidad = seleccionado.path("prices").path("measure_unit").asText("Ud");

                    if (precio > 0.0) {
                        log.info("🔥 [Dia EN VIVO] '{}' -> {} € ({})", nombre, precio, unidad);
                        return new ProductoPrecioDTO(termino, nombre, precio, unidad, true);
                    }
                }
            } else {
                log.warn("⚠️ [Dia] Respuesta HTTP {}", response.statusCode());
            }
        } catch (Exception e) {
            log.error("❌ [Dia] Error al consultar API en vivo para '{}': {}", termino, e.getMessage());
        }
        return null;
    }

    // ==========================================
    // 🟢 MERCADONA EN VIVO
    // ==========================================
    private ProductoPrecioDTO consultarMercadonaEnVivo(String termino) {
        try {
            Integer catId = resolverCategoriaMercadona(termino);
            if (catId == null) {
                log.info("ℹ️ [Mercadona] No hay mapeo de categoría para '{}'", termino);
                return null;
            }

            String url = "https://tienda.mercadona.es/api/v1_1/categories/" + catId + "/";
            log.info("🎯 [Mercadona] Consultando categoría ID {} para '{}'...", catId, termino);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode categories = root.path("categories");

                List<JsonNode> todosLosProductos = new ArrayList<>();
                if (categories.isArray()) {
                    for (JsonNode subcat : categories) {
                        JsonNode products = subcat.path("products");
                        if (products.isArray()) {
                            for (JsonNode prod : products) {
                                todosLosProductos.add(prod);
                            }
                        }
                    }
                }

                log.info("📦 [Mercadona] {} productos procesados en la categoría {}", todosLosProductos.size(), catId);

                String termNorm = normalizarTexto(termino);
                JsonNode mejorCoincidencia = null;

                for (JsonNode p : todosLosProductos) {
                    String dName = normalizarTexto(p.path("display_name").asText());
                    if (dName.contains(termNorm) || termNorm.contains(dName)) {
                        mejorCoincidencia = p;
                        break;
                    }
                }

                if (mejorCoincidencia == null && !todosLosProductos.isEmpty()) {
                    mejorCoincidencia = todosLosProductos.get(0);
                }

                if (mejorCoincidencia != null) {
                    String nombre = mejorCoincidencia.path("display_name").asText();
                    double precio = mejorCoincidencia.path("price_instructions").path("unit_price").asDouble();
                    String formato = mejorCoincidencia.path("packaging").asText("Unidad");

                    if (precio > 0.0) {
                        log.info("🔥 [Mercadona EN VIVO] '{}' -> {} € ({})", nombre, precio, formato);
                        return new ProductoPrecioDTO(termino, nombre, precio, formato, true);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ [Mercadona] Error al consultar API en vivo: {}", e.getMessage());
        }
        return null;
    }

    // ==========================================
    // ⚡ GESTIÓN DE CACHÉ POSTGRESQL (24h)
    // ==========================================
    private ProductoPrecioDTO obtenerPrecioConCache(String supermercado, String termino, BuscadorEnVivo buscador) {
        String sql = """
            SELECT nombre_producto, precio, formato, updated_at
            FROM precios_cache
            WHERE supermercado = ? AND LOWER(termino_busqueda) = LOWER(?)
            AND updated_at > NOW() - INTERVAL '24 hours'
            LIMIT 1
        """;

        try {
            List<ProductoPrecioDTO> cache = jdbcTemplate.query(sql, (rs, rowNum) -> new ProductoPrecioDTO(
                    termino,
                    rs.getString("nombre_producto"),
                    rs.getDouble("precio"),
                    rs.getString("formato"),
                    true
            ), supermercado, termino);

            if (!cache.isEmpty()) {
                ProductoPrecioDTO p = cache.get(0);
                log.info("⚡ [Caché {}] '{}' -> {} ({} €)", supermercado, termino, p.getNombre(), p.getPrecio());
                return p;
            }
        } catch (Exception e) {
            log.warn("⚠️ No se pudo consultar la caché para {} - {}: {}", supermercado, termino, e.getMessage());
        }

        // Si no está en caché, llama al buscador en vivo
        ProductoPrecioDTO obtenido = buscador.buscar(termino);

        if (obtenido != null && obtenido.getPrecio() > 0.0) {
            guardarEnCache(supermercado, termino, obtenido);
        }

        return obtenido;
    }

    private void guardarEnCache(String supermercado, String termino, ProductoPrecioDTO prod) {
        String sql = """
            INSERT INTO precios_cache (supermercado, termino_busqueda, nombre_producto, precio, formato, updated_at)
            VALUES (?, LOWER(?), ?, ?, ?, NOW())
            ON CONFLICT (supermercado, termino_busqueda)
            DO UPDATE SET
                nombre_producto = EXCLUDED.nombre_producto,
                precio = EXCLUDED.precio,
                formato = EXCLUDED.formato,
                updated_at = NOW();
        """;
        try {
            jdbcTemplate.update(sql, supermercado, termino, prod.getNombre(), prod.getPrecio(), prod.getFormato());
        } catch (Exception e) {
            log.warn("⚠️ No se pudo guardar en caché {} - {}: {}", supermercado, termino, e.getMessage());
        }
    }

    private Integer resolverCategoriaMercadona(String termino) {
        String t = normalizarTexto(termino);
        for (Map.Entry<String, Integer> entry : MERCADONA_CATEGORIAS.entrySet()) {
            if (t.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String normalizarTexto(String texto) {
        if (texto == null) return "";
        return texto.toLowerCase()
                .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")
                .trim();
    }

    private void marcarMasBarato(List<ComparativaSupermercadoDTO> resultado) {
        ComparativaSupermercadoDTO mejor = null;
        double minTotal = Double.MAX_VALUE;

        for (ComparativaSupermercadoDTO s : resultado) {
            double total = s.getTotal();
            if (total > 0 && total < minTotal) {
                minTotal = total;
                mejor = s;
            }
        }

        if (mejor != null) {
            mejor.setMasBarato(true);
        }
    }

    @FunctionalInterface
    private interface BuscadorEnVivo {
        ProductoPrecioDTO buscar(String termino);
    }
}
