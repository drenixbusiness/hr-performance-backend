-- =====================================================================
-- Two changes that arrived together.
--
-- 1. HR_LEAD can now delete accounts. It deliberately could not before —
--    a lead could hire but not fire — and that turned out to be wrong for
--    how the team actually works: the lead who adds a recruiter is the one
--    who has to remove them when they leave. Deletion is soft and audited,
--    and the self-lockout guard still stops anybody deleting themselves.
--
-- 2. Notifications need a permission of their own, held by everybody.
--    There is no inbox but your own, so this gates nothing between people:
--    it exists so a password-change-scoped token cannot read notifications,
--    which is the same rule every other read here follows.
-- =====================================================================

INSERT INTO role_permissions (role_code, permission_code) VALUES
  ('HR_LEAD', 'user:delete');

INSERT INTO permissions (code, resource, action, description) VALUES
  ('notification:read', 'notification', 'read',
   'Read your own notifications');

INSERT INTO role_permissions (role_code, permission_code)
  SELECT code, 'notification:read' FROM roles;
