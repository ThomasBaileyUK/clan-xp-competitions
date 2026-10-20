package com.clanxpcompetitions;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Clan Competitions",
	description = "Join your clan's skill and boss competitions and follow a live leaderboard",
	tags = {"clan", "xp", "competition", "leaderboard", "skilling", "boss", "kc", "sailing", "event"}
)
public class ClanXpPlugin extends Plugin
{
	private static final String TOKEN_KEY = "clanToken";
	private static final String CLAN_NAME_KEY = "clanName";
	private static final String SELECTED_KEY = "selectedCompetition";
	private static final String FINISHED_SELECTED_KEY = "selectedFinishedCompetition";
	private static final String FINISHED_OPEN_KEY = "finishedOpen";

	private static final long COMPETITIONS_EVERY_MS = 5 * 60_000L;
	private static final long COMPETITIONS_NEAR_START_OR_END_MS = 60_000L;
	private static final long REGISTRATIONS_EVERY_MS = 5 * 60_000L;
	private static final long LEADERBOARD_EVERY_MS = 60_000L;
	private static final long FINISHED_LEADERBOARD_EVERY_MS = 5 * 60_000L;

	/**
	 * Matches the game's kill-count messages, for example
	 * "Your Vorkath kill count is: 123." or
	 * "Your completed Chambers of Xeric count is: 45."
	 */
	static final Pattern KILL_COUNT = Pattern.compile(
		"Your (?:completion count for |subdued |completed )?(.+?) (?:(?:kill|harvest|lap|completion) )?(?:count )?is: ([\\d,]+)");

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ClanXpConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClanXpApi api;

	private final ClanXpState state = new ClanXpState();
	private CompetitionTracker tracker;
	private volatile ClanXpPanel panel;
	private NavigationButton navButton;

	/** The latest kill count seen in chat for each boss (key from bossKey). */
	private final Map<String, CompetitionTracker.Reading> killCounts = new ConcurrentHashMap<>();

	private final AtomicBoolean refreshQueued = new AtomicBoolean();
	private volatile boolean running;
	private volatile boolean panelActive;

	private volatile long lastCompetitionsFetch;
	private volatile long lastRegistrationsFetch;
	private final Map<Long, Long> lastLeaderboardFetch = new ConcurrentHashMap<>();
	private final AtomicBoolean competitionsInFlight = new AtomicBoolean();
	private final AtomicBoolean registrationsInFlight = new AtomicBoolean();
	private final Set<Long> leaderboardsInFlight = ConcurrentHashMap.newKeySet();

