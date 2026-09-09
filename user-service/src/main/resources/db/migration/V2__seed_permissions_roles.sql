-- =====================================================================
-- The permission vocabulary and the system roles.
-- Add new permissions here in a new migration, never at runtime.
-- =====================================================================

INSERT INTO permissions (code, resource, action, description) VALUES
  ('user:create',      'user',     'create',   'Create a user account'),
  ('user:read',        'user',     'read',     'View user accounts'),
  ('user:update',      'user',     'update',   'Edit user profile and status'),
  ('user:delete',      'user',     'delete',   'Deactivate and soft-delete a user'),
  ('user:assignRole',  'user',     'assignRole','Grant or revoke roles on a user'),
  ('user:resetPassword','user',    'resetPassword','Set a new password for another user'),
  ('role:create',      'role',     'create',   'Create a role'),
  ('role:read',        'role',     'read',     'View roles and their permissions'),
  ('role:update',      'role',     'update',   'Edit a role and its permission set'),
  ('role:delete',      'role',     'delete',   'Delete a non-system role'),
  ('audit:read',       'audit',    'read',     'Read the audit log'),
  ('session:revoke',   'session',  'revoke',   'Force sign-out of other users'),
  -- Held by every authenticated principal; the only permission a
  -- password-change token carries.
  ('auth:changeOwnPassword', 'auth', 'changeOwnPassword', 'Change own password');

INSERT INTO roles (code, name, description, is_system) VALUES
  ('SUPER_ADMIN', 'Super administrator', 'Full control over identity and configuration', TRUE),
  ('USER_ADMIN',  'User administrator',  'Manages user accounts and role assignments',   TRUE),
  ('AUDITOR',     'Auditor',             'Read-only access to users, roles and the audit log', TRUE);

-- SUPER_ADMIN gets everything that exists at migration time. Later migrations
-- that add a permission must also decide, explicitly, whether SUPER_ADMIN gets it.
INSERT INTO role_permissions (role_code, permission_code)
SELECT 'SUPER_ADMIN', code FROM permissions;

INSERT INTO role_permissions (role_code, permission_code) VALUES
  ('USER_ADMIN', 'user:create'),
  ('USER_ADMIN', 'user:read'),
  ('USER_ADMIN', 'user:update'),
  ('USER_ADMIN', 'user:delete'),
  ('USER_ADMIN', 'user:assignRole'),
  ('USER_ADMIN', 'user:resetPassword'),
  ('USER_ADMIN', 'role:read'),
  ('USER_ADMIN', 'auth:changeOwnPassword'),
  ('AUDITOR',    'user:read'),
  ('AUDITOR',    'role:read'),
  ('AUDITOR',    'audit:read'),
  ('AUDITOR',    'auth:changeOwnPassword');
