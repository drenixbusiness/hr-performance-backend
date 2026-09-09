-- =====================================================================
-- The name this person is known by on the monday.com recruiting board.
--
-- Separate from full_name because the two serve different masters. The
-- board's Source column holds short names — "Alex", "Winston" — while
-- full_name is what the UI shows and what HR records say. Deriving one
-- from the other worked only by accident, and would have silently
-- credited one recruiter's hires to another the first time two people
-- shared a first name.
-- =====================================================================

ALTER TABLE users ADD COLUMN monday_name varchar(64);

-- One board name belongs to one account, or the chart would attribute the
-- same drivers twice.
CREATE UNIQUE INDEX users_monday_name_idx
  ON users (lower(monday_name))
  WHERE monday_name IS NOT NULL AND deleted_at IS NULL;
