package com.clanxpcompetitions;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Sends the player's score to the clan server for every competition they
 * have joined. The score is XP for skill competitions and kill count for
 * boss competitions; the rules are the same for both.
 *
 * - The first score seen after a competition starts is the starting score.
 *   (For bosses that is the kill count before the first kill.)
 * - While XP is changing, an update is sent about once a minute
 *   (every 15 seconds in the last 5 minutes).
 * - The last XP seen before the end is sent after the end, so a slow
 *   connection at the finish line does not lose anything.
 * - Failed updates are retried.
 *
 * All times here are server time (see ClanXpApi.serverNow).
 */
@Slf4j
class CompetitionTracker
{
	private static final long NORMAL_INTERVAL_MS = 60_000L;
	private static final long FINAL_PHASE_MS = 5 * 60_000L;
	private static final long FINAL_INTERVAL_MS = 15_000L;
	private static final long RETRY_MS = 20_000L;
	private static final long AFTER_END_RETRY_MS = 5_000L;

	// The server accepts late reports for 120 seconds after the end.
	private static final long AFTER_END_LIMIT_MS = 110_000L;

	/** One reading of a score, e.g. "Sailing XP is 1,234,567" or "Vorkath KC is 120". */
	static class Reading
	{
		final int value;
		final int startValue;
		final long atMs;

		/**
		 * @param value      the score now
		 * @param startValue the score to use as the start if this is the first reading
		 * @param atMs       when it was seen, in server time
		 */
		Reading(int value, int startValue, long atMs)
		{
			this.value = value;
			this.startValue = startValue;
			this.atMs = atMs;
		}
	}

	/** What the sidebar shows about one competition. */
	static class View
	{
		int startXp = -1;
		int lastXp = -1;
		long lastSuccessLocalMs;
		boolean unsent;
		String problem;
	}

	private static class Progress
	{
		ApiModels.Competition competition;
		int startXp = -1;
		int serverStartXp = -1;
		int lastXp = -1;
		long lastCaptureMs;
		int lastSentXp = -1;
		long lastAttemptMs;
		long lastSuccessLocalMs;
		boolean sending;
		boolean failed;
		boolean done;
		String problem;
	}

	private final ClanXpApi api;
	private final ConfigManager configManager;
	private final Runnable onChange;
	private final Runnable onRegistrationLost;

	private final Map<Long, Progress> progress = new HashMap<>();
	private String rsn = "";

	CompetitionTracker(ClanXpApi api, ConfigManager configManager,
		Runnable onChange, Runnable onRegistrationLost)
	{
		this.api = api;
		this.configManager = configManager;
		this.onChange = onChange;
		this.onRegistrationLost = onRegistrationLost;
	}

	/** Called when a different character logs in. */
	synchronized void setPlayer(String newRsn)
	{
		if (!newRsn.equalsIgnoreCase(rsn))
		{
			progress.clear();
			rsn = newRsn;
		}
	}

	synchronized void reset()
	{
		progress.clear();
		rsn = "";
	}

	/** Runs every game tick while logged in and stats are loaded. */
	synchronized void tick(Collection<ApiModels.Competition> competitions, Set<Long> joined,
		Function<ApiModels.Competition, Reading> source)
	{
		if (rsn.isEmpty() || !api.isClockKnown())
		{
			return;
		}

		long now = api.serverNow();

		for (ApiModels.Competition c : competitions)
		{
			if (!joined.contains(c.id) || "cancelled".equals(c.status) || now < c.startMs())
			{
				continue;
			}

			Progress p = progressFor(c);
			if (p.done)
			{
				continue;
			}

			if (now < c.endMs())
			{
				Reading reading = source.apply(c);
				// Ignore readings from before the start (e.g. a kill made earlier).
				if (reading == null || reading.value < 0 || reading.atMs < c.startMs())
				{
					continue;
				}
				int xp = reading.value;

				if (p.startXp < 0)
				{
					p.startXp = Math.max(0, Math.min(reading.startValue, xp));
					saveStartXp(c.id, p.startXp);
					onChange.run();
				}

				if (xp != p.lastXp)
				{
					p.lastXp = xp;
					onChange.run();
				}
				p.lastCaptureMs = now;

				long wait = p.failed ? RETRY_MS
					: c.endMs() - now <= FINAL_PHASE_MS ? FINAL_INTERVAL_MS
					: NORMAL_INTERVAL_MS;

				if (p.lastXp != p.lastSentXp && !p.sending && now - p.lastAttemptMs >= wait)
				{
					send(p, now);
				}
			}
			else
			{
				sendFinal(p, now);
			}
		}
	}

