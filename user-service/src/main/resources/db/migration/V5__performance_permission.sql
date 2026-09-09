-- =====================================================================
-- The recruiting performance chart.
--
-- The numbers live in monday.com, but who may look at them is decided
-- here like every other permission.
-- =====================================================================

INSERT INTO permissions (code, resource, action, description) VALUES
  ('performance:read', 'performance', 'read', 'View the recruiting performance chart');

-- Granted to the roles that answer for recruiting output, and to the
-- administrator who answers for everything.
--
-- HR is deliberately left out for now: the chart ranks recruiters against
-- each other, and handing every recruiter their colleagues' numbers is a
-- decision about how the team is managed, not a technical default. Adding
-- it later is one INSERT.
INSERT INTO role_permissions (role_code, permission_code) VALUES
  ('SUPER_ADMIN', 'performance:read'),
  ('CEO',         'performance:read'),
  ('HR_LEAD',     'performance:read');
