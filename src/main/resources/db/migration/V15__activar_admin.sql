-- Asegurar que admin esté activo y con la contraseña 'admin'
UPDATE usuarios 
SET password = '$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5Bp1JBgQ3lH1Q6w1lD09XyC6kF4ye',
    activo = true,
    rol = 'ADMIN'
WHERE username = 'admin';
