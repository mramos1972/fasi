package com.miempresa.fasi.agenda;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.UUID;

@Controller
@RequestMapping("/agenda")
@RequiredArgsConstructor
public class ContactoController {

    private final ContactoService service;

    @GetMapping
    public String list(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("contactos", q != null && !q.isBlank()
            ? service.search(q) : service.findAll());
        model.addAttribute("q", q);
        return "agenda/list";
    }

    @GetMapping("/nuevo")
    public String newForm(Model model) {
        model.addAttribute("contacto", new Contacto());
        return "agenda/form";
    }

    @GetMapping("/{id}/editar")
    public String editForm(@PathVariable UUID id, Model model) {
        model.addAttribute("contacto", service.findById(id));
        return "agenda/form";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute Contacto contacto, BindingResult br) {
        if (br.hasErrors()) return "agenda/form";
        service.save(contacto);
        return "redirect:/agenda";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute Contacto contacto, BindingResult br) {
        if (br.hasErrors()) return "agenda/form";
        service.update(id, contacto);
        return "redirect:/agenda";
    }

    @PostMapping("/{id}/eliminar")
    public String delete(@PathVariable UUID id) {
        service.delete(id);
        return "redirect:/agenda";
    }
}
