-- =====================================================================
-- OWNER: every permission, every company.
--
-- Distinct from SUPER_ADMIN, which is the *operator* of the system and
-- deliberately holds performance:readAll instead of readTeam. OWNER is
-- the person who owns the business: they hold everything without
-- exception, including both visibility permissions, so no future check
-- can find a gap in what they are allowed to see.
--
-- Left unscoped in practice: grant it with entity NULL and it reaches
-- every company. A grant of OWNER@JM would be a contradiction, but the
-- entity column cannot express "must be null", so nothing stops it —
-- see the note in the README.
--
-- !! Any migration that adds a permission must also grant it to OWNER. !!
-- Permissions are read from role_permissions everywhere, so "all" is a
-- list, not a rule, and a new permission is not retroactively included.
-- =====================================================================

INSERT INTO roles (code, name, description, is_system) VALUES
  ('OWNER', 'Owner',
   'The business owner. Every permission, every company. Grant with no entity.',
   TRUE);

-- Every permission that exists, whatever it is. Written as a select rather
-- than a list so this migration cannot disagree with the permissions table
-- on the day it runs.
INSERT INTO role_permissions (role_code, permission_code)
  SELECT 'OWNER', code FROM permissions;
