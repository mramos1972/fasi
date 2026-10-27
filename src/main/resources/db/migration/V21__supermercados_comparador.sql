-- ══════════════════════════════════════════════════════════
-- V21: Módulo Comparador de Supermercados y Precios
-- ══════════════════════════════════════════════════════════

CREATE TABLE supermercados (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre         VARCHAR(100) NOT NULL UNIQUE,
    codigo_interno VARCHAR(50)  NOT NULL UNIQUE, -- ej: MERCADONA, CARREFOUR, DIA
    codigo_postal  VARCHAR(10)  NOT NULL DEFAULT '11540', -- Por defecto Sanlúcar
    activo         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now()
);

-- Pre-cargar los supermercados habituales
INSERT INTO supermercados (nombre, codigo_interno, codigo_postal, activo) VALUES
    ('Mercadona', 'MERCADONA', '11540', true),
    ('Carrefour', 'CARREFOUR', '11540', true),
    ('Dia',       'DIA',       '11540', true);

CREATE TABLE precios_cache (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    supermercado_id  UUID NOT NULL REFERENCES supermercados(id) ON DELETE CASCADE,
    termino_busqueda VARCHAR(150) NOT NULL,
    producto_nombre  VARCHAR(255) NOT NULL,
    precio           NUMERIC(8,2) NOT NULL,
    formato_unidad   VARCHAR(50), -- ej: '1 L', '1 kg', 'pack 6'
    url_producto     VARCHAR(500),
    actualizado_el   TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_precios_cache_busqueda ON precios_cache(supermercado_id, termino_busqueda);
CREATE INDEX idx_precios_cache_fecha    ON precios_cache(actualizado_el);
