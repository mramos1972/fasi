package com.miempresa.fasi.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miempresa.fasi.calendario.Evento;
import com.miempresa.fasi.calendario.EventoRepository;
import com.miempresa.fasi.calendario.EventoService;
import com.miempresa.fasi.compra.CompraService;
import com.miempresa.fasi.contexto.Usuario;
import com.miempresa.fasi.contexto.UsuarioActualService;
import com.miempresa.fasi.ia.LlmApiClient.FunctionCall;
import com.miempresa.fasi.ia.LlmApiClient.LlmTurn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class FasiAgentService {

    private final LlmApiClient llm;
    private final ObjectMapper mapper;
    private final EventoService eventoService;
    private final EventoRepository eventoRepo;
    private final CompraService compraService;
    private final UsuarioActualService usuarioActualService;

    private static final int      MAX_ITERACIONES = 8;
    private static final int      MAX_HISTORIAL   = 20;
    private static final Duration TTL_MEMORIA     = Duration.ofMinutes(30);
    private static final int      MAX_RESULTADOS  = 40;

    public record AgentResponse(String mensaje, boolean huboMutacion, long tiempoMs, int pasos) {}
    private record ToolResult(ObjectNode json, boolean mutacion) {}

    private static final class Memoria {
        final ArrayNode contents;
        Instant ultimoUso = Instant.now();
        Memoria(ArrayNode contents) { this.contents = contents; }
    }

    private final Map<UUID, Memoria> memorias = new ConcurrentHashMap<>();

    private static final Set<String> STOPWORDS = Set.of(
            "de", "del", "la", "el", "los", "las", "en", "a", "al", "y", "o", "con", "para", "por",
            "que", "un", "una", "unos", "unas", "mi", "mis", "su", "sus", "evento", "eventos", "cita");

    private static final Map<String, DayOfWeek> DIAS = Map.ofEntries(
            Map.entry("LUNES", DayOfWeek.MONDAY), Map.entry("MARTES", DayOfWeek.TUESDAY),
            Map.entry("MIERCOLES", DayOfWeek.WEDNESDAY), Map.entry("JUEVES", DayOfWeek.THURSDAY),
            Map.entry("VIERNES", DayOfWeek.FRIDAY), Map.entry("SABADO", DayOfWeek.SATURDAY),
            Map.entry("DOMINGO", DayOfWeek.SUNDAY));

    private volatile ArrayNode toolsCache;

    public AgentResponse ejecutarOrden(String texto) {
        long t0 = System.currentTimeMillis();
        if (texto == null || texto.isBlank()) {
            return new AgentResponse("Dime qué quieres hacer.", false, 0, 0);
        }

        Usuario usuario = usuarioActualService.obtenerUsuarioActual();
        Memoria mem = memoria(usuario.getId());

        synchronized (mem) {
            mem.ultimoUso = Instant.now();
            mem.contents.add(llm.userText(texto.trim()));

            String sistema = construirSystemPrompt(usuario);
            ArrayNode tools = declaracionesHerramientas();
            boolean mutacion = false;
            int pasos = 0;

            try {
                for (int i = 0; i < MAX_ITERACIONES; i++) {
                    long tl = System.currentTimeMillis();
                    LlmTurn turno = llm.generateWithTools(sistema, mem.contents, tools);
                    pasos++;
                    log.info("⏱ LLM#{} {} ms{}", pasos, System.currentTimeMillis() - tl,
                            turno.hasCalls() ? " -> " + turno.calls().stream().map(FunctionCall::name).toList() : " -> respuesta final");
                    mem.contents.add(turno.modelContent());

                    if (!turno.hasCalls()) {
                        recortar(mem);
                        long total = System.currentTimeMillis() - t0;
                        log.info("✅ Orden resuelta en {} ms ({} llamadas, mutacion={})", total, pasos, mutacion);
                        String msg = turno.text().isBlank() ? "Hecho." : turno.text();
                        return new AgentResponse(msg, mutacion, total, pasos);
                    }

                    ArrayNode partes = mapper.createArrayNode();
                    for (FunctionCall fc : turno.calls()) {
                        long tt = System.currentTimeMillis();
                        ToolResult res = ejecutarHerramienta(fc, usuario.getId());
                        log.info("⏱ tool {} {} ms args={}", fc.name(), System.currentTimeMillis() - tt, fc.args());
                        if (res.mutacion()) mutacion = true;
                        partes.add(llm.functionResponsePart(fc.name(), res.json()));
                    }
                    ObjectNode respuestaHerr = mapper.createObjectNode();
                    respuestaHerr.put("role", "user");
                    respuestaHerr.set("parts", partes);
                    mem.contents.add(respuestaHerr);
                }
                recortar(mem);
                return new AgentResponse("He realizado varias operaciones. Comprueba si falta algo.",
                        mutacion, System.currentTimeMillis() - t0, pasos);

            } catch (LlmApiClient.LlmException e) {
                log.error("❌ Agente: fallo de IA: {}", e.getMessage());
                while (mem.contents.size() > 0 && !"model".equals(mem.contents.get(mem.contents.size() - 1).path("role").asText())) {
                    mem.contents.remove(mem.contents.size() - 1);
                }
                return new AgentResponse("⚠️ " + e.userMessage(), mutacion, System.currentTimeMillis() - t0, pasos);
            } catch (Exception e) {
                log.error("❌ Agente: error inesperado", e);
                return new AgentResponse("Error procesando la orden: " + e.getMessage(), mutacion, System.currentTimeMillis() - t0, pasos);
            }
        }
    }

    public void olvidar() {
        memorias.remove(usuarioActualService.obtenerUsuarioActual().getId());
    }

    private Memoria memoria(UUID usuarioId) {
        memorias.entrySet().removeIf(en -> Duration.between(en.getValue().ultimoUso, Instant.now()).compareTo(TTL_MEMORIA) > 0);
        return memorias.computeIfAbsent(usuarioId, id -> new Memoria(mapper.createArrayNode()));
    }

    private void recortar(Memoria mem) {
        ArrayNode c = mem.contents;
        while (c.size() > MAX_HISTORIAL) c.remove(0);
        while (c.size() > 0 && !esTextoUsuario(c.get(0))) c.remove(0);
    }

    private boolean esTextoUsuario(JsonNode content) {
        return "user".equals(content.path("role").asText())
                && content.path("parts").size() > 0
                && content.path("parts").get(0).has("text");
    }

    private String construirSystemPrompt(Usuario usuario) {
        LocalDate hoy = LocalDate.now();
        String diaSemana = hoy.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es-ES"));

        StringBuilder agenda = new StringBuilder();
        List<Evento> proximos = eventoRepo.findByRango(usuario.getId(), hoy, hoy.plusDays(3));
        int n = 0;
        for (Evento e : proximos) {
            if (n++ >= 8) { agenda.append("- …\n"); break; }
            agenda.append("- ").append(e.getFechaInicio());
            if (e.getHoraInicio() != null) agenda.append(" ").append(e.getHoraInicio());
            agenda.append(" ").append(e.getTitulo()).append("\n");
        }
        if (n == 0) agenda.append("- (sin eventos)\n");

        return """
            Eres FASI, el asistente personal inteligente del hogar. Responde en español, breve y natural.
            Hoy: %s (%s). Usuario: %s.

            Reglas generales:
            - Gestionas tanto el CALENDARIO como la LISTA DE LA COMPRA.
            - LISTA DE LA COMPRA: Si el usuario te pide añadir o quitar cosas ("pon leche en la lista", "quita el arroz", "compra huevos y pan"), usa las herramientas 'anadir_a_lista_compra' o 'quitar_de_lista_compra'. Puedes pasar múltiples elementos en un array.
            - CALENDARIO: Para modificar, anotar, copiar o borrar eventos, primero 'buscar_eventos' y usa SOLO los ids devueltos.
            - Borrar más de 3 eventos requiere confirmación.
            - Al terminar confirma con exactitud lo realizado.

            Próximos 3 días en agenda:
            %s""".formatted(hoy, diaSemana,
                usuario.getNombre() != null ? usuario.getNombre() : usuario.getUsername(),
                agenda);
    }

    private ArrayNode declaracionesHerramientas() {
        ArrayNode cache = toolsCache;
        if (cache != null) return cache;
        ArrayNode tools = mapper.createArrayNode();

        // ── Herramientas de Compra ──
        tools.add(herramienta("anadir_a_lista_compra",
                "Añade uno o varios productos a la lista de la compra del usuario.",
                props(arr("items", "Lista de nombres de productos a añadir (ej: [\"aceite\", \"azúcar\"]).")),
                "items"));

        tools.add(herramienta("quitar_de_lista_compra",
                "Quita o elimina productos de la lista de la compra del usuario.",
                props(arr("items", "Lista de nombres de productos a quitar (ej: [\"arroz\"]).")),
                "items"));

        // ── Herramientas de Calendario ──
        tools.add(herramienta("buscar_eventos",
                "Busca eventos del usuario y devuelve id, título, fecha, hora, tipo, lugar, personas, etiquetas y notas.",
                props(
                        p("texto", "STRING", "Palabras clave. Opcional."),
                        p("fechaDesde", "STRING", "YYYY-MM-DD. Opcional."),
                        p("fechaHasta", "STRING", "YYYY-MM-DD. Opcional."),
                        p("tipo", "STRING", "Tipo. Opcional.")
                )));

        tools.add(herramienta("crear_evento",
                "Crea un evento.",
                props(
                        p("titulo", "STRING", "Título."),
                        p("fechaInicio", "STRING", "YYYY-MM-DD."),
                        p("fechaFin", "STRING", "YYYY-MM-DD. Opcional."),
                        p("horaInicio", "STRING", "HH:MM. Opcional."),
                        p("horaFin", "STRING", "HH:MM. Opcional."),
                        p("tipo", "STRING", "Tipo. Opcional."),
                        p("lugar", "STRING", "Opcional."),
                        p("personas", "STRING", "Opcional."),
                        p("descripcion", "STRING", "Notas. Opcional."),
                        arr("etiquetas", "Etiquetas. Opcional.")
                ), "titulo", "fechaInicio"));

        tools.add(herramienta("crear_eventos_recurrentes",
                "Crea una serie repetida entre dos fechas.",
                props(
                        p("titulo", "STRING", "Título."),
                        p("fechaInicio", "STRING", "YYYY-MM-DD."),
                        p("fechaFin", "STRING", "YYYY-MM-DD."),
                        p("frecuencia", "STRING", "DIARIA, SEMANAL o MENSUAL."),
                        arr("diasSemana", "Días semana."),
                        p("horaInicio", "STRING", "HH:MM. Opcional."),
                        p("horaFin", "STRING", "HH:MM. Opcional."),
                        p("tipo", "STRING", "Opcional."),
                        p("lugar", "STRING", "Opcional."),
                        p("personas", "STRING", "Opcional."),
                        p("descripcion", "STRING", "Opcional.")
                ), "titulo", "fechaInicio", "fechaFin", "frecuencia"));

        tools.add(herramienta("actualizar_evento",
                "Modifica campos de un evento.",
                props(
                        p("id", "STRING", "Id del evento."),
                        p("titulo", "STRING", "Opcional."),
                        p("fechaInicio", "STRING", "YYYY-MM-DD. Opcional."),
                        p("fechaFin", "STRING", "YYYY-MM-DD. Opcional."),
                        p("horaInicio", "STRING", "HH:MM. Opcional."),
                        p("horaFin", "STRING", "HH:MM. Opcional."),
                        p("tipo", "STRING", "Opcional."),
                        p("lugar", "STRING", "Opcional."),
                        p("personas", "STRING", "Opcional."),
                        p("descripcion", "STRING", "Opcional."),
                        arr("etiquetas", "Opcional.")
                ), "id"));

        tools.add(herramienta("anadir_nota",
                "Añade una observación a eventos.",
                props(arr("ids", "Ids de eventos."), p("nota", "STRING", "Texto.")),
                "ids", "nota"));

        tools.add(herramienta("borrar_eventos",
                "Elimina eventos por id.",
                props(arr("ids", "Ids a borrar.")),
                "ids"));

        toolsCache = tools;
        return tools;
    }

    private ObjectNode herramienta(String nombre, String descripcion, ObjectNode properties, String... required) {
        ObjectNode t = mapper.createObjectNode();
        t.put("name", nombre);
        t.put("description", descripcion);
        ObjectNode params = t.putObject("parameters");
        params.put("type", "OBJECT");
        params.set("properties", properties);
        if (required.length > 0) {
            ArrayNode req = params.putArray("required");
            for (String r : required) req.add(r);
        }
        return t;
    }

    private ObjectNode props(ObjectNode... campos) {
        ObjectNode o = mapper.createObjectNode();
        for (ObjectNode c : campos) o.set(c.get("_nombre").asText(), (ObjectNode) c.without("_nombre"));
        return o;
    }

    private ObjectNode p(String nombre, String tipo, String desc) {
        ObjectNode o = mapper.createObjectNode();
        o.put("_nombre", nombre);
        o.put("type", tipo);
        o.put("description", desc);
        return o;
    }

    private ObjectNode arr(String nombre, String desc) {
        ObjectNode o = p(nombre, "ARRAY", desc);
        o.putObject("items").put("type", "STRING");
        return o;
    }

    private ToolResult ejecutarHerramienta(FunctionCall fc, UUID usuarioId) {
        JsonNode a = fc.args() == null ? mapper.createObjectNode() : fc.args();
        try {
            return switch (fc.name()) {
                case "anadir_a_lista_compra"    -> new ToolResult(ejecutarAnadirCompra(a, usuarioId), true);
                case "quitar_de_lista_compra"    -> new ToolResult(ejecutarQuitarCompra(a, usuarioId), true);
                case "buscar_eventos"            -> new ToolResult(buscarEventos(a, usuarioId), false);
                case "crear_evento"              -> new ToolResult(crearEvento(a), true);
                case "crear_eventos_recurrentes" -> new ToolResult(crearRecurrentes(a), true);
                case "actualizar_evento"         -> new ToolResult(actualizarEvento(a), true);
                case "anadir_nota"               -> new ToolResult(anadirNota(a), true);
                case "borrar_eventos"            -> new ToolResult(borrarEventos(a), true);
                default -> new ToolResult(error("Herramienta desconocida: " + fc.name()), false);
            };
        } catch (Exception e) {
            log.warn("⚠️ Herramienta {} falló: {}", fc.name(), e.getMessage());
            return new ToolResult(error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), false);
        }
    }

    private ObjectNode ejecutarAnadirCompra(JsonNode a, UUID usuarioId) {
        List<String> items = new ArrayList<>();
        if (a.has("items") && a.get("items").isArray()) {
            for (JsonNode n : a.get("items")) items.add(n.asText());
        }
        List<String> agregados = compraService.anadirElementos(items, usuarioId);
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.put("mensaje", "Añadidos a la lista de la compra: " + String.join(", ", agregados));
        return out;
    }

    private ObjectNode ejecutarQuitarCompra(JsonNode a, UUID usuarioId) {
        List<String> items = new ArrayList<>();
        if (a.has("items") && a.get("items").isArray()) {
            for (JsonNode n : a.get("items")) items.add(n.asText());
        }
        List<String> quitados = compraService.quitarElementos(items, usuarioId);
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.put("mensaje", quitados.isEmpty() 
                ? "No se encontraron esos productos en la lista." 
                : "Eliminados de la lista: " + String.join(", ", quitados));
        return out;
    }

    private ObjectNode buscarEventos(JsonNode a, UUID usuarioId) {
        LocalDate desde = fecha(a, "fechaDesde");
        LocalDate hasta = fecha(a, "fechaHasta");
        String texto    = str(a, "texto");
        String tipoStr  = str(a, "tipo");

        List<Evento> base;
        if (desde != null || hasta != null) {
            LocalDate d = desde != null ? desde : hasta.minusYears(1);
            LocalDate h = hasta != null ? hasta : desde.plusYears(1);
            base = eventoRepo.findByRango(usuarioId, d, h);
        } else {
            base = eventoRepo.findAllOptimizado(usuarioId);
        }

        if (tipoStr != null) {
            Evento.TipoEvento tipo = Evento.TipoEvento.fromValue(tipoStr);
            base = base.stream().filter(e -> e.getTipo() == tipo).toList();
        }

        List<Evento> filtrados = base;
        if (texto != null) {
            List<String> tokens = Arrays.stream(norm(texto).split("[^a-z0-9]+"))
                    .filter(t -> t.length() >= 3 && !STOPWORDS.contains(t))
                    .toList();
            if (!tokens.isEmpty()) {
                List<Evento> todos = base.stream().filter(e -> tokens.stream().allMatch(t -> textoEvento(e).contains(t))).toList();
                if (todos.isEmpty()) {
                    todos = base.stream().filter(e -> tokens.stream().anyMatch(t -> textoEvento(e).contains(t))).toList();
                }
                filtrados = todos;
            }
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("total", filtrados.size());
        ArrayNode arr = out.putArray("eventos");
        filtrados.stream().limit(MAX_RESULTADOS).forEach(e -> arr.add(aJson(e)));
        if (filtrados.size() > MAX_RESULTADOS) out.put("aviso", "Se muestran solo los primeros " + MAX_RESULTADOS + ".");
        if (filtrados.isEmpty()) out.put("aviso", "No hay eventos que coincidan.");
        return out;
    }

    private String textoEvento(Evento e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getTitulo()).append(' ');
        if (e.getLugar() != null) sb.append(e.getLugar()).append(' ');
        if (e.getPersonasRelacionadas() != null) sb.append(e.getPersonasRelacionadas()).append(' ');
        if (e.getDescripcion() != null) sb.append(e.getDescripcion()).append(' ');
        if (e.getEtiquetas() != null) e.getEtiquetas().forEach(t -> sb.append(t).append(' '));
        return norm(sb.toString());
    }

    private ObjectNode crearEvento(JsonNode a) {
        String titulo = str(a, "titulo");
        LocalDate fi  = fecha(a, "fechaInicio");
        if (titulo == null || fi == null) return error("titulo y fechaInicio son obligatorios.");

        Evento e = new Evento();
        rellenar(e, a, true);
        e.setTitulo(titulo);
        e.setFechaInicio(fi);
        if (a.hasNonNull("descripcion")) e.setDescripcion(str(a, "descripcion"));
        e.setTodoDia(e.getHoraInicio() == null);
        Evento g = eventoService.save(e);

        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.set("evento", aJson(g));
        return out;
    }

    private ObjectNode crearRecurrentes(JsonNode a) {
        String titulo = str(a, "titulo");
        LocalDate fi = fecha(a, "fechaInicio");
        LocalDate ff = fecha(a, "fechaFin");
        String frec  = Optional.ofNullable(str(a, "frecuencia")).orElse("SEMANAL").toUpperCase();
        if (titulo == null || fi == null || ff == null) return error("titulo, fechaInicio y fechaFin son obligatorios.");
        if (ff.isBefore(fi)) return error("fechaFin no puede ser anterior a fechaInicio.");

        Set<DayOfWeek> dias = new HashSet<>();
        if (a.has("diasSemana") && a.get("diasSemana").isArray()) {
            for (JsonNode d : a.get("diasSemana")) {
                DayOfWeek dow = DIAS.get(norm(d.asText()).toUpperCase());
                if (dow != null) dias.add(dow);
            }
        }

        List<LocalDate> fechas = new ArrayList<>();
        switch (frec) {
            case "DIARIA" -> { for (LocalDate c = fi; !c.isAfter(ff) && fechas.size() < 120; c = c.plusDays(1)) fechas.add(c); }
            case "MENSUAL" -> { for (LocalDate c = fi; !c.isAfter(ff) && fechas.size() < 60; c = c.plusMonths(1)) fechas.add(c); }
            default -> {
                if (dias.isEmpty()) dias.add(fi.getDayOfWeek());
                for (LocalDate c = fi; !c.isAfter(ff) && fechas.size() < 120; c = c.plusDays(1)) {
                    if (dias.contains(c.getDayOfWeek())) fechas.add(c);
                }
            }
        }
        if (fechas.isEmpty()) return error("No se genera ninguna fecha con esos parámetros.");

        String serieId = UUID.randomUUID().toString();
        int creados = 0;
        for (LocalDate f : fechas) {
            Evento e = new Evento();
            rellenar(e, a, true);
            e.setTitulo(titulo);
            e.setFechaInicio(f);
            e.setFechaFin(null);
            if (a.hasNonNull("descripcion")) e.setDescripcion(str(a, "descripcion"));
            e.setTodoDia(e.getHoraInicio() == null);
            Map<String, Object> extra = new HashMap<>();
            extra.put("recurrente", true);
            extra.put("patron", frec);
            extra.put("serieId", serieId);
            e.setDatosExtra(extra);
            eventoService.save(e);
            creados++;
        }

        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.put("creados", creados);
        out.put("primeraFecha", fechas.get(0).toString());
        out.put("ultimaFecha", fechas.get(fechas.size() - 1).toString());
        out.put("serieId", serieId);
        return out;
    }

    private ObjectNode actualizarEvento(JsonNode a) {
        UUID id = uuid(str(a, "id"));
        if (id == null) return error("id inválido.");
        Evento e = eventoService.findById(id);

        if (a.hasNonNull("titulo"))      e.setTitulo(str(a, "titulo"));
        if (a.hasNonNull("fechaInicio")) { LocalDate f = fecha(a, "fechaInicio"); if (f == null) return error("fechaInicio inválida"); e.setFechaInicio(f); }
        if (a.hasNonNull("fechaFin"))    e.setFechaFin(fecha(a, "fechaFin"));
        if (a.hasNonNull("horaInicio"))  { e.setHoraInicio(hora(a, "horaInicio")); e.setTodoDia(e.getHoraInicio() == null); }
        if (a.hasNonNull("horaFin"))     e.setHoraFin(hora(a, "horaFin"));
        if (a.hasNonNull("tipo"))        e.setTipo(Evento.TipoEvento.fromValue(str(a, "tipo")));
        if (a.hasNonNull("lugar"))       e.setLugar(str(a, "lugar"));
        if (a.hasNonNull("personas"))    e.setPersonasRelacionadas(str(a, "personas"));
        if (a.hasNonNull("descripcion")) e.setDescripcion(str(a, "descripcion"));
        if (a.has("etiquetas") && a.get("etiquetas").isArray()) e.setEtiquetas(etiquetas(a.get("etiquetas")));

        Evento g = eventoService.update(id, e);
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.set("evento", aJson(g));
        return out;
    }

    private ObjectNode anadirNota(JsonNode a) {
        String nota = str(a, "nota");
        if (nota == null) return error("nota vacía.");
        List<UUID> ids = uuids(a.get("ids"));
        if (ids.isEmpty()) return error("ids vacíos: busca primero con buscar_eventos.");

        String sello = "[" + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM")) + "] " + nota.trim();
        ArrayNode tocados = mapper.createArrayNode();
        for (UUID id : ids) {
            Evento e = eventoService.findById(id);
            String previa = e.getDescripcion() != null ? e.getDescripcion().trim() : "";
            e.setDescripcion(previa.isBlank() ? sello : previa + "\n" + sello);
            tocados.add(aJson(eventoService.update(id, e)));
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.put("anotados", tocados.size());
        out.set("eventos", tocados);
        return out;
    }

    private ObjectNode borrarEventos(JsonNode a) {
        List<UUID> ids = uuids(a.get("ids"));
        if (ids.isEmpty()) return error("ids vacíos: busca primero con buscar_eventos.");
        ArrayNode borrados = mapper.createArrayNode();
        for (UUID id : ids) {
            Evento e = eventoService.findById(id);
            borrados.add(e.getTitulo() + " (" + e.getFechaInicio() + ")");
            eventoService.delete(id);
        }
        ObjectNode out = mapper.createObjectNode();
        out.put("ok", true);
        out.put("borrados", borrados.size());
        out.set("detalle", borrados);
        return out;
    }

    private void rellenar(Evento e, JsonNode a, boolean inferirTipo) {
        if (a.hasNonNull("fechaFin"))   e.setFechaFin(fecha(a, "fechaFin"));
        if (a.hasNonNull("horaInicio")) e.setHoraInicio(hora(a, "horaInicio"));
        if (a.hasNonNull("horaFin"))    e.setHoraFin(hora(a, "horaFin"));
        if (a.hasNonNull("lugar"))      e.setLugar(str(a, "lugar"));
        if (a.hasNonNull("personas"))   e.setPersonasRelacionadas(str(a, "personas"));
        if (a.has("etiquetas") && a.get("etiquetas").isArray()) e.setEtiquetas(etiquetas(a.get("etiquetas")));
        if (a.hasNonNull("tipo"))       e.setTipo(Evento.TipoEvento.fromValue(str(a, "tipo")));
        else if (inferirTipo)           e.setTipo(inferirTipo(str(a, "titulo")));
    }

    private Evento.TipoEvento inferirTipo(String titulo) {
        String t = norm(titulo == null ? "" : titulo);
        if (t.contains("cumple") || t.contains("cena") || t.contains("comida") || t.contains("fiesta")) return Evento.TipoEvento.SOCIAL;
        if (t.contains("medic") || t.contains("dentista") || t.contains("fisio") || t.contains("hospital")) return Evento.TipoEvento.SALUD;
        if (t.contains("reunion") || t.contains("claustro") || t.contains("trabajo")) return Evento.TipoEvento.TRABAJO;
        if (t.contains("tutoria") || t.contains("colegio") || t.contains("piscina")) return Evento.TipoEvento.FAMILIA;
        if (t.contains("viaje") || t.contains("vuelo")) return Evento.TipoEvento.VIAJE;
        if (t.contains("vacacion")) return Evento.TipoEvento.VACACIONES;
        if (t.contains("curso") || t.contains("clase")) return Evento.TipoEvento.APRENDIZAJE;
        if (t.contains("cine") || t.contains("teatro") || t.contains("gimnasio")) return Evento.TipoEvento.OCIO;
        return Evento.TipoEvento.PERSONAL;
    }

    private ObjectNode aJson(Evento e) {
        ObjectNode o = mapper.createObjectNode();
        o.put("id", e.getId().toString());
        o.put("titulo", e.getTitulo());
        o.put("fecha", e.getFechaInicio().toString());
        if (e.getFechaFin() != null)  o.put("fechaFin", e.getFechaFin().toString());
        if (e.getHoraInicio() != null) o.put("hora", e.getHoraInicio().toString());
        if (e.getHoraFin() != null)   o.put("horaFin", e.getHoraFin().toString());
        o.put("tipo", e.getTipo() != null ? e.getTipo().name() : "OTRO");
        if (e.getLugar() != null) o.put("lugar", e.getLugar());
        if (e.getPersonasRelacionadas() != null) o.put("personas", e.getPersonasRelacionadas());
        if (e.getEtiquetas() != null && !e.getEtiquetas().isEmpty()) {
            ArrayNode tags = o.putArray("etiquetas");
            e.getEtiquetas().forEach(tags::add);
        }
        if (e.getDescripcion() != null && !e.getDescripcion().isBlank()) {
            String d = e.getDescripcion();
            o.put("notas", d.length() > 300 ? d.substring(0, 300) + "…" : d);
        }
        return o;
    }

    private ObjectNode error(String msg) {
        ObjectNode o = mapper.createObjectNode();
        o.put("ok", false);
        o.put("error", msg);
        return o;
    }

    private static String str(JsonNode a, String k) {
        if (a == null || !a.hasNonNull(k)) return null;
        String s = a.get(k).asText().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private static LocalDate fecha(JsonNode a, String k) {
        String s = str(a, k);
        if (s == null) return null;
        try { return LocalDate.parse(s.substring(0, Math.min(10, s.length()))); } catch (Exception e) { return null; }
    }

    private static LocalTime hora(JsonNode a, String k) {
        String s = str(a, k);
        if (s == null) return null;
        try {
            if (s.matches("\\d{1,2}")) return LocalTime.of(Integer.parseInt(s), 0);
            if (s.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) {
                String[] p = s.split(":");
                return LocalTime.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static UUID uuid(String s) {
        try { return s == null ? null : UUID.fromString(s.trim()); } catch (Exception e) { return null; }
    }

    private static List<UUID> uuids(JsonNode arr) {
        List<UUID> out = new ArrayList<>();
        if (arr == null) return out;
        if (arr.isArray()) { for (JsonNode n : arr) { UUID u = uuid(n.asText()); if (u != null) out.add(u); } }
        else { UUID u = uuid(arr.asText()); if (u != null) out.add(u); }
        return out;
    }

    private static Set<String> etiquetas(JsonNode arr) {
        Set<String> s = new HashSet<>();
        for (JsonNode n : arr) {
            String t = norm(n.asText()).replaceAll("[^a-z0-9]", "");
            if (!t.isEmpty()) s.add(t);
        }
        return s;
    }

    private static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }
}
