package com.miempresa.fasi.agenda;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContactoRepository extends JpaRepository<Contacto, UUID> {
    List<Contacto> findByNombreContainingIgnoreCaseOrApellidosContainingIgnoreCase(
        String nombre, String apellidos);
    List<Contacto> findAllByOrderByApellidosAscNombreAsc();
}
