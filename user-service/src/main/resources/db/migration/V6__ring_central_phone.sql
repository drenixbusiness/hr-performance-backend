-- =====================================================================
-- The RingCentral number, kept apart from the personal phone.
--
-- Two columns rather than one because they answer different questions:
-- `phone` is how a colleague reaches the person, `ring_central_phone` is
-- the extension their calls and messages are logged against. Overloading
-- one field would make the call metrics silently wrong the first time
-- somebody put a mobile number in it.
-- =====================================================================

ALTER TABLE users ADD COLUMN ring_central_phone varchar(32);

-- E.164, or an extension number. Loose on purpose: RingCentral accounts
-- differ in how they present these, and a format this system guesses at
-- would reject valid numbers.
ALTER TABLE users ADD CONSTRAINT users_ring_central_phone_format
  CHECK (ring_central_phone IS NULL OR ring_central_phone ~ '^[+0-9][0-9 ()x.-]{2,31}$');

-- One number belongs to one person. Without this, two accounts could claim
-- the same extension and each would be credited with all of its calls.
CREATE UNIQUE INDEX users_ring_central_phone_idx
  ON users (ring_central_phone)
  WHERE ring_central_phone IS NOT NULL AND deleted_at IS NULL;
