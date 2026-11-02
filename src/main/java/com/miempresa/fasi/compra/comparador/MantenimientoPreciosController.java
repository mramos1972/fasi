package com.miempresa.fasi.compra.comparador;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
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

    @PostMapping("/api/sincronizar-aldi")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> sincronizarAldiServidor() {
        log.info("🛒 [ALDI Sync] Iniciando descubrimiento dinámico de categorías y crawling...");

        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        List<String> categorias = descubrirCategoriasAldi(client);
        log.info("🛒 [ALDI Sync] Categorías descubiertas para procesar: {}", categorias.size());

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
                    log.info("🛒 [ALDI Sync] BuildId detectado: {}", buildId);
                }
            }
        } catch (Exception e) {
            log.warn("🛒 [ALDI Sync] Usando buildId de respaldo: {}", e.getMessage());
        }

        int totalProcesados = 0;
        int categoriasConProductos = 0;

        for (String ruta : categorias) {
            try {
                String url = "https://www.aldi.es/_next/data/" + buildId + "/es/productos/" + ruta + ".html.json";
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

                                        String name = h.path("name").asText("").trim();
                                        String brand = h.path("brandName").asText("").replaceAll("[®™]", "").trim();
                                        String displayName = brand.isEmpty() ? name : name + " " + brand;
                                        String sku = h.path("objectID").asText("");
                                        String unit = h.path("salesUnit").asText("Ud").trim();
                                        String termino = ruta.contains("/") ? ruta.substring(ruta.lastIndexOf("/") + 1) : ruta;

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
                    log.info("✔ ALDI [{}] (+{} productos)", ruta, lote.size());
                }

                Thread.sleep(70);

            } catch (Exception e) {
                log.warn("⚠️ Aviso procesando ruta ALDI {}: {}", ruta, e.getMessage());
            }
        }

        long totalEnCache = precioCacheRepo.count();
        log.info("🎉 [ALDI Sync] Finalizado: {} categorías con productos, {} productos procesados. Total BD: {}",
                categoriasConProductos, totalProcesados, totalEnCache);

        return ResponseEntity.ok(Map.of(
                "supermercado", "ALDI",
                "procesados", totalProcesados,
                "categoriasProcesadas", categoriasConProductos,
                "totalEnCache", totalEnCache
        ));
    }

    @PostMapping("/api/sincronizar-eljamon")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> sincronizarElJamonServidor() {
        log.info("🛒 [El Jamón Sync] Iniciando descubrimiento y sincronización...");

        Supermercado elJamon = supermercadoRepo.findAll().stream()
                .filter(s -> {
                    String cod = s.getCodigoInterno() != null ? s.getCodigoInterno().toUpperCase() : "";
                    String nom = s.getNombre() != null ? s.getNombre().toUpperCase().replace("Ó", "O") : "";
                    return cod.equals("EL_JAMON") || nom.contains("JAMON");
                })
                .findFirst()
                .orElseGet(() -> {
                    log.info("⚠️ El Jamón no estaba en BD. Creándolo automáticamente...");
                    Supermercado nuevo = Supermercado.builder()
                            .nombre("Supermercados El Jamon")
                            .codigoInterno("EL_JAMON")
                            .codigoPostal("11540")
                            .activo(true)
                            .build();
                    return supermercadoRepo.save(nuevo);
                });

        if (!elJamon.isActivo()) {
            elJamon.setActivo(true);
            supermercadoRepo.save(elJamon);
        }

        SSLSocketFactory sslSocketFactory = crearSslSocketFactoryTolerante();

        // 1. Inicializar sesión de Comerzzia con cookies vivas (JSESSIONID) ignorando certificado roto
        Map<String, String> sessionCookies = new HashMap<>();
        String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
        try {
            org.jsoup.Connection connHome = Jsoup.connect("https://www.supermercadoseljamon.com/")
                    .userAgent(userAgent)
                    .timeout(15000);
            if (sslSocketFactory != null) {
                connHome.sslSocketFactory(sslSocketFactory);
            }
            org.jsoup.Connection.Response homeResp = connHome.execute();
            sessionCookies.putAll(homeResp.cookies());

            // 2. Fijar tienda 163 (Sanlúcar de Barrameda - CP 11540) sobre la sesión
            org.jsoup.Connection connZip = Jsoup.connect("https://www.supermercadoseljamon.com/delegate/seleccionarCodPostalAjaxServletFood")
                    .data("accion", "enviarCodTiendaRecogida")
                    .data("codTiendaRecogida", "163")
                    .data("codTipoServicio", "1020")
                    .data("locale", "es")
                    .cookies(sessionCookies)
                    .userAgent(userAgent)
                    .timeout(15000)
                    .ignoreContentType(true);
            if (sslSocketFactory != null) {
                connZip.sslSocketFactory(sslSocketFactory);
            }
            org.jsoup.Connection.Response zipResp = connZip.execute();
            sessionCookies.putAll(zipResp.cookies());
            log.info("🛒 [El Jamón Sync] Sesión iniciada y anclada a tienda 163 (CP 11540). Cookies: {}", sessionCookies.keySet());
        } catch (Exception e) {
            log.warn("⚠️ Error inicializando sesión en El Jamón: {}", e.getMessage());
        }

        HttpClient client = crearHttpClientToleranteSSL();
        List<String> subcategorias = descubrirSubcategoriasElJamon(client);
        log.info("🛒 [El Jamón Sync] Subcategorías a explorar: {}", subcategorias.size());

        int totalProcesados = 0;
        int subcatsConProductos = 0;
        LocalDateTime ahora = LocalDateTime.now();

        for (String urlSubcat : subcategorias) {
            try {
                org.jsoup.Connection connSub = Jsoup.connect(urlSubcat)
                        .cookies(sessionCookies)
                        .userAgent(userAgent)
                        .timeout(15000);
                if (sslSocketFactory != null) {
                    connSub.sslSocketFactory(sslSocketFactory);
                }

                Document doc = connSub.get();

                Elements articulos = doc.select("div[id*='articulo_']");
                if (articulos.isEmpty()) {
                    continue;
                }

                String terminoSubcat = extraerTerminoDeUrl(urlSubcat);
                int procesadosEnSubcat = 0;

                for (Element art : articulos) {
                    try {
                        Element nombreElem = art.selectFirst("p.nombre a");
                        if (nombreElem == null) continue;
                        String nombre = nombreElem.text().trim();

                        Element marcaElem = art.selectFirst("p.marca");
                        String marca = (marcaElem != null) ? marcaElem.text().trim() : "";
                        String nombreCompleto = (!marca.isEmpty() && !nombre.toLowerCase().contains(marca.toLowerCase()))
                                ? marca + " " + nombre : nombre;

                        // Buscar primero precio activo (no tachado si hay promo)
                        Element precioElem = art.selectFirst("p.precio span:not(.tachado)");
                        if (precioElem == null) precioElem = art.selectFirst("p.precio span");
                        if (precioElem == null) precioElem = art.selectFirst("p.precio");
                        if (precioElem == null) continue;

                        String texto = precioElem.text();
                        Matcher m = Pattern.compile("(\\d+[.,]\\d{2})").matcher(texto);
                        if (!m.find()) continue;

                        BigDecimal precio = new BigDecimal(m.group(1).replace(",", ".")).setScale(2, RoundingMode.HALF_UP);
                        if (precio.compareTo(BigDecimal.ZERO) <= 0) continue;

                        Element unidadElem = art.selectFirst("div.texto-porKilo");
                        String unidad = (unidadElem != null) ? unidadElem.text().trim() : "Ud";

                        Optional<PrecioCache> existente = precioCacheRepo.buscarPreciosRecientes(
                                elJamon.getId(), nombreCompleto, ahora.minusDays(60)).stream().findFirst();

                        if (existente.isPresent()) {
                            PrecioCache pc = existente.get();
                            pc.setPrecio(precio);
                            pc.setFormatoUnidad(unidad);
                            pc.setActualizadoEl(ahora);
                            precioCacheRepo.save(pc);
                        } else {
                            PrecioCache pc = PrecioCache.builder()
                                    .supermercado(elJamon)
                                    .terminoBusqueda(terminoSubcat.toLowerCase())
                                    .productoNombre(nombreCompleto)
                                    .precio(precio)
                                    .formatoUnidad(unidad)
                                    .actualizadoEl(ahora)
                                    .build();
                            precioCacheRepo.save(pc);
                        }
                        procesadosEnSubcat++;
                    } catch (Exception exProd) {
                        log.debug("Aviso procesando producto de El Jamón: {}", exProd.getMessage());
                    }
                }

                if (procesadosEnSubcat > 0) {
                    totalProcesados += procesadosEnSubcat;
                    subcatsConProductos++;
                    log.info("✔ El Jamón [{}] (+{} productos)", terminoSubcat, procesadosEnSubcat);
                }

                Thread.sleep(80);

            } catch (Exception e) {
                log.warn("⚠️ Error en subcategoría El Jamón {}: {}", urlSubcat, e.getMessage());
            }
        }

        long totalBd = precioCacheRepo.count();
        log.info("🎉 [El Jamón Sync] Finalizado: {} subcategorías con productos, {} productos procesados. Total BD: {}",
                subcatsConProductos, totalProcesados, totalBd);

        return ResponseEntity.ok(Map.of(
                "supermercado", "Supermercados El Jamón",
                "procesados", totalProcesados,
                "categoriasProcesadas", subcatsConProductos,
                "totalEnCache", totalBd
        ));
    }

    private SSLSocketFactory crearSslSocketFactoryTolerante() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            log.warn("No se pudo crear SSLSocketFactory tolerante: {}", e.getMessage());
            return null;
        }
    }

    private HttpClient crearHttpClientToleranteSSL() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());

            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        } catch (Exception e) {
            log.warn("No se pudo configurar SSL tolerante, usando estándar: {}", e.getMessage());
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        }
    }

    private List<String> descubrirSubcategoriasElJamon(HttpClient client) {
        Set<String> urls = new LinkedHashSet<>();
        List<String> raices = List.of(
                "https://www.supermercadoseljamon.com/categorias/la-despensa/01",
                "https://www.supermercadoseljamon.com/categorias/desayuno-y-dulces/02",
                "https://www.supermercadoseljamon.com/categorias/lacteos/03",
                "https://www.supermercadoseljamon.com/categorias/frescos/04",
                "https://www.supermercadoseljamon.com/categorias/congelados/05",
                "https://www.supermercadoseljamon.com/categorias/bebidas/06",
                "https://www.supermercadoseljamon.com/categorias/drogueria-y-limpieza/09"
        );

        for (String raiz : raices) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(raiz))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    Document doc = Jsoup.parse(resp.body());
                    Elements enlaces = doc.select("a[href*='/categorias/']");
                    for (Element a : enlaces) {
                        String href = a.absUrl("href");
                        if (href.matches(".*/categorias/.+/\\d{6}$")) {
                            urls.add(href);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Aviso explorando raíz {}: {}", raiz, e.getMessage());
            }
        }

        if (urls.isEmpty()) {
            return List.of(
                    "https://www.supermercadoseljamon.com/categorias/la-despensa/aceites-y-condimentos/aceite-de-oliva-virgen-y-virgen-extra/010603",
                    "https://www.supermercadoseljamon.com/categorias/la-despensa/arroz-legumbres-y-pasta/arroz/010101",
                    "https://www.supermercadoseljamon.com/categorias/la-despensa/arroz-legumbres-y-pasta/pasta/010103",
                    "https://www.supermercadoseljamon.com/categorias/lacteos/leches/leche-entera/030101",
                    "https://www.supermercadoseljamon.com/categorias/lacteos/leches/leche-semidesnatada/030102",
                    "https://www.supermercadoseljamon.com/categorias/lacteos/leches/leche-desnatada/030103",
                    "https://www.supermercadoseljamon.com/categorias/lacteos/huevos/huevos/030401",
                    "https://www.supermercadoseljamon.com/categorias/drogueria-y-limpieza/papel-higienico-y-panuelos/papel-higienico/090101"
            );
        }

        return new ArrayList<>(urls);
    }

    private String extraerTerminoDeUrl(String url) {
        try {
            String sinCodigo = url.replaceAll("/\\d+$", "");
            int ultSlash = sinCodigo.lastIndexOf('/');
            if (ultSlash != -1) {
                return sinCodigo.substring(ultSlash + 1).replace("-", " ");
            }
        } catch (Exception ignored) {}
        return "general";
    }

    private List<String> descubrirCategoriasAldi(HttpClient client) {
        Set<String> rutasHojas = new LinkedHashSet<>();
        try {
            HttpRequest sitemapReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.aldi.es/sitemaps/.aldi-nord-sitemap-pages.xml"))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();

            HttpResponse<String> sitemapResp = client.send(sitemapReq, HttpResponse.BodyHandlers.ofString());
            if (sitemapResp.statusCode() == 200) {
                Pattern pLoc = Pattern.compile("https://www.aldi.es/productos/([^<]+)\\.html");
                Matcher m = pLoc.matcher(sitemapResp.body());
                while (m.find()) {
                    String slug = m.group(1).trim();
                    if (slug.contains("/")
                            && !slug.startsWith("juguetes")
                            && !slug.startsWith("marcas")
                            && !slug.startsWith("bazar")) {
                        rutasHojas.add(slug);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ Error leyendo sitemap dinámico de ALDI: {}", e.getMessage());
        }

        if (rutasHojas.isEmpty()) {
            return List.of(
                    "despensa/arroz", "despensa/pasta", "despensa/aceites-y-vinagres",
                    "despensa/legumbres-y-cereales", "lacteos-y-huevos/leche-y-bebidas-vegetales",
                    "lacteos-y-huevos/huevos", "lacteos-y-huevos/yogures-y-postres-lacteos",
                    "carne/cerdo", "carne/pollo-y-pavo", "carne/ternera",
                    "pescado-y-marisco/pescado-fresco", "fruta-y-verdura/fruta",
                    "fruta-y-verdura/verduras-y-hortalizas", "limpieza-y-hogar/papel-higienico-y-celulosa"
            );
        }

        return new ArrayList<>(rutasHojas);
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
}
