package com.miempresa.fasi.calendario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.miempresa.fasi.calendario.dto.IaAccionResultado;
import com.miempresa.fasi.ia.FasiAgentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@Slf4j
@Controller
@RequestMapping("/calendario")
public class CalendarioController {

    private final EventoService    eventoService;
    private final EventoIaService  eventoIaService;
    private final FasiAgentService agentService;
    private final ObjectMapper     objectMapper;

    public CalendarioController(EventoService eventoService,
                                EventoIaService eventoIaService,
                                FasiAgentService agentService) {
        this.eventoService   = eventoService;
        this.eventoIaService = eventoIaService;
        this.agentService    = agentService;
        this.objectMapper    = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @GetMapping
    public String vistaCalendario(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            Model model) {

        LocalDate hoy = LocalDate.now();
        int anio = (year != null) ? year : hoy.getYear();
        int mes  = (month != null) ? month : hoy.getMonthValue();

        java.time.YearMonth ym = java.time.YearMonth.of(anio, mes);

        model.addAttribute("eventos",   eventoService.findByMes(anio, mes));
        model.addAttribute("proximos",  eventoService.findProximos(5));
        model.addAttribute("tipos",     Evento.TipoEvento.values());
        model.addAttribute("yearMonth", ym);
        model.addAttribute("hoy",       hoy);
        model.addAttribute("nuevoEvento", new Evento());

        return "calendario/index";
    }

    @GetMapping("/dia/{fecha}")
    public String viaDia(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            Model model) {
        model.addAttribute("fecha",   fecha);
        model.addAttribute("eventos", eventoService.findByDia(fecha));
        model.addAttribute("tipos",   Evento.TipoEvento.values());
        return "calendario/dia";
    }

    @GetMapping("/nuevo")
    public String nuevoForm(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha,
            Model model) {
        Evento e = new Evento();
        e.setFechaInicio(fecha != null ? fecha : LocalDate.now());
        model.addAttribute("evento", e);
        model.addAttribute("tipos",  Evento.TipoEvento.values());
        return "calendario/form";
    }

    @GetMapping("/{id}/editar")
    public String editarForm(@PathVariable UUID id, Model model) {
        model.addAttribute("evento", eventoService.findById(id));
        model.addAttribute("tipos",  Evento.TipoEvento.values());
        return "calendario/form";
    }

    @PostMapping
    public String guardar(@ModelAttribute Evento evento) {
        eventoService.save(evento);
        return "redirect:/calendario?year=" + evento.getFechaInicio().getYear()
                + "&month=" + evento.getFechaInicio().getMonthValue();
    }

    @PostMapping("/{id}")
    public String actualizar(@PathVariable UUID id, @ModelAttribute Evento evento) {
        eventoService.update(id, evento);
        return "redirect:/calendario?year=" + evento.getFechaInicio().getYear()
                + "&month=" + evento.getFechaInicio().getMonthValue();
    }

    @PostMapping("/{id}/eliminar")
    public String eliminar(@PathVariable UUID id) {
        eventoService.delete(id);
        return "redirect:/calendario";
    }

    // ══════════════════════════════════════════════════════════
    //  API AGENTE AUTÓNOMO CON FUNCTION CALLING
    // ══════════════════════════════════════════════════════════

    @PostMapping("/api/agente")
    @ResponseBody
    public ResponseEntity<FasiAgentService.AgentResponse> ejecutarAgente(@RequestBody Map<String, String> body) {
        String orden = body.getOrDefault("texto", "");
        FasiAgentService.AgentResponse respuesta = agentService.ejecutarOrden(orden);
        return ResponseEntity.ok(respuesta);
    }

    @PostMapping("/api/interpretar-accion")
    @ResponseBody
    public ResponseEntity<IaAccionResultado> interpretarAccion(@RequestBody Map<String, String> body) {
        String texto = body.getOrDefault("texto", "");
        return ResponseEntity.ok(eventoIaService.interpretarAccion(texto));
    }

    @PostMapping("/api/confirmar-lote")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> confirmarLote(@RequestBody List<EventoConfirmarRequest> peticiones) {
        List<Evento> guardados = new ArrayList<>();
        for (EventoConfirmarRequest req : peticiones) {
            guardados.add(eventoService.save(req.toEvento()));
        }
        return ResponseEntity.ok(Map.of("guardados", guardados.size()));
    }

    @PostMapping("/api/consulta")
    @ResponseBody
    public ResponseEntity<String> consultaIa(@RequestBody String pregunta) {
        return ResponseEntity.ok(eventoIaService.consultar(pregunta));
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
}
