/**
 * Clan Competitions API - version 3
 *
 * Runs on Cloudflare Workers (free plan) with a D1 database bound as DB.
 *
 * Settings (Worker > Settings > Variables and Secrets):
 *   ADMIN_KEY  (Secret)  your master password. Signs everyone's sign-ins,
 *                        so changing it signs every player and admin out.
 *
 * Cron trigger (Worker > Settings > Triggers > Cron): every minute, "* * * * *".
 *
 * Who can do what:
 *   master (ADMIN_KEY)  create clans, manage every clan's competitions
 *   clan admin          manage their own clan's competitions
 *   clan member         see their clan's competitions, join, send XP / KC
 */

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

const SKILLS = [
  "Attack", "Defence", "Strength", "Hitpoints", "Ranged", "Prayer",
  "Magic", "Cooking", "Woodcutting", "Fletching", "Fishing", "Firemaking",
  "Crafting", "Smithing", "Mining", "Herblore", "Agility", "Thieving",
  "Slayer", "Farming", "Runecraft", "Hunter", "Construction", "Sailing"
];

// Names as the official hiscores spell them. Admins can also type others.
const BOSSES = [
  "Abyssal Sire", "Alchemical Hydra", "Amoxliatl", "Araxxor", "Artio",
  "Barrows Chests", "Bryophyta", "Callisto", "Calvar'ion", "Cerberus",
  "Chambers of Xeric", "Chambers of Xeric: Challenge Mode", "Chaos Elemental",
  "Chaos Fanatic", "Commander Zilyana", "Corporeal Beast", "Crazy Archaeologist",
  "Dagannoth Prime", "Dagannoth Rex", "Dagannoth Supreme", "Deranged Archaeologist",
  "Doom of Mokhaiotl", "Duke Sucellus", "General Graardor", "Giant Mole",
  "Grotesque Guardians", "Hespori", "Kalphite Queen", "King Black Dragon", "Kraken",
  "Kree'Arra", "K'ril Tsutsaroth", "Lunar Chests", "Mimic", "Nex", "Nightmare",
  "Obor", "Phantom Muspah", "Phosani's Nightmare", "Sarachnis", "Scorpia",
  "Scurrius", "Skotizo", "Sol Heredit", "Spindel", "Tempoross", "The Corrupted Gauntlet",
  "The Gauntlet", "The Hueycoatl", "The Leviathan", "The Royal Titans", "The Whisperer",
  "Theatre of Blood", "Theatre of Blood: Hard Mode", "Thermonuclear Smoke Devil",
  "Tombs of Amascut", "Tombs of Amascut: Expert Mode", "TzKal-Zuk", "TzTok-Jad",
  "Vardorvis", "Venenatis", "Vet'ion", "Vorkath", "Wintertodt", "Yama", "Zalcano", "Zulrah"
];

const MAX_VALUE = 200000000;
const ALLOW_LATE_JOIN = true;
const END_GRACE_SECONDS = 120;
const SHOW_FINISHED_DAYS = 60;
const LEADERBOARD_CACHE_MS = 15000;
const HISCORE_BATCH = 20;

// Players added by an admin (no RuneLite) are tracked from the hiscores:
// at the start, once a day after this hour (UK time), every couple of
// minutes in the last hour, and once more as soon as the competition ends.
const DAILY_CHECK_HOUR_UK = 23;
const LAST_HOUR_CHECK_EVERY_SECONDS = 120;
const FINAL_CHECK_WINDOW_SECONDS = 3600;
const HISCORE_END_DELAY_SECONDS = 15 * 60;
const HISCORE_RECHECK_HOURS = 48;

// Password hashing strength. Kept modest to stay inside the free plan's
// CPU allowance; clan passwords are shared secrets, not bank logins.
const PBKDF2_ITERATIONS = 10000;

// Sign-in attempts allowed per minute from one address.
const LOGIN_ATTEMPTS_PER_MINUTE = 10;

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "Access-Control-Allow-Origin": "*"
    }
  });
}

function fail(status, code, message) {
  return json({ success: false, code, error: message }, status);
}

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

