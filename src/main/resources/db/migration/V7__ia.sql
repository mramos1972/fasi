CREATE TABLE ia_mensajes (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    pregunta    TEXT         NOT NULL,
    respuesta   TEXT         NOT NULL,
    modulo      VARCHAR(50)  NOT NULL DEFAULT 'general',
    tiempo_ms   BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_ia_mensajes_created_at ON ia_mensajes(created_at DESC);
CREATE INDEX idx_ia_mensajes_modulo     ON ia_mensajes(modulo);
