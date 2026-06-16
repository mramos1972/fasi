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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SERVICIO UNIFICADO DE INTERPRETACIÓN
 * 
 * Flujo:
 * 1. REGEX (rápido, seguro) → si coincide, devolver
 * 2. IA (lento, flexible) → si REGEX falla
 * 3. Validar resultado
 * 4. Devolver EventoMinimo (nunca falla, siempre devuelve algo)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventoParserService {

    private final EventoParserRegex regexParser;
    private final EventoIaService iaService;

    /**
     * Interpreta texto (dictado o escrito) y devuelve EventoMinimo.
     */
    public EventoParseResponse interpretar(String texto, LocalDate fechaContexto) {
        long inicio = System.currentTimeMillis();
        
        if (texto == null || texto.isBlank()) {
            return EventoParseResponse.error(
                "El texto está vacío. Dicta o escribe algo.",
                0
            );
        }

        try {
            // PASO 1: REGEX (< 50ms)
            log.info("REGEX: Intentando interpretar: {}", texto);
            Optional<EventoParserRegex.EventoParseResult> resultadoRegex = 
                regexParser.parse(texto, fechaContexto);

            if (resultadoRegex.isPresent()) {
                EventoParserRegex.EventoParseResult res = resultadoRegex.get();
                Evento evento = res.evento();
                boolean esRecurrente = res.esRecurrente();

                long elapsed = System.currentTimeMillis() - inicio;
                log.info("REGEX OK en {}ms (recurrente: {})", elapsed, esRecurrente);

                EventoMinimo minimo = EventoMinimo.fromEvento(evento);
                minimo.setEsRecurrente(esRecurrente);

                if (esRecurrente && evento.getFechaFin() == null) {
                    log.info("Recurrencia sin limite - pedir limite");
                    return EventoParseResponse.necesitaLimite(
                        minimo,
                        construirMensajeLimite(evento),
                        elapsed
                    );
                }

                EventoPreview preview = EventoPreview.fromEventoMinimo(minimo);
                return EventoParseResponse.success(
                    minimo,
                    preview,
                    esRecurrente,
                    elapsed
                );
            }

            log.info("REGEX no coincidio - usando IA");

            // PASO 2: IA (1-3s)
            log.info("IA: Interpretando con Ollama...");
            EventoIaRequest request = new EventoIaRequest();
            request.setTexto(texto);
            request.setFechaContexto(fechaContexto.toString());
            
            EventoIaResponse iaResp = iaService.interpretar(request);

            long elapsed = System.currentTimeMillis() - inicio;

            if (!iaResp.isExito()) {
                log.error("IA Error: {}", iaResp.getMensajeError());
                return EventoParseResponse.error(
                    iaResp.getMensajeError(),
                    elapsed
                );
            }

            Evento evento = iaResp.getEventoPreview();
            if (evento == null) {
                log.error("IA devolvio null");
                return EventoParseResponse.error(
                    "La IA no pudo interpretar el texto. Intenta reformularlo.",
                    elapsed
                );
            }

            try {
                validarEvento(evento, texto);
            } catch (IllegalArgumentException e) {
                log.error("VALIDACION Error: {}", e.getMessage());
                return EventoParseResponse.error(
                    e.getMessage(),
                    elapsed
                );
            }

            boolean esRecurrente = evento.getDatosExtra() != null && 
                                  Boolean.TRUE.equals(evento.getDatosExtra().get("recurrente"));

            EventoMinimo minimo = EventoMinimo.fromEvento(evento);
            minimo.setEsRecurrente(esRecurrente);

            if (esRecurrente && evento.getFechaFin() == null) {
                log.info("Recurrencia sin limite - pedir limite");
                return EventoParseResponse.necesitaLimite(
                    minimo,
                    construirMensajeLimite(evento),
                    elapsed
                );
            }

            log.info("IA OK en {}ms", elapsed);
            EventoPreview preview = EventoPreview.fromEventoMinimo(minimo);
            return EventoParseResponse.success(
                minimo,
                preview,
                esRecurrente,
                elapsed
            );

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - inicio;
            log.error("ERROR no controlado", e);
            return EventoParseResponse.error(
                "Error inesperado: " + e.getMessage(),
                elapsed
            );
        }
    }

    /**
     * Procesa la respuesta del usuario sobre el límite de recurrencia.
     */
    public EventoParseResponse procesarLimite(EventoMinimo eventoBase, String respuestaUsuario) {
        long inicio = System.currentTimeMillis();

        try {
            log.info("Procesando limite: {}", respuestaUsuario);

            Evento evento = eventoBase.toEvento();
            EventoIaResponse iaResp = iaService.procesarLimite(evento, respuestaUsuario);

            long elapsed = System.currentTimeMillis() - inicio;

            if (!iaResp.isExito()) {
                log.error("Error procesando limite: {}", iaResp.getMensajeError());
                return EventoParseResponse.error(
                    iaResp.getMensajeError(),
                    elapsed
                );
            }

            Evento eventoCompleto = iaResp.getEventoPreview();
            EventoMinimo minimoCompleto = EventoMinimo.fromEvento(eventoCompleto);
            minimoCompleto.setEsRecurrente(true);

            log.info("Limite procesado: hasta {}", eventoCompleto.getFechaFin());

            EventoPreview preview = EventoPreview.fromEventoMinimo(minimoCompleto);
            return EventoParseResponse.success(
                minimoCompleto,
                preview,
                true,
                elapsed
            );

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - inicio;
            log.error("Error procesando limite", e);
            return EventoParseResponse.error(
                "Error al procesar el limite: " + e.getMessage(),
                elapsed
            );
        }
    }

    /**
     * Valida que el evento tenga los campos obligatorios.
     */
    private void validarEvento(Evento evento, String textoOriginal) throws IllegalArgumentException {
        if (evento.getTitulo() == null || evento.getTitulo().isBlank()) {
            throw new IllegalArgumentException("No pude determinar el nombre del evento");
        }

        if (evento.getFechaInicio() == null) {
            throw new IllegalArgumentException("No pude determinar la fecha del evento");
        }

        if (evento.getTipo() == null) {
            evento.setTipo(Evento.TipoEvento.OTRO);
        }

        if (evento.getHoraInicio() != null && 
            evento.getHoraInicio().getHour() == 14 && 
            evento.getHoraInicio().getMinute() == 0) {
            
            String textoLower = textoOriginal.toLowerCase();
            if (!textoLower.contains("14") && 
                !textoLower.contains("dos") &&
                !textoLower.contains("2 p.m") &&
                !textoLower.contains("14:00")) {
                
                log.warn("IA invento hora 14:00 sin contexto - eliminando");
                evento.setHoraInicio(null);
            }
        }

        if (evento.getFechaFin() != null && 
            evento.getFechaFin().isBefore(evento.getFechaInicio())) {
            throw new IllegalArgumentException("La fecha fin no puede ser antes que la fecha inicio");
        }

        if (evento.getHoraFin() != null && 
            evento.getHoraInicio() != null && 
            evento.getHoraFin().isBefore(evento.getHoraInicio())) {
            throw new IllegalArgumentException("La hora fin no puede ser antes que la hora inicio");
        }

        if (evento.isTodoDia() && evento.getHoraInicio() != null) {
            log.warn("Evento todo el dia pero tiene hora - eliminar hora");
            evento.setHoraInicio(null);
            evento.setHoraFin(null);
        }
    }

    /**
     * Construye mensaje amigable para pedir límite de recurrencia.
     */
    private String construirMensajeLimite(Evento evento) {
        StringBuilder sb = new StringBuilder();
        sb.append("Evento recurrente detectado: ").append(evento.getTitulo()).append("\n\n");

        Map<String, Object> extra = evento.getDatosExtra();
        if (extra != null) {
            String patron = (String) extra.get("patron");
            
            if ("WEEKLY".equals(patron)) {
                sb.append("Se repetira todos los dias especificados.\n\n");
            } else if ("DAILY".equals(patron)) {
                sb.append("Se repetira todos los dias.\n\n");
            } else if ("MONTHLY".equals(patron)) {
                sb.append("Se repetira cada mes.\n\n");
            } else if ("YEARLY".equals(patron)) {
                sb.append("Se repetira cada ano.\n\n");
            }
        }

        sb.append("Hasta cuando quieres que se repita?\n\n");
        sb.append("Responde con:\n");
        sb.append("- Un mes: 'hasta agosto', 'hasta diciembre'\n");
        sb.append("- Una fecha: 'hasta el 31 de julio'\n");
        sb.append("- Indefinido: 'indefinido', 'sin limite'\n");

        return sb.toString();
    }

    /**
     * Respuesta del parser.
     */
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

        public static EventoParseResponse success(
            EventoMinimo evento, 
            EventoPreview preview, 
            Boolean esRecurrente,
            long tiempoMs) {
            return EventoParseResponse.builder()
                .paso("success")
                .evento(evento)
                .preview(preview)
                .esRecurrente(esRecurrente)
                .tiempoMs(tiempoMs)
                .build();
        }

        public static EventoParseResponse necesitaLimite(
            EventoMinimo evento, 
            String mensaje,
            long tiempoMs) {
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
