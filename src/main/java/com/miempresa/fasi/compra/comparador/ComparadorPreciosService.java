package com.miempresa.fasi.compra.comparador;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComparadorPreciosService {

    private final SupermercadoRepository supermercadoRepo;
    private final PrecioCacheRepository precioCacheRepo;

    private static final List<String> MODIFICADORES_EXCLUIDOS = List.of(
            "humedo", "humeda", "humedas", "toallita", "toallitas",
            "recambio", "recambios", "cuchilla", "cuchillas",
            "tortita", "tortitas", "barrita", "barritas",
            "snack", "bombones"
    );

    private static final Map<String, List<String>> REGLAS_EXCLUSION_CONTEXTO = Map.of(
            "vinagre", List.of("limpieza", "desinfectar", "lavadora", "concentrado"),
            "leche de arroz", List.of("arroz con leche", "postre", "dulce"),
            "pan", List.of("panela", "pan rallado"),
            "maquinilla", List.of("depilacion", "cera", "bandas")
    );

    private static final Map<String, List<String>> EQUIVALENCIAS = Map.of(
            "espaguetis", List.of("espagueti", "spaghetti", "spaghettis"),
            "espagueti", List.of("espaguetis", "spaghetti", "spaghettis"),
            "leche de arroz", List.of("bebida de arroz", "bebida arroz", "arroz bebida"),
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

            List<PrecioCache> catalogo = precioCacheRepo.buscarCandidatos(s.getId(), limite);

            for (String art : articulos) {
                String original = art.trim();
                List<ResultadoComparativaDto.OpcionProductoDto> opciones = obtenerOpcionesCandidatas(original, catalogo);

                if (!opciones.isEmpty()) {
                    ResultadoComparativaDto.OpcionProductoDto recomendada = opciones.get(0);
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

    private List<ResultadoComparativaDto.OpcionProductoDto> obtenerOpcionesCandidatas(
            String busquedaOriginal, List<PrecioCache> catalogo) {

        String queryNorm = normalizar(busquedaOriginal);
        List<String> tokensQuery = Arrays.stream(queryNorm.split("\\s+"))
                .filter(t -> t.length() > 2)
                .toList();

        if (tokensQuery.isEmpty()) {
            tokensQuery = List.of(queryNorm);
        }

        // 1. Probar con todas las palabras (ej: "arroz sos", "espaguetis gallo")
        List<PrecioCache> matches = filtrarCandidatos(queryNorm, tokensQuery, catalogo);

        // 2. Si no hubo coincidencia (ej: Mercadona no vende Gallo ni SOS), buscar por la raíz canónica
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

        // Ordenar: primero los que empiezan por la palabra buscada, luego por precio ascendente
        String tokenPrincipal = tokensQuery.get(0);
        matches.sort(Comparator
                .comparing((PrecioCache pc) -> normalizar(pc.getProductoNombre()).startsWith(tokenPrincipal) ? 0 : 1)
                .thenComparing(PrecioCache::getPrecio)
        );

        // Limitar a máximo 5 opciones razonables
        List<ResultadoComparativaDto.OpcionProductoDto> opciones = new ArrayList<>();
        int limiteMax = Math.min(5, matches.size());
        for (int i = 0; i < limiteMax; i++) {
            PrecioCache pc = matches.get(i);
            opciones.add(ResultadoComparativaDto.OpcionProductoDto.builder()
                    .id(pc.getId())
                    .nombre(pc.getProductoNombre())
                    .precio(pc.getPrecio())
                    .formato(pc.getFormatoUnidad())
                    .seleccionada(i == 0) // La primera (más económica) marcada como predeterminada
                    .build());
        }

        return opciones;
    }

    private List<PrecioCache> filtrarCandidatos(String queryOriginalNorm, List<String> tokensRequeridos, List<PrecioCache> catalogo) {
        List<PrecioCache> validos = new ArrayList<>();

        for (PrecioCache pc : catalogo) {
            String prodNorm = normalizar(pc.getProductoNombre());

            // A. Excluir modificadores no solicitados
            boolean excluido = false;
            for (String mod : MODIFICADORES_EXCLUIDOS) {
                if (prodNorm.contains(mod) && !queryOriginalNorm.contains(mod)) {
                    excluido = true;
                    break;
                }
            }
            if (excluido) continue;

            // B. Exclusiones de contexto (ej: vinagre de comer vs limpieza)
            for (Map.Entry<String, List<String>> entry : REGLAS_EXCLUSION_CONTEXTO.entrySet()) {
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

            // C. Coincidencia de tokens o sus equivalentes
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
