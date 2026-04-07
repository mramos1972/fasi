package com.miempresa.fasi.contabilidad;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface MovimientoRepository extends JpaRepository<Movimiento, UUID> {
    List<Movimiento> findAllByOrderByFechaDesc();
    List<Movimiento> findByFechaBetweenOrderByFechaDesc(LocalDate desde, LocalDate hasta);

    @Query("SELECT COALESCE(SUM(m.importe),0) FROM Movimiento m WHERE m.tipo = :tipo")
    BigDecimal sumByTipo(Movimiento.TipoMovimiento tipo);
}
