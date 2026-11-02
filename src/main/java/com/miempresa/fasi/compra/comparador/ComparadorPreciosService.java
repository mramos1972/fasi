package com.miempresa.fasi.compra.comparador;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miempresa.fasi.ia.LlmApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComparadorPreciosService {

    private final SupermercadoRepository supermercadoRepo;
    private final PrecioCacheRepository precioCacheRepo;
    private final LlmApiClient llmClient;
    private final ObjectMapper objectMapper;

    // Caché en memoria para evitar llamadas repetidas a la IA
    // Clave: "NOMBRE_SUPER:termino_normalizado" -> Lista de UUIDs canónicos
    private final Map<String, List<UUID>> cacheResolucionIa = new ConcurrentHashMap<>();

    private static final List<String> MODIFICADORES_EXCLUIDOS = List.of(
            "humedo", "humeda", "humedas", "toallita", "toallitas",
            "recambio", "recambios", "cuchilla", "cuchillas",
            "tortita", "tortitas", "barrita", "barritas",
            "snack", "bombones"
    );

    private static final Map<String, List<String>> REGLAS_EXCLUSION = Map.of(
            "vinagre", List.of("limpieza", "desinfectar", "lavadora", "concentrado"),
            "leche de arroz", List.of("arroz con leche", "postre", "dulce"),
            "pan", List.of("panela", "pan rallado", "picos", "regana", "reganas", "colines"),
            "maquinilla", List.of("depilacion", "cera", "bandas")
    );

    private static final Map<String, List<String>> EQUIVALENCIAS = Map.of(
            "espaguetis", List.of("espagueti", "spaghetti", "spaghettis"),
            "espagueti", List.of("espaguetis", "spaghetti", "spaghettis"),
            "leche de arroz", List.of("bebida de arroz", "bebida arroz", "arroz bebida"),
            "pan bimbo", List.of("pan de molde bimbo", "pan bimbo", "pan molde")
    );

    @Transactional(readOnly = true)
    public List<ResultadoComparativaDto> compararLista(List<String> articulos) {
        log.info("🛒 >>> INICIANDO COMPARATIVA PARA {} ARTÍCULOS: {}", articulos != null ? articulos.size() : 0, articulos);

        if (articulos == null || articulos.isEmpty()) {
            return Collections.emptyList();
        }

        List<Supermercado> supers = supermercadoRepo.findByActivoTrue();
        List<ResultadoComparativaDto> resultados = new ArrayList<>();
        LocalDateTime limite = LocalDateTime.now().minusDays(90);

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

            List<PrecioCache> catalogo = precioCacheRepo.buscarCandidatos(s.getId(), limite);

            for (String art : articulos) {
                String original = art.trim();
                List<OpcionProductoDto> opciones = obtenerOpcionesCandidatas(original, s.getNombre(), catalogo);

                if (!opciones.isEmpty()) {
                    OpcionProductoDto recomendada = opciones.get(0);
                    dto.getProductos().add(ResultadoComparativaDto.ItemComparadoDto.builder()
                            .terminoBuscado(original)
                            .productoEncontrado(recomendada.getNombre())
                            .precio(recomendada.getPrecio())
                            .formato(recomendada.getFormato())
                            .encontrado(true)
                            .opciones(opciones)
                            .build());
                    total = total.add(recomendada.getPrecio());
                    encontrados++;
                } else {
                    dto.getProductos().add(ResultadoComparativaDto.ItemComparadoDto.builder()
                            .terminoBuscado(original)
                            .productoEncontrado("No disponible")
                            .precio(null)
                            .formato(null)
                            .encontrado(false)
                            .opciones(Collections.emptyList())
                            .build());
                    sinPrecio++;
                }
            }

            dto.setTotalEstimado(total.setScale(2, RoundingMode.HALF_UP));
            dto.setProductosEncontrados(encontrados);
            dto.setProductosSinPrecio(sinPrecio);
            resultados.add(dto);
        }

        resultados.sort(Comparator
                .comparingInt(ResultadoComparativaDto::getProductosEncontrados).reversed()
                .thenComparing(ResultadoComparativaDto::getTotalEstimado)
        );

        return resultados;
    }

    private List<OpcionProductoDto> obtenerOpcionesCandidatas(String busquedaOriginal, String nombreSuper, List<PrecioCache> catalogo) {
        String queryNorm = normalizar(busquedaOriginal);
        List<String> tokensQuery = Arrays.stream(queryNorm.split("\\s+"))
                .filter(t -> t.length() > 2)
                .toList();

        if (tokensQuery.isEmpty()) {
            tokensQuery = List.of(queryNorm);
        }

        List<PrecioCache> matches = filtrarCandidatos(queryNorm, tokensQuery, catalogo);

        if (matches.isEmpty() && tokensQuery.size() > 1) {
            String raiz = tokensQuery.get(0);
            List<String> variantesRaiz = EQUIVALENCIAS.getOrDefault(raiz, List.of(raiz));
            for (String var : variantesRaiz) {
                matches = filtrarCandidatos(queryNorm, List.of(var), catalogo);
                if (!matches.isEmpty()) break;
            }
        }

        if (matches.isEmpty()) {
            return Collections.emptyList();
        }

        // Refinamiento semántico mediante IA (Gemini) para discernir producto canónico
        matches = refinarCandidatosConIa(busquedaOriginal, nombreSuper, matches);

        List<OpcionProductoDto> opciones = new ArrayList<>();
        int limiteMax = Math.min(5, matches.size());
        for (int i = 0; i < limiteMax; i++) {
            PrecioCache pc = matches.get(i);
            opciones.add(OpcionProductoDto.builder()
                    .id(pc.getId())
                    .nombre(pc.getProductoNombre())
                    .precio(pc.getPrecio())
                    .formato(pc.getFormatoUnidad())
                    .seleccionada(i == 0)
                    .build());
        }

        return opciones;
    }

    private List<PrecioCache> refinarCandidatosConIa(String terminoOriginal, String nombreSuper, List<PrecioCache> candidatos) {
        if (candidatos.size() <= 1) {
            return candidatos;
        }

        String cacheKey = (nombreSuper != null ? nombreSuper.toUpperCase() : "GENERAL") + ":" + normalizar(terminoOriginal);
        List<UUID> idsCacheados = cacheResolucionIa.get(cacheKey);

        if (idsCacheados != null && !idsCacheados.isEmpty()) {
            Map<UUID, PrecioCache> mapaCandidatos = candidatos.stream()
                    .collect(Collectors.toMap(PrecioCache::getId, c -> c, (a, b) -> a));
            List<PrecioCache> recuperados = idsCacheados.stream()
                    .map(mapaCandidatos::get)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            if (!recuperados.isEmpty()) {
                return recuperados;
            }
        }

        // Orden de prelación previo para presentar a la IA los 15 más relevantes
        String tokenPrincipal = normalizar(terminoOriginal).split("\\s+")[0];
        List<PrecioCache> candidatosOrdenados = new ArrayList<>(candidatos);
        candidatosOrdenados.sort((a, b) -> {
            boolean aStarts = normalizar(a.getProductoNombre()).startsWith(tokenPrincipal);
            boolean bStarts = normalizar(b.getProductoNombre()).startsWith(tokenPrincipal);
            if (aStarts != bStarts) return aStarts ? -1 : 1;
            return a.getPrecio().compareTo(b.getPrecio());
        });

        List<PrecioCache> sublista = candidatosOrdenados.stream().limit(15).toList();

        StringBuilder promptOpciones = new StringBuilder();
        for (int i = 0; i < sublista.size(); i++) {
            PrecioCache pc = sublista.get(i);
            promptOpciones.append(i).append(": ").append(pc.getProductoNombre())
                    .append(" (").append(pc.getPrecio()).append(" €)\n");
        }

        String systemPrompt = """
            Eres un experto clasificador de compras de supermercado para un comparador inteligente.
            El usuario busca el producto alimentario o artículo base: '%s'.
            Analiza los siguientes productos reales encontrados en el supermercado %s.
            
            REGLAS CRÍTICAS:
            1. Selecciona ÚNICAMENTE los índices de las opciones que mejor representen el producto esencial y directo buscado.
            2. Descarta terminantemente derivados no equivalentes, subproductos, snacks o ingredientes secundarios (ejemplos: si busca 'pan', descarta pan rallado, picos, regañás, tostadas, panela o harina; si busca 'leche', descarta postres lácteos, batidos o café con leche; si busca 'arroz', descarta platos preparados o snacks).
            3. Devuelve como máximo 5 índices ordenados de mejor a peor opción (la mejor barra/hogaza o el producto más estándar y habitual en primera posición).
            
            Debes responder ÚNICAMENTE un JSON con esta estructura exacta:
            {"indices": [1, 4, 0]}
            No agregues explicaciones ni bloques de texto adicionales.
            """.formatted(terminoOriginal, nombreSuper);

        try {
            String jsonRespuesta = llmClient.chatJson(systemPrompt, promptOpciones.toString());
            JsonNode root = objectMapper.readTree(jsonRespuesta);
            JsonNode indicesNode = root.path("indices");

            if (indicesNode.isArray() && !indicesNode.isEmpty()) {
                List<PrecioCache> seleccionados = new ArrayList<>();
                List<UUID> idsParaCache = new ArrayList<>();

                for (JsonNode idxElem : indicesNode) {
                    int idx = idxElem.asInt(-1);
                    if (idx >= 0 && idx < sublista.size()) {
                        PrecioCache elegido = sublista.get(idx);
                        if (!idsParaCache.contains(elegido.getId())) {
                            seleccionados.add(elegido);
                            idsParaCache.add(elegido.getId());
                        }
                    }
                }

                if (!seleccionados.isEmpty()) {
                    cacheResolucionIa.put(cacheKey, idsParaCache);
                    log.info("🧠 [IA Comparador] Arbitraje exitoso para '{}' en {}: {} opciones seleccionadas (1ª: {})",
                            terminoOriginal, nombreSuper, seleccionados.size(), seleccionados.get(0).getProductoNombre());
                    return seleccionados;
                }
            }
        } catch (Exception ex) {
            log.warn("⚠️ Fallo o timeout en arbitraje IA para '{}' en {}: {}. Usando fallback clásico.",
                    terminoOriginal, nombreSuper, ex.getMessage());
        }

        // Fallback: Si la IA falla, aplicamos ordenación estándar
        return candidatosOrdenados;
    }

    private List<PrecioCache> filtrarCandidatos(String queryOriginalNorm, List<String> tokensRequeridos, List<PrecioCache> catalogo) {
        List<PrecioCache> validos = new ArrayList<>();

        for (PrecioCache pc : catalogo) {
            String prodNorm = normalizar(pc.getProductoNombre());

            if (pc.getPrecio() == null || pc.getPrecio().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            boolean excluido = false;
            for (String mod : MODIFICADORES_EXCLUIDOS) {
                if (prodNorm.contains(mod) && !queryOriginalNorm.contains(mod)) {
                    excluido = true;
                    break;
                }
            }
            if (excluido) continue;

            for (Map.Entry<String, List<String>> entry : REGLAS_EXCLUSION.entrySet()) {
                if (queryOriginalNorm.contains(entry.getKey())) {
                    for (String noPermitido : entry.getValue()) {
                        if (prodNorm.contains(noPermitido) && !queryOriginalNorm.contains(noPermitido)) {
                            excluido = true;
                            break;
                        }
                    }
                }
            }
            if (excluido) continue;

            boolean contieneTodos = true;
            for (String tok : tokensRequeridos) {
                List<String> alternativas = EQUIVALENCIAS.getOrDefault(tok, List.of(tok));
                boolean coincideAlguna = false;
                for (String alt : alternativas) {
                    Pattern p = Pattern.compile("\\b" + Pattern.quote(alt));
                    if (p.matcher(prodNorm).find()) {
                        coincideAlguna = true;
                        break;
                    }
                }
                if (!coincideAlguna) {
                    contieneTodos = false;
                    break;
                }
            }

            if (contieneTodos) {
                validos.add(pc);
            }
        }
        return validos;
    }

    private String normalizar(String texto) {
        if (texto == null) return "";
        return texto.toLowerCase()
                .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")
                .replace("ñ", "n")
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
