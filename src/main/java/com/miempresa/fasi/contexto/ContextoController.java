package com.miempresa.fasi.contexto;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/contexto")
@RequiredArgsConstructor
public class ContextoController {

    private final ContextoFamiliarRepository repo;
    private final MareasSolarService          mareasService;

    // ── Vista carga de datos ─────────────────────────────────
    @GetMapping
    public String vista(Model model) {
        model.addAttribute("entradas", repo.findAllByOrderByClaveAsc());
        return "contexto/index";
    }

    // ── Guardar entrada de contexto ──────────────────────────
    @PostMapping("/guardar")
    public String guardar(@RequestParam String clave,
                          @RequestParam String valor,
                          @RequestParam(required = false) String descripcion) {
        ContextoFamiliar cf = repo.findByClave(clave)
            .orElse(ContextoFamiliar.builder().clave(clave).build());
        cf.setValor(valor);
        if (descripcion != null && !descripcion.isBlank()) cf.setDescripcion(descripcion);
        repo.save(cf);
        return "redirect:/contexto";
    }

    // ── Guardar texto libre (prompt IA) ──────────────────────
    @PostMapping("/prompt")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> procesarPrompt(@RequestBody Map<String, String> body) {
        String texto = body.getOrDefault("texto", "").trim();
        if (texto.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "msg", "Texto vacío"));
        }
        // Guardar como entrada de contexto libre con clave auto-generada
        String clave = "prompt_" + System.currentTimeMillis();
        repo.save(ContextoFamiliar.builder()
            .clave(clave)
            .valor(texto)
            .descripcion("Entrada libre de contexto familiar")
            .build());
        return ResponseEntity.ok(Map.of("ok", true, "msg", "Contexto guardado correctamente"));
    }

    // ── API: datos contextuales para footer ──────────────────
    @GetMapping("/api/datos")
    @ResponseBody
    public ResponseEntity<Map<String, String>> getDatos(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        if (fecha == null) fecha = LocalDate.now();
        return ResponseEntity.ok(mareasService.getDatosContextuales(fecha));
    }
}
