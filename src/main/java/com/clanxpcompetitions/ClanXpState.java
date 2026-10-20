package com.clanxpcompetitions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the sidebar needs to know, in one place.
 * The game thread and network threads update it; the sidebar takes
 * a read-only copy (a Snapshot) whenever it redraws.
 */
class ClanXpState
{
	static class Snapshot
	{
		boolean trackingEnabled;
		boolean signedIn;
		String clanName = "";
		String signInMessage;
		boolean signingIn;

		boolean loggedIn;
		String rsn = "";
		List<ApiModels.Competition> competitions = Collections.emptyList();
		boolean competitionsLoaded;
		Set<Long> joinedIds = Collections.emptySet();
		long selectedId = -1;
		long selectedFinishedId = -1;
		boolean finishedOpen;
		Map<Long, ApiModels.LeaderboardResponse> leaderboards = Collections.emptyMap();
		Map<String, Integer> scores = Collections.emptyMap();
		Map<Long, CompetitionTracker.View> progress = Collections.emptyMap();
		String connectionError;
		String joinMessage;
		boolean joining;
		long serverNowMs;

		boolean isFinished(ApiModels.Competition c)
		{
			return serverNowMs >= c.endMs();
		}

		List<ApiModels.Competition> active()
		{
			List<ApiModels.Competition> list = new ArrayList<>();
			for (ApiModels.Competition c : competitions)
			{
				if (!isFinished(c))
				{
					list.add(c);
				}
			}
			return list;
		}

		/** Finished competitions, most recent first. */
		List<ApiModels.Competition> finished()
		{
			List<ApiModels.Competition> list = new ArrayList<>();
			for (ApiModels.Competition c : competitions)
			{
				if (isFinished(c))
				{
					list.add(c);
				}
			}
			list.sort((a, b) -> Long.compare(b.endTime, a.endTime));
			return list;
		}

		ApiModels.Competition find(List<ApiModels.Competition> list, long id)
		{
			for (ApiModels.Competition c : list)
			{
				if (c.id == id)
				{
					return c;
				}
			}
			return list.isEmpty() ? null : list.get(0);
		}

		ApiModels.LeaderboardResponse leaderboardFor(long competitionId)
		{
			return leaderboards.get(competitionId);
		}
	}

	private boolean trackingEnabled;
	private boolean signedIn;
	private String clanName = "";
	private String signInMessage;
	private boolean signingIn;

	private boolean loggedIn;
	private String rsn = "";
	private List<ApiModels.Competition> competitions = new ArrayList<>();
	private boolean competitionsLoaded;
	private Set<Long> joinedIds = new HashSet<>();
	private long selectedId = -1;
	private long selectedFinishedId = -1;
	private boolean finishedOpen;
	private final Map<Long, ApiModels.LeaderboardResponse> leaderboards = new HashMap<>();
	private final Map<String, Integer> scores = new HashMap<>();
	private String connectionError;
	private String joinMessage;
	private boolean joining;

	synchronized Snapshot snapshot(Map<Long, CompetitionTracker.View> progress, long serverNowMs)
	{
		Snapshot s = new Snapshot();
		s.trackingEnabled = trackingEnabled;
		s.signedIn = signedIn;
		s.clanName = clanName;
		s.signInMessage = signInMessage;
		s.signingIn = signingIn;
		s.loggedIn = loggedIn;
		s.rsn = rsn;
		s.competitions = new ArrayList<>(competitions);
		s.competitionsLoaded = competitionsLoaded;
		s.joinedIds = new HashSet<>(joinedIds);
		s.selectedId = selectedId;
		s.selectedFinishedId = selectedFinishedId;
		s.finishedOpen = finishedOpen;
		s.leaderboards = new HashMap<>(leaderboards);
		s.scores = new HashMap<>(scores);
		s.progress = progress;
		s.connectionError = connectionError;
		s.joinMessage = joinMessage;
		s.joining = joining;
		s.serverNowMs = serverNowMs;
		return s;
	}

