-- When somebody actually started, as opposed to when their first driver appeared on a board.
--
-- The recruiting standard is measured in quarters of a person's own tenure, and until now that
-- tenure was inferred from the earliest hire credited to them on monday.com. That is the only
-- evidence either external system offers, and it is systematically wrong in one direction: it
-- cannot see any time before the board existed, or before the person made their first hire.
--
-- On the BP board it put a lead of two years at fifteen months and a recruiter in his fourth month
-- at his fifth. The first costs nothing — anything past twelve months is held to the same target —
-- but the second moves somebody from month one of a quarter to month two, which nearly doubles
-- the pace they are expected to have reached. Judging a recruiter against the wrong month of the
-- wrong quarter is the kind of error that reads as a fair assessment.
--
-- Nullable on purpose. It is a fact about a person that only a human knows, so most rows will not
-- have it for a while, and the inferred date remains the fallback. Nothing breaks when it is
-- absent; the report simply says where the date came from.
ALTER TABLE users ADD COLUMN employment_start_date DATE;

COMMENT ON COLUMN users.employment_start_date IS
    'The day this person started with the company. Overrides the tenure inferred from their '
    'first hire on the recruiting board. Null means fall back to that inference.';
