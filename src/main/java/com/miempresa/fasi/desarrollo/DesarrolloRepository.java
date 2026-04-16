package com.miempresa.fasi.desarrollo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DesarrolloRepository extends JpaRepository<Desarrollo, UUID> {
    List<Desarrollo> findAllByOrderByCreatedAtDesc();
    List<Desarrollo> findByEstadoOrderByCreatedAtDesc(Desarrollo.EstadoDesarrollo estado);
}