	// ----- Online tracking and clan sign-in -----

	synchronized boolean isTrackingEnabled()
	{
		return trackingEnabled;
	}

	synchronized void setTrackingEnabled(boolean enabled)
	{
		trackingEnabled = enabled;
		if (!enabled)
		{
			clearClanData();
		}
	}

	synchronized boolean isSignedIn()
	{
		return signedIn;
	}

	synchronized boolean isOnline()
	{
		return trackingEnabled && signedIn;
	}

	synchronized void setSignedIn(String clan)
	{
		signedIn = true;
		clanName = clan == null ? "" : clan;
		signInMessage = null;
		signingIn = false;
	}

	synchronized void setSignedOut(String message)
	{
		signedIn = false;
		clanName = "";
		signInMessage = message;
		signingIn = false;
		clearClanData();
	}

	synchronized void setSigningIn(boolean value, String message)
	{
		signingIn = value;
		signInMessage = message;
	}

	synchronized void setClanName(String name)
	{
		if (name != null && !name.isEmpty())
		{
			clanName = name;
		}
	}

	private void clearClanData()
	{
		competitions = new ArrayList<>();
		competitionsLoaded = false;
		joinedIds = new HashSet<>();
		leaderboards.clear();
		connectionError = null;
		joinMessage = null;
	}

	// ----- The player -----

	synchronized void setPlayer(String newRsn)
	{
		loggedIn = true;
		if (!newRsn.equalsIgnoreCase(rsn))
		{
			rsn = newRsn;
			joinedIds = new HashSet<>();
			scores.clear();
			joinMessage = null;
		}
	}

	synchronized void setLoggedOut()
	{
		loggedIn = false;
	}

	synchronized String getRsn()
	{
		return rsn;
	}

	synchronized boolean isLoggedIn()
	{
		return loggedIn;
	}

	/** XP in a skill or kill count of a boss, for the sidebar. */
	synchronized boolean setScore(String metric, int value)
	{
		Integer old = scores.put(metric, value);
		return old == null || old != value;
	}

	// ----- Competitions -----

	synchronized List<ApiModels.Competition> getCompetitions()
	{
		return new ArrayList<>(competitions);
	}

	synchronized void setCompetitions(List<ApiModels.Competition> list)
	{
		competitions = list == null ? new ArrayList<>() : new ArrayList<>(list);
		competitionsLoaded = true;
		connectionError = null;
	}

	synchronized Set<Long> getJoinedIds()
	{
		return new HashSet<>(joinedIds);
	}

	synchronized void setJoinedIds(String forRsn, List<Long> ids)
	{
		if (!forRsn.equalsIgnoreCase(rsn))
		{
			return; // An answer for a character who has since logged out.
		}
		joinedIds = ids == null ? new HashSet<>() : new HashSet<>(ids);
	}

	synchronized void addJoined(long competitionId)
	{
		joinedIds.add(competitionId);
	}

	synchronized long getSelectedId()
	{
		return selectedId;
	}

	synchronized void setSelectedId(long id)
	{
		selectedId = id;
	}

	synchronized long getSelectedFinishedId()
	{
		return selectedFinishedId;
	}

	synchronized void setSelectedFinishedId(long id)
	{
		selectedFinishedId = id;
	}

	synchronized boolean isFinishedOpen()
	{
		return finishedOpen;
	}

	synchronized void setFinishedOpen(boolean open)
	{
		finishedOpen = open;
	}

	synchronized void setLeaderboard(ApiModels.LeaderboardResponse board)
	{
		leaderboards.put(board.competitionId, board);
	}

	synchronized void setConnectionError(String error)
	{
		connectionError = error;
	}

	synchronized void setJoining(boolean value, String message)
	{
		joining = value;
		joinMessage = message;
	}
}
