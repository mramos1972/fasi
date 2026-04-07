package com.miempresa.fasi.documentos;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DocumentoService {

    private final DocumentoRepository repo;

    @Value("${fasi.upload.dir:./uploads}")
    private String uploadDir;

    public List<Documento> findAll() { return repo.findAllByOrderByCreatedAtDesc(); }

    public Documento findById(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Documento no encontrado: " + id));
    }

    @Transactional
    public Documento upload(String nombre, String descripcion,
                            String categoria, MultipartFile file) throws IOException {
        Path dir = Paths.get(uploadDir);
        Files.createDirectories(dir);
        String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
        Files.copy(file.getInputStream(), dir.resolve(filename),
                   StandardCopyOption.REPLACE_EXISTING);
        return repo.save(Documento.builder()
            .nombre(nombre).descripcion(descripcion).categoria(categoria)
            .tipo(file.getContentType()).ruta(filename).tamanioBytes(file.getSize())
            .build());
    }

    @Transactional
    public void delete(UUID id) {
        Documento doc = findById(id);
        try { Files.deleteIfExists(Paths.get(uploadDir, doc.getRuta())); }
        catch (IOException e) { log.warn("No se pudo borrar: {}", doc.getRuta()); }
        repo.deleteById(id);
    }
}
