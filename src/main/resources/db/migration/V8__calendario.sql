-- ============================================================
--  FASI Calendario — V8
--  Tabla principal de eventos con búsqueda full-text
-- ============================================================

-- Extensión para búsqueda full-text en español
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Wrapper inmutable de unaccent (necesario para columnas GENERATED)
CREATE OR REPLACE FUNCTION unaccent_immutable(text)
RETURNS text AS $$
  SELECT unaccent($1)
$$ LANGUAGE sql IMMUTABLE;

CREATE TABLE eventos (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Campos principales
    tipo                  VARCHAR(30)   NOT NULL,
    titulo                VARCHAR(300)  NOT NULL,

    -- Fecha y hora
    fecha_inicio          DATE          NOT NULL,
    fecha_fin             DATE,
    hora_inicio           TIME,
    hora_fin              TIME,
    todo_el_dia           BOOLEAN       NOT NULL DEFAULT false,

    -- Localización y personas
    lugar                 VARCHAR(300),
    personas_relacionadas VARCHAR(500),

    -- Contenido
    descripcion           TEXT,
    recordatorio_minutos  INTEGER,

    -- Campos específicos por tipo (JSONB — flexible y rápido)
    datos_extra           JSONB         DEFAULT '{}',

    -- Auditoría de voz
    texto_original_voz    TEXT,

    -- Búsqueda full-text (columna generada automáticamente)
    texto_busqueda        TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('spanish',
            unaccent_immutable(coalesce(titulo, ''))           || ' ' ||
            unaccent_immutable(coalesce(lugar, ''))            || ' ' ||
            unaccent_immutable(coalesce(descripcion, ''))      || ' ' ||
            unaccent_immutable(coalesce(personas_relacionadas, '')) || ' ' ||
            unaccent_immutable(coalesce(tipo, ''))
        )
    ) STORED,

    -- Timestamps
    created_at            TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT now()
);

-- ── Índices ──────────────────────────────────────────────────
-- Full-text search (GIN — el más rápido para texto)
CREATE INDEX idx_eventos_busqueda   ON eventos USING GIN(texto_busqueda);

-- Consultas por fecha (el más usado en un calendario)
CREATE INDEX idx_eventos_fecha      ON eventos(fecha_inicio);
CREATE INDEX idx_eventos_fecha_rng  ON eventos(fecha_inicio, fecha_fin);

-- Filtros por tipo
CREATE INDEX idx_eventos_tipo       ON eventos(tipo);

-- Consultas JSONB (para campos específicos por tipo)
CREATE INDEX idx_eventos_jsonb      ON eventos USING GIN(datos_extra);

-- ── Comentarios de documentación ─────────────────────────────
COMMENT ON TABLE eventos IS 'Eventos del calendario inteligente FASI';
COMMENT ON COLUMN eventos.tipo IS 'CUMPLEANOS|CITA_MEDICA|TUTORIA|CLAUSTRO|CURSO|EXTRAESCOLAR|VIAJE|VACACIONES|COMIDA|COMPRAS|OTRO';
COMMENT ON COLUMN eventos.datos_extra IS 'Campos específicos por tipo en formato JSONB';
COMMENT ON COLUMN eventos.texto_original_voz IS 'Texto dictado por voz tal como lo reconoció el navegador';
COMMENT ON COLUMN eventos.texto_busqueda IS 'Vector full-text generado automáticamente para búsqueda rápida';
