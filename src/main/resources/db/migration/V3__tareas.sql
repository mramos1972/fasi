CREATE TABLE tareas (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    titulo            VARCHAR(200) NOT NULL,
    descripcion       TEXT,
    estado            VARCHAR(20)  NOT NULL DEFAULT 'PENDIENTE',
    prioridad         VARCHAR(10)  NOT NULL DEFAULT 'MEDIA',
    fecha_vencimiento DATE,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now()
);
