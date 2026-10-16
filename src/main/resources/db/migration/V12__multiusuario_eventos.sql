-- ══════════════════════════════════════════════════════════
-- V12: Soporte multiusuario para eventos
-- ══════════════════════════════════════════════════════════

-- 1. Añadir columna usuario_id (admitiendo null temporalmente si hubiera datos previos)
ALTER TABLE eventos ADD COLUMN usuario_id UUID REFERENCES usuarios(id) ON DELETE CASCADE;

-- 2. Asignar los eventos existentes al usuario admin
UPDATE eventos 
SET usuario_id = (SELECT id FROM usuarios WHERE username = 'admin' LIMIT 1)
WHERE usuario_id IS NULL;

-- 3. Establecer la columna como NOT NULL e indexar para búsquedas rápidas
ALTER TABLE eventos ALTER COLUMN usuario_id SET NOT NULL;

CREATE INDEX idx_eventos_usuario ON eventos(usuario_id);
CREATE INDEX idx_eventos_usuario_fecha ON eventos(usuario_id, fecha_inicio);
