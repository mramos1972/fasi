INSERT INTO supermercados (nombre, codigo_interno, codigo_postal, activo)
SELECT 'Supermercados El Jamon', 'EL_JAMON', '11540', true
WHERE NOT EXISTS (
    SELECT 1 FROM supermercados WHERE codigo_interno = 'EL_JAMON'
);
