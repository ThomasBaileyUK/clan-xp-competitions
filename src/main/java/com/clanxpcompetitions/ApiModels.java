package com.clanxpcompetitions;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * The shapes of the JSON the clan server sends back.
 * Gson fills these in automatically.
 */
final class ApiModels
{
	private ApiModels()
	{
	}

	static class Clan
	{
		long id;
		String name;
	}

	static class Competition
	{
		long id;
		String name;

		// "skill" or "boss"
		String type;

		// The skill or boss name, e.g. "Sailing" or "Vorkath".
		String metric;

		// Older servers only send this.
		String skill;

		@SerializedName("start_time")
		long startTime;

		@SerializedName("end_time")
		long endTime;

		// upcoming, running, finished or cancelled
		String status;

		int participants;

		long startMs()
		{
			return startTime * 1000L;
		}

		long endMs()
		{
			return endTime * 1000L;
		}

		boolean isBoss()
		{
			return "boss".equals(type);
		}

		String metricName()
		{
			return metric != null ? metric : skill;
		}
	}

	static class CompetitionsResponse
	{
		boolean success;
		Clan clan;
		List<Competition> competitions;
	}

	static class RegistrationsResponse
	{
		boolean success;

		@SerializedName("competition_ids")
		List<Long> competitionIds;
	}

	static class LeaderboardEntry
	{
		String rsn;
		Integer rank;

		// XP for skill competitions, kills for boss competitions.
		@SerializedName("xp_gained")
		Long xpGained;

		// provisional, needs_review or verified
		@SerializedName("validation_status")
		String validationStatus;
	}

	static class LeaderboardResponse
	{
		boolean success;

		@SerializedName("competition_id")
		long competitionId;

		List<LeaderboardEntry> leaderboard;
	}

	/** The answer to a sign-in, register or score update. */
	static class Result
	{
		boolean success;
		String code;
		String error;
		String token;
		Clan clan;

		@SerializedName("starting_xp")
		Long startingXp;
	}
}
