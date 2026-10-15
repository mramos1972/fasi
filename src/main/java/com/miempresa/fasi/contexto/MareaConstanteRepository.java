package com.miempresa.fasi.contexto;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MareaConstanteRepository extends JpaRepository<MareaConstante, Integer> { // <-- Cambiado Long a Integer
    List<MareaConstante> findByActivoTrue();
}
