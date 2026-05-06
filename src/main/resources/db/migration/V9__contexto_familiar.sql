-- ============================================================
--  FASI V9 — Contexto familiar para IA y sugerencias
-- ============================================================

CREATE TABLE contexto_familiar (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    clave       VARCHAR(100) NOT NULL UNIQUE,
    valor       TEXT         NOT NULL,
    descripcion VARCHAR(300),
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE contexto_familiar IS 'Datos de contexto familiar para enriquecer la IA';

-- Datos iniciales de Sanlúcar de Barrameda
INSERT INTO contexto_familiar (clave, valor, descripcion) VALUES
('ubicacion_ciudad',    'Sanlúcar de Barrameda',  'Ciudad de residencia'),
('ubicacion_provincia', 'Cádiz',                   'Provincia'),
('ubicacion_pais',      'España',                  'País'),
('ubicacion_lat',       '36.7749',                 'Latitud decimal'),
('ubicacion_lon',       '-6.3536',                 'Longitud decimal'),
('ubicacion_zona',      'Europe/Madrid',            'Zona horaria'),
('marea_referencia_ts', '2026-05-05T17:00:00',     'Pleamar de referencia para cálculo de mareas'),
('marea_referencia_h',  '3.45',                    'Altura en metros de la pleamar de referencia');

-- Tabla de usuarios (base para multiusuario)
CREATE TABLE usuarios (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username     VARCHAR(50)  NOT NULL UNIQUE,
    password     VARCHAR(200) NOT NULL,
    nombre       VARCHAR(100),
    rol          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    activo       BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT now()
);

COMMENT ON TABLE usuarios IS 'Usuarios del sistema FASI (multiusuario)';

-- Usuario admin por defecto (password: admin — se cambiará desde la app)
INSERT INTO usuarios (username, password, nombre, rol) VALUES
('admin', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVDWXfoH.6', 'Administrador', 'ADMIN');
