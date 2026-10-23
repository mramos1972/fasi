-- ══════════════════════════════════════════════════════════
-- V20: Módulo Lista de la Compra
-- ══════════════════════════════════════════════════════════

CREATE TABLE lista_compra (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id UUID NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    nombre     VARCHAR(200) NOT NULL,
    comprado   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_lista_compra_usuario ON lista_compra(usuario_id, comprado);
