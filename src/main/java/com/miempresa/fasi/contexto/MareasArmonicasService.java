package com.miempresa.fasi.contexto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.JulianFields;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MareasArmonicasService {

    private final MareaConstanteRepository repo;

    // Intervalo de muestreo en minutos para detectar máximos/mínimos
    private static final int PASO_MINUTOS = 5;

    // ══════════════════════════════════════════════════════════
    //  API PÚBLICA
    // ══════════════════════════════════════════════════════════

    /**
     * Devuelve las pleamares y bajamares del día ordenadas por hora.
     */
    public List<PuntoMarea> calcularMareasDia(LocalDate fecha) {
        List<MareaConstante> constantes = repo.findByActivoTrue();
        if (constantes.isEmpty()) {
            log.warn("⚠️ No hay constantes armónicas en BD — usando fallback");
            return List.of();
        }

        // Separar Z0 del resto
        double z0 = constantes.stream()
                .filter(c -> "Z0".equals(c.getNombre()))
                .mapToDouble(MareaConstante::getAmplitud)
                .findFirst()
                .orElse(1.98);

        List<MareaConstante> armonicos = constantes.stream()
                .filter(c -> !"Z0".equals(c.getNombre()))
                .toList();

        // Calcular factores nodales para este año
        FactoresNodales fn = calcularFactoresNodales(fecha.getYear());

        // Muestrear la curva de marea cada PASO_MINUTOS durante el día
        int totalPasos = (24 * 60) / PASO_MINUTOS;
        double[] alturas = new double[totalPasos + 1];

        for (int i = 0; i <= totalPasos; i++) {
            double horaDecimal = i * PASO_MINUTOS / 60.0;
            alturas[i] = z0 + evaluarArmonicos(armonicos, fn, fecha, horaDecimal);
        }

        // Detectar máximos y mínimos locales
        List<PuntoMarea> puntos = new ArrayList<>();
        for (int i = 1; i < totalPasos; i++) {
            boolean esMaximo = alturas[i] > alturas[i - 1] && alturas[i] > alturas[i + 1];
            boolean esMinimo = alturas[i] < alturas[i - 1] && alturas[i] < alturas[i + 1];

            if (esMaximo || esMinimo) {
                // Refinar con interpolación parabólica para mayor precisión
                double minutosRefinados = refinarExtremo(alturas, i);
                int minutosTotal = (int) Math.round(minutosRefinados);
                int hora   = minutosTotal / 60;
                int minuto = minutosTotal % 60;

                // Altura interpolada en el punto exacto
                double alturaExacta = z0 + evaluarArmonicos(
                        armonicos, fn, fecha, minutosRefinados / 60.0);

                puntos.add(new PuntoMarea(
                        String.format("%02d:%02d", hora, minuto),
                        Math.round(alturaExacta * 100.0) / 100.0,
                        esMaximo ? TipoMarea.PLEAMAR : TipoMarea.BAJAMAR
                ));
            }
        }

        log.info("🌊 Mareas {}: {} puntos calculados", fecha, puntos.size());
        return puntos;
    }

    /**
     * Devuelve la altura del mar en un momento concreto (en metros).
     */
    public double alturaEnMomento(LocalDate fecha, double horaDecimal) {
        List<MareaConstante> constantes = repo.findByActivoTrue();
        double z0 = constantes.stream()
                .filter(c -> "Z0".equals(c.getNombre()))
                .mapToDouble(MareaConstante::getAmplitud)
                .findFirst().orElse(1.98);
        List<MareaConstante> armonicos = constantes.stream()
                .filter(c -> !"Z0".equals(c.getNombre())).toList();
        FactoresNodales fn = calcularFactoresNodales(fecha.getYear());
        return z0 + evaluarArmonicos(armonicos, fn, fecha, horaDecimal);
    }

    // ══════════════════════════════════════════════════════════
    //  NÚCLEO MATEMÁTICO
    // ══════════════════════════════════════════════════════════

    /**
     * Evalúa la suma de armónicos en un instante dado.
     *
     * h(t) = Σ fᵢ · Hᵢ · cos(ωᵢ·t + Vᵢ + uᵢ - gᵢ)
     */
    private double evaluarArmonicos(List<MareaConstante> armonicos,
                                     FactoresNodales fn,
                                     LocalDate fecha,
                                     double horaDecimal) {
        // Horas transcurridas desde el 1 de enero del año a las 00:00 UTC
        double horaDelAnio = (fecha.getDayOfYear() - 1) * 24.0 + horaDecimal;

        double suma = 0.0;
        for (MareaConstante c : armonicos) {
            double f = fn.getF(c.getNombre());
            double u = fn.getU(c.getNombre());
            double V = calcularV0(c.getNombre(), fecha.getYear());

            // Argumento en grados → convertir a radianes para Math.cos
            double argumento = c.getVelocidad() * horaDelAnio + V + u - c.getFase();
            double argRad    = Math.toRadians(argumento);

            suma += f * c.getAmplitud() * Math.cos(argRad);
        }
        return suma;
    }

    /**
     * Interpolación parabólica para refinar la posición del extremo.
     * Devuelve los minutos exactos (con decimales) del máximo/mínimo.
     */
    private double refinarExtremo(double[] alturas, int i) {
        double y1 = alturas[i - 1];
        double y2 = alturas[i];
        double y3 = alturas[i + 1];
        // Fórmula de interpolación parabólica
        double delta = (y1 - y3) / (2.0 * (y1 - 2 * y2 + y3));
        return (i + delta) * PASO_MINUTOS;
    }

    // ══════════════════════════════════════════════════════════
    //  FACTORES NODALES (ciclo de 18.6 años de la Luna)
    // ══════════════════════════════════════════════════════════

    /**
     * Calcula los factores nodales f y u para un año dado.
     * Estos corrigen la amplitud y fase por la inclinación orbital lunar.
     */
    private FactoresNodales calcularFactoresNodales(int year) {
        // Número de años julianos desde J2000.0
        double T = (year - 2000.0) / 100.0;

        // Longitud del nodo ascendente lunar (grados)
        double N = 125.0445 - 1934.1363 * T;
        N = N % 360.0;
        if (N < 0) N += 360.0;

        double Nrad = Math.toRadians(N);

        // Factores f (corrección de amplitud)
        Map<String, Double> f = new HashMap<>();
        f.put("M2",  1.0 - 0.037 * Math.cos(Nrad));
        f.put("S2",  1.0);  // Solar — sin corrección nodal
        f.put("N2",  1.0 - 0.037 * Math.cos(Nrad));
        f.put("K1",  1.006 + 0.115 * Math.cos(Nrad) - 0.009 * Math.cos(2 * Nrad));
        f.put("O1",  1.009 + 0.187 * Math.cos(Nrad) - 0.015 * Math.cos(2 * Nrad));
        f.put("M4",  Math.pow(1.0 - 0.037 * Math.cos(Nrad), 2));
        f.put("MS4", 1.0 - 0.037 * Math.cos(Nrad));

        // Factores u (corrección de fase, en grados)
        Map<String, Double> u = new HashMap<>();
        u.put("M2",  -2.14 * Math.sin(Nrad));
        u.put("S2",   0.0);
        u.put("N2",  -2.14 * Math.sin(Nrad));
        u.put("K1",  -8.86 * Math.sin(Nrad) + 0.68 * Math.sin(2 * Nrad));
        u.put("O1",   10.8 * Math.sin(Nrad) - 1.34 * Math.sin(2 * Nrad));
        u.put("M4",  -4.28 * Math.sin(Nrad));
        u.put("MS4", -2.14 * Math.sin(Nrad));

        return new FactoresNodales(f, u);
    }

    /**
     * Fase astronómica V0 para cada armónico al inicio del año.
     * Valores aproximados suficientes para precisión de ±5 min.
     */
    private double calcularV0(String nombre, int year) {
        // Horas desde J2000.0 (1 enero 2000 12:00 UTC) hasta 1 enero del año pedido
        double diasDesdeJ2000 = (year - 2000) * 365.25;
        double T = diasDesdeJ2000 / 36525.0;

        // Argumentos astronómicos fundamentales (grados)
        double s  = 218.3164 + 13.1763966 * diasDesdeJ2000;  // longitud media Luna
        double h  = 280.4665 +  0.9856474 * diasDesdeJ2000;  // longitud media Sol
        double p  = 83.3532  +  0.1114041 * diasDesdeJ2000;  // perigeo lunar
        double N  = 125.0445 -  0.0529539 * diasDesdeJ2000;  // nodo ascendente

        // Normalizar a [0, 360)
        s = ((s % 360) + 360) % 360;
        h = ((h % 360) + 360) % 360;
        p = ((p % 360) + 360) % 360;
        N = ((N % 360) + 360) % 360;

        return switch (nombre) {
            case "M2"  -> 2 * h - 2 * s;
            case "S2"  -> 0.0;
            case "N2"  -> 2 * h - 3 * s + p;
            case "K1"  -> h + 90.0;
            case "O1"  -> h - 2 * s - 90.0;
            case "M4"  -> 4 * h - 4 * s;
            case "MS4" -> 4 * h - 2 * s;
            default    -> 0.0;
        };
    }

    // ══════════════════════════════════════════════════════════
    //  CLASES INTERNAS
    // ══════════════════════════════════════════════════════════

    public enum TipoMarea { PLEAMAR, BAJAMAR }

    public record PuntoMarea(String hora, double altura, TipoMarea tipo) {
        public String emoji() {
            return tipo == TipoMarea.PLEAMAR ? "🌊" : "🏖️";
        }
        public String label() {
            return tipo == TipoMarea.PLEAMAR ? "Pleamar" : "Bajamar";
        }
    }

    private record FactoresNodales(Map<String, Double> f, Map<String, Double> u) {
        double getF(String nombre) { return f.getOrDefault(nombre, 1.0); }
        double getU(String nombre) { return u.getOrDefault(nombre, 0.0); }
    }
}
