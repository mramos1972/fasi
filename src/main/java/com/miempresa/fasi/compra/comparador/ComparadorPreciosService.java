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

    // Palabras prohibidas si el usuario NO las mencionó explícitamente en su búsqueda
    private static final List<String> PALABRAS_TRAMPA = List.of(
            "tortita", "tortitas", "barrita", "barritas", "chocolate", "chocolates",
            "galleta", "galletas", "snack", "postre", "flan", "yogur", "crema", "salsa"
    );

    // Diccionario de equivalencias / sinónimos habituales de supermercado
    private static final Map<String, List<String>> SINONIMOS = Map.of(
            "espaguetis", List.of("spaghetti", "spaghettis", "espagueti"),
            "espagueti", List.of("spaghetti", "spaghettis", "espaguetis"),
            "leche de arroz", List.of("bebida de arroz", "bebida arroz", "arroz bebida"),
            "arroz con leche", List.of("postre arroz con leche")
    );

    @Transactional(readOnly = true)
    public List<ResultadoComparativaDto> compararLista(List<String> articulos) {
        if (articulos == null || articulos.isEmpty()) {
            return Collections.emptyList();
        }

        List<Supermercado> supers = supermercadoRepo.findByActivoTrue();
        List<ResultadoComparativaDto> resultados = new ArrayList<>();
        LocalDateTime limiteCache = LocalDateTime.now().minusDays(90);

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

        // El que más productos encuentre gana. En caso de empate, la cesta más barata.
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
        List<String> variantesBusqueda = expandirVariantes(busquedaNorm);
        List<CandidatoPuntuado> validos = new ArrayList<>();

        for (PrecioCache pc : candidatos) {
            String prodNorm = normalizar(pc.getProductoNombre());

            // 1. Descartar alimentos trampa (ej: si busco "leche de arroz", descartar "tortitas", "chocolate")
            boolean tieneTrampa = false;
            for (String trampa : PALABRAS_TRAMPA) {
                if (prodNorm.contains(trampa) && !busquedaNorm.contains(trampa)) {
                    tieneTrampa = true;
                    break;
                }
            }
            if (tieneTrampa) continue;

            // 2. Evaluar coincidencia con las variantes posibles
            int mejorScore = 0;
            for (String variante : variantesBusqueda) {
                int score = calcularScore(variante, prodNorm);
                if (score > mejorScore) {
                    mejorScore = score;
                }
            }

            // Umbral mínimo de confianza para evitar emparejamientos aleatorios
            if (mejorScore >= 60) {
                validos.add(new CandidatoPuntuado(pc, mejorScore));
            }
        }

        if (validos.isEmpty()) {
            return null;
        }

        // Ordenar por mayor puntuación y, en caso de empate, por el producto más barato
        validos.sort(Comparator
                .comparingInt(CandidatoPuntuado::score).reversed()
                .thenComparing(cp -> cp.item().getPrecio())
        );

        return validos.get(0).item();
    }

    private int calcularScore(String busqueda, String producto) {
        if (producto.equals(busqueda)) return 100;

        String[] tokens = busqueda.split("\\s+");
        int tokensRelevantes = 0;
        int tokensEncontrados = 0;

        for (String tok : tokens) {
            if (tok.length() <= 2) continue; // omitir de, el, la, con...
            tokensRelevantes++;

            // Comprobar coincidencia de palabra completa o prefijo
            Pattern p = Pattern.compile("\\b" + Pattern.quote(tok));
            if (p.matcher(producto).find()) {
                tokensEncontrados++;
            }
        }

        if (tokensRelevantes == 0) return 0;

        // Si buscó múltiples palabras (ej: "arroz calasparra", "leche arroz"), deben aparecer todas
        if (tokensRelevantes > 1 && tokensEncontrados < tokensRelevantes) {
            return 0;
        }

        // Si buscó 1 palabra (ej: "arroz") y no está, fuera
        if (tokensRelevantes == 1 && tokensEncontrados == 0) {
            return 0;
        }

        int score = (tokensEncontrados * 30);

        // Bonificación si el producto empieza por la palabra principal
        if (producto.startsWith(tokens[0])) {
            score += 25;
        }

        // Penalizar productos excesivamente largos con muchos agregados secundarios
        if (producto.length() > busqueda.length() + 35) {
            score -= 10;
        }

        return Math.max(score, 0);
    }

    private List<String> expandirVariantes(String texto) {
        List<String> variantes = new ArrayList<>();
        variantes.add(texto);

        for (Map.Entry<String, List<String>> entry : SINONIMOS.entrySet()) {
            if (texto.contains(entry.getKey())) {
                for (String sin : entry.getValue()) {
                    variantes.add(texto.replace(entry.getKey(), sin));
                }
            }
        }
        return variantes;
    }

    private record CandidatoPuntuado(PrecioCache item, int score) {}

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