	@Provides
	ClanXpConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ClanXpConfig.class);
	}

	@Override
	protected void startUp()
	{
		running = true;
		tracker = new CompetitionTracker(api, configManager, this::refreshPanel,
			() -> lastRegistrationsFetch = 0);

		api.setOnSignedOut(() -> signOut("Your clan's password has changed. Please sign in again."));

		state.setTrackingEnabled(config.onlineTracking());
		String token = configManager.getConfiguration(ClanXpConfig.GROUP, TOKEN_KEY);
		api.setToken(token);
		if (api.hasToken())
		{
			state.setSignedIn(configManager.getConfiguration(ClanXpConfig.GROUP, CLAN_NAME_KEY));
		}
		state.setSelectedId(readLong(SELECTED_KEY));
		state.setSelectedFinishedId(readLong(FINISHED_SELECTED_KEY));
		state.setFinishedOpen("true".equals(configManager.getConfiguration(ClanXpConfig.GROUP, FINISHED_OPEN_KEY)));

		panel = new ClanXpPanel(this);

		BufferedImage icon = ImageUtil.loadImageResource(ClanXpPlugin.class, "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Clan Competitions")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		refreshPanel();
		poll();
	}

	@Override
	protected void shutDown()
	{
		if (tracker != null)
		{
			tracker.flush(true);
		}

		running = false;
		clientToolbar.removeNavigation(navButton);
		navButton = null;

		if (panel != null)
		{
			panel.shutDown();
			panel = null;
		}

		state.setLoggedOut();
		state.setTrackingEnabled(false);
		killCounts.clear();
		lastCompetitionsFetch = 0;
		lastRegistrationsFetch = 0;
		lastLeaderboardFetch.clear();
	}

	// ------------------------------------------------------------------
	// Game events
	// ------------------------------------------------------------------

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player player = client.getLocalPlayer();
		if (client.getGameState() != GameState.LOGGED_IN || player == null || player.getName() == null)
		{
			return;
		}

		String rsn = cleanName(player.getName());
		if (rsn.isEmpty())
		{
			return;
		}

		if (!state.isLoggedIn() || !rsn.equalsIgnoreCase(state.getRsn()))
		{
			if (!rsn.equalsIgnoreCase(state.getRsn()))
			{
				killCounts.clear();
			}
			state.setPlayer(rsn);
			tracker.setPlayer(rsn);
			lastRegistrationsFetch = 0;
			if (state.isOnline())
			{
				fetchRegistrations();
			}
			refreshPanel();
		}

		// Every account has at least level 10 Hitpoints, so a lower number
		// means the stats haven't arrived from the server yet.
		if (client.getRealSkillLevel(Skill.HITPOINTS) < 10)
		{
			return;
		}

		List<ApiModels.Competition> competitions = state.getCompetitions();
		boolean changed = false;
		for (ApiModels.Competition c : competitions)
		{
			if (!c.isBoss())
			{
				int xp = xpFor(c.metricName());
				if (xp >= 0 && state.setScore(c.metricName(), xp))
				{
					changed = true;
				}
			}
		}

		if (state.isOnline())
		{
			tracker.tick(competitions, state.getJoinedIds(), this::readingFor);
		}

		if (changed)
		{
			refreshPanel();
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		Matcher matcher = KILL_COUNT.matcher(Text.removeTags(event.getMessage()));
		if (!matcher.find())
		{
			return;
		}

		int killCount;
		try
		{
			killCount = Integer.parseInt(matcher.group(2).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return;
		}

		String key = bossKey(matcher.group(1));
		// The kill that produced this message is part of the count, so the
		// count before it is one lower.
		killCounts.put(key, new CompetitionTracker.Reading(killCount, killCount - 1, api.serverNow()));

		for (ApiModels.Competition c : state.getCompetitions())
		{
			if (c.isBoss() && bossKey(c.metricName()).equals(key))
			{
				state.setScore(c.metricName(), killCount);
			}
		}
		refreshPanel();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState gameState = event.getGameState();

		// Loading screens (crossing map areas) are NOT a logout.
		if (gameState == GameState.LOGIN_SCREEN || gameState == GameState.HOPPING)
		{
			tracker.flush(true);
		}

		if (gameState == GameState.LOGIN_SCREEN)
		{
			state.setLoggedOut();
			refreshPanel();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ClanXpConfig.GROUP.equals(event.getGroup())
			|| !ClanXpConfig.ONLINE_TRACKING_KEY.equals(event.getKey()))
		{
			return;
		}

		boolean enabled = config.onlineTracking();
		state.setTrackingEnabled(enabled);

		if (enabled)
		{
			tracker.setPlayer(state.getRsn());
			refreshNow();
		}
		else
		{
			tracker.reset();
		}

		refreshPanel();
	}

	/** Background timer: keeps competitions, sign-ups and leaderboards fresh. */
	@Schedule(period = 10, unit = ChronoUnit.SECONDS, asynchronous = true)
	public void poll()
	{
		if (!running || !state.isOnline())
		{
			return;
		}

		long now = System.currentTimeMillis();
		long competitionsEvery = nearStartOrEnd() ? COMPETITIONS_NEAR_START_OR_END_MS : COMPETITIONS_EVERY_MS;

		if (now - lastCompetitionsFetch >= competitionsEvery)
		{
			fetchCompetitions();
		}

		if (now - lastRegistrationsFetch >= REGISTRATIONS_EVERY_MS)
		{
			fetchRegistrations();
		}

		if (panelActive)
		{
			fetchLeaderboard(state.getSelectedId(), LEADERBOARD_EVERY_MS);
			if (state.isFinishedOpen())
			{
				fetchLeaderboard(state.getSelectedFinishedId(), FINISHED_LEADERBOARD_EVERY_MS);
			}
		}

		tracker.flush(false);
		refreshPanel();
	}

	// ------------------------------------------------------------------
	// Actions from the sidebar
	// ------------------------------------------------------------------

	/** Called after the player has agreed to the privacy warning. */
	void signIn(String clan, String password)
	{
		if (clan.trim().isEmpty() || password.isEmpty())
		{
			state.setSigningIn(false, "Enter your clan name and password.");
			refreshPanel();
			return;
		}

		if (!config.onlineTracking())
		{
			turnOnTracking();
		}

		state.setSigningIn(true, null);
		refreshPanel();

		api.signIn(clan.trim(), password, (result, error) ->
		{
			if (result != null && result.success && result.token != null)
			{
				String name = result.clan != null ? result.clan.name : clan.trim();
				configManager.setConfiguration(ClanXpConfig.GROUP, TOKEN_KEY, result.token);
				configManager.setConfiguration(ClanXpConfig.GROUP, CLAN_NAME_KEY, name);
				api.setToken(result.token);
				state.setSignedIn(name);
				tracker.setPlayer(state.getRsn());
				refreshNow();
			}
			else
			{
				String message = result != null && result.error != null ? result.error : error;
				state.setSigningIn(false, message);
			}
			refreshPanel();
		});
	}

	void signOut(String message)
	{
		configManager.unsetConfiguration(ClanXpConfig.GROUP, TOKEN_KEY);
		configManager.unsetConfiguration(ClanXpConfig.GROUP, CLAN_NAME_KEY);
		api.setToken(null);
		tracker.reset();
		tracker.setPlayer(state.getRsn());
		state.setSignedOut(message);
		lastLeaderboardFetch.clear();
		refreshPanel();
	}

	void select(long competitionId)
	{
		if (competitionId == state.getSelectedId())
		{
			return;
		}
		state.setSelectedId(competitionId);
		configManager.setConfiguration(ClanXpConfig.GROUP, SELECTED_KEY, competitionId);
		fetchLeaderboard(competitionId, 0);
		refreshPanel();
	}

	void selectFinished(long competitionId)
	{
		if (competitionId == state.getSelectedFinishedId())
		{
			return;
		}
		state.setSelectedFinishedId(competitionId);
		configManager.setConfiguration(ClanXpConfig.GROUP, FINISHED_SELECTED_KEY, competitionId);
		fetchLeaderboard(competitionId, 0);
		refreshPanel();
	}

	void setFinishedOpen(boolean open)
	{
		state.setFinishedOpen(open);
		configManager.setConfiguration(ClanXpConfig.GROUP, FINISHED_OPEN_KEY, open);
		if (open)
		{
			fetchLeaderboard(state.getSelectedFinishedId(), 15_000L);
		}
		refreshPanel();
	}

	void join(long competitionId)
	{
		String rsn = state.getRsn();
		if (!state.isLoggedIn() || rsn.isEmpty())
		{
			return;
		}

		state.setJoining(true, null);
		refreshPanel();

		api.register(competitionId, rsn, (result, error) ->
		{
			if (result != null && result.success)
			{
				state.addJoined(competitionId);
				state.setJoining(false, null);
				fetchLeaderboard(competitionId, 0);
			}
			else
			{
				String message = result != null && result.error != null ? result.error : error;
				state.setJoining(false, message);
			}
			refreshPanel();
		});
	}

	void refreshNow()
	{
		lastCompetitionsFetch = 0;
		lastRegistrationsFetch = 0;
		lastLeaderboardFetch.clear();
		poll();
	}

	/** A competition just started or ended (the sidebar's countdown hit zero). */
	void boundaryPassed()
	{
		chooseSelection(state.getCompetitions());
		lastCompetitionsFetch = 0;
		refreshPanel();
	}

	void turnOnTracking()
	{
		configManager.setConfiguration(ClanXpConfig.GROUP, ClanXpConfig.ONLINE_TRACKING_KEY, true);
	}

	void openWebsite(long competitionId)
	{
		LinkBrowser.browse(ClanXpApi.BASE_URL + (competitionId > 0 ? "/?id=" + competitionId : "/"));
	}

	void setPanelActive(boolean active)
	{
		panelActive = active;
		if (active)
		{
			fetchLeaderboard(state.getSelectedId(), 15_000L);
			if (state.isFinishedOpen())
			{
				fetchLeaderboard(state.getSelectedFinishedId(), 15_000L);
			}
		}
	}

	long serverNow()
	{
		return api.serverNow();
	}

	ClanXpState.Snapshot snapshot()
	{
		return state.snapshot(tracker == null ? Collections.emptyMap() : tracker.views(), api.serverNow());
	}

	// ------------------------------------------------------------------
	// Fetching from the server
	// ------------------------------------------------------------------

	private void fetchCompetitions()
	{
		if (!state.isOnline() || !competitionsInFlight.compareAndSet(false, true))
		{
			return;
		}
		lastCompetitionsFetch = System.currentTimeMillis();

		api.getCompetitions((result, error) ->
		{
			competitionsInFlight.set(false);
			if (!running)
			{
				return;
			}

			if (result != null && result.competitions != null)
			{
				if (result.clan != null)
				{
					state.setClanName(result.clan.name);
				}
				state.setCompetitions(result.competitions);
				chooseSelection(result.competitions);
			}
			else
			{
				state.setConnectionError(error);
				// Try again sooner than usual.
				lastCompetitionsFetch = System.currentTimeMillis() - COMPETITIONS_EVERY_MS + 30_000L;
			}
			refreshPanel();
		});
	}

	private void fetchRegistrations()
	{
		String rsn = state.getRsn();
		if (!state.isOnline() || !state.isLoggedIn() || rsn.isEmpty()
			|| !registrationsInFlight.compareAndSet(false, true))
		{
			return;
		}
		lastRegistrationsFetch = System.currentTimeMillis();

		api.getRegistrations(rsn, (result, error) ->
		{
			registrationsInFlight.set(false);
			if (!running)
			{
				return;
			}

			if (result != null)
			{
				state.setJoinedIds(rsn, result.competitionIds);
			}
			else
			{
				lastRegistrationsFetch = System.currentTimeMillis() - REGISTRATIONS_EVERY_MS + 30_000L;
			}
			refreshPanel();
		});
	}

	/** Fetches a leaderboard unless it was fetched less than minGapMs ago. */
	private void fetchLeaderboard(long id, long minGapMs)
	{
		if (id < 1 || !state.isOnline())
		{
			return;
		}

		long now = System.currentTimeMillis();
		Long last = lastLeaderboardFetch.get(id);
		if (last != null && now - last < minGapMs)
		{
			return;
		}
		if (!leaderboardsInFlight.add(id))
		{
			return;
		}
		lastLeaderboardFetch.put(id, now);

		api.getLeaderboard(id, (result, error) ->
		{
			leaderboardsInFlight.remove(id);
			if (!running)
			{
				return;
			}

			if (result != null)
			{
				result.competitionId = id;
				state.setLeaderboard(result);
			}
			refreshPanel();
		});
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/** Redraws the sidebar soon. Many calls in a row are merged into one redraw. */
	private void refreshPanel()
	{
		if (panel == null || !refreshQueued.compareAndSet(false, true))
		{
			return;
		}

		SwingUtilities.invokeLater(() ->
		{
			refreshQueued.set(false);
			ClanXpPanel current = panel;
			if (current != null && running)
			{
				current.render(snapshot());
			}
		});
	}

	/**
	 * Keeps the two dropdowns pointing at something sensible. A competition
	 * that has just ended moves into Finished events and is shown there.
	 */
	private void chooseSelection(List<ApiModels.Competition> competitions)
	{
		long now = api.serverNow();
		Set<Long> joined = state.getJoinedIds();

		ApiModels.Competition current = null;
		ApiModels.Competition bestActive = null;
		int bestScore = Integer.MAX_VALUE;
		ApiModels.Competition latestFinished = null;
		boolean finishedSelectionValid = false;

		for (ApiModels.Competition c : competitions)
		{
			boolean finished = now >= c.endMs();

			if (c.id == state.getSelectedId())
			{
				current = c;
			}
			if (finished)
			{
				if (latestFinished == null || c.endTime > latestFinished.endTime)
				{
					latestFinished = c;
				}
				if (c.id == state.getSelectedFinishedId())
				{
					finishedSelectionValid = true;
				}
				continue;
			}

			boolean started = now >= c.startMs();
			int score = started && joined.contains(c.id) ? 0 : started ? 1 : 2;
			if (bestActive == null || score < bestScore
				|| (score == bestScore && c.startTime < bestActive.startTime))
			{
				bestActive = c;
				bestScore = score;
			}
		}

		if (current != null && now >= current.endMs())
		{
			// The competition being watched has just ended: show its results.
			state.setSelectedFinishedId(current.id);
			configManager.setConfiguration(ClanXpConfig.GROUP, FINISHED_SELECTED_KEY, current.id);
			setFinishedOpen(true);
			finishedSelectionValid = true;
			current = null;
		}

		if (current == null)
		{
			long id = bestActive == null ? -1 : bestActive.id;
			state.setSelectedId(id);
			fetchLeaderboard(id, 0);
		}

		if (!finishedSelectionValid)
		{
			state.setSelectedFinishedId(latestFinished == null ? -1 : latestFinished.id);
		}
	}

	private CompetitionTracker.Reading readingFor(ApiModels.Competition c)
	{
		if (c.isBoss())
		{
			return killCounts.get(bossKey(c.metricName()));
		}

		int xp = xpFor(c.metricName());
		return xp < 0 ? null : new CompetitionTracker.Reading(xp, xp, api.serverNow());
	}

	private boolean nearStartOrEnd()
	{
		long now = api.serverNow();
		for (ApiModels.Competition c : state.getCompetitions())
		{
			if (Math.abs(c.startMs() - now) < 3 * 60_000L || Math.abs(c.endMs() - now) < 3 * 60_000L)
			{
				return true;
			}
		}
		return false;
	}

	private long readLong(String key)
	{
		String saved = configManager.getConfiguration(ClanXpConfig.GROUP, key);
		try
		{
			return saved == null ? -1 : Long.parseLong(saved);
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/** XP in a skill by its name, e.g. "Sailing". -1 if unknown. */
	private int xpFor(String skillName)
	{
		Skill skill = skillByName(skillName);
		return skill == null ? -1 : client.getSkillExperience(skill);
	}

	static Skill skillByName(String name)
	{
		if (name == null)
		{
			return null;
		}
		for (Skill skill : Skill.values())
		{
			if (skill.getName().equalsIgnoreCase(name.trim()))
			{
				return skill;
			}
		}
		return null;
	}

	/**
	 * Turns a boss name into a simple key so the game's wording and the
	 * hiscores' wording match: "The Gauntlet" and "Gauntlet" both become
	 * "gauntlet", "Barrows Chests" and "Barrows chest" both "barrowschest".
	 */
	static String bossKey(String name)
	{
		String key = name.toLowerCase(Locale.ROOT).trim();
		if (key.startsWith("the "))
		{
			key = key.substring(4);
		}
		key = key.replaceAll("[^a-z0-9]", "");
		if (key.endsWith("s"))
		{
			key = key.substring(0, key.length() - 1);
		}
		return key;
	}

	/** RuneLite gives spaces in names as non-breaking spaces. */
	static String cleanName(String name)
	{
		return name.replace(' ', ' ').replace('_', ' ').trim().replaceAll("\\s+", " ");
	}
}
