-- Tabla de permisos de acceso a calendarios
CREATE TABLE usuario_permisos_calendario (
    propietario_id UUID NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    lector_id      UUID NOT NULL REFERENCES usuarios(id) ON DELETE CASCADE,
    PRIMARY KEY (propietario_id, lector_id)
);

CREATE INDEX idx_permisos_lector ON usuario_permisos_calendario(lector_id);
CREATE INDEX idx_permisos_propietario ON usuario_permisos_calendario(propietario_id);

COMMENT ON TABLE usuario_permisos_calendario IS 
  'Define qué usuarios (lector_id) tienen permiso para ver el calendario de qué otros usuarios (propietario_id).';
