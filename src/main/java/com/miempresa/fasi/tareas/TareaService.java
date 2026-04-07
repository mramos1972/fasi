package com.miempresa.fasi.tareas;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TareaService {

    private final TareaRepository repo;

    public List<Tarea> findAll() {
        return repo.findAllByOrderByPrioridadAscFechaVencimientoAsc();
    }

    public List<Tarea> findByEstado(Tarea.EstadoTarea estado) {
        return repo.findByEstadoOrderByPrioridadAscFechaVencimientoAsc(estado);
    }

    public Tarea findById(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Tarea no encontrada: " + id));
    }

    @Transactional
    public Tarea save(Tarea tarea) { return repo.save(tarea); }

    @Transactional
    public Tarea update(UUID id, Tarea datos) {
        Tarea tarea = findById(id);
        tarea.setTitulo(datos.getTitulo());
        tarea.setDescripcion(datos.getDescripcion());
        tarea.setEstado(datos.getEstado());
        tarea.setPrioridad(datos.getPrioridad());
        tarea.setFechaVencimiento(datos.getFechaVencimiento());
        return repo.save(tarea);
    }

    @Transactional
    public void delete(UUID id) { repo.deleteById(id); }
}
