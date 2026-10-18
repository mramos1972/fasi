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
        Eres FASI, el asistente operativo inteligente de calendario.
        Hoy es %s (%s). Año en curso: %d.
        
        Clasifica la orden del usuario de forma estricta en JSON puro (sin bloques ```json):
        
        1. ACCIÓN "OBSERVACION" (o nota/apunte/recordatorio):
        Regla de oro: NUNCA cambies el título. Las notas u observaciones van siempre al campo de observaciones.
        Si el usuario dice "en todos", "en la serie", o se refiere a un evento recurrente en general, 'afectarATodos' debe ser true.
        {
          "accion": "OBSERVACION",
          "buscar": {
            "texto": "piscina miguel",
            "fechaDesde": "YYYY-MM-DD", // opcional si acota mes o fecha (ej: 2026-11-01)
            "fechaHasta": "YYYY-MM-DD"  // opcional (ej: 2026-11-30)
          },
          "afectarATodos": true,        // true si debe aplicarse a todas las repeticiones encontradas; false si es solo a uno
          "textoObservacion": "Llevar gorro y chanclas",
          "mensaje": "He añadido la observación a las sesiones de piscina de Miguel."
        }
        
        2. ACCIÓN "COPIAR_SERIE" o "COPIAR":
        Si pide replicar o copiar eventos recurrentes de un mes/periodo a otro (ej: "copia los martes de piscina de noviembre a febrero"):
        {
          "accion": "COPIAR_SERIE",
          "buscar": {
            "texto": "piscina miguel",
            "mesOrigen": 11,           // mes origen numérico (1-12)
            "añoOrigen": 2026          // año origen
          },
          "destino": {
            "mesDestino": 2,           // mes destino numérico
            "añoDestino": 2027         // año destino (si el mes destino es anterior al mes actual, asume el año siguiente)
          },
          "mensaje": "He copiado todas las sesiones de piscina de noviembre a febrero."
        }
        
        Si pide copiar un único evento a un día concreto:
        {
          "accion": "COPIAR",
          "buscar": { "texto": "médico", "fecha": "YYYY-MM-DD" },
          "destino": { "nuevaFecha": "YYYY-MM-DD", "nuevaHora": "HH:MM" },
          "mensaje": "He copiado el evento al [fecha]."
        }
        
        3. ACCIÓN "MODIFICAR":
        Cambiar título, fecha u hora de un evento existente.
        {
          "accion": "MODIFICAR",
          "buscar": { "texto": "cumpleaños", "fecha": "YYYY-MM-DD" },
          "cambios": { "nuevaFecha": "YYYY-MM-DD", "nuevaHora": "HH:MM", "nuevoTitulo": null },
          "mensaje": "He modificado el evento."
        }
        
        4. ACCIÓN "RECURRENTE":
        Crear eventos recurrentes desde cero:
        {
          "accion": "RECURRENTE",
          "evento": {
            "titulo": "Piscina Miguel",
            "fechaInicio": "2026-11-03",
            "horaInicio": "17:00",
            "tipo": "OCIO"
          },
          "frecuencia": "SEMANAL",     // SEMANAL, DIARIA, MENSUAL
          "repeticiones": 4,
          "intervalo": 1,
          "mensaje": "He programado las repeticiones del evento."
        }
        
        5. ACCIÓN "BORRAR":
        Eliminar uno o varios eventos:
        {
          "accion": "BORRAR",
          "texto": "piscina",
          "fecha": "YYYY-MM-DD",       // o null
          "borrarTodos": false,        // true si pide borrar toda la serie o todos los que coincidan
          "mensaje": "He eliminado el evento de tu calendario."
        }
        
        6. ACCIÓN "CREAR":
        Crear evento nuevo normal:
        {
          "accion": "CREAR",
          "eventos": [ { "titulo": "...", "fechaInicio": "YYYY-MM-DD", "horaInicio": "HH:MM" } ],
          "mensaje": "He creado el evento."
        }
        
        7. ACCIÓN "CONSULTA":
        {
          "accion": "CONSULTA",
          "mensaje": "Respuesta clara a la duda."
        }
        """;

    public AgentResponse ejecutarOrden(String texto) {
        LocalDate hoy = LocalDate.now();
        String prompt = SYSTEM_PROMPT.formatted(hoy, hoy.getDayOfWeek(), hoy.getYear());

        try {
            log.info("🤖 Interpretando orden: '{}'", texto);
            String rawJson = llmClient.chatJson(prompt, texto);
            log.info("📥 Respuesta cruda de la IA: {}", rawJson);

            String limpio = sanitizarJson(rawJson);
            JsonNode root = objectMapper.readTree(limpio);

            String accion = root.path("accion").asText("CONSULTA").toUpperCase();
            String mensaje = root.path("mensaje").asText("Operación completada.");
            UUID usuarioId = usuarioActualService.obtenerUsuarioActual().getId();

            switch (accion) {
                case "OBSERVACION", "NOTA" -> {
                    JsonNode buscarNode = root.path("buscar");
                    String textoBuscar = buscarNode.path("texto").asText("");
                    String fDesde = buscarNode.path("fechaDesde").asText(null);
                    String fHasta = buscarNode.path("fechaHasta").asText(null);
                    boolean afectarATodos = root.path("afectarATodos").asBoolean(false);
                    String notaNueva = root.hasNonNull("textoObservacion") 
                            ? root.path("textoObservacion").asText() 
                            : root.path("nota").asText("");

                    List<Evento> candidatos = buscarEventosPorRangoOTexto(usuarioId, textoBuscar, fDesde, fHasta);
                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No encontré ningún evento de '" + textoBuscar + "' para añadirle las observaciones.", false);
                    }

                    List<Evento> destino = afectarATodos ? candidatos : List.of(candidatos.get(0));
                    String timestamp = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM"));
                    String apunte = "[" + timestamp + "] " + notaNueva;

                    for (Evento ev : destino) {
                        String previa = ev.getDescripcion() != null ? ev.getDescripcion().trim() : "";
                        if (previa.isBlank()) {
                            ev.setDescripcion(apunte);
                        } else {
                            ev.setDescripcion(previa + "\n" + apunte);
                        }
                        eventoService.update(ev.getId(), ev);
                        log.info("📝 Observación añadida a '{}' ({})", ev.getTitulo(), ev.getFechaInicio());
                    }

                    String confirmacion = destino.size() > 1 
                            ? "He añadido la observación en los " + destino.size() + " eventos encontrados."
                            : "He añadido la observación al evento de " + destino.get(0).getTitulo() + ".";
                    return new AgentResponse(mensaje.isBlank() ? confirmacion : mensaje, true);
                }

                case "COPIAR_SERIE" -> {
                    JsonNode b = root.path("buscar");
                    JsonNode d = root.path("destino");

                    String textoBuscar = b.path("texto").asText("");
                    int mesOrigen = b.path("mesOrigen").asInt(hoy.getMonthValue());
                    int anioOrigen = b.path("añoOrigen").asInt(hoy.getYear());

                    int mesDestino = d.path("mesDestino").asInt(mesOrigen);
                    int anioDestino = d.path("añoDestino").asInt(anioOrigen);

                    YearMonth ymOrigen = YearMonth.of(anioOrigen, mesOrigen);
                    List<Evento> eventosMes = eventoRepo.findByRango(usuarioId, ymOrigen.atDay(1), ymOrigen.atEndOfMonth());

                    List<Evento> aCopiar = new ArrayList<>();
                    for (Evento e : eventosMes) {
                        if (e.getTitulo().toLowerCase().contains(textoBuscar.toLowerCase())) {
                            aCopiar.add(e);
                        }
                    }

                    if (aCopiar.isEmpty()) {
                        return new AgentResponse("No encontré eventos de '" + textoBuscar + "' en el mes indicado para duplicar.", false);
                    }

                    YearMonth ymDestino = YearMonth.of(anioDestino, mesDestino);
                    int creados = 0;

                    for (Evento orig : aCopiar) {
                        int diaSemanaObjetivo = orig.getFechaInicio().getDayOfWeek().getValue();
                        
                        // Buscar el día de la semana correspondiente en el mes destino
                        LocalDate fechaDestino = null;
                        for (int dia = 1; dia <= ymDestino.lengthOfMonth(); dia++) {
                            LocalDate candidata = ymDestino.atDay(dia);
                            if (candidata.getDayOfWeek().getValue() == diaSemanaObjetivo) {
                                // Mapeamos por número de semana dentro del mes
                                int ocurrenciaOrigen = (orig.getFechaInicio().getDayOfMonth() - 1) / 7;
                                int ocurrenciaCandidata = (dia - 1) / 7;
                                if (ocurrenciaOrigen == ocurrenciaCandidata) {
                                    fechaDestino = candidata;
                                    break;
                                }
                            }
                        }

                        if (fechaDestino == null) {
                            // Si el mes destino tiene menos semanas, ponemos la última ocurrencia de ese día
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

                    log.info("📋 Copiados {} eventos de la serie '{}' a {}/{}", creados, textoBuscar, mesDestino, anioDestino);
                    return new AgentResponse(mensaje, true);
                }

                case "COPIAR" -> {
                    JsonNode buscarNode = root.path("buscar");
                    JsonNode destinoNode = root.path("destino");

                    List<Evento> candidatos = buscarEventosPorRangoOTexto(usuarioId, buscarNode.path("texto").asText(""), buscarNode.path("fecha").asText(null), null);
                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No encontré el evento original que deseas copiar.", false);
                    }

                    Evento orig = candidatos.get(0);
                    Evento clon = new Evento();
                    clon.setTitulo(orig.getTitulo());
                    clon.setTipo(orig.getTipo());
                    clon.setLugar(orig.getLugar());
                    clon.setPersonasRelacionadas(orig.getPersonasRelacionadas());
                    clon.setDescripcion(orig.getDescripcion());
                    clon.setTodoDia(orig.isTodoDia());

                    LocalDate fechaDestino = destinoNode.hasNonNull("nuevaFecha") && !destinoNode.path("nuevaFecha").asText().isBlank()
                            ? LocalDate.parse(destinoNode.path("nuevaFecha").asText())
                            : orig.getFechaInicio();
                    clon.setFechaInicio(fechaDestino);

                    if (destinoNode.hasNonNull("nuevaHora") && !destinoNode.path("nuevaHora").asText().isBlank()) {
                        clon.setHoraInicio(LocalTime.parse(destinoNode.path("nuevaHora").asText()));
                        clon.setTodoDia(false);
                    } else {
                        clon.setHoraInicio(orig.getHoraInicio());
                    }

                    eventoService.save(clon);
                    log.info("📋 Evento duplicado: '{}' en {}", clon.getTitulo(), clon.getFechaInicio());
                    return new AgentResponse(mensaje, true);
                }

                case "MODIFICAR" -> {
                    JsonNode buscarNode = root.path("buscar");
                    JsonNode cambiosNode = root.path("cambios");

                    List<Evento> candidatos = buscarEventosPorRangoOTexto(usuarioId, buscarNode.path("texto").asText(""), buscarNode.path("fecha").asText(null), null);
                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No encontré ningún evento que coincida con esa búsqueda para modificar.", false);
                    }

                    Evento ev = candidatos.get(0);
                    if (cambiosNode.hasNonNull("nuevaFecha") && !cambiosNode.path("nuevaFecha").asText().isBlank()) {
                        ev.setFechaInicio(LocalDate.parse(cambiosNode.path("nuevaFecha").asText()));
                    }
                    if (cambiosNode.hasNonNull("nuevaHora") && !cambiosNode.path("nuevaHora").asText().isBlank()) {
                        ev.setHoraInicio(LocalTime.parse(cambiosNode.path("nuevaHora").asText()));
                        ev.setTodoDia(false);
                    }
                    if (cambiosNode.hasNonNull("nuevoTitulo") && !cambiosNode.path("nuevoTitulo").asText().isBlank()) {
                        ev.setTitulo(cambiosNode.path("nuevoTitulo").asText());
                    }

                    eventoService.update(ev.getId(), ev);
                    log.info("✏️ Evento modificado: '{}' -> Fecha: {}", ev.getTitulo(), ev.getFechaInicio());
                    return new AgentResponse(mensaje, true);
                }

                case "RECURRENTE" -> {
                    JsonNode evNode = root.path("evento");
                    String frecuencia = root.path("frecuencia").asText("SEMANAL").toUpperCase();
                    int repeticiones = root.path("repeticiones").asInt(4);
                    int intervalo = root.path("intervalo").asInt(1);
                    if (repeticiones > 52) repeticiones = 52;

                    LocalDate fechaBase = LocalDate.parse(evNode.path("fechaInicio").asText(hoy.toString()));
                    LocalTime hora = evNode.hasNonNull("horaInicio") && !evNode.path("horaInicio").asText().isBlank()
                            ? LocalTime.parse(evNode.path("horaInicio").asText()) : null;

                    String tipoStr = evNode.path("tipo").asText("OTRO");
                    Evento.TipoEvento tipo;
                    try { tipo = Evento.TipoEvento.valueOf(tipoStr.toUpperCase()); }
                    catch (Exception ex) { tipo = Evento.TipoEvento.OTRO; }

                    String titulo = evNode.path("titulo").asText("Evento recurrente");
                    String lugar = evNode.path("lugar").asText(null);

                    for (int i = 0; i < repeticiones; i++) {
                        LocalDate f = switch (frecuencia) {
                            case "DIARIA" -> fechaBase.plusDays((long) i * intervalo);
                            case "MENSUAL" -> fechaBase.plusMonths((long) i * intervalo);
                            case "CADA_X_DIAS" -> fechaBase.plusDays((long) i * intervalo);
                            default -> fechaBase.plusWeeks((long) i * intervalo);
                        };

                        Evento e = new Evento();
                        e.setTitulo(titulo);
                        e.setFechaInicio(f);
                        e.setHoraInicio(hora);
                        e.setTodoDia(hora == null);
                        e.setTipo(tipo);
                        e.setLugar(lugar);
                        eventoService.save(e);
                    }

                    log.info("🔁 Creados {} eventos recurrentes de '{}'", repeticiones, titulo);
                    return new AgentResponse(mensaje, true);
                }

                case "BORRAR" -> {
                    String fechaStr = root.path("fecha").asText(null);
                    String palabrasClave = root.path("texto").asText("");
                    boolean borrarTodos = root.path("borrarTodos").asBoolean(false);

                    List<Evento> candidatos = buscarEventosPorRangoOTexto(usuarioId, palabrasClave, fechaStr, null);
                    if (candidatos.isEmpty()) {
                        return new AgentResponse("No encontré ningún evento en tu calendario que coincida para borrar.", false);
                    }

                    List<Evento> aBorrar = borrarTodos ? candidatos : List.of(candidatos.get(0));

                    for (Evento e : aBorrar) {
                        eventoService.delete(e.getId());
                        log.info("🗑️ Evento borrado: '{}' ({})", e.getTitulo(), e.getFechaInicio());
                    }

                    String confirmacion = aBorrar.size() == 1
                            ? "He eliminado '" + aBorrar.get(0).getTitulo() + "' de tu calendario."
                            : "He eliminado " + aBorrar.size() + " eventos de tu calendario.";

                    return new AgentResponse(mensaje.isBlank() ? confirmacion : mensaje, true);
                }

                case "CREAR" -> {
                    JsonNode evArray = root.path("eventos");
                    if (evArray.isArray() && !evArray.isEmpty()) {
                        for (JsonNode node : evArray) {
                            Evento e = new Evento();
                            e.setTitulo(node.path("titulo").asText("Nuevo evento"));
                            e.setFechaInicio(LocalDate.parse(node.path("fechaInicio").asText(hoy.toString())));

                            if (node.hasNonNull("horaInicio") && !node.path("horaInicio").asText().isBlank()) {
                                try { e.setHoraInicio(LocalTime.parse(node.path("horaInicio").asText())); } catch (Exception ignored) {}
                            }

                            String tipoStr = node.path("tipo").asText("OTRO");
                            try { e.setTipo(Evento.TipoEvento.valueOf(tipoStr.toUpperCase())); }
                            catch (Exception ex) { e.setTipo(Evento.TipoEvento.OTRO); }

                            if (node.hasNonNull("lugar") && !node.path("lugar").asText().isBlank()) {
                                e.setLugar(node.path("lugar").asText());
                            }
                            if (node.hasNonNull("personas") && !node.path("personas").asText().isBlank()) {
                                e.setPersonasRelacionadas(node.path("personas").asText());
                            }

                            e.setTodoDia(node.path("todoDia").asBoolean(e.getHoraInicio() == null));
                            eventoService.save(e);
                            log.info("📅 Evento creado: '{}' para {}", e.getTitulo(), e.getFechaInicio());
                        }
                        return new AgentResponse(mensaje, true);
                    }
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

    private List<Evento> buscarEventosPorRangoOTexto(UUID usuarioId, String texto, String desdeStr, String hastaStr) {
        List<Evento> resultado = new ArrayList<>();

        if (desdeStr != null && !desdeStr.isBlank() && !desdeStr.equals("null")) {
            try {
                LocalDate fInicio = LocalDate.parse(desdeStr);
                LocalDate fFin = (hastaStr != null && !hastaStr.isBlank() && !hastaStr.equals("null")) 
                        ? LocalDate.parse(hastaStr) 
                        : fInicio;
                resultado = eventoRepo.findByRango(usuarioId, fInicio, fFin);
            } catch (Exception ignored) {}
        }

        if (resultado.isEmpty() && texto != null && !texto.isBlank()) {
            resultado = eventoRepo.buscarFullText(usuarioId, texto);
        }

        if (resultado.isEmpty()) {
            resultado = eventoRepo.findAllOptimizado(usuarioId);
        }

        if (texto == null || texto.isBlank()) {
            return resultado;
        }

        List<Evento> filtrados = new ArrayList<>();
        String[] tokens = texto.toLowerCase().split("\\s+");

        for (Evento e : resultado) {
            String matchStr = (e.getTitulo() + " " + (e.getLugar() != null ? e.getLugar() : "")).toLowerCase();
            for (String token : tokens) {
                if (!token.isBlank() && matchStr.contains(token)) {
                    filtrados.add(e);
                    break;
                }
            }
        }

        return filtrados.isEmpty() ? resultado : filtrados;
    }

    private String sanitizarJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstLine = s.indexOf('\n');
            int lastBacktick = s.lastIndexOf("```");
            if (firstLine != -1 && lastBacktick > firstLine) {
                s = s.substring(firstLine + 1, lastBacktick).trim();
            }
        }
        return s;
    }
}
