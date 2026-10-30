package com.miempresa.fasi.compra.comparador;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComparadorPreciosService {

    private final SupermercadoRepository supermercadoRepo;
    private final PrecioCacheRepository precioCacheRepo;

    // Palabras modificadoras que NO deben coincidir si el usuario no las pidió explícitamente
    private static final List<String> MODIFICADORES_EXCLUIDOS = List.of(
            "humedo", "humeda", "humedas", "toallita", "toallitas",
            "recambio", "recambios", "cuchilla", "cuchillas",
            "tortita", "tortitas", "barrita", "barritas",
            "snack", "chocolate", "bombones"
    );

    // Mapeo canónico para salvar diferencias léxicas
    private static final Map<String, List<String>> EQUIVALENCIAS = Map.of(
            "espaguetis", List.of("espagueti", "spaghetti", "spaghettis"),
            "espagueti", List.of("espaguetis", "spaghetti", "spaghettis"),
            "leche de arroz", List.of("bebida de arroz", "bebida arroz"),
            "pan bimbo", List.of("pan de molde")
    );

    @Transactional(readOnly = true)
    public List<ResultadoComparativaDto> compararLista(List<String> articulos) {
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

            // Candidatos del súper cargados en memoria para evitar N queries por lista
            List<PrecioCache> catalogo = precioCacheRepo.buscarCandidatos(s.getId(), limite);

            for (String art : articulos) {
                String original = art.trim();
                PrecioCache match = resolverMejorProducto(original, catalogo);

                if (match != null && match.getPrecio() != null) {
                    log.info("🎯 [Match {}] '{}' -> {} ({} €)", s.getNombre(), original, match.getProductoNombre(), match.getPrecio());
                    dto.getProductos().add(ResultadoComparativaDto.ItemComparadoDto.builder()
                            .terminoBuscado(original)
                            .productoEncontrado(match.getProductoNombre())
                            .precio(match.getPrecio())
                            .formato(match.getFormatoUnidad())
                            .encontrado(true)
                            .build());
                    total = total.add(match.getPrecio());
                    encontrados++;
                } else {
                    dto.getProductos().add(ResultadoComparativaDto.ItemComparadoDto.builder()
                            .terminoBuscado(original)
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

        // Ordenar primero por mayor cobertura de productos, luego por total de la cesta
        resultados.sort(Comparator
                .comparingInt(ResultadoComparativaDto::getProductosEncontrados).reversed()
                .thenComparing(ResultadoComparativaDto::getTotalEstimado)
        );

        return resultados;
    }

    private PrecioCache resolverMejorProducto(String busquedaOriginal, List<PrecioCache> catalogo) {
        String queryNorm = normalizar(busquedaOriginal);
        List<String> tokensQuery = Arrays.stream(queryNorm.split("\\s+"))
                .filter(t -> t.length() > 2)
                .toList();

        if (tokensQuery.isEmpty()) {
            tokensQuery = List.of(queryNorm);
        }

        // Detectar si la búsqueda pide expresamente algún modificador sensible
        List<String> permitidos = MODIFICADORES_EXCLUIDOS.stream()
                .filter(queryNorm::contains)
                .toList();

        // 1. Intentar coincidencia con TODAS las palabras (incluyendo marca si la hay, ej: "arroz sos", "espaguetis gallo")
        PrecioCache matchCompleto = buscarConTokens(tokensQuery, permitidos, catalogo);
        if (matchCompleto != null) {
            return matchCompleto;
        }

        // 2. Si tenía marca y falló (ej. Mercadona no tiene 'Gallo' o 'SOS'), intentar con la raíz canónica del producto
        String raiz = tokensQuery.get(0);
        List<String> variantesRaiz = EQUIVALENCIAS.getOrDefault(raiz, List.of(raiz));

        for (String var : variantesRaiz) {
            PrecioCache matchRaiz = buscarConTokens(List.of(var), permitidos, catalogo);
            if (matchRaiz != null) {
                return matchRaiz;
            }
        }

        return null;
    }

    private PrecioCache buscarConTokens(List<String> tokensRequeridos, List<String> permitidos, List<PrecioCache> catalogo) {
        List<PrecioCache> candidatosValidos = new ArrayList<>();

        for (PrecioCache pc : catalogo) {
            String prodNorm = normalizar(pc.getProductoNombre());

            // Filtro de exclusión: descartar si tiene palabras trampa no solicitadas
            boolean excluido = false;
            for (String mod : MODIFICADORES_EXCLUIDOS) {
                if (prodNorm.contains(mod) && !permitidos.contains(mod)) {
                    excluido = true;
                    break;
                }
            }
            if (excluido) continue;

            // Comprobar que contiene TODOS los tokens requeridos
            boolean contieneTodos = true;
            for (String tok : tokensRequeridos) {
                // Si el token tiene sinónimo canónico (ej: espaguetis -> spaghetti), aceptar cualquiera
                List<String> alternativas = EQUIVALENCIAS.getOrDefault(tok, List.of(tok));
                boolean coincideAlguna = alternativas.stream().anyMatch(prodNorm::contains);
                if (!coincideAlguna) {
                    contieneTodos = false;
                    break;
                }
            }

            if (contieneTodos) {
                candidatosValidos.add(pc);
            }
        }

        if (candidatosValidos.isEmpty()) {
            return null;
        }

        // Preferir:
        // 1. Aquel cuyo nombre empiece por el producto buscado
        // 2. El más corto en longitud de texto (evita productos hiper-compuestos)
        // 3. El más barato
        String tokenPrincipal = tokensRequeridos.get(0);
        candidatosValidos.sort(Comparator
                .comparing((PrecioCache pc) -> normalizar(pc.getProductoNombre()).startsWith(tokenPrincipal) ? 0 : 1)
                .thenComparingInt(pc -> pc.getProductoNombre().length())
                .thenComparing(PrecioCache::getPrecio)
        );

        return candidatosValidos.get(0);
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
