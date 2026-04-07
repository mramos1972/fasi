package com.miempresa.fasi.fotos;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/fotos")
public class FotoStubController {

    @GetMapping
    public String stub(Model model) {
        model.addAttribute("mensaje",
            "Módulo de fotos disponible cuando se configure el almacenamiento en nube ☁️");
        return "fotos/stub";
    }
}
