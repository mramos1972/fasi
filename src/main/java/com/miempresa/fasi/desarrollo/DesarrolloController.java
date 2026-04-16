package com.miempresa.fasi.desarrollo;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Controller
@RequestMapping("/desarrollo")
@RequiredArgsConstructor
public class DesarrolloController {

    private final DesarrolloService service;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("desarrollos", service.findAll());
        model.addAttribute("estados", Desarrollo.EstadoDesarrollo.values());
        return "desarrollo/list";
    }

    @GetMapping("/nuevo")
    public String newForm(Model model) {
        model.addAttribute("desarrollo", Desarrollo.builder()
                .estado(Desarrollo.EstadoDesarrollo.PENDIENTE)
                .build());
        model.addAttribute("estados", Desarrollo.EstadoDesarrollo.values());
        return "desarrollo/form";
    }

    @GetMapping("/{id}/editar")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("desarrollo", service.findById(id));
        model.addAttribute("estados", Desarrollo.EstadoDesarrollo.values());
        return "desarrollo/form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute Desarrollo desarrollo, BindingResult br, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("estados", Desarrollo.EstadoDesarrollo.values());
            return "desarrollo/form";
        }
        service.save(desarrollo);
        return "redirect:/desarrollo";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute Desarrollo desarrollo,
                         BindingResult br, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("estados", Desarrollo.EstadoDesarrollo.values());
            return "desarrollo/form";
        }
        service.update(id, desarrollo);
        return "redirect:/desarrollo";
    }

    @PostMapping("/{id}/aplicar")
    public String aplicar(@PathVariable UUID id) {
        service.marcarAplicado(id);
        return "redirect:/desarrollo";
    }

    @PostMapping("/{id}/descartar")
    public String descartar(@PathVariable UUID id) {
        service.marcarDescartado(id);
        return "redirect:/desarrollo";
    }

    @GetMapping("/{id}/descargar")
    public ResponseEntity<byte[]> descargarScript(@PathVariable UUID id) {
        Desarrollo d = service.findById(id);
        String script = d.getScriptGenerado() != null ? d.getScriptGenerado() : "#!/bin/bash\n# Sin script generado";
        byte[] bytes = script.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"apply-" + d.getId() + ".sh\"")
            .contentType(MediaType.parseMediaType("application/x-sh"))
            .body(bytes);
    }

    @PostMapping("/{id}/eliminar")
    public String delete(@PathVariable UUID id) {
        service.delete(id);
        return "redirect:/desarrollo";
    }
}
