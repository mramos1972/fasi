package com.miempresa.fasi.compra;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/compra")
@RequiredArgsConstructor
public class CompraController {

    private final CompraService compraService;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("items", compraService.listarTodos());
        return "compra/index";
    }

    @PostMapping("/anadir")
    public String anadirManual(@RequestParam String nombre) {
        if (nombre != null && !nombre.isBlank()) {
            compraService.anadirElementos(List.of(nombre), null);
        }
        return "redirect:/compra";
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable UUID id) {
        compraService.toggleComprado(id);
        return "redirect:/compra";
    }

    @PostMapping("/{id}/eliminar")
    public String eliminar(@PathVariable UUID id) {
        compraService.eliminarPorId(id);
        return "redirect:/compra";
    }
}
