-- =====================================================================
-- `position` was declared SMALLINT in V10, but JPA's @OrderColumn maps to
-- INTEGER and Hibernate's schema validation refuses the mismatch at
-- startup — the service would not boot at all.
--
-- A separate migration rather than an edit to V10: V10 has already run
-- where this is deployed, and changing an applied migration fails the
-- checksum instead of fixing anything.
-- =====================================================================

ALTER TABLE user_ring_central_phones
  ALTER COLUMN position TYPE INTEGER;
