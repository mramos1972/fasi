package com.miempresa.fasi.agenda;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContactoService {

    private final ContactoRepository repo;

    public List<Contacto> findAll() {
        return repo.findAllByOrderByApellidosAscNombreAsc();
    }

    public List<Contacto> search(String q) {
        return repo.findByNombreContainingIgnoreCaseOrApellidosContainingIgnoreCase(q, q);
    }

    public Contacto findById(UUID id) {
        return repo.findById(id)
            .orElseThrow(() -> new EntityNotFoundException("Contacto no encontrado: " + id));
    }

    @Transactional
    public Contacto save(Contacto c) { return repo.save(c); }

    @Transactional
    public Contacto update(UUID id, Contacto datos) {
        Contacto c = findById(id);
        c.setNombre(datos.getNombre());
        c.setApellidos(datos.getApellidos());
        c.setEmail(datos.getEmail());
        c.setTelefono(datos.getTelefono());
        c.setNotas(datos.getNotas());
        return repo.save(c);
    }

    @Transactional
    public void delete(UUID id) { repo.deleteById(id); }
}
