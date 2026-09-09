-- =====================================================================
-- The organisation as it actually operates today.
--
-- Two changes, both reflecting business reality rather than aspiration:
--   1. Only JM and BP are trading. WF and Kaisen are dropped from the
--      allowed set until they exist.
--   2. The three roles the company is actually staffed with: HR, the HR
--      lead, and the CEO.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Narrow the company vocabulary to the two that operate.
--
-- Nothing is currently granted against WF or Kaisen, so no rows need
-- rewriting; the assertion below makes that explicit rather than
-- assumed, and fails the migration loudly if it ever stops being true.
-- ---------------------------------------------------------------------
DO $$
DECLARE stale_grants integer;
BEGIN
  SELECT count(*) INTO stale_grants FROM user_roles WHERE entity IN ('WF', 'KAISEN');
  IF stale_grants > 0 THEN
    RAISE EXCEPTION
      'Cannot narrow the entity list: % role grant(s) still reference WF or KAISEN. '
      'Reassign them to JM or BP, or make them global, and run this migration again.',
      stale_grants;
  END IF;
END $$;

ALTER TABLE user_roles DROP CONSTRAINT user_roles_entity_valid;
ALTER TABLE user_roles ADD CONSTRAINT user_roles_entity_valid
  CHECK (entity IS NULL OR entity IN ('JM', 'BP'));

-- ---------------------------------------------------------------------
-- 2. Allow two-letter role codes.
--
-- V1 demanded three characters or more, which rejects HR — and IT, PR
-- and QA after it. There is no security reason for the floor; two-letter
-- department codes are ordinary org vocabulary. The rest of the rule is
-- unchanged: start with a letter, then uppercase letters, digits and
-- underscores, up to 48 characters.
--
-- Keep this in step with the @Pattern on AdminRoleController.CreateRoleBody.
-- ---------------------------------------------------------------------
ALTER TABLE roles DROP CONSTRAINT roles_code_format;
ALTER TABLE roles ADD CONSTRAINT roles_code_format
  CHECK (code ~ '^[A-Z][A-Z0-9_]{1,47}$');

-- ---------------------------------------------------------------------
-- 3. The staffed roles.
--
-- is_system = FALSE on purpose. The three roles seeded in V2 are part of
-- the product and must never be deleted; these three describe one
-- company's org chart, which changes. Leaving them ordinary means their
-- permissions can be tuned through PUT /api/v1/admin/roles/{code}
-- without a migration.
-- ---------------------------------------------------------------------
INSERT INTO roles (code, name, description, is_system) VALUES
  ('HR',       'HR specialist',  'Day-to-day HR. Reads accounts and roles; changes nothing.', FALSE),
  ('HR_LEAD',  'HR lead',        'Runs the HR team. Creates and maintains accounts, assigns roles, reads the audit log.', FALSE),
  ('CEO',      'Chief executive','Company-wide visibility. Reads everything, changes nothing.', FALSE);

-- HR: read-only. An HR specialist needs to look people up, not to
-- create or disable their access.
INSERT INTO role_permissions (role_code, permission_code) VALUES
  ('HR', 'user:read'),
  ('HR', 'role:read'),
  ('HR', 'auth:changeOwnPassword');

-- HR lead: the account lifecycle, minus deletion.
--
-- user:delete is deliberately absent. Removing an account erases someone's
-- access and detaches them from the audit trail's live view; that stays
-- with an administrator, so the person who manages accounts day to day
-- cannot also make one disappear.
INSERT INTO role_permissions (role_code, permission_code) VALUES
  ('HR_LEAD', 'user:create'),
  ('HR_LEAD', 'user:read'),
  ('HR_LEAD', 'user:update'),
  ('HR_LEAD', 'user:resetPassword'),
  ('HR_LEAD', 'user:assignRole'),
  ('HR_LEAD', 'role:read'),
  ('HR_LEAD', 'audit:read'),
  ('HR_LEAD', 'auth:changeOwnPassword');

-- CEO: sees everything, touches nothing.
--
-- Read-only by design. A chief executive rarely performs account
-- administration, and an account that can see the whole company is worth
-- stealing — this way what a thief gains is visibility, not control.
INSERT INTO role_permissions (role_code, permission_code) VALUES
  ('CEO', 'user:read'),
  ('CEO', 'role:read'),
  ('CEO', 'audit:read'),
  ('CEO', 'auth:changeOwnPassword');
