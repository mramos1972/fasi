-- V19: Añadir columna opcional de color personalizado para eventos
ALTER TABLE eventos ADD COLUMN IF NOT EXISTS color_personalizado VARCHAR(7);

COMMENT ON COLUMN eventos.color_personalizado IS 'Color hex opcional (#RRGGBB) para sobrescribir el color por defecto del tipo de evento';
