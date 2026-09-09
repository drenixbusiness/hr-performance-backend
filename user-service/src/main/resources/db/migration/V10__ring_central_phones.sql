-- =====================================================================
-- One account, several RingCentral numbers.
--
-- HR leads turned out to work two extensions, and with a single column
-- only one of them was ever counted: the other number's calls and
-- messages belonged to nobody and silently vanished from the report.
--
-- A child table rather than a second column, because "two" is a fact
-- about today. `position` keeps the order the admin entered them in, so
-- the first one stays the one shown where a single number is displayed.
-- =====================================================================

CREATE TABLE user_ring_central_phones (
  user_id  UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  position SMALLINT    NOT NULL,
  phone    VARCHAR(32) NOT NULL,

  PRIMARY KEY (user_id, position),

  -- E.164, or an extension number. Loose on purpose, exactly as the old
  -- column was: RingCentral accounts differ in how they present these.
  CONSTRAINT user_ring_central_phones_format
    CHECK (phone ~ '^[+0-9][0-9 ()x.-]{2,31}$')
);

-- One number belongs to one person. Without this, two accounts could claim
-- the same extension and each would be credited with all of its calls.
--
-- Unconditional, where the old index excluded soft-deleted rows. The rows
-- are removed when an account is deleted instead, which frees the number
-- for reassignment the same way the old partial index did.
CREATE UNIQUE INDEX user_ring_central_phones_phone_idx
  ON user_ring_central_phones (phone);

CREATE INDEX user_ring_central_phones_user_idx
  ON user_ring_central_phones (user_id);

-- Carry the existing numbers over. Deleted accounts are skipped: their
-- number was already free under the old partial index.
INSERT INTO user_ring_central_phones (user_id, position, phone)
  SELECT id, 0, ring_central_phone
    FROM users
   WHERE ring_central_phone IS NOT NULL
     AND deleted_at IS NULL;

DROP INDEX users_ring_central_phone_idx;
ALTER TABLE users DROP CONSTRAINT users_ring_central_phone_format;
ALTER TABLE users DROP COLUMN ring_central_phone;
