-- Clan XP Competitions: database upgrade to version 2
-- Run ONCE in Cloudflare: Storage & Databases > D1 > clan-xp-db > Console.
-- It only ADDS columns. No existing data is changed or removed.

ALTER TABLE participants ADD COLUMN hiscore_start_xp INTEGER;
ALTER TABLE participants ADD COLUMN hiscore_start_checked_at INTEGER;
ALTER TABLE participants ADD COLUMN hiscore_end_xp INTEGER;
ALTER TABLE participants ADD COLUMN hiscore_end_checked_at INTEGER;
CREATE INDEX IF NOT EXISTS idx_participants_rsn ON participants(rsn COLLATE NOCASE);
