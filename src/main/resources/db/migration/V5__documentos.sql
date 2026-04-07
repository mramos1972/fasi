CREATE TABLE documentos (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre        VARCHAR(200)  NOT NULL,
    descripcion   TEXT,
    tipo          VARCHAR(100),
    ruta          VARCHAR(500)  NOT NULL,
    tamanio_bytes BIGINT,
    categoria     VARCHAR(100),
    created_at    TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT now()
);
