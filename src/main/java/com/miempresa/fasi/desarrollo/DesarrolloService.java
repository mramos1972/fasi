package com.miempresa.fasi.desarrollo;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DesarrolloService {

    private final DesarrolloRepository repo;

    public List<Desarrollo> findAll() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    public List<Desarrollo> findByEstado(Desarrollo.EstadoDesarrollo estado) {
        return repo.findByEstadoOrderByCreatedAtDesc(estado);
    }

    public Desarrollo findById(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Desarrollo no encontrado: " + id));
    }

    @Transactional
    public Desarrollo save(Desarrollo d) {
        if (d.getEstado() == null) {
            d.setEstado(Desarrollo.EstadoDesarrollo.PENDIENTE);
        }
        return repo.save(d);
    }

    @Transactional
    public Desarrollo update(UUID id, Desarrollo datos) {
        Desarrollo d = findById(id);
        d.setTitulo(datos.getTitulo());
        d.setDescripcion(datos.getDescripcion());
        d.setEstado(datos.getEstado());
        d.setScriptGenerado(datos.getScriptGenerado());
        return repo.save(d);
    }

    @Transactional
    public Desarrollo marcarAplicado(UUID id) {
        Desarrollo d = findById(id);
        d.setEstado(Desarrollo.EstadoDesarrollo.APLICADO);
        d.setFechaAplicado(LocalDateTime.now());
        return repo.save(d);
    }

    @Transactional
    public Desarrollo marcarDescartado(UUID id) {
        Desarrollo d = findById(id);
        d.setEstado(Desarrollo.EstadoDesarrollo.DESCARTADO);
        return repo.save(d);
    }

    @Transactional
    public void delete(UUID id) {
        repo.deleteById(id);
    }
}
