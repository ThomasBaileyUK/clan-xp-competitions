package com.clanxpcompetitions;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to the clan server. Every call runs in the background and
 * reports back through a Handler, so the game never freezes.
 *
 * It also keeps track of the server's clock, so start and end times
 * are right even if this computer's clock is wrong.
 */
@Slf4j
@Singleton
class ClanXpApi
{
	static final String BASE_URL = "https://clan-xp-api.thomas-bailey20.workers.dev";

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	interface Handler<T>
	{
		/** result is null when the call failed; error then says why. */
		void done(T result, String error);
	}

	private final OkHttpClient http;
	private final Gson gson;

	private volatile long clockOffsetMs;
	private volatile boolean clockKnown;
	private volatile String token;
	private volatile Runnable onSignedOut = () -> { };

	@Inject
	ClanXpApi(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	/** The current time on the server, in milliseconds. */
	long serverNow()
	{
		return System.currentTimeMillis() + clockOffsetMs;
	}

	boolean isClockKnown()
	{
		return clockKnown;
	}

	void setToken(String token)
	{
		this.token = token == null || token.isEmpty() ? null : token;
	}

	boolean hasToken()
	{
		return token != null;
	}

	/** Runs when the server says our clan sign-in no longer works. */
	void setOnSignedOut(Runnable onSignedOut)
	{
		this.onSignedOut = onSignedOut;
	}

	void signIn(String clan, String password, Handler<ApiModels.Result> handler)
	{
		JsonObject body = new JsonObject();
		body.addProperty("clan", clan);
		body.addProperty("password", password);
		post("/api/clan/login", body, handler);
	}

	void getCompetitions(Handler<ApiModels.CompetitionsResponse> handler)
	{
		get("/api/competitions", ApiModels.CompetitionsResponse.class, handler);
	}

	void getRegistrations(String rsn, Handler<ApiModels.RegistrationsResponse> handler)
	{
		get("/api/registrations?rsn=" + URLEncoder.encode(rsn, StandardCharsets.UTF_8),
			ApiModels.RegistrationsResponse.class, handler);
	}

	void getLeaderboard(long competitionId, Handler<ApiModels.LeaderboardResponse> handler)
	{
		get("/api/leaderboard?competition_id=" + competitionId,
			ApiModels.LeaderboardResponse.class, handler);
	}

	void register(long competitionId, String rsn, Handler<ApiModels.Result> handler)
	{
		JsonObject body = new JsonObject();
		body.addProperty("competition_id", competitionId);
		body.addProperty("rsn", rsn);
		post("/api/register", body, handler);
	}

	/** value is the XP total for skills, or the kill count for bosses. */
	void reportXp(long competitionId, String rsn, int value, int startValue, long capturedAtMs,
		Handler<ApiModels.Result> handler)
	{
		JsonObject body = new JsonObject();
		body.addProperty("competition_id", competitionId);
		body.addProperty("rsn", rsn);
		body.addProperty("xp", value);
		if (startValue >= 0)
		{
			body.addProperty("start_xp", startValue);
		}
		body.addProperty("captured_at_ms", capturedAtMs);
		post("/api/xp", body, handler);
	}

	private <T> void get(String path, Class<T> type, Handler<T> handler)
	{
		call(builder(path).get().build(), type, handler, true);
	}

	private void post(String path, JsonObject body, Handler<ApiModels.Result> handler)
	{
		Request request = builder(path)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		// Sign-in, register and score replies carry a useful "code" even when
		// refused, so they are passed on whatever the HTTP status.
		call(request, ApiModels.Result.class, handler, false);
	}

	private Request.Builder builder(String path)
	{
		Request.Builder builder = new Request.Builder().url(BASE_URL + path);
		String current = token;
		if (current != null)
		{
			builder.header("Authorization", "Bearer " + current);
		}
		return builder;
	}

	private <T> void call(Request request, Class<T> type, Handler<T> handler, boolean requireOk)
	{
		final long sentAt = System.currentTimeMillis();
		final boolean signedInCall = token != null;

		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Clan Competitions request failed: {}", request.url(), e);
				handler.done(null, "Can't reach the clan server");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				long receivedAt = System.currentTimeMillis();

				try (ResponseBody responseBody = response.body())
				{
					String text = responseBody == null ? "" : responseBody.string();
					JsonObject json = gson.fromJson(text, JsonObject.class);

					if (json == null)
					{
						handler.done(null, "Unexpected reply from the clan server");
						return;
					}

					if (json.has("server_time_ms"))
					{
						updateClock(sentAt, receivedAt, json.get("server_time_ms").getAsLong());
					}

					boolean signedOut = response.code() == 401 && json.has("code")
						&& "sign_in".equals(json.get("code").getAsString());
					if (signedOut && signedInCall)
					{
						onSignedOut.run();
					}

					if (requireOk && !response.isSuccessful())
					{
						String error = json.has("error")
							? json.get("error").getAsString()
							: "Clan server error " + response.code();
						handler.done(null, error);
						return;
					}

					handler.done(gson.fromJson(json, type), null);
				}
				catch (Exception e)
				{
					log.debug("Clan Competitions reply could not be read: {}", request.url(), e);
					handler.done(null, "Unexpected reply from the clan server");
				}
			}
		});
	}

	private void updateClock(long sentAt, long receivedAt, long serverTime)
	{
		long roundTrip = receivedAt - sentAt;
		if (roundTrip < 0 || roundTrip > 5000)
		{
			return; // Too slow to be accurate.
		}

		long offset = serverTime - (sentAt + roundTrip / 2);
		clockOffsetMs = clockKnown ? (clockOffsetMs * 3 + offset) / 4 : offset;
		clockKnown = true;
	}
}
