-- =====================================================================
-- One row per person per finished shift.
--
-- RingCentral holds the call log, but reading a month of it costs
-- minutes of throttled paging at ten requests a minute — you cannot ask
-- it "how did last month go" and get an answer while somebody waits. A
-- snapshot is therefore written as each shift passes, and every question
-- about a past range is answered from here instead.
--
-- Counts only. No targets are stored: the standard is editable, and a
-- stored verdict would freeze last month's numbers against a bar that
-- has since moved. Judging happens at read time.
-- =====================================================================

CREATE TABLE daily_activity (
  user_id       UUID        NOT NULL,
  -- The day the shift STARTED. A shift running 18:00-03:00 belongs to the
  -- evening it began on, exactly as the live report reports it.
  shift_date    DATE        NOT NULL,

  entity        TEXT        NOT NULL,
  calls         INTEGER     NOT NULL DEFAULT 0,
  talk_seconds  INTEGER     NOT NULL DEFAULT 0,
  sms_sent      INTEGER     NOT NULL DEFAULT 0,

  -- The numbers these counts came from, as a comma-separated list. Kept for
  -- provenance: a figure that looks wrong is usually a number that moved
  -- between people, and without this there is no way to tell after the fact.
  phones        TEXT        NOT NULL DEFAULT '',

  -- True once the shift has ended and the figure can no longer change. A
  -- shift still running is stored anyway and refreshed on each pass, so the
  -- day is visible while it happens rather than appearing at 03:00.
  final         BOOLEAN     NOT NULL DEFAULT FALSE,
  recorded_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

  PRIMARY KEY (user_id, shift_date),
  CONSTRAINT daily_activity_counts_sane
    CHECK (calls >= 0 AND talk_seconds >= 0 AND sms_sent >= 0)
);

-- The one query the report makes: these people, over this range.
CREATE INDEX daily_activity_date_idx ON daily_activity (shift_date, user_id);