function validId(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function cleanRsn(value) {
  return String(value).replace(/[ _]/g, " ").trim().replace(/\s+/g, " ");
}

function validRsn(value) {
  if (typeof value !== "string") return false;
  const name = cleanRsn(value);
  return name.length >= 1 && name.length <= 12 && /^[a-zA-Z0-9 -]+$/.test(name);
}

function cleanClanName(value) {
  return typeof value === "string" ? value.trim().replace(/\s+/g, " ") : "";
}

function validValue(value) {
  return Number.isSafeInteger(value) && value >= 0 && value <= MAX_VALUE;
}

function normaliseSkill(value) {
  if (typeof value !== "string") return null;
  return SKILLS.find(s => s.toLowerCase() === value.trim().toLowerCase()) || null;
}

function normaliseBoss(value) {
  if (typeof value !== "string") return null;
  const name = value.trim().replace(/\s+/g, " ");
  if (name.length < 2 || name.length > 50) return null;
  return BOSSES.find(b => b.toLowerCase() === name.toLowerCase()) || name;
}

function liveStatus(c, now) {
  if (c.status === "cancelled") return "cancelled";
  if (now < c.start_time) return "upcoming";
  if (now < c.end_time) return "running";
  return "finished";
}

function publicCompetition(c, now) {
  return {
    id: c.id,
    name: c.name,
    type: c.type || "skill",
    // "skill" is kept for older plugin versions; "metric" is the skill or boss.
    skill: c.skill,
    metric: c.skill,
    start_time: c.start_time,
    end_time: c.end_time,
    status: liveStatus(c, now),
    participants: c.participants ?? undefined,
    clan_id: c.clan_id ?? null,
    clan_name: c.clan_name ?? undefined
  };
}

async function readBody(request) {
  try {
    return await request.json();
  } catch (error) {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Passwords and sign-in tokens
// ---------------------------------------------------------------------------

const encoder = new TextEncoder();

function toBase64Url(bytes) {
  let text = "";
  for (const b of new Uint8Array(bytes)) text += String.fromCharCode(b);
  return btoa(text).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(text) {
  const padded = text.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((text.length + 3) % 4);
  const raw = atob(padded);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes;
}

function randomSalt() {
  return toBase64Url(crypto.getRandomValues(new Uint8Array(16)));
}

async function hashPassword(password, salt) {
  const key = await crypto.subtle.importKey(
    "raw", encoder.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: encoder.encode(salt), iterations: PBKDF2_ITERATIONS },
    key, 256);
  return "pbkdf2$" + PBKDF2_ITERATIONS + "$" + toBase64Url(bits);
}

function sameText(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let difference = 0;
  for (let i = 0; i < a.length; i++) difference |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return difference === 0;
}

async function checkPassword(password, salt, storedHash) {
  if (typeof password !== "string" || !salt || !storedHash) return false;
  return sameText(await hashPassword(password, salt), storedHash);
}

function tokenSecret(env) {
  return env.TOKEN_SECRET || env.ADMIN_KEY || "";
}

async function hmacKey(env) {
  return crypto.subtle.importKey(
    "raw", encoder.encode("clan-xp-token:" + tokenSecret(env)),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}

// A token says which clan and role it is for. It stops working when that
// clan's password changes, because the password's salt is part of it.
async function makeToken(env, payload) {
  const body = toBase64Url(encoder.encode(JSON.stringify(payload)));
  const signature = await crypto.subtle.sign("HMAC", await hmacKey(env), encoder.encode(body));
  return body + "." + toBase64Url(signature);
}

async function readToken(env, token) {
  if (typeof token !== "string" || !token.includes(".") || !tokenSecret(env)) return null;
  const [body, signature] = token.split(".");
  try {
    const ok = await crypto.subtle.verify(
      "HMAC", await hmacKey(env), fromBase64Url(signature), encoder.encode(body));
    if (!ok) return null;
    return JSON.parse(new TextDecoder().decode(fromBase64Url(body)));
  } catch (error) {
    return null;
  }
}

function saltTag(salt) {
  return String(salt || "").slice(0, 12);
}

/** Works out who is calling. Returns null if they are not signed in. */
async function authenticate(request, env) {
  const header = request.headers.get("Authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  const payload = await readToken(env, token);
  if (!payload) return null;

  if (payload.r === "master") {
    return { role: "master", clanId: null, clanName: null };
  }

  const clan = await env.DB.prepare(
    `SELECT id, name, join_salt, admin_salt FROM clans WHERE id = ?`
  ).bind(payload.c).first();
  if (!clan) return null;

  if (payload.r === "member" && payload.v === saltTag(clan.join_salt)) {
    return { role: "member", clanId: clan.id, clanName: clan.name };
  }
  if (payload.r === "admin" && clan.admin_salt && payload.v === saltTag(clan.admin_salt)) {
    return { role: "admin", clanId: clan.id, clanName: clan.name };
  }
  return null;
}

// Simple per-address limit on sign-in attempts. Each Cloudflare server keeps
// its own count, which is enough to slow down guessing.
const loginAttempts = new Map();

function tooManyAttempts(request) {
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const minute = Math.floor(Date.now() / 60000);
  const key = ip + ":" + minute;
  const count = (loginAttempts.get(key) || 0) + 1;
  loginAttempts.set(key, count);
  if (loginAttempts.size > 5000) loginAttempts.clear();
  return count > LOGIN_ATTEMPTS_PER_MINUTE;
}

async function findClanByName(env, name) {
  return env.DB.prepare(
    `SELECT id, name, join_hash, join_salt, admin_hash, admin_salt
     FROM clans WHERE name = ? COLLATE NOCASE`
  ).bind(cleanClanName(name)).first();
}

// ---------------------------------------------------------------------------
// Database helpers
// ---------------------------------------------------------------------------

async function getCompetition(env, id) {
  return env.DB.prepare(
    `SELECT id, name, skill, type, start_time, end_time, status, clan_id
     FROM competitions WHERE id = ?`
  ).bind(id).first();
}

/** The competition, but only if the caller's clan may see it. */
async function getCompetitionFor(env, who, id) {
  const c = await getCompetition(env, id);
  if (!c) return null;
  if (who.role === "master") return c;
  return c.clan_id === who.clanId ? c : null;
}

async function findParticipant(env, competitionId, rsn) {
  return env.DB.prepare(
    `SELECT p.id, p.rsn, c.start_time, c.end_time, c.status, c.clan_id,
            s.xp AS starting_xp,
            t.latest_xp, t.last_server_received_at
     FROM participants p
     JOIN competitions c ON c.id = p.competition_id
     LEFT JOIN xp_snapshots s
       ON s.participant_id = p.id AND s.snapshot_type = 'start'
     LEFT JOIN player_tracking t ON t.participant_id = p.id
     WHERE p.competition_id = ?
       AND p.rsn = ? COLLATE NOCASE`
  ).bind(competitionId, rsn).first();
}

const leaderboardCache = new Map();

function clearLeaderboardCache(competitionId) {
  leaderboardCache.delete(competitionId);
}

async function buildLeaderboard(env, competition) {
  const cached = leaderboardCache.get(competition.id);
  if (cached && Date.now() - cached.at < LEADERBOARD_CACHE_MS) {
    return cached.data;
  }

  const { results } = await env.DB.prepare(
    `SELECT p.rsn, p.tracking_method,
            s.xp AS starting_xp,
            t.latest_xp,
            t.last_server_received_at,
            COALESCE(t.validation_status, 'provisional') AS validation_status
     FROM participants p
     LEFT JOIN xp_snapshots s
       ON s.participant_id = p.id AND s.snapshot_type = 'start'
     LEFT JOIN player_tracking t ON t.participant_id = p.id
     WHERE p.competition_id = ?`
  ).bind(competition.id).all();

  const rows = results.map(r => {
    const start = r.starting_xp;
    const current = start === null ? null : Math.max(start, r.latest_xp ?? start);
    return {
      rsn: r.rsn,
      tracking_method: r.tracking_method,
      starting_xp: start,
      current_xp: current,
      // "xp_gained" is XP for skill competitions and kills for boss ones.
      xp_gained: start === null ? null : current - start,
      last_update: r.last_server_received_at ?? null,
      validation_status: r.validation_status,
      verified: r.validation_status === "verified"
    };
  });

  rows.sort((a, b) => {
    if (a.xp_gained === null && b.xp_gained === null) return a.rsn.localeCompare(b.rsn);
    if (a.xp_gained === null) return 1;
    if (b.xp_gained === null) return -1;
    if (b.xp_gained !== a.xp_gained) return b.xp_gained - a.xp_gained;
    return a.rsn.localeCompare(b.rsn);
  });

  let rank = 0;
  let previous = null;
  rows.forEach((row, index) => {
    if (row.xp_gained === null) {
      row.rank = null;
      return;
    }
    if (row.xp_gained !== previous) {
      rank = index + 1;
      previous = row.xp_gained;
    }
    row.rank = rank;
  });

  const data = {
    competition: publicCompetition(competition, nowSeconds()),
    leaderboard: rows
  };
  leaderboardCache.set(competition.id, { at: Date.now(), data });
  return data;
}

async function deleteCompetition(env, id) {
  const ids = `SELECT id FROM participants WHERE competition_id = ?`;
  await env.DB.batch([
    env.DB.prepare(`DELETE FROM finishing_snapshots WHERE participant_id IN (${ids})`).bind(id),
    env.DB.prepare(`DELETE FROM xp_snapshots WHERE participant_id IN (${ids})`).bind(id),
    env.DB.prepare(`DELETE FROM player_tracking WHERE participant_id IN (${ids})`).bind(id),
    env.DB.prepare(`DELETE FROM participants WHERE competition_id = ?`).bind(id),
    env.DB.prepare(`DELETE FROM entry_requests WHERE competition_id = ?`).bind(id),
    env.DB.prepare(`DELETE FROM competitions WHERE id = ?`).bind(id)
  ]);
  clearLeaderboardCache(id);
}

// ---------------------------------------------------------------------------
// Clan sign-in
// ---------------------------------------------------------------------------

async function handleClanLogin(request, env) {
  if (tooManyAttempts(request)) {
    return fail(429, "slow_down", "Too many attempts. Wait a minute and try again.");
  }

  const body = await readBody(request);
  if (!body) return fail(400, "bad_json", "Invalid JSON request.");

  const clan = await findClanByName(env, body.clan);
  if (!clan || !(await checkPassword(body.password, clan.join_salt, clan.join_hash))) {
    return fail(401, "wrong_password", "Clan name or password is wrong.");
  }

  const token = await makeToken(env, { c: clan.id, r: "member", v: saltTag(clan.join_salt) });
  return json({ success: true, token, clan: { id: clan.id, name: clan.name } });
}

// ---------------------------------------------------------------------------
// Member endpoints
// ---------------------------------------------------------------------------

async function handleCompetitions(env, who) {
  const now = nowSeconds();
  const { results } = await env.DB.prepare(
    `SELECT c.id, c.name, c.skill, c.type, c.start_time, c.end_time, c.status, c.clan_id,
            (SELECT COUNT(*) FROM participants p WHERE p.competition_id = c.id) AS participants
     FROM competitions c
     WHERE c.clan_id = ?
       AND c.status != 'cancelled'
       AND c.end_time > ?
     ORDER BY c.start_time ASC`
  ).bind(who.clanId, now - SHOW_FINISHED_DAYS * 86400).all();

  return json({
    success: true,
    server_time_ms: Date.now(),
    clan: { id: who.clanId, name: who.clanName },
    competitions: results.map(c => publicCompetition(c, now))
  });
}

async function handleRegistrations(url, env, who) {
  const rsn = url.searchParams.get("rsn");
  if (!validRsn(rsn)) return fail(400, "bad_rsn", "Invalid username.");

  const { results } = await env.DB.prepare(
    `SELECT c.id AS competition_id
     FROM participants p
     JOIN competitions c ON c.id = p.competition_id
     WHERE p.rsn = ? COLLATE NOCASE
       AND c.clan_id = ?
       AND c.status != 'cancelled'
       AND c.end_time > ?`
  ).bind(cleanRsn(rsn), who.clanId, nowSeconds() - SHOW_FINISHED_DAYS * 86400).all();

  return json({
    success: true,
    server_time_ms: Date.now(),
    rsn: cleanRsn(rsn),
    competition_ids: results.map(r => r.competition_id)
  });
}

async function handleRegister(request, env, ctx, who) {
  const body = await readBody(request);
  if (!body) return fail(400, "bad_json", "Invalid JSON request.");

  const competitionId = body.competition_id;
  if (!validId(competitionId) || !validRsn(body.rsn)) {
    return fail(400, "bad_input", "Invalid competition or username.");
  }

  const name = cleanRsn(body.rsn);
  const competition = await getCompetitionFor(env, who, competitionId);
  if (!competition) return fail(404, "not_found", "Competition not found.");

  const status = liveStatus(competition, nowSeconds());
  const open = status === "upcoming" || (ALLOW_LATE_JOIN && status === "running");
  if (!open) return fail(409, "closed", "Registration for this competition is closed.");

  const result = await env.DB.prepare(
    `INSERT OR IGNORE INTO participants (competition_id, rsn, tracking_method)
     VALUES (?, ?, 'runelite')`
  ).bind(competitionId, name).run();

  if (result.meta.changes === 0 && status === "upcoming") {
    // An admin added them for hiscores tracking, but they use RuneLite after all.
    await env.DB.prepare(
      `UPDATE participants SET tracking_method = 'runelite'
       WHERE competition_id = ? AND rsn = ? COLLATE NOCASE AND tracking_method = 'hiscores'`
    ).bind(competitionId, name).run();
  }

  clearLeaderboardCache(competitionId);

  if (result.meta.changes > 0 && ctx) {
    ctx.waitUntil(checkHiscoreStart(env, competitionId, name).catch(() => {}));
  }

  return json({
    success: true,
    already_registered: result.meta.changes === 0,
    message: result.meta.changes > 0
      ? "Successfully entered the competition!"
      : "Already registered for this competition.",
    rsn: name,
    competition_id: competitionId
  });
}

async function handleReport(request, env, who) {
  const body = await readBody(request);
  if (!body) return fail(400, "bad_json", "Invalid JSON request.");

  const competitionId = body.competition_id;
  // "xp" is the XP total for skills or the kill count for bosses.
  const value = body.xp;

  if (!validId(competitionId) || !validRsn(body.rsn) || !validValue(value)) {
    return fail(400, "bad_input", "Invalid competition, username or value.");
  }

  const name = cleanRsn(body.rsn);
  const participant = await findParticipant(env, competitionId, name);

  if (!participant || participant.clan_id !== who.clanId) {
    return fail(403, "not_registered", "Player is not registered for this competition.");
  }
  if (participant.status === "cancelled") {
    return fail(409, "cancelled", "This competition was cancelled.");
  }

  const receivedMs = Date.now();
  const now = Math.floor(receivedMs / 1000);
  let capturedMs = Number.isSafeInteger(body.captured_at_ms) ? body.captured_at_ms : receivedMs;
  capturedMs = Math.min(capturedMs, receivedMs);
  const captured = capturedMs / 1000;

  if (captured < participant.start_time) {
    return fail(409, "not_started", "Competition has not started yet.");
  }
  if (captured >= participant.end_time || now >= participant.end_time + END_GRACE_SECONDS) {
    return fail(409, "ended", "Competition has ended.");
  }

  let start = participant.starting_xp;
  if (start === null || start === undefined) {
    let proposed = Number.isSafeInteger(body.start_xp) ? body.start_xp : value;
    if (!validValue(proposed) || proposed > value) proposed = value;

    await env.DB.prepare(
      `INSERT OR IGNORE INTO xp_snapshots (participant_id, snapshot_type, xp)
       VALUES (?, 'start', ?)`
    ).bind(participant.id, proposed).run();

    const saved = await env.DB.prepare(
      `SELECT xp FROM xp_snapshots WHERE participant_id = ? AND snapshot_type = 'start'`
    ).bind(participant.id).first();
    start = saved.xp;
  }

  if (value < start) {
    return fail(409, "below_start", "Value is lower than the starting value.");
  }

  const latest = Math.max(participant.latest_xp ?? start, start);
  if (value > latest || participant.latest_xp === null || participant.latest_xp === undefined) {
    await env.DB.prepare(
      `INSERT INTO player_tracking (
         participant_id, latest_xp, last_client_capture_ms,
         last_server_received_at, validation_status
       )
       VALUES (?, ?, ?, unixepoch(), 'provisional')
       ON CONFLICT(participant_id) DO UPDATE SET
         latest_xp = MAX(COALESCE(player_tracking.latest_xp, 0), excluded.latest_xp),
         last_client_capture_ms = excluded.last_client_capture_ms,
         last_server_received_at = unixepoch()`
    ).bind(participant.id, value, capturedMs).run();
    clearLeaderboardCache(competitionId);
  }

  const current = Math.max(value, latest);
  return json({
    success: true,
    message: "Update accepted.",
    server_time_ms: Date.now(),
    rsn: participant.rsn,
    starting_xp: start,
    current_xp: current,
    xp_gained: current - start
  });
}

async function handleLeaderboard(url, env, who) {
  const competitionId = Number(url.searchParams.get("competition_id"));
  if (!validId(competitionId)) return fail(400, "bad_input", "Invalid competition ID.");

  const competition = await getCompetitionFor(env, who, competitionId);
  if (!competition) return fail(404, "not_found", "Competition not found.");

  const data = await buildLeaderboard(env, competition);
  return json({ success: true, server_time_ms: Date.now(), competition_id: competitionId, ...data });
}

// ---------------------------------------------------------------------------
// Hiscores cross-check (skills use XP, bosses use kill count)
// ---------------------------------------------------------------------------

async function fetchHiscoreValue(rsn, type, metric) {
  const url = "https://secure.runescape.com/m=hiscore_oldschool/index_lite.json?player=" +
    encodeURIComponent(rsn);
  const response = await fetch(url, {
    headers: { "User-Agent": "clan-xp-competitions (RuneLite clan plugin)" }
  });

  if (response.status === 404) return { found: false, value: null };
  if (!response.ok) throw new Error("Hiscores returned " + response.status);

  const data = await response.json();
  const wanted = String(metric).toLowerCase();

  if (type === "boss") {
    const entry = (data.activities || []).find(a => String(a.name).toLowerCase() === wanted);
    const score = entry && Number.isSafeInteger(entry.score) && entry.score >= 0 ? entry.score : null;
    return { found: true, value: score };
  }

  const entry = (data.skills || []).find(s => String(s.name).toLowerCase() === wanted);
  const xp = entry && Number.isSafeInteger(entry.xp) && entry.xp >= 0 ? entry.xp : null;
  return { found: true, value: xp };
}

async function checkHiscoreStart(env, competitionId, rsn) {
  const row = await env.DB.prepare(
    `SELECT p.id, c.skill, c.type FROM participants p
     JOIN competitions c ON c.id = p.competition_id
     WHERE p.competition_id = ? AND p.rsn = ? COLLATE NOCASE
       AND p.tracking_method = 'runelite'
       AND p.hiscore_start_checked_at IS NULL`
  ).bind(competitionId, rsn).first();
  if (!row) return;

  const result = await fetchHiscoreValue(rsn, row.type, row.skill);
  await env.DB.prepare(
    `UPDATE participants SET hiscore_start_xp = ?, hiscore_start_checked_at = unixepoch()
     WHERE id = ?`
  ).bind(result.value, row.id).run();
}

function decideValidation(row, hiscoreEnd) {
  if (row.starting_xp === null || row.latest_xp === null) return "provisional";
  if (row.hiscore_start_xp === null || hiscoreEnd === null) return "provisional";
  const startOk = row.starting_xp >= row.hiscore_start_xp;
  const endOk = row.latest_xp <= hiscoreEnd;
  return startOk && endOk ? "verified" : "needs_review";
}

async function runHiscoreChecks(env, onlyCompetitionId = null, startingBudget = HISCORE_BATCH) {
  const now = nowSeconds();
  let budget = startingBudget;
  const summary = { start_checked: 0, end_checked: 0, errors: 0 };

  const starts = await env.DB.prepare(
    `SELECT p.rsn, c.id AS competition_id
     FROM participants p
     JOIN competitions c ON c.id = p.competition_id
     WHERE p.hiscore_start_checked_at IS NULL
       AND p.tracking_method = 'runelite'
       AND c.status != 'cancelled'
       AND c.end_time > ?
       AND (? IS NULL OR c.id = ?)
     LIMIT ?`
  ).bind(now, onlyCompetitionId, onlyCompetitionId, budget).all();

  for (const p of starts.results) {
    if (budget <= 0) break;
    budget--;
    try {
      await checkHiscoreStart(env, p.competition_id, p.rsn);
      summary.start_checked++;
    } catch (e) {
      summary.errors++;
    }
  }
  if (budget <= 0) return summary;

  const ends = await env.DB.prepare(
    `SELECT p.id, p.rsn, p.hiscore_start_xp, c.id AS competition_id, c.skill, c.type,
            s.xp AS starting_xp, t.latest_xp
     FROM participants p
     JOIN competitions c ON c.id = p.competition_id
     LEFT JOIN xp_snapshots s ON s.participant_id = p.id AND s.snapshot_type = 'start'
     LEFT JOIN player_tracking t ON t.participant_id = p.id
     WHERE c.status != 'cancelled'
       AND p.tracking_method = 'runelite'
       AND c.end_time <= ?
       AND c.end_time > ?
       AND (? IS NULL OR c.id = ?)
       AND (
         p.hiscore_end_checked_at IS NULL
         OR (COALESCE(t.validation_status, 'provisional') != 'verified'
             AND p.hiscore_end_checked_at < ?)
       )
     ORDER BY p.hiscore_end_checked_at IS NOT NULL, p.hiscore_end_checked_at
     LIMIT ?`
  ).bind(
    now - HISCORE_END_DELAY_SECONDS,
    now - HISCORE_RECHECK_HOURS * 3600,
    onlyCompetitionId, onlyCompetitionId,
    now - 3 * 3600,
    budget
  ).all();

  for (const p of ends.results) {
    if (budget <= 0) break;
    budget--;
    try {
      const result = await fetchHiscoreValue(p.rsn, p.type, p.skill);
      const status = decideValidation(p, result.value);
      await env.DB.prepare(
        `UPDATE participants SET hiscore_end_xp = ?, hiscore_end_checked_at = unixepoch()
         WHERE id = ?`
      ).bind(result.value, p.id).run();
      await env.DB.prepare(
        `INSERT INTO player_tracking (participant_id, validation_status) VALUES (?, ?)
         ON CONFLICT(participant_id) DO UPDATE SET validation_status = excluded.validation_status`
      ).bind(p.id, status).run();
      clearLeaderboardCache(p.competition_id);
      summary.end_checked++;
    } catch (e) {
      summary.errors++;
    }
  }
  return summary;
}

// ---------------------------------------------------------------------------
// Players tracked by the hiscores (added by an admin, no RuneLite)
// ---------------------------------------------------------------------------

function ukTime(seconds) {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/London", year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", hourCycle: "h23"
  }).formatToParts(new Date(seconds * 1000));
  const get = type => parts.find(p => p.type === type).value;
  return { day: get("year") + "-" + get("month") + "-" + get("day"), hour: Number(get("hour")) };
}

/** 0 = not due. Otherwise a priority: 1 final, 2 start, 3 last hour, 4 daily. */
function hiscoreCheckDue(p, now) {
  if (now >= p.end_time) return 1;
  if (p.starting_xp === null || p.starting_xp === undefined) return 2;
  const last = p.hiscore_last_checked_at || 0;
  if (p.end_time - now <= 3600) {
    return now - last >= LAST_HOUR_CHECK_EVERY_SECONDS ? 3 : 0;
  }
  // Once a day, after 23:00 UK time (a reading earlier that day doesn't count).
  const today = ukTime(now);
  const previous = ukTime(last);
  const doneToday = previous.day === today.day && previous.hour >= DAILY_CHECK_HOUR_UK;
  if (today.hour >= DAILY_CHECK_HOUR_UK && !doneToday) return 4;
  return 0;
}

/** Reads one player's hiscores and stores it. Throws if the hiscores can't be reached. */
async function readHiscoresFor(env, p, isFinal, now) {
  const result = await fetchHiscoreValue(p.rsn, p.type, p.skill);

  if (result.value === null) {
    const what = p.type === "boss" ? p.skill + " kills" : p.skill;
    const note = result.found
      ? "Not ranked for " + what + " on the hiscores yet"
      : "Name not found on the hiscores";
    await env.DB.prepare(
      `UPDATE participants SET hiscore_last_checked_at = ?, hiscore_note = ?,
         hiscore_final_at = CASE WHEN ? = 1 THEN ? ELSE hiscore_final_at END
       WHERE id = ?`
    ).bind(now, note, isFinal ? 1 : 0, now, p.id).run();
    return;
  }

  const statements = [];
  if (p.starting_xp === null || p.starting_xp === undefined) {
    statements.push(env.DB.prepare(
      `INSERT OR IGNORE INTO xp_snapshots (participant_id, snapshot_type, xp) VALUES (?, 'start', ?)`
    ).bind(p.id, result.value));
  }
  statements.push(env.DB.prepare(
    `INSERT INTO player_tracking (participant_id, latest_xp, last_server_received_at, validation_status)
     VALUES (?, ?, unixepoch(), ?)
     ON CONFLICT(participant_id) DO UPDATE SET
       latest_xp = MAX(COALESCE(player_tracking.latest_xp, 0), excluded.latest_xp),
       last_server_received_at = unixepoch(),
       validation_status = excluded.validation_status`
  ).bind(p.id, result.value, isFinal ? "verified" : "provisional"));
  statements.push(env.DB.prepare(
    `UPDATE participants SET hiscore_last_checked_at = ?, hiscore_note = NULL,
       hiscore_final_at = CASE WHEN ? = 1 THEN ? ELSE hiscore_final_at END
     WHERE id = ?`
  ).bind(now, isFinal ? 1 : 0, now, p.id));

  await env.DB.batch(statements);
  clearLeaderboardCache(p.competition_id);
}

/** Runs every minute from the cron trigger. Returns how many lookups it used. */
async function runHiscoreTracking(env, budget) {
  const now = nowSeconds();
  const { results } = await env.DB.prepare(
    `SELECT p.id, p.rsn, p.hiscore_last_checked_at,
            c.id AS competition_id, c.skill, c.type, c.start_time, c.end_time,
            s.xp AS starting_xp
     FROM participants p
     JOIN competitions c ON c.id = p.competition_id
     LEFT JOIN xp_snapshots s ON s.participant_id = p.id AND s.snapshot_type = 'start'
     WHERE p.tracking_method = 'hiscores'
       AND p.hiscore_final_at IS NULL
       AND c.status != 'cancelled'
       AND c.start_time <= ?
       AND c.end_time > ?`
  ).bind(now, now - FINAL_CHECK_WINDOW_SECONDS).all();

  const due = results
    .map(p => ({ p, priority: hiscoreCheckDue(p, now) }))
    .filter(x => x.priority > 0)
    .sort((a, b) => a.priority - b.priority ||
      (a.p.hiscore_last_checked_at || 0) - (b.p.hiscore_last_checked_at || 0));

  let used = 0;
  for (const { p, priority } of due) {
    if (used >= budget) break;
    used++;
    try {
      await readHiscoresFor(env, p, priority === 1, now);
    } catch (e) {
      // The hiscores couldn't be reached; it is tried again next minute.
    }
  }
  return used;
}

async function syncStatuses(env) {
  const now = nowSeconds();
  await env.DB.prepare(
    `UPDATE competitions SET status = 'running'
     WHERE status = 'scheduled' AND start_time <= ? AND end_time > ?`
  ).bind(now, now).run();
  await env.DB.prepare(
    `UPDATE competitions SET status = 'finished'
     WHERE status IN ('scheduled', 'running') AND end_time <= ?`
  ).bind(now).run();
}

// ---------------------------------------------------------------------------
// Adding players without RuneLite (by an admin, or by approving a request)
// ---------------------------------------------------------------------------

/** Adds a player tracked by the hiscores. Returns { ok, ... } rather than a Response. */
async function addHiscoresPlayer(env, competition, rawName) {
  if (!validRsn(rawName)) {
    return { ok: false, status: 400, code: "bad_rsn", error: "Enter a valid RuneScape name." };
  }
  const name = cleanRsn(rawName);
  const status = liveStatus(competition, nowSeconds());
  if (status === "finished" || status === "cancelled") {
    return { ok: false, status: 409, code: "closed", error: "This competition has ended." };
  }

  let check;
  try {
    check = await fetchHiscoreValue(name, competition.type, competition.skill);
  } catch (e) {
    return { ok: false, status: 502, code: "hiscores_down", error: "Couldn't reach the official hiscores. Try again in a minute." };
  }
  if (!check.found) {
    return { ok: false, status: 404, code: "not_on_hiscores", error: "That name isn't on the official hiscores. Check the spelling." };
  }

  const added = await env.DB.prepare(
    `INSERT OR IGNORE INTO participants (competition_id, rsn, tracking_method) VALUES (?, ?, 'hiscores')`
  ).bind(competition.id, name).run();
  if (added.meta.changes === 0) {
    return { ok: false, status: 409, code: "exists", error: name + " is already in this competition." };
  }

  if (status === "running") {
    // Joined late: their starting point is now.
    const p = await env.DB.prepare(`SELECT id FROM participants WHERE competition_id = ? AND rsn = ?`)
      .bind(competition.id, name).first();
    await readHiscoresFor(env, {
      id: p.id, rsn: name, type: competition.type, skill: competition.skill,
      competition_id: competition.id, starting_xp: null
    }, false, nowSeconds()).catch(() => {});
  }
  clearLeaderboardCache(competition.id);

  const what = competition.type === "boss" ? competition.skill + " kills" : competition.skill;
  return {
    ok: true,
    name,
    warning: check.value === null
      ? name + " isn't ranked for " + what + " on the hiscores yet, so their score can't be read until they are."
      : null
  };
}

async function clearRequest(env, competitionId, rsn) {
  await env.DB.prepare(`DELETE FROM entry_requests WHERE competition_id = ? AND rsn = ? COLLATE NOCASE`)
    .bind(competitionId, rsn).run();
}

/** A clan member (on the website) asks for a player to be entered without RuneLite. */
async function handleEntryRequest(request, env, who) {
  const body = await readBody(request);
  if (!body) return fail(400, "bad_json", "Invalid JSON request.");
  if (!validId(body.competition_id) || !validRsn(body.rsn)) {
    return fail(400, "bad_input", "Enter a valid RuneScape name.");
  }

  const competition = await getCompetitionFor(env, who, body.competition_id);
  if (!competition) return fail(404, "not_found", "Competition not found.");
  const status = liveStatus(competition, nowSeconds());
  if (status !== "upcoming" && !(ALLOW_LATE_JOIN && status === "running")) {
    return fail(409, "closed", "Entries for this competition are closed.");
  }

  const name = cleanRsn(body.rsn);
  const existing = await env.DB.prepare(
    `SELECT 1 FROM participants WHERE competition_id = ? AND rsn = ? COLLATE NOCASE`
  ).bind(competition.id, name).first();
  if (existing) return fail(409, "exists", name + " is already in this competition.");

  const pending = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM entry_requests WHERE competition_id = ? AND status = 'pending'`
  ).bind(competition.id).first();
  if (pending.n >= 200) return fail(429, "too_many", "There are too many requests waiting. Ask an admin.");

  let check;
  try {
    check = await fetchHiscoreValue(name, competition.type, competition.skill);
  } catch (e) {
    return fail(502, "hiscores_down", "Couldn't reach the official hiscores. Try again in a minute.");
  }
  if (!check.found) {
    return fail(404, "not_on_hiscores", "That name isn't on the official hiscores. Check the spelling.");
  }

  const previous = await env.DB.prepare(
    `SELECT status FROM entry_requests WHERE competition_id = ? AND rsn = ? COLLATE NOCASE`
  ).bind(competition.id, name).first();
  if (previous && previous.status === "pending") {
    return json({ success: true, already: true, message: "Already requested. An admin will look at it soon." });
  }
  if (previous && previous.status === "declined") {
    return fail(409, "declined", "A clan admin declined this request. Ask them in clan chat.");
  }

  await env.DB.prepare(`INSERT INTO entry_requests (competition_id, rsn) VALUES (?, ?)`)
    .bind(competition.id, name).run();

  const what = competition.type === "boss" ? competition.skill + " kills" : competition.skill;
  return json({
    success: true,
    message: "Request sent. A clan admin needs to approve it." +
      (check.value === null ? " Note: " + name + " isn't ranked for " + what + " on the hiscores yet." : "")
  });
}

// ---------------------------------------------------------------------------
// Admin
// ---------------------------------------------------------------------------

async function handleAdminLogin(request, env) {
  if (!env.ADMIN_KEY) {
    return fail(503, "admin_disabled", "Set the ADMIN_KEY secret in Cloudflare to enable admin.");
  }
  if (tooManyAttempts(request)) {
    return fail(429, "slow_down", "Too many attempts. Wait a minute and try again.");
  }

  const body = await readBody(request);
  if (!body || typeof body.password !== "string") {
    return fail(400, "bad_input", "Enter a password.");
  }

  // The master password works with or without a clan name.
  if (sameText(body.password, env.ADMIN_KEY)) {
    const token = await makeToken(env, { r: "master" });
    return json({ success: true, token, role: "master" });
  }

  const clan = await findClanByName(env, body.clan);
  if (clan && clan.admin_hash &&
      await checkPassword(body.password, clan.admin_salt, clan.admin_hash)) {
    const token = await makeToken(env, { c: clan.id, r: "admin", v: saltTag(clan.admin_salt) });
    return json({ success: true, token, role: "admin", clan: { id: clan.id, name: clan.name } });
  }

  return fail(401, "wrong_password", "Wrong clan name or admin password.");
}

async function handleAdmin(request, url, env, ctx) {
  const who = await authenticate(request, env);
  if (!who || (who.role !== "master" && who.role !== "admin")) {
    return fail(401, "unauthorised", "Please sign in to the admin page again.");
  }

  const path = url.pathname;
  const method = request.method;
  const isMaster = who.role === "master";

  if (path === "/api/admin/me" && method === "GET") {
    return json({ success: true, role: who.role, clan: who.clanId ? { id: who.clanId, name: who.clanName } : null });
  }

  // ----- Clans -----

  if (path === "/api/admin/clans" && method === "GET") {
    const { results } = await env.DB.prepare(
      `SELECT k.id, k.name, k.admin_hash IS NOT NULL AS has_admin,
              (SELECT COUNT(*) FROM competitions c WHERE c.clan_id = k.id) AS competitions
       FROM clans k
       WHERE (? = 1 OR k.id = ?)
       ORDER BY k.name COLLATE NOCASE`
    ).bind(isMaster ? 1 : 0, who.clanId).all();
    const unassigned = isMaster
      ? (await env.DB.prepare(`SELECT COUNT(*) AS n FROM competitions WHERE clan_id IS NULL`).first()).n
      : 0;
    return json({ success: true, clans: results, unassigned_competitions: unassigned });
  }

  if (path === "/api/admin/clans" && method === "POST") {
    if (!isMaster) return fail(403, "forbidden", "Only the master admin can create clans.");
    const body = await readBody(request);
    const name = cleanClanName(body && body.name);
    if (name.length < 2 || name.length > 40) return fail(400, "bad_name", "Clan name must be 2 to 40 characters.");
    if (!body.join_password || String(body.join_password).length < 4) {
      return fail(400, "bad_password", "The member password must be at least 4 characters.");
    }
    if (body.admin_password && String(body.admin_password).length < 8) {
      return fail(400, "bad_password", "The clan admin password must be at least 8 characters.");
    }
    if (await findClanByName(env, name)) return fail(409, "exists", "A clan with that name already exists.");

    const joinSalt = randomSalt();
    const joinHash = await hashPassword(String(body.join_password), joinSalt);
    let adminSalt = null;
    let adminHash = null;
    if (body.admin_password) {
      adminSalt = randomSalt();
      adminHash = await hashPassword(String(body.admin_password), adminSalt);
    }

    const existing = await env.DB.prepare(`SELECT COUNT(*) AS n FROM clans`).first();
    const result = await env.DB.prepare(
      `INSERT INTO clans (name, join_hash, join_salt, admin_hash, admin_salt) VALUES (?, ?, ?, ?, ?)`
    ).bind(name, joinHash, joinSalt, adminHash, adminSalt).run();
    const clanId = result.meta.last_row_id;

    // The very first clan takes over competitions made before clans existed.
    let adopted = 0;
    if (existing.n === 0) {
      const moved = await env.DB.prepare(
        `UPDATE competitions SET clan_id = ? WHERE clan_id IS NULL`
      ).bind(clanId).run();
      adopted = moved.meta.changes;
    }
    return json({ success: true, id: clanId, adopted_competitions: adopted });
  }

  const passwordMatch = path.match(/^\/api\/admin\/clans\/(\d+)\/passwords$/);
  if (passwordMatch && method === "POST") {
    const clanId = Number(passwordMatch[1]);
    if (!isMaster && clanId !== who.clanId) return fail(403, "forbidden", "Not your clan.");
    const body = await readBody(request) || {};
    const statements = [];

    if (body.join_password) {
      if (String(body.join_password).length < 4) return fail(400, "bad_password", "The member password must be at least 4 characters.");
      const salt = randomSalt();
      statements.push(env.DB.prepare(`UPDATE clans SET join_hash = ?, join_salt = ? WHERE id = ?`)
        .bind(await hashPassword(String(body.join_password), salt), salt, clanId));
    }
    if (body.admin_password) {
      if (!isMaster) return fail(403, "forbidden", "Only the master admin can change a clan admin password.");
      if (String(body.admin_password).length < 8) return fail(400, "bad_password", "The clan admin password must be at least 8 characters.");
      const salt = randomSalt();
      statements.push(env.DB.prepare(`UPDATE clans SET admin_hash = ?, admin_salt = ? WHERE id = ?`)
        .bind(await hashPassword(String(body.admin_password), salt), salt, clanId));
    }
    if (statements.length === 0) return fail(400, "bad_input", "Enter a new password.");
    await env.DB.batch(statements);
    return json({ success: true });
  }

  const adoptMatch = path.match(/^\/api\/admin\/clans\/(\d+)\/adopt$/);
  if (adoptMatch && method === "POST") {
    if (!isMaster) return fail(403, "forbidden", "Only the master admin can do this.");
    const moved = await env.DB.prepare(`UPDATE competitions SET clan_id = ? WHERE clan_id IS NULL`)
      .bind(Number(adoptMatch[1])).run();
    return json({ success: true, adopted_competitions: moved.meta.changes });
  }

  // ----- Competitions -----

  if (path === "/api/admin/competitions" && method === "GET") {
    const { results } = await env.DB.prepare(
      `SELECT c.id, c.name, c.skill, c.type, c.start_time, c.end_time, c.status, c.clan_id,
              k.name AS clan_name,
              (SELECT COUNT(*) FROM participants p WHERE p.competition_id = c.id) AS participants,
              (SELECT COUNT(*) FROM entry_requests r WHERE r.competition_id = c.id AND r.status = 'pending') AS requests
       FROM competitions c
       LEFT JOIN clans k ON k.id = c.clan_id
       WHERE (? = 1 OR c.clan_id = ?)
       ORDER BY c.start_time DESC
       LIMIT 200`
    ).bind(isMaster ? 1 : 0, who.clanId).all();
    const now = nowSeconds();
    return json({ success: true, competitions: results.map(c => ({ ...publicCompetition(c, now), requests: c.requests })) });
  }

  if (path === "/api/admin/competitions" && method === "POST") {
    const body = await readBody(request);
    if (!body) return fail(400, "bad_json", "Invalid JSON request.");

    const clanId = isMaster ? body.clan_id : who.clanId;
    if (!validId(clanId)) return fail(400, "bad_clan", "Choose a clan.");
    const clan = await env.DB.prepare(`SELECT id FROM clans WHERE id = ?`).bind(clanId).first();
    if (!clan) return fail(400, "bad_clan", "Clan not found.");

    const name = typeof body.name === "string" ? body.name.trim() : "";
    const type = body.type === "boss" ? "boss" : "skill";
    const metric = type === "boss" ? normaliseBoss(body.metric) : normaliseSkill(body.metric ?? body.skill);
    const start = body.start_time;
    const end = body.end_time;

    if (name.length < 1 || name.length > 60) return fail(400, "bad_name", "Name must be 1 to 60 characters.");
    if (!metric) return fail(400, "bad_metric", type === "boss" ? "Enter a boss name." : "Unknown skill.");
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || end <= start) {
      return fail(400, "bad_time", "End must be after start.");
    }

    const result = await env.DB.prepare(
      `INSERT INTO competitions (name, skill, type, start_time, end_time, status, clan_id)
       VALUES (?, ?, ?, ?, ?, 'scheduled', ?)`
    ).bind(name, metric, type, start, end, clanId).run();
    return json({ success: true, id: result.meta.last_row_id });
  }

  const match = path.match(/^\/api\/admin\/competitions\/(\d+)(?:\/([a-z]+))?$/);
  if (match) {
    const id = Number(match[1]);
    const action = match[2] || "";
    const competition = await getCompetitionFor(env, who, id);
    if (!competition) return fail(404, "not_found", "Competition not found.");

    if (action === "" && method === "GET") {
      const { results } = await env.DB.prepare(
        `SELECT p.rsn, p.registered_at, p.hiscore_start_xp, p.hiscore_end_xp,
                p.tracking_method, p.hiscore_note, p.hiscore_last_checked_at,
                s.xp AS starting_xp, t.latest_xp, t.last_server_received_at,
                COALESCE(t.validation_status, 'provisional') AS validation_status
         FROM participants p
         LEFT JOIN xp_snapshots s ON s.participant_id = p.id AND s.snapshot_type = 'start'
         LEFT JOIN player_tracking t ON t.participant_id = p.id
         WHERE p.competition_id = ?
         ORDER BY p.rsn COLLATE NOCASE`
      ).bind(id).all();
      const requests = await env.DB.prepare(
        `SELECT rsn, status, requested_at FROM entry_requests
         WHERE competition_id = ? ORDER BY status = 'declined', requested_at`
      ).bind(id).all();
      return json({
        success: true,
        competition: publicCompetition(competition, nowSeconds()),
        participants: results,
        requests: requests.results
      });
    }

    if (action === "" && method === "DELETE") {
      await deleteCompetition(env, id);
      return json({ success: true });
    }

    if (action === "add" && method === "POST") {
      const body = await readBody(request);
      const result = await addHiscoresPlayer(env, competition, body && body.rsn);
      if (result.ok) await clearRequest(env, id, result.name);
      return result.ok
        ? json({ success: true, warning: result.warning })
        : fail(result.status, result.code, result.error);
    }

    if (action === "approve" && method === "POST") {
      const body = await readBody(request);
      const result = await addHiscoresPlayer(env, competition, body && body.rsn);
      if (result.ok || result.code === "exists") await clearRequest(env, id, cleanRsn(body.rsn));
      return result.ok
        ? json({ success: true, warning: result.warning })
        : fail(result.status, result.code, result.error);
    }

    if (action === "decline" && method === "POST") {
      const body = await readBody(request);
      if (!body || !validRsn(body.rsn)) return fail(400, "bad_rsn", "Invalid username.");
      await env.DB.prepare(
        `UPDATE entry_requests SET status = 'declined' WHERE competition_id = ? AND rsn = ? COLLATE NOCASE`
      ).bind(id, cleanRsn(body.rsn)).run();
      return json({ success: true });
    }

    if (action === "cancel" && method === "POST") {
      await env.DB.prepare(`UPDATE competitions SET status = 'cancelled' WHERE id = ?`).bind(id).run();
      clearLeaderboardCache(id);
      return json({ success: true });
    }

    if (action === "hiscores" && method === "POST") {
      return json({ success: true, ...(await runHiscoreChecks(env, id)) });
    }

    if ((action === "remove" || action === "verify") && method === "POST") {
      const body = await readBody(request);
      if (!body || !validRsn(body.rsn)) return fail(400, "bad_rsn", "Invalid username.");
      const p = await env.DB.prepare(
        `SELECT id FROM participants WHERE competition_id = ? AND rsn = ? COLLATE NOCASE`
      ).bind(id, cleanRsn(body.rsn)).first();
      if (!p) return fail(404, "not_found", "Player not found.");

      if (action === "remove") {
        await env.DB.batch([
          env.DB.prepare(`DELETE FROM finishing_snapshots WHERE participant_id = ?`).bind(p.id),
          env.DB.prepare(`DELETE FROM xp_snapshots WHERE participant_id = ?`).bind(p.id),
          env.DB.prepare(`DELETE FROM player_tracking WHERE participant_id = ?`).bind(p.id),
          env.DB.prepare(`DELETE FROM participants WHERE id = ?`).bind(p.id)
        ]);
      } else {
        if (!["provisional", "needs_review", "verified"].includes(body.status)) {
          return fail(400, "bad_input", "Invalid status.");
        }
        await env.DB.prepare(
          `INSERT INTO player_tracking (participant_id, validation_status) VALUES (?, ?)
           ON CONFLICT(participant_id) DO UPDATE SET validation_status = excluded.validation_status`
        ).bind(p.id, body.status).run();
      }
      clearLeaderboardCache(id);
      return json({ success: true });
    }
  }

  return fail(404, "not_found", "Admin endpoint not found.");
}

// ---------------------------------------------------------------------------
// Web pages
// ---------------------------------------------------------------------------

const PAGE_STYLE = `
:root{--bg:#1e1e1e;--panel:#282828;--line:#3a3a3a;--text:#e6e6e6;--muted:#9a9a9a;
--accent:#ff981f;--good:#4caf50;--warn:#e5b13a;--bad:#e05555}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);
font:15px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:760px;margin:0 auto;padding:24px 16px 64px}
h1{font-size:22px;margin:0 0 4px}h2{font-size:17px;margin:28px 0 10px}
.muted{color:var(--muted)}
.card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px 16px;margin:10px 0}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.pill{font-size:12px;padding:2px 8px;border-radius:99px;border:1px solid var(--line)}
.pill.running{color:var(--good);border-color:var(--good)}
.pill.upcoming{color:var(--accent);border-color:var(--accent)}
.pill.finished{color:var(--muted)}.pill.cancelled{color:var(--bad);border-color:var(--bad)}
table{width:100%;border-collapse:collapse;font-variant-numeric:tabular-nums}
th,td{text-align:left;padding:7px 6px;border-bottom:1px solid var(--line)}
th{color:var(--muted);font-weight:500;font-size:13px}
td.num,th.num{text-align:right}
button,input,select{font:inherit;color:var(--text);background:#333;border:1px solid var(--line);
border-radius:7px;padding:7px 10px}
button{cursor:pointer;background:var(--accent);color:#1b1b1b;border:0;font-weight:600}
button.ghost{background:transparent;color:var(--text);border:1px solid var(--line);font-weight:500}
button.danger{background:transparent;color:var(--bad);border:1px solid var(--bad);font-weight:500}
label{display:block;font-size:13px;color:var(--muted);margin:10px 0 4px}
input,select{width:100%}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:10px}
@media(max-width:560px){.grid{grid-template-columns:1fr}}
a{color:var(--accent)}
.flag{color:var(--warn)}.ok{color:var(--good)}.err{color:var(--bad)}
details summary{cursor:pointer;font-weight:600;font-size:17px;margin:28px 0 10px}
`;

const PAGE_HELPERS = `
const fmt = n => n === null || n === undefined ? "-" : Number(n).toLocaleString();
const when = s => new Date(s * 1000).toLocaleString([], {weekday:"short", day:"numeric", month:"short", hour:"2-digit", minute:"2-digit"});
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const unit = c => c.type === "boss" ? "Kills" : "XP gained";
const store = {
  get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { v === null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (e) {} }
};
`;

const PUBLIC_PAGE = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Clan Competitions</title><style>${PAGE_STYLE}</style></head><body><main>
<h1 id="title">Clan Competitions</h1>
<div class="muted" id="sub"></div>
<form class="card" id="login" hidden onsubmit="signIn(event)">
  <b>Sign in to your clan</b>
  <label for="clan">Clan name</label><input id="clan" autocomplete="organization" required>
  <label for="pw">Clan password</label><input id="pw" type="password" autocomplete="current-password" required>
  <div class="row" style="margin-top:12px"><button>Sign in</button><span class="err" id="loginMsg"></span></div>
</form>
<div id="app" hidden>
  <div id="list"></div>
  <div id="board"></div>
  <details id="finishedBox"><summary>Finished events</summary><div id="finished"></div></details>
</div>
<script>
${PAGE_HELPERS}
let token = store.get("clanToken");
let selected = Number(new URLSearchParams(location.search).get("id")) || null;
let comps = [];

async function api(path, opts = {}) {
  const r = await fetch(path, { ...opts, headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) } });
  const data = await r.json();
  if (r.status === 401 && path !== "/api/clan/login") { signOut("Please sign in again."); }
  return data;
}
function signOut(msg) {
  token = null; store.set("clanToken", null);
  document.getElementById("app").hidden = true;
  document.getElementById("login").hidden = false;
  document.getElementById("title").textContent = "Clan Competitions";
  document.getElementById("sub").innerHTML = "";
  document.getElementById("loginMsg").textContent = msg || "";
}
async function signIn(e) {
  e.preventDefault();
  const r = await api("/api/clan/login", { method: "POST", body: JSON.stringify({ clan: document.getElementById("clan").value, password: document.getElementById("pw").value }) });
  if (!r.success) { document.getElementById("loginMsg").textContent = r.error; return; }
  token = r.token; store.set("clanToken", token); load();
}
async function load() {
  const r = await api("/api/competitions");
  if (!r.success) return;
  document.getElementById("login").hidden = true;
  document.getElementById("app").hidden = false;
  document.getElementById("title").textContent = r.clan.name;
  document.getElementById("sub").innerHTML = 'Clan competitions · <a href="#" onclick="signOut();return false">Sign out</a>';
  comps = r.competitions;
  const active = comps.filter(c => c.status !== "finished");
  const finished = comps.filter(c => c.status === "finished").reverse();
  if (!selected || !comps.some(c => c.id === selected)) {
    selected = (active.find(c => c.status === "running") || active[0] || finished[0] || {}).id || null;
  }
  const card = c => \`<div class="card row" style="cursor:pointer" onclick="pick(\${c.id})">
      <div style="flex:1"><b>\${esc(c.name)}</b>
      <div class="muted">\${c.type === "boss" ? "Boss: " : ""}\${esc(c.metric)} · \${when(c.start_time)} → \${when(c.end_time)}</div></div>
      <span class="pill \${c.status}">\${c.status}</span>
      <span class="muted">\${c.participants} players</span></div>\`;
  document.getElementById("list").innerHTML = active.map(card).join("") || '<div class="card muted">No competitions right now.</div>';
  document.getElementById("finished").innerHTML = finished.map(card).join("") || '<div class="muted">None yet.</div>';
  document.getElementById("finishedBox").hidden = finished.length === 0;
  if (finished.some(c => c.id === selected)) document.getElementById("finishedBox").open = true;
  if (selected) loadBoard(); else document.getElementById("board").innerHTML = "";
}
async function loadBoard() {
  const r = await api("/api/leaderboard?competition_id=" + selected);
  if (!r.success) return;
  const c = r.competition;
  document.getElementById("board").innerHTML = \`
    <h2>\${esc(c.name)} <span class="pill \${c.status}">\${c.status}</span></h2>
    <div class="card"><table><thead><tr><th>#</th><th>Player</th>
    <th class="num">\${unit(c)}</th><th></th></tr></thead><tbody>\${
      r.leaderboard.map(p => \`<tr><td>\${p.rank ?? "-"}</td><td>\${esc(p.rsn)}\${p.tracking_method === "hiscores" ? ' <span class="muted" style="font-size:12px" title="Tracked from the official hiscores, which update when the player logs out">(hiscores)</span>' : ""}</td>
      <td class="num">\${fmt(p.xp_gained)}</td>
      <td>\${p.validation_status === "verified" ? '<span class="ok" title="Matches the official hiscores">✓</span>'
        : p.validation_status === "needs_review" ? '<span class="flag" title="Being checked">?</span>' : ""}</td></tr>\`).join("")
      || '<tr><td colspan="4" class="muted">Nobody has entered yet.</td></tr>'
    }</tbody></table></div>
    <div class="muted">Updates every minute or two while players are online.</div>
    \${c.status === "upcoming" || c.status === "running" ? \`
    <form class="card" onsubmit="requestPlace(event, \${c.id})">
      <b>Not using RuneLite?</b>
      <div class="muted" style="font-size:14px">Ask for a place and a clan admin will approve it. Your \${c.type === "boss" ? "kill count" : "XP"} is then read from the official hiscores,
      which only update when you log out, so <b>log out just before the start and just before the end</b>.</div>
      <label for="reqName">Your RuneScape name</label>
      <div class="row"><input id="reqName" maxlength="12" required style="flex:1;min-width:140px"><button>Request a place</button></div>
      <div id="reqMsg" style="margin-top:6px"></div>
    </form>\` : ""}\`;
}
async function requestPlace(e, id) {
  e.preventDefault();
  const r = await api("/api/request", { method: "POST", body: JSON.stringify({ competition_id: id, rsn: document.getElementById("reqName").value }) });
  const msg = document.getElementById("reqMsg");
  msg.className = r.success ? "ok" : "err";
  msg.textContent = r.success ? r.message : r.error;
}
function pick(id) { selected = id; history.replaceState(null, "", "?id=" + id); loadBoard(); }
if (token) load(); else signOut();
setInterval(() => {
  const typing = document.activeElement && document.activeElement.id === "reqName";
  if (token && selected && !typing) loadBoard();
}, 60000);
</script></main></body></html>`;

const ADMIN_PAGE = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Competition Admin</title><style>${PAGE_STYLE}</style></head><body><main>
<h1>Competition Admin</h1>
<div class="muted" id="who"></div>
<form class="card" id="login" onsubmit="signIn(event)">
  <label for="lclan">Clan name (leave empty for the master password)</label><input id="lclan" autocomplete="organization">
  <label for="key">Admin password</label><input id="key" type="password" autocomplete="current-password" required>
  <div class="row" style="margin-top:12px"><button>Sign in</button><span class="err" id="loginMsg"></span></div>
</form>
<div id="app" hidden>
  <section id="clansSection" hidden>
    <h2>Clans</h2>
    <div id="clans"></div>
    <details class="card"><summary style="font-size:15px;margin:0">Add a clan</summary>
      <label for="cname">Clan name</label><input id="cname" maxlength="40" placeholder="RS Universe">
      <div class="grid">
        <div><label for="cjoin">Member password (shared in your clan chat)</label><input id="cjoin"></div>
        <div><label for="cadmin">Clan admin password (optional, 8+ characters)</label><input id="cadmin"></div>
      </div>
      <div class="row" style="margin-top:12px"><button onclick="createClan()">Create clan</button><span class="muted" id="clanMsg"></span></div>
    </details>
  </section>
  <section id="mineSection" hidden>
    <h2>Your clan</h2>
    <div class="card">
      <label for="newjoin">New member password (everyone will need to sign in again)</label>
      <div class="row"><input id="newjoin" style="flex:1"><button class="ghost" onclick="changeJoin(myClan)">Change</button></div>
      <span class="muted" id="joinMsg"></span>
    </div>
  </section>
  <h2>New competition</h2>
  <div class="card">
    <div id="clanPick"><label for="cclan">Clan</label><select id="cclan"></select></div>
    <label for="name">Name</label><input id="name" maxlength="60" placeholder="Sailing Sprint">
    <div class="grid">
      <div><label for="type">Type</label><select id="type" onchange="typeChanged()"><option value="skill">Skill (XP)</option><option value="boss">Boss (kills)</option></select></div>
      <div id="skillBox"><label for="skill">Skill</label><select id="skill"></select></div>
      <div id="bossBox" hidden><label for="boss">Boss</label><input id="boss" list="bossList" placeholder="Vorkath"><datalist id="bossList"></datalist></div>
    </div>
    <div class="grid">
      <div><label for="start">Starts (your local time)</label><input id="start" type="datetime-local"></div>
      <div><label for="end">Ends (your local time)</label><input id="end" type="datetime-local"></div>
    </div>
    <div class="row" style="margin-top:12px"><button onclick="create()">Create competition</button>
    <span class="muted" id="createMsg"></span></div>
  </div>
  <h2>Competitions</h2>
  <div id="comps"></div>
  <p><a href="#" onclick="signOut();return false">Sign out</a></p>
</div>
<script>
${PAGE_HELPERS}
const SKILLS = ${JSON.stringify(SKILLS)};
const BOSSES = ${JSON.stringify(BOSSES)};
let token = null;
try { token = sessionStorage.getItem("adminToken"); } catch (e) {}
let role = null, myClan = null, clans = [];
document.getElementById("skill").innerHTML = SKILLS.map(s => "<option" + (s === "Sailing" ? " selected" : "") + ">" + s + "</option>").join("");
document.getElementById("bossList").innerHTML = BOSSES.map(b => '<option value="' + esc(b) + '">').join("");

async function api(path, method = "GET", body) {
  const r = await fetch(path, { method, headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) },
    body: body ? JSON.stringify(body) : undefined });
  const data = await r.json();
  if (r.status === 401 && path !== "/api/admin/login") signOut(data.error);
  return data;
}
function saveToken(t) { token = t; try { t ? sessionStorage.setItem("adminToken", t) : sessionStorage.removeItem("adminToken"); } catch (e) {} }
function signOut(msg) {
  saveToken(null);
  document.getElementById("app").hidden = true; document.getElementById("login").hidden = false;
  document.getElementById("who").textContent = ""; document.getElementById("loginMsg").textContent = msg || "";
}
async function signIn(e) {
  e.preventDefault();
  const r = await api("/api/admin/login", "POST", { clan: document.getElementById("lclan").value, password: document.getElementById("key").value });
  if (!r.success) { document.getElementById("loginMsg").textContent = r.error; return; }
  saveToken(r.token); start();
}
async function start() {
  const me = await api("/api/admin/me");
  if (!me.success) return;
  role = me.role; myClan = me.clan ? me.clan.id : null;
  document.getElementById("login").hidden = true; document.getElementById("app").hidden = false;
  document.getElementById("who").textContent = role === "master" ? "Signed in with the master password" : "Signed in as admin of " + me.clan.name;
  document.getElementById("clansSection").hidden = role !== "master";
  document.getElementById("mineSection").hidden = role === "master";
  document.getElementById("clanPick").hidden = role !== "master";
  await loadClans(); refresh();
}
async function loadClans() {
  const r = await api("/api/admin/clans"); if (!r.success) return;
  clans = r.clans;
  document.getElementById("cclan").innerHTML = clans.map(c => '<option value="' + c.id + '">' + esc(c.name) + '</option>').join("");
  if (role !== "master") return;
  document.getElementById("clans").innerHTML = clans.map(c => \`<div class="card"><div class="row"><b style="flex:1">\${esc(c.name)}</b>
    <span class="muted">\${c.competitions} competitions · \${c.has_admin ? "has a clan admin" : "no clan admin"}</span></div>
    <div class="row" style="margin-top:8px">
      <button class="ghost" onclick="changeJoin(\${c.id})">New member password</button>
      <button class="ghost" onclick="changeAdmin(\${c.id})">New clan admin password</button>
      \${r.unassigned_competitions ? \`<button class="ghost" onclick="adopt(\${c.id})">Take \${r.unassigned_competitions} old competitions</button>\` : ""}
    </div></div>\`).join("") || '<div class="muted">No clans yet. Add your first clan below.</div>';
}
async function createClan() {
  const r = await api("/api/admin/clans", "POST", { name: document.getElementById("cname").value,
    join_password: document.getElementById("cjoin").value, admin_password: document.getElementById("cadmin").value || undefined });
  document.getElementById("clanMsg").textContent = r.success
    ? "Created." + (r.adopted_competitions ? " Moved " + r.adopted_competitions + " existing competitions into it." : "")
    : r.error;
  if (r.success) { document.getElementById("cjoin").value = ""; document.getElementById("cadmin").value = ""; }
  await loadClans(); refresh();
}
async function changeJoin(id) {
  const field = document.getElementById("newjoin");
  const pw = role === "master" ? prompt("New member password (everyone in the clan will need to sign in again):") : field.value;
  if (!pw) return;
  const r = await api("/api/admin/clans/" + id + "/passwords", "POST", { join_password: pw });
  if (role === "master") alert(r.success ? "Member password changed." : r.error);
  else { document.getElementById("joinMsg").textContent = r.success ? "Changed." : r.error; field.value = ""; }
}
async function changeAdmin(id) {
  const pw = prompt("New clan admin password (8+ characters):"); if (!pw) return;
  const r = await api("/api/admin/clans/" + id + "/passwords", "POST", { admin_password: pw });
  alert(r.success ? "Clan admin password changed." : r.error); loadClans();
}
async function adopt(id) {
  if (!confirm("Move every competition that has no clan into this clan?")) return;
  const r = await api("/api/admin/clans/" + id + "/adopt", "POST"); alert(r.success ? "Moved " + r.adopted_competitions + "." : r.error);
  loadClans(); refresh();
}
function typeChanged() {
  const boss = document.getElementById("type").value === "boss";
  document.getElementById("skillBox").hidden = boss; document.getElementById("bossBox").hidden = !boss;
}
function renderComps(list) {
  const row = c => \`<div class="card"><div class="row"><b style="flex:1">#\${c.id} \${esc(c.name)}</b>
    \${c.requests ? \`<span class="pill" style="color:var(--warn);border-color:var(--warn)">\${c.requests} to approve</span>\` : ""}
    <span class="pill \${c.status}">\${c.status}</span></div>
    <div class="muted">\${role === "master" ? esc(c.clan_name || "No clan") + " · " : ""}\${c.type === "boss" ? "Boss: " : ""}\${esc(c.metric)} · \${when(c.start_time)} → \${when(c.end_time)} · \${c.participants} players</div>
    <div class="row" style="margin-top:8px">
      <button class="ghost" onclick="toggleDetail(\${c.id})">Players\${c.requests ? " and requests" : ""}</button>
      <button class="ghost" onclick="hiscores(\${c.id})">Run hiscores check</button>
      \${c.status !== "cancelled" && c.status !== "finished" ? \`<button class="ghost" onclick="cancelComp(\${c.id})">Cancel</button>\` : ""}
      <button class="danger" onclick="deleteComp(\${c.id}, \${esc(JSON.stringify(c.name))})">Delete</button>
    </div><div id="detail-\${c.id}"></div></div>\`;
  const active = list.filter(c => c.status === "upcoming" || c.status === "running");
  const other = list.filter(c => !(c.status === "upcoming" || c.status === "running"));
  document.getElementById("comps").innerHTML = (active.map(row).join("") || '<div class="muted">No upcoming or running competitions.</div>')
    + (other.length ? '<details><summary>Finished and cancelled (' + other.length + ')</summary>' + other.map(row).join("") + '</details>' : "");
}
async function refresh() {
  const r = await api("/api/admin/competitions");
  if (!r.success) return;
  renderComps(r.competitions);
  if (openDetail) await detail(openDetail);
}
let openDetail = null;
function toggleDetail(id) {
  if (openDetail === id) { closeDetail(); return; }
  closeDetail(); detail(id);
}
function closeDetail() {
  if (openDetail) { const box = document.getElementById("detail-" + openDetail); if (box) box.innerHTML = ""; }
  openDetail = null;
}
async function create() {
  const s = document.getElementById("start").value, e = document.getElementById("end").value;
  const type = document.getElementById("type").value;
  const body = { name: document.getElementById("name").value, type,
    metric: type === "boss" ? document.getElementById("boss").value : document.getElementById("skill").value,
    clan_id: Number(document.getElementById("cclan").value) || undefined,
    start_time: Math.floor(new Date(s).getTime() / 1000), end_time: Math.floor(new Date(e).getTime() / 1000) };
  const r = await api("/api/admin/competitions", "POST", body);
  document.getElementById("createMsg").textContent = r.success ? "Created #" + r.id : r.error;
  refresh(); loadClans();
}
async function cancelComp(id) {
  if (!confirm("Cancel competition #" + id + "? Players will no longer see it.")) return;
  await api("/api/admin/competitions/" + id + "/cancel", "POST"); refresh();
}
async function deleteComp(id, name) {
  if (!confirm("Permanently delete #" + id + " " + name + " and all its results? This cannot be undone.")) return;
  const r = await api("/api/admin/competitions/" + id, "DELETE");
  if (!r.success) alert(r.error);
  openDetail = null; refresh(); loadClans();
}
async function hiscores(id) {
  const r = await api("/api/admin/competitions/" + id + "/hiscores", "POST");
  alert(r.success ? "Start checks: " + r.start_checked + ", end checks: " + r.end_checked + ", errors: " + r.errors : r.error);
  detail(id);
}
async function setStatus(id, rsn, status) { await api("/api/admin/competitions/" + id + "/verify", "POST", { rsn, status }); detail(id); }
async function addPlayer(id) {
  const field = document.getElementById("add-" + id);
  const r = await api("/api/admin/competitions/" + id + "/add", "POST", { rsn: field.value });
  openDetail = id; await refresh();
  const msg = document.getElementById("addMsg-" + id);
  if (msg) { msg.className = r.success && !r.warning ? "ok" : r.success ? "flag" : "err";
    msg.textContent = r.success ? (r.warning || "Added.") : r.error; }
}
async function approve(id, rsn) {
  const r = await api("/api/admin/competitions/" + id + "/approve", "POST", { rsn });
  openDetail = id; await refresh();
  const msg = document.getElementById("reqMsg-" + id) || document.getElementById("addMsg-" + id);
  if (msg) { msg.className = r.success && !r.warning ? "ok" : r.success ? "flag" : "err";
    msg.textContent = r.success ? (r.warning || rsn + " approved and added.") : r.error; }
}
async function decline(id, rsn) {
  if (!confirm("Decline " + rsn + "'s request?")) return;
  await api("/api/admin/competitions/" + id + "/decline", "POST", { rsn });
  openDetail = id; await refresh();
}
async function removePlayer(id, rsn) {
  if (!confirm("Remove " + rsn + " and all their results from #" + id + "?")) return;
  await api("/api/admin/competitions/" + id + "/remove", "POST", { rsn }); detail(id); refresh();
}
async function detail(id) {
  const r = await api("/api/admin/competitions/" + id);
  const box = document.getElementById("detail-" + id);
  if (!r.success || !box) return;
  openDetail = id;
  const label = r.competition.type === "boss" ? "KC" : "XP";
  const open = r.competition.status === "upcoming" || r.competition.status === "running";
  const requests = (r.requests || []).map(q => {
    const n = esc(JSON.stringify(q.rsn));
    return \`<tr><td>\${esc(q.rsn)}</td><td class="muted">\${when(q.requested_at)}</td>
      <td class="\${q.status === "declined" ? "muted" : "flag"}">\${q.status === "declined" ? "Declined" : "Waiting"}</td>
      <td class="row">\${open ? \`<button class="ghost" onclick="approve(\${id}, \${n})">Approve</button>\` : ""}
      \${q.status !== "declined" ? \`<button class="ghost" onclick="decline(\${id}, \${n})">Decline</button>\` : ""}</td></tr>\`;
  }).join("");
  box.innerHTML = (requests ? \`<div style="margin:14px 0 4px"><b>Requests to join</b>
    <div class="muted" style="font-size:13px">Players without RuneLite who asked on the website. Approving adds them, tracked by the hiscores.</div></div>
    <div style="overflow-x:auto"><table><thead><tr><th>Player</th><th>Asked</th><th>Status</th><th></th></tr></thead>
    <tbody>\${requests}</tbody></table></div><div id="reqMsg-\${id}" style="font-size:13px"></div>\` : "")
    + \`<div class="row" style="margin:14px 0 4px"><b style="flex:1">Players (\${r.participants.length})</b>
    <a href="#" onclick="closeDetail();return false">Close</a></div><div style="overflow-x:auto">
    <table><thead><tr><th>Player</th><th>Tracked by</th><th class="num">Start \${label}</th><th class="num">Latest</th>
    <th class="num">Gained</th><th class="num">Hiscores start</th><th class="num">Hiscores end</th><th>Status</th><th></th></tr></thead>
    <tbody>\${r.participants.map(p => {
      const n = esc(JSON.stringify(p.rsn));
      const gained = p.starting_xp === null ? null : Math.max(p.latest_xp ?? p.starting_xp, p.starting_xp) - p.starting_xp;
      const by = p.tracking_method === "hiscores"
        ? 'Hiscores' + (p.hiscore_note ? '<div class="flag" style="font-size:12px">' + esc(p.hiscore_note) + '</div>' : "")
        : "RuneLite";
      return \`<tr><td>\${esc(p.rsn)}</td><td>\${by}</td><td class="num">\${fmt(p.starting_xp)}</td><td class="num">\${fmt(p.latest_xp)}</td>
      <td class="num">\${fmt(gained)}</td><td class="num">\${fmt(p.hiscore_start_xp)}</td><td class="num">\${fmt(p.hiscore_end_xp)}</td>
      <td class="\${p.validation_status === "verified" ? "ok" : p.validation_status === "needs_review" ? "flag" : ""}">\${p.validation_status}</td>
      <td class="row"><button class="ghost" onclick="setStatus(\${id}, \${n}, 'verified')" title="Mark this result as checked">Mark checked</button>
      <button class="ghost" onclick="removePlayer(\${id}, \${n})">Remove</button></td></tr>\`; }).join("")
      || '<tr><td colspan="9" class="muted">No players.</td></tr>'}</tbody></table></div>
    \${r.competition.status === "upcoming" || r.competition.status === "running" ? \`
    <div class="row" style="margin-top:10px">
      <input id="add-\${id}" maxlength="12" placeholder="RuneScape name" style="flex:1;min-width:140px" aria-label="RuneScape name to add">
      <button class="ghost" onclick="addPlayer(\${id})">Add player (hiscores)</button>
    </div>
    <div class="muted" style="font-size:13px;margin-top:4px">For players without RuneLite. Their scores come from the official hiscores, which only update when they log out.</div>
    <div id="addMsg-\${id}" style="font-size:13px"></div>\` : ""}\`;
}
if (token) start();
</script></main></body></html>`;

function html(body) {
  return new Response(body, {
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Frame-Options": "DENY"
    }
  });
}

// ---------------------------------------------------------------------------
// Router
// ---------------------------------------------------------------------------

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;
    const method = request.method;

    try {
      if (method === "OPTIONS") {
        return new Response(null, {
          headers: {
            "Access-Control-Allow-Origin": "*",
            "Access-Control-Allow-Methods": "GET, POST, DELETE",
            "Access-Control-Allow-Headers": "Content-Type, Authorization"
          }
        });
      }

      if (path === "/" && method === "GET") return html(PUBLIC_PAGE);
      if (path === "/admin" && method === "GET") return html(ADMIN_PAGE);

      if (path === "/api/health" && method === "GET") {
        const result = await env.DB.prepare("SELECT COUNT(*) AS total FROM competitions").first();
        return json({
          success: true,
          version: 4,
          message: "Clan Competitions API is working!",
          server_time_ms: Date.now(),
          competitions: result.total
        });
      }

      if (path === "/api/clan/login" && method === "POST") return handleClanLogin(request, env);
      if (path === "/api/admin/login" && method === "POST") return handleAdminLogin(request, env);
      if (path.startsWith("/api/admin/")) return handleAdmin(request, url, env, ctx);

      // Everything below needs a clan sign-in.
      const who = await authenticate(request, env);
      if (!who || who.role !== "member") {
        return fail(401, "sign_in", "Please sign in to your clan again.");
      }

      if (path === "/api/clan/me" && method === "GET") {
        return json({ success: true, clan: { id: who.clanId, name: who.clanName } });
      }
      if (path === "/api/competitions" && method === "GET") return handleCompetitions(env, who);
      if (path === "/api/registrations" && method === "GET") return handleRegistrations(url, env, who);
      if (path === "/api/register" && method === "POST") return handleRegister(request, env, ctx, who);
      if (path === "/api/request" && method === "POST") return handleEntryRequest(request, env, who);
      if (path === "/api/xp" && method === "POST") return handleReport(request, env, who);
      if (path === "/api/leaderboard" && method === "GET") return handleLeaderboard(url, env, who);

      return fail(404, "not_found", "Endpoint not found.");
    } catch (error) {
      console.error("API error:", error && error.stack || error);
      return fail(500, "server_error", "An internal server error occurred.");
    }
  },

  // Runs on the cron trigger. Set it to every minute ("* * * * *") so the
  // last-hour hiscores checks can happen every couple of minutes.
  async scheduled(event, env, ctx) {
    await syncStatuses(env);
    const used = await runHiscoreTracking(env, HISCORE_BATCH);
    await runHiscoreChecks(env, null, HISCORE_BATCH - used);
  }
};
