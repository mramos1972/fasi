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
import java.time.LocalTime;
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

    private static final int CTX_INTERPRETAR   = 1024;
    private static final int MAX_OUTPUT_TOKENS = 600;

    public EventoIaResponse interpretar(EventoIaRequest request) {
        long inicio = System.currentTimeMillis();
        LocalDate fechaContexto = request.getFechaContexto() != null && !request.getFechaContexto().isBlank()
            ? LocalDate.parse(request.getFechaContexto())
            : LocalDate.now();

        try {
            Optional<EventoParserRegex.EventoParseResult> res = parserRegex.parse(request.getTexto(), fechaContexto);

            if (res.isPresent()) {
                long elapsed = System.currentTimeMillis() - inicio;
                Evento evento = res.get().evento();
                boolean esRecurrente = res.get().esRecurrente();

                log.info("⚡ REGEX exitoso en {}ms (recurrente: {})", elapsed, esRecurrente);

                if (esRecurrente && evento.getDatosExtra() != null) {
                    Boolean necesitaLimite = (Boolean) evento.getDatosExtra().get("necesitaLimite");
                    if (Boolean.TRUE.equals(necesitaLimite)) {
                        return crearRespuestaNecesitaLimite(evento, elapsed);
                    }
                }

                return new EventoIaResponse(true, null, evento, null, elapsed);
            }

            log.info("⏩ REGEX no coincide → usando IA");
            return interpretarConIA(request, fechaContexto, inicio);

        } catch (Exception e) {
            log.error("❌ Error interpretando texto: {}", e.getMessage(), e);
            return new EventoIaResponse(false,
                    "❌ No pude interpretar el texto. Por favor, intenta reformularlo de forma más clara.",
                    null, System.currentTimeMillis() - inicio);
        }
    }

    private EventoIaResponse crearRespuestaNecesitaLimite(Evento evento, long tiempoMs) {
        Map<String, Object> datosExtra = evento.getDatosExtra();
        datosExtra.put("esperandoLimite", true);
        evento.setDatosExtra(datosExtra);

        String patron = (String) datosExtra.get("patron");
        String mensaje = construirMensajeLimite(patron, evento);

        EventoIaResponse response = new EventoIaResponse();
        response.setExito(false);
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

    public EventoIaResponse procesarLimite(Evento eventoBase, String respuestaUsuario) {
        long inicio = System.currentTimeMillis();

        try {
            LocalDate fechaLimite = parsearLimite(respuestaUsuario, LocalDate.now());

            if (fechaLimite == null) {
                return new EventoIaResponse(false,
                    "❌ No entendí el límite. Por favor especifica: 'hasta agosto', 'hasta el 31/12', o 'indefinido'",
                    null, System.currentTimeMillis() - inicio);
            }

            eventoBase.setFechaFin(fechaLimite);
            Map<String, Object> datosExtra = eventoBase.getDatosExtra();
            datosExtra.remove("necesitaLimite");
            datosExtra.remove("esperandoLimite");
            eventoBase.setDatosExtra(datosExtra);

            log.info("✅ Límite establecido: {}", fechaLimite);
            return new EventoIaResponse(true, null, eventoBase, null, System.currentTimeMillis() - inicio);

        } catch (Exception e) {
            log.error("❌ Error procesando límite: {}", e.getMessage());
            return new EventoIaResponse(false,
                "❌ Error al procesar el límite: " + e.getMessage(),
                null, System.currentTimeMillis() - inicio);
        }
    }

    private LocalDate parsearLimite(String texto, LocalDate hoy) {
        texto = texto.toLowerCase().trim();

        if (texto.matches("(?i).*(indefinido|sin\\s+l[ií]mite|fin\\s+de\\s+año|final\\s+del\\s+año).*")) {
            return LocalDate.of(hoy.getYear(), 12, 31);
        }

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

        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("(\\d{1,2})[/-](\\d{1,2})");
        java.util.regex.Matcher m2 = p2.matcher(texto);
        if (m2.find()) {
            int dia = Integer.parseInt(m2.group(1));
            int mes = Integer.parseInt(m2.group(2));
            int anio = hoy.getYear();
            if (mes < hoy.getMonthValue()) anio++;
            return LocalDate.of(anio, mes, dia);
        }

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
            log.error("❌ La IA no devolvió eventos válidos. JSON: {}", jsonLimpio);
            throw new IllegalStateException("❌ La IA no pudo interpretar el evento. Por favor, intenta reformular la frase de forma más clara.");
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

            if (evento.getTitulo() == null || evento.getTitulo().isBlank() || evento.getTitulo().equals("-")) {
                log.error("❌ La IA no devolvió título válido. JSON: {}", node.toString());
                throw new IllegalStateException("❌ No pude determinar el nombre del evento. Por favor, especifica qué tipo de evento quieres crear.");
            }

            if (evento.getFechaInicio() == null) {
                log.warn("⚠️ La IA no devolvió fechaInicio. Intentando recuperar...");

                if (node.has("fecha") && !node.get("fecha").isNull()) {
                    String fechaTexto = node.get("fecha").asText();
                    log.warn("⚠️ Intentando parsear campo 'fecha': {}", fechaTexto);

                    try {
                        evento.setFechaInicio(parsearFechaTexto(fechaTexto, fechaContexto));
                        log.info("✅ Fecha recuperada: {}", evento.getFechaInicio());
                    } catch (Exception e) {
                        log.error("❌ No se pudo parsear fecha: {}", fechaTexto);
                        throw new IllegalStateException("❌ No pude determinar la fecha del evento. Por favor, especifica una fecha clara (ej: 'el 5 de junio', 'el lunes').");
                    }
                } else {
                    throw new IllegalStateException("❌ No pude determinar la fecha del evento. Por favor, especifica cuándo debe ocurrir.");
                }
            }

            if (evento.getHoraInicio() == null && node.has("hora") && !node.get("hora").isNull()) {
                String horaTexto = node.get("hora").asText();
                log.warn("⚠️ La IA usó campo 'hora' en lugar de 'horaInicio': {}", horaTexto);

                try {
                    if (horaTexto.matches("\\d{1,2}:\\d{2}")) {
                        horaTexto += ":00";
                    }
                    evento.setHoraInicio(LocalTime.parse(horaTexto));
                    log.info("✅ Hora recuperada: {}", evento.getHoraInicio());
                } catch (Exception e) {
                    log.error("❌ No se pudo parsear hora: {}", horaTexto);
                }
            }

            if (evento.getTipo() == null) {
                log.warn("⚠️ La IA no devolvió tipo, usando OTRO por defecto");
                evento.setTipo(Evento.TipoEvento.OTRO);
            }

            if (evento.getHoraInicio() != null && evento.getHoraInicio().equals(LocalTime.of(14, 0))) {
                if (!request.getTexto().toLowerCase().contains("14") &&
                    !request.getTexto().toLowerCase().contains("dos") &&
                    !request.getTexto().toLowerCase().contains("2")) {
                    log.warn("⚠️ La IA inventó hora 14:00 sin que estuviera en el texto. Eliminando.");
                    evento.setHoraInicio(null);
                }
            }

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

                if (evento.getFechaFin() == null) {
                    extra.put("necesitaLimite", true);
                }

                evento.setDatosExtra(extra);

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

    private String construirPrompt(String texto, LocalDate fechaContexto) {
        LocalDate manana = fechaContexto.plusDays(1);
        LocalDate proximoLunes = fechaContexto.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));

        return """
            Eres un extractor de eventos de calendario. Convierte texto en español a JSON válido.

            ═══════════════════════════════════════════════════════════════════════════
            📋 FORMATO DE SALIDA (OBLIGATORIO)
            ═══════════════════════════════════════════════════════════════════════════

            SIEMPRE devuelve este JSON EXACTO (sin markdown, sin explicaciones):
            {
              "eventos": [
                {
                  "tipo": "SALUD",
                  "titulo": "Cita Médica",
                  "fechaInicio": "2026-06-05",
                  "fechaFin": null,
                  "horaInicio": "15:00:00",
                  "horaFin": null,
                  "todoDia": false,
                  "lugar": null,
                  "personasRelacionadas": "Rocío",
                  "descripcion": null,
                  "recordatorioMinutos": null,
                  "recurrente": false,
                  "patron": null
                }
              ]
            }

            🚨 CAMPOS OBLIGATORIOS (USA ESTOS NOMBRES EXACTOS):
            - "fechaInicio" (NO "fecha")
            - "horaInicio" (NO "hora")
            - "personasRelacionadas" (NO "personas")

            ═══════════════════════════════════════════════════════════════════════════
            🎯 TIPOS DE EVENTO
            ═══════════════════════════════════════════════════════════════════════════

            PERSONAL    → Eventos privados
            SOCIAL      → Cumpleaños, fiestas, comidas
            SALUD       → Médico, dentista, fisio, BAJAS MÉDICAS
            TRABAJO     → Oficina, reuniones, claustros, PERMISOS LABORALES
            FAMILIA     → Tutorías, colegio, extraescolares
            VIAJE       → Desplazamientos con transporte (avión, tren, coche)
            VACACIONES  → Periodos de descanso (con o sin viaje)
            APRENDIZAJE → Cursos, clases
            OCIO        → Hobbies, deportes, cine
            GESTION     → Compras, trámites, banco
            OTRO        → Cuando no encaje

            ═══════════════════════════════════════════════════════════════════════════
            📚 GLOSARIO DE TÉRMINOS LABORALES (ESPECÍFICO DEL USUARIO)
            ═══════════════════════════════════════════════════════════════════════════

            IMPORTANTE: Reconoce estos términos específicos del trabajo del usuario:

            • "día moscoso" / "moscoso" → tipo: TRABAJO, titulo: "Día moscoso de [persona]", todoDia: true
            • "día maloso" / "maloso" → tipo: SALUD, titulo: "Baja por enfermedad de [persona]", todoDia: true
            • "baja" / "baja médica" → tipo: SALUD, titulo: "Baja médica de [persona]", todoDia: true
            • "día sin sueldo" / "sin sueldo" → tipo: TRABAJO, titulo: "Permiso sin sueldo de [persona]", todoDia: true
            • "asunto propio" / "asuntos propios" → tipo: TRABAJO, titulo: "Asunto propio de [persona]", todoDia: true
            • "teletrabajo" / "trabajo remoto" → tipo: TRABAJO, titulo: "Teletrabajo de [persona]", todoDia: true
            • "trabajo presencial" / "presencial" → tipo: TRABAJO, titulo: "Trabajo presencial de [persona]", todoDia: true
            • "vacaciones" → tipo: VACACIONES, titulo: "Vacaciones de [persona]", todoDia: true

            Cuando detectes estos términos:
            1. Usa el tipo correcto (TRABAJO, SALUD o VACACIONES según la tabla)
            2. Mantén el término original en el título
            3. Marca todoDia: true (son eventos de día completo)
            4. Si hay nombre de persona, ponlo en personasRelacionadas

            EJEMPLOS DE TÉRMINOS LABORALES:

            "día moscoso de Miguel el 20 de julio" →
            {
              "tipo": "TRABAJO",
              "titulo": "Día moscoso de Miguel",
              "fechaInicio": "2026-07-20",
              "todoDia": true,
              "personasRelacionadas": "Miguel"
            }

            "baja de Rocío del 5 al 10 de agosto" →
            {
              "tipo": "SALUD",
              "titulo": "Baja médica de Rocío",
              "fechaInicio": "2026-08-05",
              "fechaFin": "2026-08-10",
              "todoDia": true,
              "personasRelacionadas": "Rocío"
            }

            "vacaciones de Miguel el día 20 de julio" →
            {
              "tipo": "VACACIONES",
              "titulo": "Vacaciones de Miguel",
              "fechaInicio": "2026-07-20",
              "todoDia": true,
              "personasRelacionadas": "Miguel"
            }

            ═══════════════════════════════════════════════════════════════════════════
            📅 RESOLUCIÓN DE FECHAS (HOY: %s)
            ═══════════════════════════════════════════════════════════════════════════

            EJEMPLOS CONCRETOS:

            "el 5 de junio"    → "fechaInicio": "2026-06-05"
            "el 23 de marzo"   → "fechaInicio": "2026-03-23"
            "mañana"           → "fechaInicio": "%s"
            "el lunes"         → "fechaInicio": "%s"

            REGLAS:
            • Formato SIEMPRE: "YYYY-MM-DD" (ej: "2026-06-05")
            • Si el mes ya pasó este año → usar año siguiente
            • "mañana" → HOY + 1 día
            • "el [día semana]" → próximo día de esa semana

            ═══════════════════════════════════════════════════════════════════════════
            🕐 HORA (FORMATO: HH:MM:SS)
            ═══════════════════════════════════════════════════════════════════════════

            EJEMPLOS:
            "a las 15:00"      → "horaInicio": "15:00:00"
            "a las 10"         → "horaInicio": "10:00:00"
            "a las 9:30"       → "horaInicio": "09:30:00"
            "de 10 a 12"       → "horaInicio": "10:00:00", "horaFin": "12:00:00"
            Sin hora           → "horaInicio": null

            🚨 IMPORTANTE:
            - Siempre formato HH:MM:SS (ej: "15:00:00", NO "15:00")
            - Si NO hay hora en el texto → "horaInicio": null (NO inventes "14:00:00")

            ═══════════════════════════════════════════════════════════════════════════
            📍 LUGAR Y PERSONAS
            ═══════════════════════════════════════════════════════════════════════════

            EJEMPLOS:
            "de Rocío"                    → "personasRelacionadas": "Rocío"
            "para Alberto"                → "personasRelacionadas": "Alberto"
            "de Rocío y Alberto"          → "personasRelacionadas": "Rocío, Alberto"
            "en Jerez"                    → "lugar": "Jerez"
            "cita médica de rocío jerez"  → "personasRelacionadas": "Rocío", "lugar": "Jerez"

            ═══════════════════════════════════════════════════════════════════════════
            ✅ EJEMPLOS COMPLETOS (COPIA ESTE FORMATO EXACTO)
            ═══════════════════════════════════════════════════════════════════════════

            ENTRADA: "cita médica de rocío el 5 de junio a las 15:00"
            SALIDA:
            {
              "eventos": [{
                "tipo": "SALUD",
                "titulo": "Cita Médica",
                "fechaInicio": "2026-06-05",
                "fechaFin": null,
                "horaInicio": "15:00:00",
                "horaFin": null,
                "todoDia": false,
                "lugar": null,
                "personasRelacionadas": "Rocío",
                "descripcion": null,
                "recordatorioMinutos": null,
                "recurrente": false,
                "patron": null
              }]
            }

            ENTRADA: "tutoría el lunes a las 17"
            SALIDA:
            {
              "eventos": [{
                "tipo": "FAMILIA",
                "titulo": "Tutoría",
                "fechaInicio": "%s",
                "fechaFin": null,
                "horaInicio": "17:00:00",
                "horaFin": null,
                "todoDia": false,
                "lugar": null,
                "personasRelacionadas": null,
                "descripcion": null,
                "recordatorioMinutos": null,
                "recurrente": false,
                "patron": null
              }]
            }

            ENTRADA: "cumpleaños de alberto el 5 de junio"
            SALIDA:
            {
              "eventos": [{
                "tipo": "SOCIAL",
                "titulo": "Cumpleaños de Alberto",
                "fechaInicio": "2026-06-05",
                "fechaFin": null,
                "horaInicio": null,
                "horaFin": null,
                "todoDia": true,
                "lugar": null,
                "personasRelacionadas": "Alberto",
                "descripcion": null,
                "recordatorioMinutos": null,
                "recurrente": false,
                "patron": null
              }]
            }

            ENTRADA: "todos los lunes gimnasio"
            SALIDA:
            {
              "eventos": [{
                "tipo": "OCIO",
                "titulo": "Gimnasio",
                "fechaInicio": "%s",
                "fechaFin": null,
                "horaInicio": null,
                "horaFin": null,
                "todoDia": false,
                "lugar": null,
                "personasRelacionadas": null,
                "descripcion": null,
                "recordatorioMinutos": null,
                "recurrente": true,
                "patron": "WEEKLY",
                "diasSemana": ["MONDAY"]
              }]
            }

            ═══════════════════════════════════════════════════════════════════════════
            🚨 REGLAS CRÍTICAS (LEE ESTO ANTES DE RESPONDER)
            ═══════════════════════════════════════════════════════════════════════════

            1. USA ESTOS NOMBRES DE CAMPO EXACTOS:
               - "fechaInicio" (NO "fecha")
               - "horaInicio" (NO "hora")
               - "personasRelacionadas" (NO "personas")

            2. FORMATO DE FECHA: "YYYY-MM-DD" (ej: "2026-06-05")

            3. FORMATO DE HORA: "HH:MM:SS" (ej: "15:00:00", NO "15:00")

            4. Si no hay hora → "horaInicio": null (NO inventes "14:00:00")

            5. NUNCA inventes campos nuevos

            6. Devuelve JSON válido SIN markdown (NO uses ```json)

            7. Si no puedes determinar algo → usa null (NO "-" ni cadenas vacías)

            8. RECONOCE LOS TÉRMINOS LABORALES del glosario arriba

            ═══════════════════════════════════════════════════════════════════════════
            📝 TEXTO A INTERPRETAR
            ═══════════════════════════════════════════════════════════════════════════

            "%s"

            ═══════════════════════════════════════════════════════════════════════════

            Responde SOLO con JSON. Sin explicaciones. Sin markdown. Usa los nombres de campo EXACTOS.
            """.formatted(
                fechaContexto,
                manana,
                proximoLunes,
                proximoLunes,
                proximoLunes,
                texto
            );
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

    private LocalDate parsearFechaTexto(String texto, LocalDate hoy) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(?i)el\\s+(\\d{1,2})\\s+de\\s+(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)"
        );
        java.util.regex.Matcher m = p.matcher(texto);

        if (m.find()) {
            int dia = Integer.parseInt(m.group(1));
            String mes = m.group(2).toLowerCase();

            java.util.Map<String, Integer> meses = java.util.Map.ofEntries(
                java.util.Map.entry("enero", 1), java.util.Map.entry("febrero", 2),
                java.util.Map.entry("marzo", 3), java.util.Map.entry("abril", 4),
                java.util.Map.entry("mayo", 5), java.util.Map.entry("junio", 6),
                java.util.Map.entry("julio", 7), java.util.Map.entry("agosto", 8),
                java.util.Map.entry("septiembre", 9), java.util.Map.entry("octubre", 10),
                java.util.Map.entry("noviembre", 11), java.util.Map.entry("diciembre", 12)
            );

            int mesNum = meses.get(mes);
            int anio = hoy.getYear();

            LocalDate fecha = LocalDate.of(anio, mesNum, dia);
            if (fecha.isBefore(hoy)) {
                fecha = fecha.plusYears(1);
            }

            return fecha;
        }

        throw new IllegalArgumentException("No se pudo parsear: " + texto);
    }
}
