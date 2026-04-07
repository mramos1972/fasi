package com.miempresa.fasi.documentos;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Paths;
import java.util.UUID;

@Controller
@RequestMapping("/documentos")
@RequiredArgsConstructor
public class DocumentoController {

    private final DocumentoService service;

    @Value("${fasi.upload.dir:./uploads}")
    private String uploadDir;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("documentos", service.findAll());
        return "documentos/list";
    }

    @GetMapping("/subir")
    public String uploadForm() { return "documentos/form"; }

    @PostMapping("/subir")
    public String upload(@RequestParam String nombre,
                         @RequestParam(required = false) String descripcion,
                         @RequestParam(required = false) String categoria,
                         @RequestParam MultipartFile file) throws IOException {
        service.upload(nombre, descripcion, categoria, file);
        return "redirect:/documentos";
    }

    @GetMapping("/{id}/descargar")
    public ResponseEntity<Resource> download(@PathVariable UUID id)
            throws MalformedURLException {
        Documento doc = service.findById(id);
        Resource resource = new UrlResource(Paths.get(uploadDir, doc.getRuta()).toUri());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + doc.getNombre() + "\"")
            .contentType(MediaType.parseMediaType(
                doc.getTipo() != null ? doc.getTipo() : "application/octet-stream"))
            .body(resource);
    }

    @PostMapping("/{id}/eliminar")
    public String delete(@PathVariable UUID id) throws IOException {
        service.delete(id);
        return "redirect:/documentos";
    }
}
