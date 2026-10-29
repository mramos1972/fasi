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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComparadorPreciosService {

    private final SupermercadoRepository supermercadoRepo;
    private final PrecioCacheRepository precioCacheRepo;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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

    @Transactional
    public List<ResultadoComparativaDto> compararLista(List<String> articulos) {
        if (articulos == null || articulos.isEmpty()) {
            return Collections.emptyList();
        }

        List<Supermercado> supers = supermercadoRepo.findByActivoTrue();
        List<ResultadoComparativaDto> resultados = new ArrayList<>();
        // En supermercados con sincronización, la validez es de 30 días
        LocalDateTime limiteCache = LocalDateTime.now().minusDays(30);

        for (Supermercado s : supers) {
            ResultadoComparativaDto dto = ResultadoComparativaDto.builder()
                    .supermercado(s.getNombre())
                    .codigoPostal(s.getCodigoPostal())
                    .productos(new ArrayList<>())
                    .totalEstimado(BigDecimal.ZERO)
                    .build();

            BigDecimal total = BigDecimal.ZERO;
            int encontrados = 0;
            int sinPrecio = 0;

            for (String art : articulos) {
                String termino = art.trim();
                ResultadoComparativaDto.ItemComparadoDto item = obtenerPrecio(s, termino, limiteCache);

                if (item != null && item.isEncontrado() && item.getPrecio() != null) {
                    dto.getProductos().add(item);
                    total = total.add(item.getPrecio());
                    encontrados++;
                } else {
                    dto.getProductos().add(ResultadoComparativaDto.ItemComparadoDto.builder()
                            .terminoBuscado(termino)
                            .productoEncontrado("No disponible")
                            .precio(null)
                            .formato(null)
                            .encontrado(false)
                            .build());
                    sinPrecio++;
                }
            }

            dto.setTotalEstimado(total.setScale(2, RoundingMode.HALF_UP));
            dto.setProductosEncontrados(encontrados);
            dto.setProductosSinPrecio(sinPrecio);
            resultados.add(dto);
        }

        resultados.sort(Comparator.comparing(
                (ResultadoComparativaDto r) -> r.getProductosEncontrados() == 0)
                .thenComparing(ResultadoComparativaDto::getTotalEstimado)
        );

        return resultados;
    }

    private ResultadoComparativaDto.ItemComparadoDto obtenerPrecio(Supermercado s, String termino, LocalDateTime limiteCache) {
        // 1. Comprobar en precios_cache
        Optional<PrecioCache> cache = precioCacheRepo.buscarPrecioReciente(s.getId(), termino.toLowerCase(), limiteCache);
        if (cache.isPresent()) {
            PrecioCache c = cache.get();
            log.info("⚡ [Caché {}] '{}' -> {} ({} €)", s.getNombre(), termino, c.getProductoNombre(), c.getPrecio());
            return ResultadoComparativaDto.ItemComparadoDto.builder()
                    .terminoBuscado(termino)
                    .productoEncontrado(c.getProductoNombre())
                    .precio(c.getPrecio())
                    .formato(c.getFormatoUnidad())
                    .encontrado(true)
                    .build();
        }

        // 2. Consulta en vivo para Mercadona
        String cod = s.getCodigoInterno() != null ? s.getCodigoInterno().toUpperCase() : "";
        String nom = normalizarTexto(s.getNombre()).toUpperCase();

        if (cod.contains("MERCADONA") || nom.contains("MERCADONA")) {
            ResultadoComparativaDto.ItemComparadoDto enVivo = consultarMercadonaEnVivo(termino);
            if (enVivo != null && enVivo.isEncontrado() && enVivo.getPrecio() != null) {
                guardarEnCache(s, termino, enVivo);
                return enVivo;
            }
        }

        return null;
    }

    private void guardarEnCache(Supermercado s, String termino, ResultadoComparativaDto.ItemComparadoDto item) {
        try {
            PrecioCache pc = PrecioCache.builder()
                    .supermercado(s)
                    .terminoBusqueda(termino.toLowerCase())
                    .productoNombre(item.getProductoEncontrado())
                    .precio(item.getPrecio())
                    .formatoUnidad(item.getFormato())
                    .actualizadoEl(LocalDateTime.now())
                    .build();
            precioCacheRepo.save(pc);
        } catch (Exception e) {
            log.warn("⚠️ No se pudo guardar caché para {} - {}: {}", s.getNombre(), termino, e.getMessage());
        }
    }

    // ==========================================
    // 🟢 MERCADONA EN VIVO
    // ==========================================
    private ResultadoComparativaDto.ItemComparadoDto consultarMercadonaEnVivo(String termino) {
        try {
            Integer catId = resolverCategoriaMercadona(termino);
            if (catId == null) {
                return null;
            }

            String url = "https://tienda.mercadona.es/api/v1_1/categories/" + catId + "/";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200 && resp.body() != null) {
                JsonNode root = objectMapper.readTree(resp.body());
                JsonNode categories = root.path("categories");

                List<JsonNode> todos = new ArrayList<>();
                if (categories.isArray()) {
                    for (JsonNode subcat : categories) {
                        JsonNode products = subcat.path("products");
                        if (products.isArray()) {
                            for (JsonNode prod : products) {
                                todos.add(prod);
                            }
                        }
                    }
                }

                String termNorm = normalizarTexto(termino);
                JsonNode match = null;

                for (JsonNode p : todos) {
                    String dName = normalizarTexto(p.path("display_name").asText());
                    if (dName.contains(termNorm) || termNorm.contains(dName)) {
                        match = p;
                        break;
                    }
                }

                if (match == null && !todos.isEmpty()) {
                    match = todos.get(0);
                }

                if (match != null) {
                    String nombre = match.path("display_name").asText();
                    double precioDbl = match.path("price_instructions").path("unit_price").asDouble(0.0);
                    String formato = match.path("packaging").asText("Ud");

                    if (precioDbl > 0.0) {
                        BigDecimal precio = BigDecimal.valueOf(precioDbl).setScale(2, RoundingMode.HALF_UP);
                        log.info("🔥 [Mercadona EN VIVO] '{}' -> {} € ({})", nombre, precio, formato);
                        return ResultadoComparativaDto.ItemComparadoDto.builder()
                                .terminoBuscado(termino)
                                .productoEncontrado(nombre)
                                .precio(precio)
                                .formato(formato)
                                .encontrado(true)
                                .build();
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ [Mercadona] Error para '{}': {}", termino, e.getMessage());
        }
        return null;
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
}