	/**
	 * Sends anything not yet sent. Runs on logout and from the
	 * background timer, so it also works while logged out.
	 */
	synchronized void flush(boolean force)
	{
		if (!api.isClockKnown())
		{
			return;
		}

		long now = api.serverNow();

		for (Progress p : progress.values())
		{
			if (p.done || p.sending || p.lastXp < 0 || p.lastXp == p.lastSentXp)
			{
				continue;
			}

			if (now >= p.competition.endMs())
			{
				sendFinal(p, now);
			}
			else if (force || now - p.lastAttemptMs >= RETRY_MS)
			{
				send(p, now);
			}
		}
	}

	synchronized Map<Long, View> views()
	{
		Map<Long, View> result = new HashMap<>();
		for (Map.Entry<Long, Progress> entry : progress.entrySet())
		{
			Progress p = entry.getValue();
			View v = new View();
			v.startXp = p.serverStartXp >= 0 ? p.serverStartXp : p.startXp;
			v.lastXp = p.lastXp;
			v.lastSuccessLocalMs = p.lastSuccessLocalMs;
			v.unsent = p.lastXp >= 0 && p.lastXp != p.lastSentXp && !p.done;
			v.problem = p.problem;
			result.put(entry.getKey(), v);
		}
		return result;
	}

	private void sendFinal(Progress p, long now)
	{
		boolean nothingToSend = p.lastXp < 0 || p.lastXp == p.lastSentXp;
		boolean tooLate = now >= p.competition.endMs() + AFTER_END_LIMIT_MS;

		if (nothingToSend || tooLate)
		{
			p.done = true;
			return;
		}

		if (!p.sending && now - p.lastAttemptMs >= AFTER_END_RETRY_MS)
		{
			send(p, now);
		}
	}

	private void send(Progress p, long now)
	{
		final int xp = p.lastXp;
		final long competitionId = p.competition.id;

		p.sending = true;
		p.lastAttemptMs = now;

		api.reportXp(competitionId, rsn, xp, p.startXp, p.lastCaptureMs, (result, error) ->
		{
			synchronized (CompetitionTracker.this)
			{
				p.sending = false;

				if (result != null && result.success)
				{
					p.lastSentXp = Math.max(p.lastSentXp, xp);
					p.lastSuccessLocalMs = System.currentTimeMillis();
					if (result.startingXp != null)
					{
						p.serverStartXp = result.startingXp.intValue();
					}
					p.failed = false;
					p.problem = null;
				}
				else if (result != null)
				{
					handleRefusal(p, result);
				}
				else
				{
					p.failed = true;
					p.problem = "Offline - will retry";
				}
			}

			onChange.run();
		});
	}

	private void handleRefusal(Progress p, ApiModels.Result result)
	{
		String code = result.code == null ? "" : result.code;
		log.debug("Clan Competitions report refused: {} {}", code, result.error);

		switch (code)
		{
			case "ended":
			case "cancelled":
				p.done = true;
				p.problem = null;
				break;
			case "not_registered":
				p.failed = true;
				p.problem = "Not registered on the server";
				onRegistrationLost.run();
				break;
			case "below_start":
				p.done = true;
				p.problem = "Score doesn't match the start - ask a clan admin";
				break;
			default:
				p.failed = true;
				p.problem = result.error != null ? result.error : "Update refused - will retry";
				break;
		}
	}

	private Progress progressFor(ApiModels.Competition c)
	{
		Progress p = progress.get(c.id);
		if (p == null)
		{
			p = new Progress();
			p.startXp = loadStartXp(c.id);
			progress.put(c.id, p);
		}
		p.competition = c;
		return p;
	}

	// The starting XP is saved so it survives a client restart before
	// the first update reaches the server.

	private String startKey(long competitionId)
	{
		return "start_" + competitionId + "_" + rsn.toLowerCase().replaceAll("[^a-z0-9]", "_");
	}

	private int loadStartXp(long competitionId)
	{
		String saved = configManager.getConfiguration(ClanXpConfig.GROUP, startKey(competitionId));
		try
		{
			return saved == null ? -1 : Integer.parseInt(saved);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private void saveStartXp(long competitionId, int xp)
	{
		configManager.setConfiguration(ClanXpConfig.GROUP, startKey(competitionId), xp);
	}
}
