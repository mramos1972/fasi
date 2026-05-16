-- ══════════════════════════════════════════════════════════
-- V10: Rediseño de tipos de evento (10 macrocategorías + etiquetas)
-- BD vacía → no migración de datos.
-- ══════════════════════════════════════════════════════════

-- Tabla de etiquetas libres (uno-a-muchos con eventos)
CREATE TABLE evento_etiquetas (
    evento_id  UUID         NOT NULL REFERENCES eventos(id) ON DELETE CASCADE,
    etiqueta   VARCHAR(50)  NOT NULL,
    PRIMARY KEY (evento_id, etiqueta)
);

CREATE INDEX idx_etiquetas_etiqueta ON evento_etiquetas(etiqueta);
CREATE INDEX idx_etiquetas_evento   ON evento_etiquetas(evento_id);

COMMENT ON TABLE evento_etiquetas IS
  'Etiquetas libres asociadas a eventos. Permiten búsqueda y filtros granulares además del tipo macro.';
