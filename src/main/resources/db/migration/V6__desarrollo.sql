CREATE TABLE desarrollos (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    titulo           VARCHAR(200)  NOT NULL,
    descripcion      TEXT,
    estado           VARCHAR(20)   NOT NULL DEFAULT 'PENDIENTE',
    script_generado  TEXT,
    fecha_aplicado   TIMESTAMP,
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now()
);
