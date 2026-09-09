-- =====================================================================
-- The call and SMS standard, made editable.
--
-- It began as configuration in performance-service, which meant a lead who
-- wanted to raise the SMS target had to ask for a deployment. A target is a
-- management decision, so it belongs in data.
--
-- One row, forced. A settings table that can grow rows grows disagreements
-- about which row is the real one.
-- =====================================================================

CREATE TABLE activity_standard (
  id                   SMALLINT     PRIMARY KEY DEFAULT 1,
  calls_per_day        INTEGER      NOT NULL,
  talk_seconds_per_day INTEGER      NOT NULL,
  sms_sent_per_day     INTEGER      NOT NULL,
  -- Length of a full shift. Asking for 18:00-03:00 is asking about nine hours
  -- of this, and the targets are scaled by that ratio.
  shift_minutes        INTEGER      NOT NULL,
  updated_at           TIMESTAMPTZ,
  updated_by           TEXT,

  CONSTRAINT activity_standard_singleton CHECK (id = 1),
  CONSTRAINT activity_standard_positive  CHECK (
    calls_per_day        BETWEEN 0 AND 100000 AND
    talk_seconds_per_day BETWEEN 0 AND 86400  AND
    sms_sent_per_day     BETWEEN 0 AND 100000 AND
    shift_minutes        BETWEEN 1 AND 1440)
);

-- The values that were compiled in until now, so nothing changes on upgrade.
-- Nine hours because the shift people actually ask about is 18:00 to 03:00.
INSERT INTO activity_standard
  (id, calls_per_day, talk_seconds_per_day, sms_sent_per_day, shift_minutes)
VALUES (1, 125, 3600, 150, 540);

-- Changing the bar everyone is judged by is a bigger act than reading it, so
-- it gets a permission of its own rather than riding on performance:readTeam.
INSERT INTO permissions (code, resource, action, description) VALUES
  ('performance:manageStandard', 'performance', 'manageStandard',
   'Change the daily call, talk and SMS targets');

INSERT INTO role_permissions (role_code, permission_code) VALUES
  ('HR_LEAD',     'performance:manageStandard'),
  ('CEO',         'performance:manageStandard'),
  ('SUPER_ADMIN', 'performance:manageStandard');
