package com.miempresa.fasi.calendario;


import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/calendario")
@RequiredArgsConstructor
public class CalendarioController {

    private final EventoService    eventoService;
    private final EventoIaService  eventoIaService;

    // ── Vista principal (mes actual por defecto) ─────────────
    @GetMapping
    public String vistaCalendario(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String vista,
            Model model) {

        LocalDate hoy     = LocalDate.now();
        int       anio    = year  != null ? year  : hoy.getYear();
        int       mes     = month != null ? month : hoy.getMonthValue();
        String    vistaSel = vista != null ? vista : "mes";

        YearMonth ym      = YearMonth.of(anio, mes);

        model.addAttribute("eventos",     eventoService.findByMes(anio, mes));
        model.addAttribute("proximos",    eventoService.findProximos(5));
        model.addAttribute("tipos",       Evento.TipoEvento.values());
        model.addAttribute("yearMonth",   ym);
        model.addAttribute("hoy",         hoy);
        model.addAttribute("vista",       vistaSel);
        model.addAttribute("nuevoEvento", new Evento());

        return "calendario/index";
    }

    // ── Vista de un día concreto ─────────────────────────────
    @GetMapping("/dia/{fecha}")
    public String viaDia(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                         LocalDate fecha, Model model) {
        model.addAttribute("fecha",   fecha);
        model.addAttribute("eventos", eventoService.findByDia(fecha));
        model.addAttribute("tipos",   Evento.TipoEvento.values());
        return "calendario/dia";
    }

    // ── Formulario nuevo evento (manual) ─────────────────────
    @GetMapping("/nuevo")
    public String nuevoForm(@RequestParam(required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
                            Model model) {
        Evento e = new Evento();
        e.setFechaInicio(fecha != null ? fecha : LocalDate.now());
        model.addAttribute("evento", e);
        model.addAttribute("tipos",  Evento.TipoEvento.values());
        return "calendario/form";
    }

    // ── Formulario editar evento ─────────────────────────────
    @GetMapping("/{id}/editar")
    public String editarForm(@PathVariable UUID id, Model model) {
        model.addAttribute("evento", eventoService.findById(id));
        model.addAttribute("tipos",  Evento.TipoEvento.values());
        return "calendario/form";
    }

    // ── Guardar nuevo evento ─────────────────────────────────
    @PostMapping
    public String guardar(@Valid @ModelAttribute Evento evento,
            @RequestParam(value = "etiquetasRaw", required = false) String etiquetasRaw) {
        evento.setEtiquetas(parseEtiquetas(etiquetasRaw));
        eventoService.save(evento);
        return "redirect:/calendario?year=" + evento.getFechaInicio().getYear()
                + "&month=" + evento.getFechaInicio().getMonthValue();
    }

    // ── Actualizar evento ────────────────────────────────────
    @PostMapping("/{id}")
    public String actualizar(@PathVariable UUID id,
                             @Valid @ModelAttribute Evento evento,
            @RequestParam(value = "etiquetasRaw", required = false) String etiquetasRaw) {
        evento.setEtiquetas(parseEtiquetas(etiquetasRaw));
        eventoService.update(id, evento);
        return "redirect:/calendario?year=" + evento.getFechaInicio().getYear()
                + "&month=" + evento.getFechaInicio().getMonthValue();
    }

    // ── Eliminar evento ──────────────────────────────────────
    @PostMapping("/{id}/eliminar")
    public String eliminar(@PathVariable UUID id) {
        eventoService.delete(id);
        return "redirect:/calendario";
    }

    // ── API: Buscar eventos (buscador full-text) ─────────────
    @GetMapping("/api/buscar")
    @ResponseBody
    public ResponseEntity<List<Evento>> buscar(@RequestParam String q) {
        return ResponseEntity.ok(eventoService.buscar(q));
    }

    // ── API: Interpretar texto con IA → preview evento ───────
    @PostMapping("/api/interpretar")
    @ResponseBody
    public ResponseEntity<EventoIaResponse> interpretarVoz(
            @Valid @RequestBody EventoIaRequest request) {
        return ResponseEntity.ok(eventoIaService.interpretar(request));
    }

    // ── API: Confirmar y guardar evento interpretado por IA ──
    @PostMapping("/api/confirmar")
    @ResponseBody
    public ResponseEntity<Evento> confirmarEvento(@RequestBody EventoConfirmarRequest dto) {
        Evento evento = dto.toEvento();
        return ResponseEntity.ok(eventoService.save(evento));
    }

    // ── API: Consulta IA sobre el calendario ─────────────────
    @PostMapping("/api/consulta")
    @ResponseBody
    public ResponseEntity<String> consultaIa(@RequestBody String pregunta) {
        return ResponseEntity.ok(eventoIaService.consultar(pregunta));
    }

    // ── API: Eventos del mes (para renderizado dinámico) ─────
    @GetMapping("/api/mes")
    @ResponseBody
    public ResponseEntity<List<Evento>> eventosMes(
            @RequestParam int year, @RequestParam int month) {
        return ResponseEntity.ok(eventoService.findByMes(year, month));
    }

    // ── API: Eliminar evento individual (desde el grid) ───────
    @DeleteMapping("/api/eliminar/{id}")
    @ResponseBody
    public ResponseEntity<Void> eliminarApi(@PathVariable UUID id) {
        eventoService.delete(id);
        return ResponseEntity.ok().build();
    }

    // ── API: Vaciar todos los eventos (solo para pruebas) ─────
    @DeleteMapping("/api/vaciar")
    @ResponseBody
    public ResponseEntity<Void> vaciarEventos() {
        eventoService.deleteAll();
        return ResponseEntity.ok().build();
    }

    // ── API: Confirmar evento recurrente → genera todos los eventos ──
    @PostMapping("/api/confirmar-recurrente")
    @ResponseBody
    public ResponseEntity<List<Evento>> confirmarRecurrente(
            @RequestBody EventoConfirmarRequest dto) {
        return ResponseEntity.ok(eventoService.saveRecurrente(dto));
    }

    @GetMapping("/api/pendientes-datos")
    @ResponseBody
    public List<Evento> getPendientesDatos() {
        return eventoService.getEventosPendientesDatos();
    }

    /** Convierte "tutoria, colegio" en Set sin tildes ni mayusculas */
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


    // ── API: Obtener todas las etiquetas existentes ──────────
    @GetMapping("/api/etiquetas")
    @ResponseBody
    public ResponseEntity<List<String>> getEtiquetas() {
        return ResponseEntity.ok(eventoService.getAllEtiquetas());
    }
}
