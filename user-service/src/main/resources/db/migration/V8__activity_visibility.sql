-- =====================================================================
-- Who may see whose call and SMS numbers.
--
-- The token carries permissions, not role names, so the breadth of what a
-- person can see has to be a permission of its own. One `performance:read`
-- could not distinguish an HR from their lead: both carry entity JM, and
-- entity alone would have shown every recruiter's figures to every
-- recruiter in the company.
-- =====================================================================

INSERT INTO permissions (code, resource, action, description) VALUES
  ('performance:readTeam', 'performance', 'readTeam',
   'View recruiting and call figures for everyone in your own company'),
  ('performance:readAll',  'performance', 'readAll',
   'View recruiting and call figures for the whole organisation');

-- HR sees only their own figures. Handed out now that seeing anything at all
-- is separated from seeing everybody.
INSERT INTO role_permissions (role_code, permission_code) VALUES
  ('HR', 'performance:read');

INSERT INTO role_permissions (role_code, permission_code) VALUES
  ('HR_LEAD',     'performance:readTeam'),
  ('CEO',         'performance:readAll'),
  ('SUPER_ADMIN', 'performance:readAll');
