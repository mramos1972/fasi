package com.miempresa.fasi.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miempresa.fasi.calendario.Evento;
import com.miempresa.fasi.calendario.EventoRepository;
import com.miempresa.fasi.calendario.EventoService;
import com.miempresa.fasi.contexto.UsuarioActualService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class FasiAgentService {

    private final LlmApiClient llmClient;
    private final ObjectMapper objectMapper;
    private final EventoService eventoService;
    private final EventoRepository eventoRepo;
    private final UsuarioActualService usuarioActualService;

    public record AgentResponse(String mensaje, boolean huboMutacion) {}

    private static final String SYSTEM_PROMPT = """
        Eres FASI, asistente operativo de calendario.
        Hoy es %s (%s). Año: %d.
        
        Devuelve EXCLUSIVAMENTE un objeto JSON válido con la estructura correspondiente:

        1. Para AÑADIR NOTAS U OBSERVACIONES (no toques el título jamás):
        {"accion":"OBSERVACION","buscarTexto":"piscina miguel","todos":true,"nota":"llevar toalla y gorro","mensaje":"He añadido la observación a las sesiones de piscina."}

        2. Para COPIAR O REPLICAR EVENTOS DE UN MES A OTRO:
        {"accion":"COPIAR_SERIE","buscarTexto":"piscina miguel","mesOrigen":11,"añoOrigen":2026,"mesDestino":2,"añoDestino":2027,"mensaje":"He copiado las clases de piscina a febrero."}

        3. Para COPIAR UN EVENTO SUELTO A OTRO DÍA:
        {"accion":"COPIAR","buscarTexto":"médico","nuevaFecha":"2026-11-15","nuevaHora":null,"mensaje":"He copiado el evento."}

        4. Para MODIFICAR (mover de día u hora):
        {"accion":"MODIFICAR","buscarTexto":"cumpleaños","nuevaFecha":"2026-11-20","nuevaHora":"18:00","mensaje":"He movido el cumpleaños."}

        5. Para CREAR EVENTOS RECURRENTES:
        {"accion":"RECURRENTE","titulo":"Piscina Miguel","fechaInicio":"2026-11-03","horaInicio":"17:00","frecuencia":"SEMANAL","repeticiones":4,"mensaje":"He agendado las clases."}

        6. Para BORRAR EVENTOS:
        {"accion":"BORRAR","buscarTexto":"piscina","fecha":null,"borrarTodos":false,"mensaje":"He eliminado el evento."}

        7. Para CREAR UN EVENTO NORMAL:
        {"accion":"CREAR","titulo":"Cena","fechaInicio":"2026-11-05","horaInicio":"21:00","tipo":"SOCIAL","mensaje":"He añadido la cena."}

        8. CONSULTA:
        {"accion":"CONSULTA","mensaje":"Respuesta al usuario."}
        """;

    public AgentResponse ejecutarOrden(String texto) {
        LocalDate hoy = LocalDate.now();
        String prompt = SYSTEM_PROMPT.formatted(hoy, hoy.getDayOfWeek(), hoy.getYear());

        try {
            log.info("🤖 Interpretando orden: '{}'", texto);
            String rawJson = llmClient.chatJson(prompt, texto);
            log.info("📥 Respuesta cruda de la IA: {}", rawJson);

            JsonNode root = extraerJsonValido(rawJson);

            // Si la IA devolvió {} o no incluyó "accion", hacemos reintento en modo texto libre y extraemos el JSON
            if (root == null || !root.hasNonNull("accion") || root.path("accion").asText().isBlank()) {
                log.warn("⚠️ La IA devolvió JSON vacío {}. Reintentando con prompt directo...", rawJson);
                String fallbackRaw = llmClient.chat(prompt + "\n\nResponde ÚNICAMENTE con el objeto JSON entre llaves {}.", texto);
                root = extraerJsonValido(fallbackRaw);
            }

            if (root == null || !root.hasNonNull("accion")) {
                return new AgentResponse("No logré interpretar la orden. Por favor intenta especificarlo de otra forma.", false);
            }

            String accion = root.path("accion").asText("CONSULTA").toUpperCase();
            String mensaje = root.path("mensaje").asText("Operación completada.");
            UUID usuarioId = usuarioActualService.obtenerUsuarioActual().getId();

            switch (accion) {
                case "OBSERVACION", "NOTA" -> {
                    String textoBuscar = root.path("buscarTexto").asText("");
                    boolean todos = root.path("todos").asBoolean(true);
                    String nota = root.path("nota").asText("");

                    List<Evento> candidatos = buscarEventosPorTexto(usuarioId, textoBuscar);
                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No encontré eventos de '" + textoBuscar + "' para añadir la observación.", false);
                    }

                    List<Evento> destino = todos ? candidatos : List.of(candidatos.get(0));
                    String timestamp = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM"));
                    String apunte = "[" + timestamp + "] " + nota;

                    for (Evento ev : destino) {
                        String previa = ev.getDescripcion() != null ? ev.getDescripcion().trim() : "";
                        ev.setDescripcion(previa.isBlank() ? apunte : previa + "\n" + apunte);
                        eventoService.update(ev.getId(), ev);
                        log.info("📝 Observación guardada en '{}' ({})", ev.getTitulo(), ev.getFechaInicio());
                    }

                    String confirmacion = destino.size() > 1
                            ? "He añadido la observación en los " + destino.size() + " eventos de '" + textoBuscar + "'."
                            : "He añadido la observación al evento '" + destino.get(0).getTitulo() + "'.";
                    return new AgentResponse(mensaje.isBlank() ? confirmacion : mensaje, true);
                }

                case "COPIAR_SERIE" -> {
                    String textoBuscar = root.path("buscarTexto").asText("");
                    int mesOrigen = root.path("mesOrigen").asInt(hoy.getMonthValue());
                    int anioOrigen = root.path("añoOrigen").asInt(hoy.getYear());
                    int mesDestino = root.path("mesDestino").asInt(mesOrigen);
                    int anioDestino = root.path("añoDestino").asInt(anioOrigen);

                    YearMonth ymOrigen = YearMonth.of(anioOrigen, mesOrigen);
                    List<Evento> eventosMes = eventoRepo.findByRango(usuarioId, ymOrigen.atDay(1), ymOrigen.atEndOfMonth());

                    List<Evento> aCopiar = new ArrayList<>();
                    for (Evento e : eventosMes) {
                        if (e.getTitulo().toLowerCase().contains(textoBuscar.toLowerCase())) {
                            aCopiar.add(e);
                        }
                    }

                    if (aCopiar.isEmpty()) {
                        return new AgentResponse("No encontré eventos de '" + textoBuscar + "' en el mes indicado.", false);
                    }

                    YearMonth ymDestino = YearMonth.of(anioDestino, mesDestino);
                    int creados = 0;

                    for (Evento orig : aCopiar) {
                        int diaSemanaObjetivo = orig.getFechaInicio().getDayOfWeek().getValue();
                        int ocurrenciaOrigen = (orig.getFechaInicio().getDayOfMonth() - 1) / 7;

                        LocalDate fechaDestino = null;
                        for (int dia = 1; dia <= ymDestino.lengthOfMonth(); dia++) {
                            LocalDate candidata = ymDestino.atDay(dia);
                            if (candidata.getDayOfWeek().getValue() == diaSemanaObjetivo) {
                                int ocurrenciaCandidata = (dia - 1) / 7;
                                if (ocurrenciaOrigen == ocurrenciaCandidata) {
                                    fechaDestino = candidata;
                                    break;
                                }
                            }
                        }

                        if (fechaDestino == null) {
                            for (int dia = ymDestino.lengthOfMonth(); dia >= 1; dia--) {
                                LocalDate candidata = ymDestino.atDay(dia);
                                if (candidata.getDayOfWeek().getValue() == diaSemanaObjetivo) {
                                    fechaDestino = candidata;
                                    break;
                                }
                            }
                        }

                        if (fechaDestino != null) {
                            Evento clon = new Evento();
                            clon.setTitulo(orig.getTitulo());
                            clon.setFechaInicio(fechaDestino);
                            clon.setHoraInicio(orig.getHoraInicio());
                            clon.setTodoDia(orig.isTodoDia());
                            clon.setTipo(orig.getTipo());
                            clon.setLugar(orig.getLugar());
                            clon.setPersonasRelacionadas(orig.getPersonasRelacionadas());
                            clon.setDescripcion(orig.getDescripcion());
                            eventoService.save(clon);
                            creados++;
                        }
                    }

                    log.info("📋 Copiados {} eventos de '{}' al mes {}/{}", creados, textoBuscar, mesDestino, anioDestino);
                    return new AgentResponse("He copiado " + creados + " eventos de " + textoBuscar + " a febrero.", true);
                }

                case "COPIAR" -> {
                    String textoBuscar = root.path("buscarTexto").asText("");
                    List<Evento> candidatos = buscarEventosPorTexto(usuarioId, textoBuscar);
                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No encontré el evento original para copiar.", false);
                    }

                    Evento orig = candidatos.get(0);
                    Evento clon = new Evento();
                    clon.setTitulo(orig.getTitulo());
                    clon.setTipo(orig.getTipo());
                    clon.setLugar(orig.getLugar());
                    clon.setPersonasRelacionadas(orig.getPersonasRelacionadas());
                    clon.setDescripcion(orig.getDescripcion());
                    clon.setTodoDia(orig.isTodoDia());

                    String nuevaF = root.path("nuevaFecha").asText(null);
                    clon.setFechaInicio(nuevaF != null && !nuevaF.isBlank() ? LocalDate.parse(nuevaF) : orig.getFechaInicio());

                    String nuevaH = root.path("nuevaHora").asText(null);
                    if (nuevaH != null && !nuevaH.isBlank()) {
                        clon.setHoraInicio(LocalTime.parse(nuevaH));
                        clon.setTodoDia(false);
                    } else {
                        clon.setHoraInicio(orig.getHoraInicio());
                    }

                    eventoService.save(clon);
                    return new AgentResponse(mensaje, true);
                }

                case "MODIFICAR" -> {
                    String textoBuscar = root.path("buscarTexto").asText("");
                    List<Evento> candidatos = buscarEventosPorTexto(usuarioId, textoBuscar);
                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No encontré ningún evento que coincida para modificar.", false);
                    }

                    Evento ev = candidatos.get(0);
                    String nuevaF = root.path("nuevaFecha").asText(null);
                    if (nuevaF != null && !nuevaF.isBlank()) {
                        ev.setFechaInicio(LocalDate.parse(nuevaF));
                    }
                    String nuevaH = root.path("nuevaHora").asText(null);
                    if (nuevaH != null && !nuevaH.isBlank()) {
                        ev.setHoraInicio(LocalTime.parse(nuevaH));
                        ev.setTodoDia(false);
                    }

                    eventoService.update(ev.getId(), ev);
                    return new AgentResponse(mensaje, true);
                }

                case "RECURRENTE" -> {
                    String titulo = root.path("titulo").asText("Evento recurrente");
                    LocalDate fechaBase = LocalDate.parse(root.path("fechaInicio").asText(hoy.toString()));
                    LocalTime hora = root.hasNonNull("horaInicio") && !root.path("horaInicio").asText().isBlank()
                            ? LocalTime.parse(root.path("horaInicio").asText()) : null;

                    String frec = root.path("frecuencia").asText("SEMANAL").toUpperCase();
                    int reps = root.path("repeticiones").asInt(4);

                    for (int i = 0; i < reps; i++) {
                        LocalDate f = switch (frec) {
                            case "DIARIA" -> fechaBase.plusDays(i);
                            case "MENSUAL" -> fechaBase.plusMonths(i);
                            default -> fechaBase.plusWeeks(i);
                        };

                        Evento e = new Evento();
                        e.setTitulo(titulo);
                        e.setFechaInicio(f);
                        e.setHoraInicio(hora);
                        e.setTodoDia(hora == null);
                        e.setTipo(Evento.TipoEvento.OCIO);
                        eventoService.save(e);
                    }

                    return new AgentResponse(mensaje, true);
                }

                case "BORRAR" -> {
                    String palabrasClave = root.path("buscarTexto").asText("");
                    boolean borrarTodos = root.path("borrarTodos").asBoolean(false);

                    List<Evento> candidatos = buscarEventosPorTexto(usuarioId, palabrasClave);
                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No encontré eventos que coincidan para borrar.", false);
                    }

                    List<Evento> aBorrar = borrarTodos ? candidatos : List.of(candidatos.get(0));
                    for (Evento e : aBorrar) {
                        eventoService.delete(e.getId());
                    }

                    return new AgentResponse(mensaje, true);
                }

                case "CREAR" -> {
                    Evento e = new Evento();
                    e.setTitulo(root.path("titulo").asText("Nuevo evento"));
                    e.setFechaInicio(LocalDate.parse(root.path("fechaInicio").asText(hoy.toString())));
                    if (root.hasNonNull("horaInicio") && !root.path("horaInicio").asText().isBlank()) {
                        try { e.setHoraInicio(LocalTime.parse(root.path("horaInicio").asText())); } catch (Exception ignored) {}
                    }
                    e.setTodoDia(e.getHoraInicio() == null);
                    eventoService.save(e);
                    return new AgentResponse(mensaje, true);
                }

                case "CONSULTA" -> {
                    return new AgentResponse(mensaje, false);
                }
            }

            return new AgentResponse(mensaje, false);

        } catch (Exception e) {
            log.error("Error al procesar orden agéntica", e);
            return new AgentResponse("Error procesando la orden: " + e.getMessage(), false);
        }
    }

    private List<Evento> buscarEventosPorTexto(UUID usuarioId, String texto) {
        if (texto == null || texto.isBlank()) {
            return eventoRepo.findAllOptimizado(usuarioId);
        }

        List<Evento> res = eventoRepo.buscarFullText(usuarioId, texto);
        if (!res.isEmpty()) return res;

        List<Evento> todos = eventoRepo.findAllOptimizado(usuarioId);
        List<Evento> filtrados = new ArrayList<>();
        String[] tokens = texto.toLowerCase().split("\\s+");

        for (Evento e : todos) {
            String cad = (e.getTitulo() + " " + (e.getLugar() != null ? e.getLugar() : "")).toLowerCase();
            for (String t : tokens) {
                if (!t.isBlank() && cad.contains(t)) {
                    filtrados.add(e);
                    break;
                }
            }
        }

        return filtrados.isEmpty() ? todos : filtrados;
    }

    private JsonNode extraerJsonValido(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String s = raw.trim();
            if (s.startsWith("```")) {
                int firstLine = s.indexOf('\n');
                int lastBacktick = s.lastIndexOf("```");
                if (firstLine != -1 && lastBacktick > firstLine) {
                    s = s.substring(firstLine + 1, lastBacktick).trim();
                }
            }

            // Si aún no empieza con llave {, extraer el contenido entre la primera { y la última }
            int firstBrace = s.indexOf('{');
            int lastBrace = s.lastIndexOf('}');
            if (firstBrace != -1 && lastBrace > firstBrace) {
                s = s.substring(firstBrace, lastBrace + 1);
            }

            JsonNode node = objectMapper.readTree(s);
            if (node.isObject() && node.size() > 0) {
                return node;
            }
        } catch (Exception e) {
            log.warn("No se pudo parsear como JSON: {}", raw);
        }
        return null;
    }
}
