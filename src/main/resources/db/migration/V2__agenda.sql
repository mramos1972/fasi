CREATE TABLE contactos (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    nombre      VARCHAR(100) NOT NULL,
    apellidos   VARCHAR(100),
    email       VARCHAR(150),
    telefono    VARCHAR(30),
    notas       TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);
