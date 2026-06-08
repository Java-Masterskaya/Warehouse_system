INSERT INTO users (username, password, role, is_active)
SELECT 'admin', '$2a$10$EwBSy2QKVhFH4bWJMMH/XeoERRlg0VZC9/it458IKci.Wo/GvZ6qW', 'ROLE_ADMIN', true
WHERE NOT EXISTS (SELECT 1 FROM users WHERE username = 'admin');