package com.miempresa.fasi.calendario.service;

import com.miempresa.fasi.calendario.Evento;
import com.miempresa.fasi.calendario.EventoIaService;
import com.miempresa.fasi.calendario.EventoIaRequest;
import com.miempresa.fasi.calendario.EventoIaResponse;
import com.miempresa.fasi.calendario.EventoParserRegex;
import com.miempresa.fasi.calendario.dto.EventoMinimo;
import com.miempresa.fasi.calendario.dto.EventoPreview;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * SERVICIO UNIFICADO DE INTERPRETACIÓN
 *
 * Flujo:
 * 1. IA (Gemini Flash) → Motor principal para comprensión de lenguaje natural
 * 2. REGEX → Fallback de respaldo si la IA no está disponible
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventoParserService {

    private final EventoParserRegex regexParser;
    private final EventoIaService iaService;

    public EventoParseResponse interpretar(String texto, LocalDate fechaContexto) {
        long inicio = System.currentTimeMillis();

        if (texto == null || texto.isBlank()) {
            return EventoParseResponse.error(
                "El texto está vacío. Dicta o escribe algo.",
                0
            );
        }

        // ── PASO 1: Intentar con IA (Gemini Flash) como motor prioritario ──
        try {
            log.info("🤖 IA: Interpretando con Gemini Flash: '{}'", texto);
            EventoIaRequest request = new EventoIaRequest();
            request.setTexto(texto);
            if (fechaContexto != null) {
                request.setFechaContexto(fechaContexto.toString());
            }

            EventoIaResponse iaResp = iaService.interpretar(request);
            long elapsed = System.currentTimeMillis() - inicio;

            if (iaResp.isExito() && iaResp.getEventoPreview() != null) {
                Evento evento = iaResp.getEventoPreview();
                log.info("✅ Gemini Flash interpretó con éxito en {}ms: '{}'", elapsed, evento.getTitulo());

                validarEvento(evento, texto);

                boolean esRecurrente = evento.getDatosExtra() != null &&
                                      Boolean.TRUE.equals(evento.getDatosExtra().get("recurrente"));

                EventoMinimo minimo = EventoMinimo.fromEvento(evento);
                minimo.setEsRecurrente(esRecurrente);

                if (esRecurrente && evento.getFechaFin() == null) {
                    return EventoParseResponse.necesitaLimite(
                        minimo,
                        construirMensajeLimite(evento),
                        elapsed
                    );
                }

                EventoPreview preview = EventoPreview.fromEventoMinimo(minimo);
                return EventoParseResponse.success(minimo, preview, esRecurrente, elapsed);
            } else {
                log.warn("⚠️ IA devolvió éxito falso o preview nulo. Probando fallback REGEX...");
            }

        } catch (Exception e) {
            log.warn("⚠️ Error en llamada a IA (Gemini): {}. Activando fallback REGEX...", e.getMessage());
        }

        // ── PASO 2: Fallback REGEX si la IA falló o no dio resultado ──
        try {
            log.info("🔍 REGEX (Fallback): Intentando interpretar: {}", texto);
            Optional<EventoParserRegex.EventoParseResult> resultadoRegex =
                regexParser.parse(texto, fechaContexto);

            long elapsed = System.currentTimeMillis() - inicio;

            if (resultadoRegex.isPresent()) {
                EventoParserRegex.EventoParseResult res = resultadoRegex.get();
                Evento evento = res.evento();
                boolean esRecurrente = res.esRecurrente();

                log.info("✅ REGEX Fallback completado en {}ms", elapsed);

                EventoMinimo minimo = EventoMinimo.fromEvento(evento);
                minimo.setEsRecurrente(esRecurrente);

                if (esRecurrente && evento.getFechaFin() == null) {
                    return EventoParseResponse.necesitaLimite(
                        minimo,
                        construirMensajeLimite(evento),
                        elapsed
                    );
                }

                EventoPreview preview = EventoPreview.fromEventoMinimo(minimo);
                return EventoParseResponse.success(minimo, preview, esRecurrente, elapsed);
            }

            return EventoParseResponse.error("No se pudo interpretar el evento.", elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - inicio;
            log.error("❌ Error en fallback REGEX", e);
            return EventoParseResponse.error("Error inesperado: " + e.getMessage(), elapsed);
        }
    }

    public EventoParseResponse procesarLimite(EventoMinimo eventoBase, String respuestaUsuario) {
        long inicio = System.currentTimeMillis();

        try {
            log.info("Procesando límite: {}", respuestaUsuario);
            Evento evento = eventoBase.toEvento();
            EventoIaResponse iaResp = iaService.procesarLimite(evento, respuestaUsuario);

            long elapsed = System.currentTimeMillis() - inicio;

            if (!iaResp.isExito()) {
                return EventoParseResponse.error(iaResp.getMensajeError(), elapsed);
            }

            Evento eventoCompleto = iaResp.getEventoPreview();
            EventoMinimo minimoCompleto = EventoMinimo.fromEvento(eventoCompleto);
            minimoCompleto.setEsRecurrente(true);

            EventoPreview preview = EventoPreview.fromEventoMinimo(minimoCompleto);
            return EventoParseResponse.success(minimoCompleto, preview, true, elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - inicio;
            return EventoParseResponse.error("Error al procesar el límite: " + e.getMessage(), elapsed);
        }
    }

    private void validarEvento(Evento evento, String textoOriginal) {
        if (evento.getTitulo() == null || evento.getTitulo().isBlank()) {
            evento.setTitulo("Nuevo Evento");
        }
        if (evento.getFechaInicio() == null) {
            evento.setFechaInicio(LocalDate.now());
        }
        if (evento.getTipo() == null) {
            evento.setTipo(Evento.TipoEvento.OTRO);
        }
        if (evento.getFechaFin() != null && evento.getFechaFin().isBefore(evento.getFechaInicio())) {
            evento.setFechaFin(null);
        }
        if (evento.getHoraFin() != null && evento.getHoraInicio() != null && evento.getHoraFin().isBefore(evento.getHoraInicio())) {
            evento.setHoraFin(null);
        }
    }

    private String construirMensajeLimite(Evento evento) {
        StringBuilder sb = new StringBuilder();
        sb.append("Evento recurrente detectado: ").append(evento.getTitulo()).append("\n\n");

        Map<String, Object> extra = evento.getDatosExtra();
        if (extra != null) {
            String patron = (String) extra.get("patron");
            if ("WEEKLY".equals(patron)) sb.append("Se repetirá todas las semanas.\n\n");
            else if ("DAILY".equals(patron)) sb.append("Se repetirá todos los días.\n\n");
            else if ("MONTHLY".equals(patron)) sb.append("Se repetirá cada mes.\n\n");
            else if ("YEARLY".equals(patron)) sb.append("Se repetirá cada año.\n\n");
        }

        sb.append("¿Hasta cuándo quieres que se repita?\n");
        return sb.toString();
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @lombok.Builder
    public static class EventoParseResponse {
        private String paso;
        private EventoMinimo evento;
        private EventoPreview preview;
        private Boolean esRecurrente;
        private String mensaje;
        private Long tiempoMs;

        public static EventoParseResponse success(EventoMinimo evento, EventoPreview preview, Boolean esRecurrente, long tiempoMs) {
            return EventoParseResponse.builder()
                .paso("success")
                .evento(evento)
                .preview(preview)
                .esRecurrente(esRecurrente)
                .tiempoMs(tiempoMs)
                .build();
        }

        public static EventoParseResponse necesitaLimite(EventoMinimo evento, String mensaje, long tiempoMs) {
            return EventoParseResponse.builder()
                .paso("necesita_limite")
                .evento(evento)
                .mensaje(mensaje)
                .tiempoMs(tiempoMs)
                .build();
        }

        public static EventoParseResponse error(String mensaje, long tiempoMs) {
            return EventoParseResponse.builder()
                .paso("error")
                .mensaje(mensaje)
                .tiempoMs(tiempoMs)
                .build();
        }
    }
}
