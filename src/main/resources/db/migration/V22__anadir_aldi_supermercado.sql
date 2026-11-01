-- V22: Alta de ALDI en el comparador de supermercados
INSERT INTO supermercados (nombre, codigo_interno, codigo_postal, activo)
VALUES ('Aldi', 'ALDI', '11540', true)
ON CONFLICT (codigo_interno) DO NOTHING;
