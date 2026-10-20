-- Clan XP Competitions: database upgrade to version 3 (clans + boss competitions)
-- Run ONCE in Cloudflare: Storage & Databases > D1 > clan-xp-db > Console.
-- Run upgrade-v2.sql first if you haven't already.
-- It only ADDS things. No existing data is changed or removed.

CREATE TABLE IF NOT EXISTS clans (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE COLLATE NOCASE,
  join_hash TEXT NOT NULL,
  join_salt TEXT NOT NULL,
  admin_hash TEXT,
  admin_salt TEXT,
  created_at INTEGER NOT NULL DEFAULT (unixepoch())
);

ALTER TABLE competitions ADD COLUMN clan_id INTEGER REFERENCES clans(id);
ALTER TABLE competitions ADD COLUMN type TEXT NOT NULL DEFAULT 'skill';
CREATE INDEX IF NOT EXISTS idx_competitions_clan ON competitions(clan_id, end_time);
