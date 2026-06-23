package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.miempresa.fasi.calendario.service.EventoParserService;
import com.miempresa.fasi.calendario.dto.EventoMinimo;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

@Controller
@RequestMapping("/calendario")
public class CalendarioController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CalendarioController.class);

    private final EventoService           eventoService;
    private final EventoIaService         eventoIaService;
    private final EventoParserRegex       regexParser;
    private final EventoParserService     parserService;
    private final ObjectMapper            objectMapper;

    public CalendarioController(EventoService eventoService,
                               EventoIaService eventoIaService,
                               EventoParserRegex regexParser,
                               EventoParserService parserService) {
        this.eventoService = eventoService;
        this.eventoIaService = eventoIaService;
        this.regexParser = regexParser;
        this.parserService = parserService;

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ── Vista principal ──────────────────────────────────────
    @GetMapping
    public String vistaCalendario(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String vista,
            Model model) {

        LocalDate hoy = LocalDate.now();
        int anio = year != null ? year : hoy.getYear();
        int mes = month != null ? month : hoy.getMonthValue();

        java.time.YearMonth ym = java.time.YearMonth.of(anio, mes);

        model.addAttribute("eventos", eventoService.findByMes(anio, mes));
        model.addAttribute("proximos", eventoService.findProximos(5));
        model.addAttribute("tipos", Evento.TipoEvento.values());
        model.addAttribute("yearMonth", ym);
        model.addAttribute("hoy", hoy);
        model.addAttribute("vista", vista != null ? vista : "mes");
        model.addAttribute("nuevoEvento", new Evento());

        return "calendario/index";
    }

    // ── Vista día ────────────────────────────────────────────
    @GetMapping("/dia/{fecha}")
    public String viaDia(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha, Model model) {
        model.addAttribute("fecha", fecha);
        model.addAttribute("eventos", eventoService.findByDia(fecha));
        model.addAttribute("tipos", Evento.TipoEvento.values());
        return "calendario/dia";
    }

    // ── Formulario nuevo ─────────────────────────────────────
    @GetMapping("/nuevo")
    public String nuevoForm(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha, Model model) {
        Evento e = new Evento();
        e.setFechaInicio(fecha != null ? fecha : LocalDate.now());
        model.addAttribute("evento", e);
        model.addAttribute("tipos", Evento.TipoEvento.values());
        return "calendario/form";
    }

    // ── Formulario editar ────────────────────────────────────
    @GetMapping("/{id}/editar")
    public String editarForm(@PathVariable UUID id, Model model) {
        model.addAttribute("evento", eventoService.findById(id));
        model.addAttribute("tipos", Evento.TipoEvento.values());
        return "calendario/form";
    }

    // ── Guardar nuevo ────────────────────────────────────────
    @PostMapping
    public String guardar(@Valid @ModelAttribute Evento evento,
            @RequestParam(value = "etiquetasRaw", required = false) String etiquetasRaw) {
        evento.setEtiquetas(parseEtiquetas(etiquetasRaw));
        eventoService.save(evento);
        return "redirect:/calendario?year=" + evento.getFechaInicio().getYear()
                + "&month=" + evento.getFechaInicio().getMonthValue();
    }

    // ── Actualizar ───────────────────────────────────────────
    @PostMapping("/{id}")
    public String actualizar(@PathVariable UUID id, @Valid @ModelAttribute Evento evento,
            @RequestParam(value = "etiquetasRaw", required = false) String etiquetasRaw) {
        evento.setEtiquetas(parseEtiquetas(etiquetasRaw));
        eventoService.update(id, evento);
        return "redirect:/calendario?year=" + evento.getFechaInicio().getYear()
                + "&month=" + evento.getFechaInicio().getMonthValue();
    }

    // ── Eliminar ─────────────────────────────────────────────
    @PostMapping("/{id}/eliminar")
    public String eliminar(@PathVariable UUID id) {
        eventoService.delete(id);
        return "redirect:/calendario";
    }

    // ══════════════════════════════════════════════════════════
    //  API ENDPOINTS
    // ══════════════════════════════════════════════════════════

    @GetMapping("/api/buscar")
    @ResponseBody
    public ResponseEntity<List<Evento>> buscar(@RequestParam String q) {
        return ResponseEntity.ok(eventoService.buscar(q));
    }

    @GetMapping("/api/mes")
    @ResponseBody
    public ResponseEntity<List<Evento>> eventosMes(@RequestParam int year, @RequestParam int month) {
        return ResponseEntity.ok(eventoService.findByMes(year, month));
    }

    @GetMapping("/api/etiquetas")
    @ResponseBody
    public ResponseEntity<List<String>> getEtiquetas() {
        return ResponseEntity.ok(eventoService.getAllEtiquetas());
    }

    @GetMapping("/api/pendientes-datos")
    @ResponseBody
    public List<Evento> getPendientesDatos() {
        return eventoService.getEventosPendientesDatos();
    }

    @DeleteMapping("/api/eliminar/{id}")
    @ResponseBody
    public ResponseEntity<Void> eliminarApi(@PathVariable UUID id) {
        eventoService.delete(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/api/vaciar")
    @ResponseBody
    public ResponseEntity<Void> vaciarEventos() {
        eventoService.deleteAll();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/consulta")
    @ResponseBody
    public ResponseEntity<String> consultaIa(@RequestBody String pregunta) {
        return ResponseEntity.ok(eventoIaService.consultar(pregunta));
    }

    // ══════════════════════════════════════════════════════════
    //  INTERPRETAR PREVIEW (endpoint principal del modal)
    // ══════════════════════════════════════════════════════════

    @PostMapping("/api/interpretar-preview")
    @ResponseBody
    public Map<String, Object> interpretarPreview(@RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();

        try {
            String texto = body.get("texto");
            LocalDate hoy = LocalDate.now();

            EventoParserService.EventoParseResponse resp = parserService.interpretar(texto, hoy);

            // Necesita límite
            if ("necesita_limite".equals(resp.getPaso())) {
                result.put("necesitaLimite", true);
                result.put("eventoBase", resp.getEvento());
                result.put("mensajeLimite", resp.getMensaje());
                return result;
            }

            // Error
            if ("error".equals(resp.getPaso())) {
                result.put("error", resp.getMensaje());
                return result;
            }

            // Éxito
            Evento evento = resp.getEvento().toEvento();
            boolean esRecurrente = resp.getEsRecurrente() != null && resp.getEsRecurrente();

            result.put("evento", evento);
            result.put("esRecurrente", esRecurrente);

            if (esRecurrente) {
                List<LocalDate> fechas = eventoService.generarVistaPrevia(evento);
                result.put("totalEventos", fechas.size());
                result.put("descripcionPreview", generarDescripcionPreview(fechas));
            }

            return result;

        } catch (Exception e) {
            log.error("❌ Error en interpretar-preview", e);
            result.put("error", "Error: " + e.getMessage());
            return result;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  PROCESAR LÍMITE (para recurrentes)
    // ══════════════════════════════════════════════════════════

    @PostMapping("/api/procesar-limite")
    @ResponseBody
    public Map<String, Object> procesarLimite(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventoBaseMap = (Map<String, Object>) body.get("eventoBase");
            String respuestaLimite = (String) body.get("respuestaLimite");

            // Convertir a EventoMinimo (NO a Evento)
            EventoMinimo eventoMinimo = objectMapper.convertValue(eventoBaseMap, EventoMinimo.class);

            // Procesar límite
            EventoParserService.EventoParseResponse resp = parserService.procesarLimite(eventoMinimo, respuestaLimite);

            if ("error".equals(resp.getPaso())) {
                result.put("error", resp.getMensaje());
                return result;
            }

            // Convertir a Evento para vista previa
            Evento evento = resp.getEvento().toEvento();
            List<LocalDate> fechas = eventoService.generarVistaPrevia(evento);

            result.put("evento", evento);
            result.put("esRecurrente", true);
            result.put("totalEventos", fechas.size());
            result.put("descripcionPreview", generarDescripcionPreview(fechas));

            return result;

        } catch (Exception e) {
            log.error("❌ Error procesando límite", e);
            result.put("error", "Error al procesar el límite: " + e.getMessage());
            return result;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  CONFIRMAR EVENTOS
    // ══════════════════════════════════════════════════════════

    @PostMapping("/api/confirmar")
    @ResponseBody
    public ResponseEntity<Evento> confirmarEvento(@RequestBody EventoConfirmarRequest dto) {
        Evento evento = dto.toEvento();
        return ResponseEntity.ok(eventoService.save(evento));
    }

    @PostMapping("/api/confirmar-recurrente")
    @ResponseBody
    public ResponseEntity<List<Evento>> confirmarRecurrente(@RequestBody EventoConfirmarRequest dto) {
        return ResponseEntity.ok(eventoService.saveRecurrente(dto));
    }

    // ══════════════════════════════════════════════════════════
    //  MÉTODOS AUXILIARES
    // ══════════════════════════════════════════════════════════

    private java.util.Set<String> parseEtiquetas(String raw) {
        if (raw == null || raw.isBlank()) return new java.util.HashSet<>();
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replaceAll("[áàä]", "a")
                           .replaceAll("[éèë]", "e")
                           .replaceAll("[íìï]", "i")
                           .replaceAll("[óòö]", "o")
                           .replaceAll("[úùü]", "u")
                           .replaceAll("[ñ]", "n"))
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
    }

    private String generarDescripcionPreview(List<LocalDate> fechas) {
        if (fechas.isEmpty()) return "No se generarán eventos";

        StringBuilder sb = new StringBuilder();
        sb.append("Se crearán ").append(fechas.size()).append(" evento");
        if (fechas.size() > 1) sb.append("s");
        sb.append(":\n\n");

        int limite = Math.min(10, fechas.size());
        for (int i = 0; i < limite; i++) {
            LocalDate fecha = fechas.get(i);
            String diaSemana = switch (fecha.getDayOfWeek()) {
                case MONDAY -> "Lun";
                case TUESDAY -> "Mar";
                case WEDNESDAY -> "Mié";
                case THURSDAY -> "Jue";
                case FRIDAY -> "Vie";
                case SATURDAY -> "Sáb";
                case SUNDAY -> "Dom";
            };
            sb.append(String.format("%-3s %02d/%02d/%d\n", diaSemana, fecha.getDayOfMonth(), fecha.getMonthValue(), fecha.getYear()));
        }

        if (fechas.size() > 10) {
            sb.append("\n... y ").append(fechas.size() - 10).append(" más");
        }

        return sb.toString();
    }
}
