package com.miempresa.fasi.ia;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/ia")
@RequiredArgsConstructor
public class IaController {

    private final IaService service;

    /** Pantalla principal del chat */
    @GetMapping
    public String chat(Model model) {
        model.addAttribute("request",  new IaChatRequest());
        model.addAttribute("historial", service.historial());
        return "ia/chat";
    }

    /**
     * Endpoint HTMX — devuelve solo el fragmento HTML de la respuesta.
     * El formulario hace POST aquí y HTMX inserta el resultado en el chat.
     */
    @PostMapping("/chat")
    public String chatHtmx(@Valid @ModelAttribute IaChatRequest request,
                            Model model) {
        IaChatResponse resp = service.chat(request);
        model.addAttribute("pregunta",  request.getMensaje());
        model.addAttribute("respuesta", resp.getRespuesta());
        model.addAttribute("tiempoMs",  resp.getTiempoMs());
        model.addAttribute("modulo",    resp.getModulo());
        return "ia/chat :: #respuesta-fragment";
    }

    /** API REST para uso externo / testing */
    @PostMapping("/api/chat")
    @ResponseBody
    public ResponseEntity<IaChatResponse> apiChat(@Valid @RequestBody IaChatRequest request) {
        return ResponseEntity.ok(service.chat(request));
    }
}
