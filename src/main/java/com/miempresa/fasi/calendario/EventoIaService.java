package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miempresa.fasi.ia.IaContextBuilder;
import com.miempresa.fasi.ia.OllamaClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventoIaService {

    private final OllamaClient           ollamaClient;
    private final EventoRepository       repo;
    private final ObjectMapper           objectMapper;
    private final IaContextBuilder       contextBuilder;
    private final EventoParserRegex      parserRegex;

    private static final int CTX_INTERPRETAR   = 512;
    private static final int MAX_OUTPUT_TOKENS = 600;

    // ══════════════════════════════════════════════════════════
    //  SISTEMA HÍBRIDO: REGEX → IA
    // ══════════════════════════════════════════════════════════

    public EventoIaResponse interpretar(EventoIaRequest request) {
        long inicio = System.currentTimeMillis();
        LocalDate fechaContexto = request.getFechaContexto() != null && !request.getFechaContexto().isBlank()
            ? LocalDate.parse(request.getFechaContexto())
            : LocalDate.now();

        try {
            // ── PASO 1: Intentar REGEX (< 50ms) ──────────────────
            Optional<EventoParserRegex.EventoParseResult> resultado =
                parserRegex.parse(request.getTexto(), fechaContexto);

            if (resultado.isPresent()) {
                long elapsed = System.currentTimeMillis() - inicio;
                Evento evento = resultado.get().evento();
                boolean esRecurrente = resultado.get().esRecurrente();

                log.info("⚡ REGEX exitoso en {}ms (recurrente: {})", elapsed, esRecurrente);

                // ── VERIFICAR SI NECESITA LÍMITE ──────────────────
                if (esRecurrente && evento.getDatosExtra() != null) {
                    Boolean necesitaLimite = (Boolean) evento.getDatosExtra().get("necesitaLimite");
                    if (Boolean.TRUE.equals(necesitaLimite)) {
                        return crearRespuestaNecesitaLimite(evento, elapsed);
                    }
                }

                return new EventoIaResponse(true, null, evento, null, elapsed);
            }

            log.info("⏩ REGEX no coincide → usando IA");

            // ── PASO 2: Usar IA para casos complejos ─────────────
            return interpretarConIA(request, fechaContexto, inicio);

        } catch (Exception e) {
            log.error("❌ Error interpretando texto: {}", e.getMessage(), e);
            return new EventoIaResponse(false,
                    "No pude interpretar el texto. Inténtalo de nuevo.",
                    null, System.currentTimeMillis() - inicio);
        }
    }

    // ══════════════════════════════════════════════════════════
    //  RESPUESTA CONVERSACIONAL: PEDIR LÍMITE
    // ══════════════════════════════════════════════════════════

    private EventoIaResponse crearRespuestaNecesitaLimite(Evento evento, long tiempoMs) {
        // Marcar el evento como "esperando límite"
        Map<String, Object> datosExtra = evento.getDatosExtra();
        datosExtra.put("esperandoLimite", true);
        evento.setDatosExtra(datosExtra);

        String patron = (String) datosExtra.get("patron");
        String mensaje = construirMensajeLimite(patron, evento);

        EventoIaResponse response = new EventoIaResponse();
        response.setExito(false); // No está listo para confirmar
        response.setMensajeError(mensaje);
        response.setEventoPreview(evento);
        response.setTiempoMs(tiempoMs);

        return response;
    }

    private String construirMensajeLimite(String patron, Evento evento) {
        StringBuilder sb = new StringBuilder();
        sb.append("📅 Evento recurrente detectado: **").append(evento.getTitulo()).append("**\n\n");
        
        switch (patron) {
            case "WEEKLY" -> {
                @SuppressWarnings("unchecked")
                List<String> dias = (List<String>) evento.getDatosExtra().get("diasSemana");
                String diasStr = dias != null ? String.join(", ", dias) : "días especificados";
                sb.append("Se repetirá los **").append(diasStr).append("**.\n\n");
            }
            case "DAILY" -> sb.append("Se repetirá **todos los días**.\n\n");
            case "MONTHLY" -> {
                Integer dia = (Integer) evento.getDatosExtra().get("diaDelMes");
                sb.append("Se repetirá el **día ").append(dia).append(" de cada mes**.\n\n");
            }
            case "MONTHLY_RELATIVE" -> {
                String diaSemana = (String) evento.getDatosExtra().get("diaSemana");
                Integer posicion = (Integer) evento.getDatosExtra().get("posicion");
                String posStr = posicion == -1 ? "último" : posicion + "º";
                sb.append("Se repetirá el **").append(posStr).append(" ").append(diaSemana).append(" de cada mes**.\n\n");
            }
        }

        sb.append("⚠️ **¿Hasta cuándo quieres que se repita?**\n\n");
        sb.append("Responde con:\n");
        sb.append("• Un mes: *\"hasta agosto\"*, *\"hasta diciembre\"*\n");
        sb.append("• Una fecha: *\"hasta el 31 de julio\"*, *\"hasta fin de año\"*\n");
        sb.append("• Indefinido: *\"indefinido\"*, *\"sin límite\"*, *\"hasta fin de año\"*\n");

        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════
    //  PROCESAR RESPUESTA DE LÍMITE
    // ══════════════════════════════════════════════════════════

    public EventoIaResponse procesarLimite(Evento eventoBase, String respuestaUsuario) {
        long inicio = System.currentTimeMillis();
        
        try {
            LocalDate fechaLimite = parsearLimite(respuestaUsuario, LocalDate.now());
            
            if (fechaLimite == null) {
                return new EventoIaResponse(false, 
                    "No entendí el límite. Por favor especifica: 'hasta agosto', 'hasta el 31/12', o 'indefinido'",
                    null, System.currentTimeMillis() - inicio);
            }

            // Actualizar el evento con el límite
            eventoBase.setFechaFin(fechaLimite);
            Map<String, Object> datosExtra = eventoBase.getDatosExtra();
            datosExtra.remove("necesitaLimite");
            datosExtra.remove("esperandoLimite");
            eventoBase.setDatosExtra(datosExtra);

            log.info("✅ Límite establecido: {}", fechaLimite);
            return new EventoIaResponse(true, null, eventoBase, null, System.currentTimeMillis() - inicio);

        } catch (Exception e) {
            log.error("Error procesando límite: {}", e.getMessage());
            return new EventoIaResponse(false, 
                "Error al procesar el límite: " + e.getMessage(),
                null, System.currentTimeMillis() - inicio);
        }
    }

    private LocalDate parsearLimite(String texto, LocalDate hoy) {
        texto = texto.toLowerCase().trim();

        // "indefinido" / "sin límite" → fin de año
        if (texto.matches("(?i).*(indefinido|sin\\s+l[ií]mite|fin\\s+de\\s+año|final\\s+del\\s+año).*")) {
            return LocalDate.of(hoy.getYear(), 12, 31);
        }

        // "hasta agosto" / "hasta diciembre"
        Map<String, Integer> meses = Map.ofEntries(
            Map.entry("enero", 1), Map.entry("febrero", 2), Map.entry("marzo", 3),
            Map.entry("abril", 4), Map.entry("mayo", 5), Map.entry("junio", 6),
            Map.entry("julio", 7), Map.entry("agosto", 8), Map.entry("septiembre", 9),
            Map.entry("octubre", 10), Map.entry("noviembre", 11), Map.entry("diciembre", 12)
        );

        for (Map.Entry<String, Integer> entry : meses.entrySet()) {
            if (texto.contains(entry.getKey())) {
                int mes = entry.getValue();
                int anio = hoy.getYear();
                if (mes < hoy.getMonthValue()) anio++;
                return LocalDate.of(anio, mes, 1).withDayOfMonth(
                    LocalDate.of(anio, mes, 1).lengthOfMonth()
                );
            }
        }

        // "hasta el 31 de julio" / "31/07" / "31-07-2026"
        java.util.regex.Pattern p1 = java.util.regex.Pattern.compile(
            "(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
        );
        java.util.regex.Matcher m1 = p1.matcher(texto);
        if (m1.find()) {
            int dia = Integer.parseInt(m1.group(1));
            int mes = meses.get(m1.group(2));
            int anio = hoy.getYear();
            if (mes < hoy.getMonthValue()) anio++;
            return LocalDate.of(anio, mes, dia);
        }

        // "31/12" / "31-12"
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("(\\d{1,2})[/-](\\d{1,2})");
        java.util.regex.Matcher m2 = p2.matcher(texto);
        if (m2.find()) {
            int dia = Integer.parseInt(m2.group(1));
            int mes = Integer.parseInt(m2.group(2));
            int anio = hoy.getYear();
            if (mes < hoy.getMonthValue()) anio++;
            return LocalDate.of(anio, mes, dia);
        }

        // "2026-12-31"
        java.util.regex.Pattern p3 = java.util.regex.Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
        java.util.regex.Matcher m3 = p3.matcher(texto);
        if (m3.find()) {
            return LocalDate.of(
                Integer.parseInt(m3.group(1)),
                Integer.parseInt(m3.group(2)),
                Integer.parseInt(m3.group(3))
            );
        }

        return null;
    }

    // ══════════════════════════════════════════════════════════
    //  INTERPRETACIÓN CON IA
    // ══════════════════════════════════════════════════════════

    private EventoIaResponse interpretarConIA(EventoIaRequest request, LocalDate fechaContexto, long inicio) throws Exception {
        String prompt = construirPrompt(request.getTexto(), fechaContexto);
        String json   = ollamaClient.generateJson(prompt, CTX_INTERPRETAR, MAX_OUTPUT_TOKENS);

        log.info("📥 IA RAW: {}", json);

        String jsonLimpio = extraerJson(json);
        log.info("📋 IA LIMPIO: {}", jsonLimpio);

        JsonNode root = objectMapper.readTree(jsonLimpio);
        JsonNode eventosNode = root.has("eventos") ? root.get("eventos") : root;

        List<ObjectNode> nodos = new ArrayList<>();
        if (eventosNode.isArray()) {
            eventosNode.forEach(n -> { if (n.isObject()) nodos.add((ObjectNode) n); });
        } else if (eventosNode.isObject()) {
            nodos.add((ObjectNode) eventosNode);
        }

        if (nodos.isEmpty()) {
            throw new IllegalStateException("La IA no devolvió eventos en el JSON.");
        }

        List<Evento> eventos = new ArrayList<>();
        for (ObjectNode node : nodos) {
            normalizarHora(node, "horaInicio");
            normalizarHora(node, "horaFin");
            normalizarPersonas(node);

            boolean esRecurrente = node.has("recurrente") && node.get("recurrente").asBoolean(false);
            String  patron = node.has("patron") ? node.get("patron").asText(null) : null;

            node.remove("recurrente");
            node.remove("patron");

            Evento evento = objectMapper.treeToValue(node, Evento.class);
            evento.setTextoOriginalVoz(request.getTexto());

            if (esRecurrente && patron != null) {
                Map<String, Object> extra = evento.getDatosExtra() != null
                    ? new HashMap<>(evento.getDatosExtra())
                    : new HashMap<>();
                extra.put("recurrente", true);
                extra.put("patron", patron);
                extra.put("textoRecurrencia", request.getTexto());
                
                if (node.has("diaSemana") && !node.get("diaSemana").isNull()) {
                    extra.put("diaSemana", node.get("diaSemana").asText());
                }
                if (node.has("diasSemana") && node.get("diasSemana").isArray()) {
                    List<String> dias = new ArrayList<>();
                    node.get("diasSemana").forEach(d -> dias.add(d.asText()));
                    extra.put("diasSemana", dias);
                }

                // ── VERIFICAR SI TIENE FECHA FIN ──────────────────
                if (evento.getFechaFin() == null) {
                    extra.put("necesitaLimite", true);
                }

                evento.setDatosExtra(extra);

                // ── SI NECESITA LÍMITE, DEVOLVER PREGUNTA ─────────
                if (evento.getFechaFin() == null) {
                    long elapsed = System.currentTimeMillis() - inicio;
                    return crearRespuestaNecesitaLimite(evento, elapsed);
                }
            }

            eventos.add(evento);
        }

        long elapsed = System.currentTimeMillis() - inicio;

        if (eventos.size() == 1) {
            log.info("✅ IA detectó 1 evento en {}ms", elapsed);
            return new EventoIaResponse(true, null, eventos.get(0), null, elapsed);
        } else {
            log.info("✅ IA detectó {} eventos múltiples en {}ms", eventos.size(), elapsed);
            return new EventoIaResponse(true, null, null, eventos, elapsed);
        }
    }

    private void normalizarHora(ObjectNode node, String campo) {
        if (!node.has(campo) || node.get(campo).isNull()) return;
        String val = node.get(campo).asText("").trim()
                         .replaceAll("(?i)\\s*[Hh].*$", "").trim();
        if (val.isEmpty() || val.equals("null")) { node.putNull(campo); return; }
        if (val.matches("\\d{1,2}:\\d{2}$")) val = val + ":00";
        if (val.matches("\\d{1,2}$"))        val = val + ":00:00";
        node.put(campo, val);
    }

    private void normalizarPersonas(ObjectNode node) {
        if (!node.has("personasRelacionadas")) return;
        JsonNode pr = node.get("personasRelacionadas");
        if (pr.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pr.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(pr.get(i).asText());
            }
            String unido = sb.toString().trim();
            if (unido.isEmpty()) node.putNull("personasRelacionadas");
            else                 node.put("personasRelacionadas", unido);
        } else if (pr.isNull() || pr.asText().isBlank()) {
            node.putNull("personasRelacionadas");
        }
    }

    @Transactional(readOnly = true)
    public String consultar(String pregunta) {
        String prompt = contextBuilder.buildPrompt(pregunta, "calendario");
        return ollamaClient.generate(prompt, 1536);
    }

    // ══════════════════════════════════════════════════════════
    //  PROMPT MEJORADO CON DIFERENCIACIÓN ESTRICTA
    // ══════════════════════════════════════════════════════════

private String construirPrompt(String texto, LocalDate fechaContexto) {
    return """
        Eres extractor de eventos de calendario. Devuelve SOLO JSON válido:
        {"eventos":[{"tipo":"TRABAJO","titulo":"...","fechaInicio":"YYYY-MM-DD","fechaFin":"YYYY-MM-DD","horaInicio":"HH:mm:ss",...}]}

        TIPOS: PERSONAL, SOCIAL, SALUD, TRABAJO, FAMILIA, VIAJE, APRENDIZAJE, OCIO, GESTION, OTRO

        ═══════════════════════════════════════════════════════════════════════════
        REGLAS CRÍTICAS — CUMPLEAÑOS
        ═══════════════════════════════════════════════════════════════════════════

        ✅ CUMPLEAÑOS ÚNICO (por defecto):
        - "cumpleaños de Rocío el 23 de marzo" → ÚNICO, NO recurrente
        - "cumpleaños de Alberto el 5 de junio" → ÚNICO, NO recurrente
        - Regla: Si NO dice "cada año", "todos los años", "anual" → ÚNICO

        ✅ CUMPLEAÑOS RECURRENTE (solo si lo especifica):
        - "cumpleaños de Rocío el 23 de marzo cada año" → recurrente: true, patron: YEARLY
        - "cumpleaños de mi madre el 10 de mayo todos los años" → recurrente: true, patron: YEARLY
        - Regla: SOLO si dice "cada año", "todos los años", "anual" → recurrente

        ═══════════════════════════════════════════════════════════════════════════
        REGLAS CRÍTICAS — DIFERENCIACIÓN ÚNICO vs RECURRENTE
        ═══════════════════════════════════════════════════════════════════════════

        ✅ EVENTO ÚNICO (recurrente: false):
        - "tutoría el lunes" → próximo lunes, NO se repite
        - "claustro el viernes" → próximo viernes, NO se repite
        - "cita médica el jueves" → próximo jueves, NO se repite
        - "dentista el 27 de mayo" → solo ese día
        - Regla: "el [día]" SIN "todos" ni plural → ÚNICO

        ✅ EVENTO RECURRENTE (recurrente: true):
        - "todos los lunes" → recurrente, patron: WEEKLY, diasSemana: ["MONDAY"]
        - "los lunes" (plural) → recurrente, patron: WEEKLY, diasSemana: ["MONDAY"]
        - "cada viernes" → recurrente, patron: WEEKLY, diasSemana: ["FRIDAY"]
        - "gimnasio los martes y jueves" → recurrente, patron: WEEKLY, diasSemana: ["TUESDAY","THURSDAY"]
        - "todos los días de julio" → recurrente, patron: DAILY, fechaInicio: 2026-07-01, fechaFin: 2026-07-31
        - "el día 15 de cada mes" → recurrente, patron: MONTHLY, diaDelMes: 15
        - "cada primer lunes de cada mes" → recurrente, patron: MONTHLY_RELATIVE, diaSemana: MONDAY, posicion: 1

        ⚠️ MÚLTIPLES FECHAS (crear eventos separados):
        - "dentista el 27 de mayo y el 29 de mayo" → CREAR 2 EVENTOS:
          [
            {"tipo":"SALUD","titulo":"Dentista","fechaInicio":"2026-05-27"},
            {"tipo":"SALUD","titulo":"Dentista","fechaInicio":"2026-05-29"}
          ]

        ⚠️ LÍMITE DE FECHA:
        - Si el texto NO especifica "hasta [fecha/mes]" → fechaFin: null
        - Si dice "hasta agosto" → fechaFin: 2026-08-31
        - Si dice "hasta el 31 de diciembre" → fechaFin: 2026-12-31
        - Si dice "de junio a agosto" → fechaInicio: 2026-06-01, fechaFin: 2026-08-31

        ⚠️ CAMPOS OBLIGATORIOS:
        - tipo: DEBE ser uno de los 10 tipos válidos
        - titulo: NUNCA vacío, NUNCA null
        - fechaInicio: SIEMPRE formato YYYY-MM-DD

        ═══════════════════════════════════════════════════════════════════════════

        FECHA CONTEXTO: %s
        TEXTO: "%s"

        Responde SOLO con JSON, sin explicaciones.
        """.formatted(fechaContexto, texto);
}

    private String extraerJson(String respuesta) {
        if (respuesta == null) return "{}";
        respuesta = respuesta.trim();
        int objInicio = respuesta.indexOf('{');
        int objFin    = respuesta.lastIndexOf('}');
        if (objInicio >= 0 && objFin > objInicio) {
            return respuesta.substring(objInicio, objFin + 1);
        }
        return respuesta;
    }
}
