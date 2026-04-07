package com.miempresa.fasi.contabilidad;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.UUID;

@Controller
@RequestMapping("/contabilidad")
@RequiredArgsConstructor
public class MovimientoController {

    private final MovimientoService service;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("movimientos", service.findAll());
        model.addAttribute("resumen", service.resumen());
        model.addAttribute("tipos", Movimiento.TipoMovimiento.values());
        return "contabilidad/list";
    }

    @GetMapping("/nuevo")
    public String newForm(Model model) {
        model.addAttribute("movimiento", new Movimiento());
        model.addAttribute("tipos", Movimiento.TipoMovimiento.values());
        return "contabilidad/form";
    }

    @GetMapping("/{id}/editar")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("movimiento", service.findById(id));
        model.addAttribute("tipos", Movimiento.TipoMovimiento.values());
        return "contabilidad/form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute Movimiento movimiento, BindingResult br, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("tipos", Movimiento.TipoMovimiento.values());
            return "contabilidad/form";
        }
        service.save(movimiento);
        return "redirect:/contabilidad";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute Movimiento movimiento,
                         BindingResult br, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("tipos", Movimiento.TipoMovimiento.values());
            return "contabilidad/form";
        }
        service.update(id, movimiento);
        return "redirect:/contabilidad";
    }

    @PostMapping("/{id}/eliminar")
    public String delete(@PathVariable UUID id) {
        service.delete(id);
        return "redirect:/contabilidad";
    }
}
