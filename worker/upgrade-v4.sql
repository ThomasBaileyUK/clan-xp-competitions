-- Clan Competitions: database upgrade to version 4 (hiscores players + entry requests)
-- Run ONCE in Cloudflare: Storage & Databases > D1 > clan-xp-db > Console.
-- It only ADDS columns and a table. No existing data is changed or removed.

ALTER TABLE participants ADD COLUMN hiscore_last_checked_at INTEGER;
ALTER TABLE participants ADD COLUMN hiscore_final_at INTEGER;
ALTER TABLE participants ADD COLUMN hiscore_note TEXT;

-- Players without RuneLite ask to join on the website; an admin approves them.
CREATE TABLE IF NOT EXISTS entry_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  competition_id INTEGER NOT NULL REFERENCES competitions(id),
  rsn TEXT NOT NULL COLLATE NOCASE,
  status TEXT NOT NULL DEFAULT 'pending',
  requested_at INTEGER NOT NULL DEFAULT (unixepoch()),
  UNIQUE (competition_id, rsn),
  CHECK (status IN ('pending', 'declined'))
);
