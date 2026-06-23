package com.miempresa.fasi.contexto;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MareasSolarService {

    private final ContextoFamiliarRepository  contextoRepo;
    private final MareaConstanteRepository    mareaRepo;

    // Intervalo de muestreo en minutos
    private static final int PASO_MIN = 5;

    // ══════════════════════════════════════════════════════════
    //  API PÚBLICA — misma firma que antes, el controller no cambia
    // ══════════════════════════════════════════════════════════

    public Map<String, String> getDatosContextuales(LocalDate fecha) {
        Map<String, String> datos = new LinkedHashMap<>();

        // ── Sol ──────────────────────────────────────────────
        calcularSol(fecha, datos);

        // ── Mareas ───────────────────────────────────────────
        calcularMareas(fecha, datos);

        // ── Ciudad ───────────────────────────────────────────
        String ciudad = contextoRepo.findByClave("ciudad_base")
                .map(ContextoFamiliar::getValor)
                .orElse("Sanlúcar de Barrameda");
        datos.put("ciudad", ciudad);

        return datos;
    }

    // ══════════════════════════════════════════════════════════
    //  MAREAS — análisis armónico con constantes de Bonanza
    // ══════════════════════════════════════════════════════════

    private void calcularMareas(LocalDate fecha, Map<String, String> datos) {
        List<MareaConstante> constantes = mareaRepo.findByActivoTrue();

        if (constantes.isEmpty()) {
            log.warn("⚠️ Sin constantes armónicas en BD — mareas no disponibles");
            datos.put("pleamar1",  "--:--");
            datos.put("pleamar2",  "--:--");
            datos.put("bajamar1",  "--:--");
            datos.put("bajamar2",  "--:--");
            return;
        }

        double z0 = constantes.stream()
                .filter(c -> "Z0".equals(c.getNombre()))
                .mapToDouble(MareaConstante::getAmplitud)
                .findFirst().orElse(1.98);

        List<MareaConstante> armonicos = constantes.stream()
                .filter(c -> !"Z0".equals(c.getNombre()))
                .toList();

        FactoresNodales fn = calcularFactoresNodales(fecha.getYear());

        // Muestrear curva de marea cada PASO_MIN minutos durante el día
        int pasos = (24 * 60) / PASO_MIN;
        double[] alturas = new double[pasos + 1];
        for (int i = 0; i <= pasos; i++) {
            alturas[i] = z0 + evaluarArmonicos(armonicos, fn, fecha, i * PASO_MIN / 60.0);
        }

        // Detectar máximos y mínimos locales
        List<String> pleamares = new ArrayList<>();
        List<String> bajamares = new ArrayList<>();

        for (int i = 1; i < pasos; i++) {
            boolean esMax = alturas[i] > alturas[i - 1] && alturas[i] > alturas[i + 1];
            boolean esMin = alturas[i] < alturas[i - 1] && alturas[i] < alturas[i + 1];

            if (esMax || esMin) {
                // Refinamiento parabólico para mayor precisión
                double delta = (alturas[i-1] - alturas[i+1])
                             / (2.0 * (alturas[i-1] - 2*alturas[i] + alturas[i+1]));
                int minutosExactos = (int) Math.round((i + delta) * PASO_MIN);
                minutosExactos = Math.max(0, Math.min(minutosExactos, 24 * 60 - 1));

                String hora = String.format("%02d:%02d",
                        minutosExactos / 60, minutosExactos % 60);

                if (esMax) pleamares.add(hora);
                else       bajamares.add(hora);
            }
        }

        datos.put("pleamar1", pleamares.size() > 0 ? pleamares.get(0) : "--:--");
        datos.put("pleamar2", pleamares.size() > 1 ? pleamares.get(1) : "--:--");
        datos.put("bajamar1", bajamares.size() > 0 ? bajamares.get(0) : "--:--");
        datos.put("bajamar2", bajamares.size() > 1 ? bajamares.get(1) : "--:--");

        log.info("🌊 Mareas {}: pleamares={} bajamares={}", fecha, pleamares, bajamares);
    }

    // ══════════════════════════════════════════════════════════
    //  NÚCLEO MATEMÁTICO
    // ══════════════════════════════════════════════════════════

    /**
     * h(t) = Σ fᵢ · Hᵢ · cos(ωᵢ·t + V₀ᵢ + uᵢ - gᵢ)
     *
     * @param horaDecimal horas del día [0.0 – 24.0)
     */
    private double evaluarArmonicos(List<MareaConstante> armonicos,
                                     FactoresNodales fn,
                                     LocalDate fecha,
                                     double horaDecimal) {
        // Horas desde el 1 de enero del año a las 00:00 UTC
        double horaDelAnio = (fecha.getDayOfYear() - 1) * 24.0 + horaDecimal;

        double suma = 0.0;
        for (MareaConstante c : armonicos) {
            double f   = fn.getF(c.getNombre());
            double u   = fn.getU(c.getNombre());
            double V0  = calcularV0(c.getNombre(), fecha.getYear());
            double arg = c.getVelocidad() * horaDelAnio + V0 + u - c.getFase();
            suma += f * c.getAmplitud() * Math.cos(Math.toRadians(arg));
        }
        return suma;
    }

    // ── Factores nodales (ciclo de 18.6 años) ────────────────

    private FactoresNodales calcularFactoresNodales(int year) {
        double diasDesdeJ2000 = (year - 2000) * 365.25;
        double T  = diasDesdeJ2000 / 36525.0;
        double N  = ((125.0445 - 1934.1363 * T) % 360 + 360) % 360;
        double Nr = Math.toRadians(N);

        Map<String, Double> f = new HashMap<>();
        f.put("M2",  1.0 - 0.037 * Math.cos(Nr));
        f.put("S2",  1.0);
        f.put("N2",  1.0 - 0.037 * Math.cos(Nr));
        f.put("K1",  1.006 + 0.115 * Math.cos(Nr) - 0.009 * Math.cos(2 * Nr));
        f.put("O1",  1.009 + 0.187 * Math.cos(Nr) - 0.015 * Math.cos(2 * Nr));
        f.put("M4",  Math.pow(1.0 - 0.037 * Math.cos(Nr), 2));
        f.put("MS4", 1.0 - 0.037 * Math.cos(Nr));

        Map<String, Double> u = new HashMap<>();
        u.put("M2",  -2.14  * Math.sin(Nr));
        u.put("S2",   0.0);
        u.put("N2",  -2.14  * Math.sin(Nr));
        u.put("K1",  -8.86  * Math.sin(Nr) + 0.68 * Math.sin(2 * Nr));
        u.put("O1",   10.8  * Math.sin(Nr) - 1.34 * Math.sin(2 * Nr));
        u.put("M4",  -4.28  * Math.sin(Nr));
        u.put("MS4", -2.14  * Math.sin(Nr));

        return new FactoresNodales(f, u);
    }

    // ── Fase astronómica V0 al inicio del año ─────────────────

    private double calcularV0(String nombre, int year) {
        double d  = (year - 2000) * 365.25;
        double s  = ((218.3164 + 13.1763966 * d) % 360 + 360) % 360;
        double h  = ((280.4665 +  0.9856474 * d) % 360 + 360) % 360;
        double p  = (( 83.3532 +  0.1114041 * d) % 360 + 360) % 360;

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
    //  SOL — igual que antes
    // ══════════════════════════════════════════════════════════

    private void calcularSol(LocalDate fecha, Map<String, String> datos) {
        // Coordenadas de Sanlúcar de Barrameda
        double lat =  36.7833;
        double lon =  -6.3500;

        double[] sol = calcularSalPuestaSol(
                fecha.getYear(), fecha.getMonthValue(), fecha.getDayOfMonth(),
                lat, lon);

        datos.put("amanecer",  minutosAHora((int) sol[0]));
        datos.put("atardecer", minutosAHora((int) sol[1]));
    }

    private double[] calcularSalPuestaSol(int year, int month, int day,
                                           double lat, double lon) {
        // Algoritmo NOAA simplificado
        double jd = julianDay(year, month, day);
        double n  = jd - 2451545.0;

        double L = (280.460 + 0.9856474 * n) % 360;
        double g = Math.toRadians((357.528 + 0.9856003 * n) % 360);
        double lambda = Math.toRadians(L + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));

        double epsilon = Math.toRadians(23.439 - 0.0000004 * n);
        double sinDec  = Math.sin(epsilon) * Math.sin(lambda);
        double dec     = Math.asin(sinDec);

        double cosH = (Math.cos(Math.toRadians(90.833))
                      - Math.sin(Math.toRadians(lat)) * sinDec)
                    / (Math.cos(Math.toRadians(lat)) * Math.cos(dec));

        // Si el sol no sale/no se pone (polar)
        if (cosH < -1 || cosH > 1) return new double[]{0, 0};

        double H   = Math.toDegrees(Math.acos(cosH));
        double eot = calcularEcuacionTiempo(n);

        double salidaUTC  = 720 - 4 * (lon + H) - eot;
        double puestaUTC  = 720 - 4 * (lon - H) - eot;

        // Convertir a hora local (España peninsular: UTC+2 en verano)
        int offsetMin = 120;
        return new double[]{salidaUTC + offsetMin, puestaUTC + offsetMin};
    }

    private double calcularEcuacionTiempo(double n) {
        double L0 = Math.toRadians(280.460 + 0.9856474 * n);
        double e  = 0.016708;
        double M  = Math.toRadians(357.528 + 0.9856003 * n);
        return 4 * Math.toDegrees(
                -2 * e * Math.sin(M)
                + Math.sin(2 * L0)
                - 2 * e * Math.sin(M) * Math.cos(2 * L0));
    }

    private double julianDay(int y, int m, int d) {
        if (m <= 2) { y--; m += 12; }
        int A = y / 100;
        int B = 2 - A + A / 4;
        return (int)(365.25 * (y + 4716))
             + (int)(30.6001 * (m + 1))
             + d + B - 1524.5;
    }

    private String minutosAHora(int minutos) {
        minutos = Math.max(0, Math.min(minutos, 24 * 60 - 1));
        return String.format("%02d:%02d", minutos / 60, minutos % 60);
    }

    // ══════════════════════════════════════════════════════════
    //  CLASES INTERNAS
    // ══════════════════════════════════════════════════════════

    private record FactoresNodales(Map<String, Double> f, Map<String, Double> u) {
        double getF(String n) { return f.getOrDefault(n, 1.0); }
        double getU(String n) { return u.getOrDefault(n, 0.0); }
    }

    // Clase de compatibilidad — la usaba el controller antiguo
    public record MareasDia(String pleamar1, String pleamar2,
                             String bajamar1, String bajamar2) {}
}
