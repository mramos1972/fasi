cat << 'EOF' > src/main/resources/db/migration/V12__reset_password_admin.sql
-- Establece la contrasena del usuario 'admin' a: admin
UPDATE usuarios 
SET password = '$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5Bp1JBgQ3lH1Q6w1lD09XyC6kF4ye'
WHERE username = 'admin';
EOF
