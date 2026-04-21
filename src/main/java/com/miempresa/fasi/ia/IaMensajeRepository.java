package com.miempresa.fasi.ia;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface IaMensajeRepository extends JpaRepository<IaMensaje, UUID> {
    List<IaMensaje> findTop20ByOrderByCreatedAtDesc();
    List<IaMensaje> findByModuloOrderByCreatedAtDesc(String modulo);
}
