package com.miempresa.fasi.tareas;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.UUID;

@Controller
@RequestMapping("/tareas")
@RequiredArgsConstructor
public class TareaController {

    private final TareaService service;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("tareas", service.findAll());
        model.addAttribute("estados", Tarea.EstadoTarea.values());
        model.addAttribute("prioridades", Tarea.PrioridadTarea.values());
        return "tareas/list";
    }

    @GetMapping("/nueva")
    public String newForm(Model model) {
        model.addAttribute("tarea", new Tarea());
        model.addAttribute("estados", Tarea.EstadoTarea.values());
        model.addAttribute("prioridades", Tarea.PrioridadTarea.values());
        return "tareas/form";
    }

    @GetMapping("/{id}/editar")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("tarea", service.findById(id));
        model.addAttribute("estados", Tarea.EstadoTarea.values());
        model.addAttribute("prioridades", Tarea.PrioridadTarea.values());
        return "tareas/form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute Tarea tarea, BindingResult br, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("estados", Tarea.EstadoTarea.values());
            model.addAttribute("prioridades", Tarea.PrioridadTarea.values());
            return "tareas/form";
        }
        service.save(tarea);
        return "redirect:/tareas";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute Tarea tarea,
                         BindingResult br, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("estados", Tarea.EstadoTarea.values());
            model.addAttribute("prioridades", Tarea.PrioridadTarea.values());
            return "tareas/form";
        }
        service.update(id, tarea);
        return "redirect:/tareas";
    }

    @PostMapping("/{id}/eliminar")
    public String delete(@PathVariable UUID id) {
        service.delete(id);
        return "redirect:/tareas";
    }
}
