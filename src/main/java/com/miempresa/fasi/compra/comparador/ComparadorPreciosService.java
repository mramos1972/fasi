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

    // Exclusiones semánticas para evitar confusiones de orden y contexto
    private static final Map<String, List<String>> EXCLUSIONES = Map.of(
            "arroz", List.of("cereal", "cereales", "copos", "tortita", "tortitas", "bebida", "snack", "barrita", "leche de"),
            "leche de arroz", List.of("arroz con leche", "postre"),
            "leche", List.of("corporal", "limpiadora", "solar", "chocolate con leche", "galleta", "galletas", "flan", "arroz con leche"),
            "azucar", List.of("vainillado", "avainillado", "glass", "glace", "moreno", "edulcorante"),
            "harina", List.of("tortitas", "preparado", "bizcocho"),
            "papel higienico", List.of("humedo", "humedas", "toallitas")
    );

    @Transactional(readOnly = true)
    public List<ResultadoComparativaDto> compararLista(List<String> articulos) {
        if (articulos == null || articulos.isEmpty()) {
            return Collections.emptyList();
        }

        List<Supermercado> supers = supermercadoRepo.findByActivoTrue();
        List<ResultadoComparativaDto> resultados = new ArrayList<>();
        LocalDateTime limiteCache = LocalDateTime.now().minusDays(45);

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

            // Obtener todos los candidatos de este supermercado en BD una sola vez por comparativa
            List<PrecioCache> candidatos = precioCacheRepo.buscarCandidatos(s.getId(), limiteCache);

            for (String art : articulos) {
                String termino = art.trim();
                ResultadoComparativaDto.ItemComparadoDto item = obtenerPrecioInteligente(s, termino, candidatos);

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

        // Ordenar con justicia: quien tenga más productos encontrados va primero; ante empate, el total más bajo
        resultados.sort(Comparator
                .comparingInt(ResultadoComparativaDto::getProductosEncontrados).reversed()
                .thenComparing(ResultadoComparativaDto::getTotalEstimado)
        );

        return resultados;
    }

    private ResultadoComparativaDto.ItemComparadoDto obtenerPrecioInteligente(
            Supermercado s, String termino, List<PrecioCache> candidatos) {

        String terminoNorm = normalizar(termino);
        PrecioCache mejor = seleccionarMejorCandidato(terminoNorm, candidatos);

        if (mejor != null) {
            log.info("🎯 [Match Inteligente {}] '{}' -> {} ({} €)", 
                    s.getNombre(), termino, mejor.getProductoNombre(), mejor.getPrecio());

            return ResultadoComparativaDto.ItemComparadoDto.builder()
                    .terminoBuscado(termino)
                    .productoEncontrado(mejor.getProductoNombre())
                    .precio(mejor.getPrecio())
                    .formato(mejor.getFormatoUnidad())
                    .encontrado(true)
                    .build();
        }

        return null;
    }

    private PrecioCache seleccionarMejorCandidato(String busquedaNorm, List<PrecioCache> candidatos) {
        String[] tokens = busquedaNorm.split("\\s+");
        List<String> exclusiones = EXCLUSIONES.getOrDefault(busquedaNorm, Collections.emptyList());

        List<CandidatoPuntuado> validos = new ArrayList<>();

        for (PrecioCache pc : candidatos) {
            String prodNombreNorm = normalizar(pc.getProductoNombre());

            // 1. Filtro de exclusiones semánticas
            boolean excluido = false;
            for (String exc : exclusiones) {
                if (prodNombreNorm.contains(normalizar(exc))) {
                    excluido = true;
                    break;
                }
            }
            if (excluido) continue;

            // 2. Comprobación estricta de tokens
            int score = 0;
            int tokensCoincidentes = 0;
            int tokensRelevantes = 0;

            for (String tok : tokens) {
                if (tok.length() <= 2) continue; // omitir de, la, el, en...
                tokensRelevantes++;

                if (prodNombreNorm.contains(tok)) {
                    tokensCoincidentes++;
                    score += 20;

                    // Bonificación si coincide como palabra completa o empieza por ella
                    if (prodNombreNorm.startsWith(tok)) {
                        score += 15;
                    }
                }
            }

            // Exigencia: si hay 2 o más tokens relevantes (ej: "arroz calasparra", "leche arroz"), deben coincidir todos
            if (tokensRelevantes >= 2 && tokensCoincidentes < tokensRelevantes) {
                continue;
            }

            // Si es un solo término (ej: "arroz"), debe contenerlo obligatoriamente
            if (tokensRelevantes == 1 && tokensCoincidentes == 0) {
                continue;
            }

            if (score > 0) {
                validos.add(new CandidatoPuntuado(pc, score));
            }
        }

        if (validos.isEmpty()) {
            return null;
        }

        // Ordenar por mayor afinidad semántica, y a igualdad de puntuación, por menor precio
        validos.sort(Comparator
                .comparingInt(CandidatoPuntuado::score).reversed()
                .thenComparing(cp -> cp.item().getPrecio())
        );

        return validos.get(0).item();
    }

    private record CandidatoPuntuado(PrecioCache item, int score) {}

    private String normalizar(String texto) {
        if (texto == null) return "";
        return texto.toLowerCase()
                .replace("á", "a").replace("é", "e").replace("í", "i").replace("ó", "o").replace("ú", "u")
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
