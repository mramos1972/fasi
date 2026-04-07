CREATE TABLE movimientos (
    id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    concepto      VARCHAR(200)   NOT NULL,
    importe       NUMERIC(12,2)  NOT NULL,
    tipo          VARCHAR(10)    NOT NULL,
    categoria     VARCHAR(100),
    fecha         DATE           NOT NULL,
    notas         TEXT,
    created_at    TIMESTAMP      NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP      NOT NULL DEFAULT now()
);
