package com.clanxpcompetitions;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Arc2D;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

/**
 * The sidebar. It is redrawn from a ClanXpState.Snapshot whenever
 * something changes; countdowns tick once a second in between.
 */
class ClanXpPanel extends PluginPanel
{
	// Colours from the approved design.
	private static final Color BG = ColorScheme.DARK_GRAY_COLOR;
	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color TEXT = new Color(0xC8C8C8);
	private static final Color MUTED = new Color(0xA5A5A5);
	private static final Color WHITE = Color.WHITE;
	private static final Color ACCENT = new Color(0xFF981F);
	private static final Color ACCENT_HOVER = new Color(0xFFB85C);
	private static final Color GREEN = new Color(0x3FBF5F);
	private static final Color AMBER = new Color(0xE5B13A);
	private static final Color GREY = new Color(0x5A5A5A);
	private static final Color HIGHLIGHT = new Color(0x3A2C17);
	private static final Color GOLD = new Color(0xFFD700);
	private static final Color BRONZE = new Color(0xCD7F32);
	private static final Color TRACK = new Color(0x3C3C3C);

	private static final int TEXT_WIDTH = 145;
	private static final int TOP_ROWS_RUNNING = 8;
	private static final int TOP_ROWS_FINISHED = 5;

	private static final String PRIVACY_WARNING =
		"This feature submits your IP address to a 3rd-party server\n"
			+ "not controlled or verified by RuneLite developers.";

	private static final DateTimeFormatter DATE = DateTimeFormatter
		.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)
		.withZone(ZoneId.systemDefault());

	private final ClanXpPlugin plugin;

	private final JPanel clanLine = new JPanel(new BorderLayout(6, 0));
	private final JLabel clanLabel = new JLabel();
	private final Picker activePicker;
	private final JPanel body = new JPanel(new DynamicGridLayout(0, 1, 0, 10));

	private final JPanel finishedBlock = new JPanel(new DynamicGridLayout(0, 1, 0, 8));
	private final JLabel finishedTitle = new JLabel();
	private final JLabel finishedToggle = new JLabel();
	private final Picker finishedPicker;
	private final JPanel finishedBody = new JPanel(new DynamicGridLayout(0, 1, 0, 10));
	private boolean finishedOpen;

	// Skilling / Bosses tabs inside Finished events.
	private final JPanel finishedTabs = new JPanel(new GridLayout(1, 2, 4, 0));
	private final JLabel skillTab = new JLabel("", SwingConstants.CENTER);
	private final JLabel bossTab = new JLabel("", SwingConstants.CENTER);
	private long latestSkillFinishedId = -1;
	private long latestBossFinishedId = -1;

	// Kept between redraws so typing is never lost.
	private final JTextField clanField = new JTextField();
	private final JPasswordField passwordField = new JPasswordField();
	private String signInShownFor;

	private final Timer timer;

	/** Things that change every second (countdowns, "synced 5s ago"). */
	private final List<Runnable> secondTickers = new ArrayList<>();
	private final List<Long> boundaries = new ArrayList<>();

	/** One entry in a competition dropdown. */
	private static class Item
	{
		final long id;
		final String name;

		Item(long id, String name)
		{
			this.id = id;
			this.name = name;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	/** A label plus dropdown of competitions. */
	private static class Picker
	{
		final JPanel block = new JPanel(new DynamicGridLayout(0, 1, 0, 4));
		final JLabel label = new JLabel();
		final JComboBox<Item> combo = new JComboBox<>();
		final String caption;
		boolean updating;

		Picker(String caption, LongConsumer onPick)
		{
			this.caption = caption;
			block.setOpaque(false);
			styleLabel(label, FontManager.getRunescapeSmallFont(), MUTED);
			combo.setPrototypeDisplayValue(new Item(-1, "Sailing Sprint"));
			combo.setFocusable(false);
			combo.addActionListener(e ->
			{
				Item item = (Item) combo.getSelectedItem();
				if (!updating && item != null)
				{
					onPick.accept(item.id);
				}
			});
			block.add(label);
			block.add(combo);
		}

		void update(List<ApiModels.Competition> list, long selectedId)
		{
			boolean same = combo.getItemCount() == list.size();
			for (int i = 0; same && i < list.size(); i++)
			{
				Item item = combo.getItemAt(i);
				same = item.id == list.get(i).id && item.name.equals(list.get(i).name);
			}

			updating = true;
			try
			{
				if (!same)
				{
					DefaultComboBoxModel<Item> model = new DefaultComboBoxModel<>();
					for (ApiModels.Competition c : list)
					{
						model.addElement(new Item(c.id, c.name));
					}
					combo.setModel(model);
				}

				int index = 0;
				for (int i = 0; i < combo.getItemCount(); i++)
				{
					if (combo.getItemAt(i).id == selectedId)
					{
						index = i;
					}
				}
				if (combo.getItemCount() > 0 && combo.getSelectedIndex() != index)
				{
					combo.setSelectedIndex(index);
				}
				label.setText(caption + " (" + (index + 1) + " of " + combo.getItemCount() + ")");
			}
			finally
			{
				updating = false;
			}
		}
	}

	private static class Row
	{
		String rsn;
		long value = -1;
		int rank;
		String status;
		boolean me;
	}

	ClanXpPanel(ClanXpPlugin plugin)
	{
		this.plugin = plugin;

		setLayout(new BorderLayout());
		setBackground(BG);

		JPanel stack = new JPanel(new DynamicGridLayout(0, 1, 0, 10));
		stack.setOpaque(false);
		stack.add(buildHeader());

		clanLine.setOpaque(false);
		styleLabel(clanLabel, FontManager.getRunescapeSmallFont(), MUTED);
		clanLine.add(clanLabel, BorderLayout.CENTER);
		JLabel signOut = link("Sign out", () ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				"Sign out of your clan on this computer?", "Clan Competitions",
				JOptionPane.YES_NO_OPTION);
			if (choice == JOptionPane.YES_OPTION)
			{
				plugin.signOut(null);
			}
		});
		clanLine.add(signOut, BorderLayout.EAST);
		stack.add(clanLine);

		activePicker = new Picker("Competition", plugin::select);
		stack.add(activePicker.block);

		body.setOpaque(false);
		stack.add(body);

		finishedPicker = new Picker("Finished event", plugin::selectFinished);
		finishedBlock.setOpaque(false);
		JPanel toggleRow = new JPanel(new BorderLayout());
		toggleRow.setBackground(CARD);
		toggleRow.setBorder(new EmptyBorder(8, 10, 8, 10));
		styleLabel(finishedTitle, FontManager.getRunescapeBoldFont(), WHITE);
		styleLabel(finishedToggle, FontManager.getRunescapeSmallFont(), ACCENT);
		toggleRow.add(finishedTitle, BorderLayout.WEST);
		toggleRow.add(finishedToggle, BorderLayout.EAST);
		toggleRow.setToolTipText("Show or hide competitions that have ended");
		makeClickable(toggleRow, () -> plugin.setFinishedOpen(!finishedOpen));
		finishedBlock.add(toggleRow);

		finishedTabs.setOpaque(false);
		for (JLabel tab : new JLabel[]{skillTab, bossTab})
		{
			tab.setOpaque(true);
			tab.setFont(FontManager.getRunescapeSmallFont());
			tab.setBorder(new EmptyBorder(5, 4, 5, 4));
			finishedTabs.add(tab);
		}
		makeClickable(skillTab, () ->
		{
			if (latestSkillFinishedId > 0)
			{
				plugin.selectFinished(latestSkillFinishedId);
			}
		});
		makeClickable(bossTab, () ->
		{
			if (latestBossFinishedId > 0)
			{
				plugin.selectFinished(latestBossFinishedId);
			}
		});
		finishedBlock.add(finishedTabs);
		finishedBlock.add(finishedPicker.block);
		finishedBody.setOpaque(false);
		finishedBlock.add(finishedBody);
		stack.add(finishedBlock);

		add(stack, BorderLayout.NORTH);

		passwordField.addActionListener(e -> submitSignIn());

		timer = new Timer(1000, e -> everySecond());
		timer.start();
	}

	void shutDown()
	{
		timer.stop();
	}

	@Override
	public void onActivate()
	{
		plugin.setPanelActive(true);
	}

	@Override
	public void onDeactivate()
	{
		plugin.setPanelActive(false);
	}

	// ------------------------------------------------------------------
	// Drawing
	// ------------------------------------------------------------------

	void render(ClanXpState.Snapshot s)
	{
		if (!s.signedIn)
		{
			renderSignIn(s);
			return;
		}

		signInShownFor = null;
		passwordField.setText("");
		secondTickers.clear();
		boundaries.clear();
		body.removeAll();
		finishedBody.removeAll();

		clanLine.setVisible(true);
		clanLabel.setText("Clan: " + s.clanName);

		if (!s.trackingEnabled)
		{
			activePicker.block.setVisible(false);
			finishedBlock.setVisible(false);
			body.add(trackingOffCard());
		}
		else
		{
			renderActive(s);
			renderFinishedSection(s);
		}

		revalidate();
		repaint();
	}

	private void renderSignIn(ClanXpState.Snapshot s)
	{
		// Redraw only when something on the sign-in card changes, so the
		// text boxes keep their focus while typing.
		String key = s.trackingEnabled + "|" + s.signingIn + "|" + s.signInMessage;
		if (key.equals(signInShownFor))
		{
			return;
		}
		signInShownFor = key;

		secondTickers.clear();
		boundaries.clear();
		body.removeAll();
		finishedBody.removeAll();
		clanLine.setVisible(false);
		activePicker.block.setVisible(false);
		finishedBlock.setVisible(false);

		JPanel card = card();
		card.setBorder(new EmptyBorder(12, 12, 12, 12));
		card.add(label("Join your clan", FontManager.getRunescapeBoldFont(), WHITE));
		card.add(wrapped("Enter your clan's name and password. Ask in your clan chat if you don't have them.", TEXT));

		card.add(label("Clan name", FontManager.getRunescapeSmallFont(), MUTED));
		card.add(clanField);
		card.add(label("Clan password", FontManager.getRunescapeSmallFont(), MUTED));
		card.add(passwordField);
		clanField.getAccessibleContext().setAccessibleName("Clan name");
		passwordField.getAccessibleContext().setAccessibleName("Clan password");

		AccentButton button = new AccentButton(s.signingIn ? "Signing in..." : "Sign in");
		button.setEnabled(!s.signingIn);
		button.addActionListener(e -> submitSignIn());
		card.add(button);

		if (s.signInMessage != null)
		{
			card.add(wrapped(s.signInMessage, AMBER));
		}
		if (!s.trackingEnabled)
		{
			card.add(wrapped("Signing in turns on online tracking, which shares your IP address with a server not run or checked by RuneLite.", MUTED));
		}
		body.add(card);
		body.add(howItWorksCard());

		revalidate();
		repaint();
	}

	private void submitSignIn()
	{
		String clan = clanField.getText();
		String password = new String(passwordField.getPassword());

		if (!plugin.snapshot().trackingEnabled)
		{
			int choice = JOptionPane.showConfirmDialog(this,
				PRIVACY_WARNING + "\n\nTurn on online tracking and sign in?",
				"Clan Competitions", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.YES_OPTION)
			{
				return;
			}
		}
		plugin.signIn(clan, password);
	}

	private void renderActive(ClanXpState.Snapshot s)
	{
		List<ApiModels.Competition> active = s.active();
		activePicker.block.setVisible(!active.isEmpty());

		if (!s.competitionsLoaded)
		{
			body.add(s.connectionError != null
				? noticeCard(s.connectionError + ". Trying again shortly.")
				: noticeCard("Loading competitions..."));
			return;
		}

		if (s.connectionError != null)
		{
			body.add(wrapped(s.connectionError + ". Showing the last known data.", AMBER));
		}

		if (active.isEmpty())
		{
			JPanel card = card();
			card.add(label("No competitions right now", FontManager.getRunescapeBoldFont(), WHITE));
			card.add(wrapped("New ones appear here when your clan admin creates them.", MUTED));
			body.add(card);
			body.add(websiteLink(-1));
			return;
		}

		activePicker.update(active, s.selectedId);
		ApiModels.Competition c = s.find(active, s.selectedId);
		boolean joined = s.joinedIds.contains(c.id);
		boundaries.add(c.startMs());
		boundaries.add(c.endMs());

		if (s.serverNowMs < c.startMs())
		{
			renderUpcoming(s, c, joined);
		}
		else
		{
			renderRunning(s, c, joined);
		}

		body.add(websiteLink(c.id));
	}

	private void renderFinishedSection(ClanXpState.Snapshot s)
	{
		List<ApiModels.Competition> finished = s.finished();
		finishedBlock.setVisible(!finished.isEmpty());
		if (finished.isEmpty())
		{
			return;
		}

		finishedOpen = s.finishedOpen;
		finishedTitle.setText("Finished events (" + finished.size() + ")");
		finishedToggle.setText(finishedOpen ? "Hide" : "Show");
		finishedTabs.setVisible(finishedOpen);
		finishedPicker.block.setVisible(finishedOpen);
		finishedBody.setVisible(finishedOpen);

		if (!finishedOpen)
		{
			return;
		}

		// Split into skilling and boss events (both lists are newest first).
		List<ApiModels.Competition> skills = new ArrayList<>();
		List<ApiModels.Competition> bosses = new ArrayList<>();
		for (ApiModels.Competition c : finished)
		{
			(c.isBoss() ? bosses : skills).add(c);
		}
		latestSkillFinishedId = skills.isEmpty() ? -1 : skills.get(0).id;
		latestBossFinishedId = bosses.isEmpty() ? -1 : bosses.get(0).id;

		ApiModels.Competition selected = s.find(finished, s.selectedFinishedId);
		boolean showBosses = selected.isBoss();
		List<ApiModels.Competition> shown = showBosses ? bosses : skills;

		styleTab(skillTab, "Skilling (" + skills.size() + ")", !showBosses, !skills.isEmpty());
		styleTab(bossTab, "Bosses (" + bosses.size() + ")", showBosses, !bosses.isEmpty());

		finishedPicker.update(shown, selected.id);
		renderFinished(s, selected, s.joinedIds.contains(selected.id));
	}

	private void renderUpcoming(ClanXpState.Snapshot s, ApiModels.Competition c, boolean joined)
	{
		if (!joined)
		{
			body.add(playerCard(s, c));
		}

		JPanel card = card();
		card.add(headerRow(pill("UPCOMING", ACCENT), label(c.participants + " entered", FontManager.getRunescapeSmallFont(), MUTED)));

		JPanel countdown = new JPanel(new DynamicGridLayout(0, 1, 0, 0));
		countdown.setOpaque(false);
		countdown.add(label("Starts in", FontManager.getRunescapeSmallFont(), MUTED));
		countdown.add(countdownLabel(c.startMs(), "", FontManager.getRunescapeBoldFont(), WHITE));
		card.add(countdown);

		card.add(detailRow(c.isBoss() ? "Boss" : "Skill", c.metricName()));
		card.add(detailRow("Starts", DATE.format(Instant.ofEpochMilli(c.startMs()))));
		card.add(detailRow("Ends", DATE.format(Instant.ofEpochMilli(c.endMs()))));
		body.add(card);

		if (joined)
		{
			body.add(enteredNote(s.rsn, c.isBoss()
				? "Your kills count from the start. Just play as normal."
				: "Tracking starts by itself when the competition begins."));
		}
		else
		{
			addJoinButton(s, c, c.isBoss()
				? "Your kills are counted from the game's kill-count messages."
				: "Your XP is tracked automatically while you play.");
		}

		ApiModels.LeaderboardResponse board = s.leaderboardFor(c.id);
		if (board != null && board.leaderboard != null)
		{
			JPanel list = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
			list.setOpaque(false);
			list.add(label("Who's in", FontManager.getRunescapeBoldFont(), WHITE));

			if (board.leaderboard.isEmpty())
			{
				list.add(label("Nobody has joined yet.", FontManager.getRunescapeSmallFont(), MUTED));
			}

			int shown = 0;
			for (ApiModels.LeaderboardEntry e : board.leaderboard)
			{
				if (shown == 5)
				{
					break;
				}
				boolean me = e.rsn != null && ClanXpPlugin.cleanName(e.rsn).equalsIgnoreCase(s.rsn);
				list.add(nameRow(e.rsn + (me ? " (you)" : ""), me));
				shown++;
			}

			int more = board.leaderboard.size() - shown;
			if (more > 0)
			{
				list.add(label("and " + more + " more", FontManager.getRunescapeSmallFont(), MUTED));
			}
			body.add(list);
		}
	}

	private void renderRunning(ClanXpState.Snapshot s, ApiModels.Competition c, boolean joined)
	{
		long liveGain = liveGain(s, c);
		List<Row> rows = rows(s, c, joined, liveGain);
		String unit = c.isBoss() ? "kills" : "XP";

		if (!joined)
		{
			body.add(playerCard(s, c));

			JPanel card = card();
			card.add(headerRow(pill("RUNNING", GREEN), countdownLabel(c.endMs(), "Ends in ", FontManager.getRunescapeSmallFont(), MUTED)));
			card.add(detailRow(c.isBoss() ? "Boss" : "Skill", c.metricName()));
			card.add(detailRow("Ends", DATE.format(Instant.ofEpochMilli(c.endMs()))));
			body.add(card);

			addJoinButton(s, c, c.isBoss()
				? "You can still join. Kills count from when you join."
				: "You can still join. Your XP counts from when you join.");
		}
		else
		{
			Row mine = null;
			Row above = null;
			for (int i = 0; i < rows.size(); i++)
			{
				if (rows.get(i).me)
				{
					mine = rows.get(i);
					above = i > 0 ? rows.get(i - 1) : null;
					break;
				}
			}

			JPanel card = card();
			card.add(headerRow(pill("RUNNING", GREEN), countdownLabel(c.endMs(), "Ends in ", FontManager.getRunescapeSmallFont(), MUTED)));

			long gained = mine != null && mine.value >= 0 ? mine.value : Math.max(0, liveGain);
			JPanel numbers = new JPanel(new BorderLayout());
			numbers.setOpaque(false);
			numbers.add(stat(c.isBoss() ? "Your kills" : "Your XP gained", format(gained), WHITE, SwingConstants.LEFT), BorderLayout.WEST);
			String rankText = mine != null && mine.rank > 0 ? "#" + mine.rank + " / " + rows.size() : "-";
			numbers.add(stat("Rank", rankText, ACCENT, SwingConstants.RIGHT), BorderLayout.EAST);
			card.add(numbers);

			if (mine != null && above != null && above.value >= 0)
			{
				long behind = Math.max(0, above.value - gained);
				double fraction = above.value <= 0 ? 1.0 : (double) gained / above.value;
				card.add(new ProgressBar(fraction));
				card.add(wrapped(format(behind) + " " + unit + " behind " + above.rsn, MUTED));
			}
			else if (mine != null && mine.rank == 1 && rows.size() > 1)
			{
				card.add(new ProgressBar(1.0));
				Row second = rows.get(1);
				long ahead = second.value >= 0 ? gained - second.value : gained;
				card.add(wrapped("In the lead by " + format(Math.max(0, ahead)) + " " + unit, MUTED));
			}

			card.add(syncLine(s, c));
			body.add(card);
		}

		body.add(leaderboard("Leaderboard", c, rows, TOP_ROWS_RUNNING, false));
	}

	private void renderFinished(ClanXpState.Snapshot s, ApiModels.Competition c, boolean joined)
	{
		List<Row> rows = rows(s, c, joined, -1);
		Row mine = null;
		for (Row r : rows)
		{
			if (r.me)
			{
				mine = r;
			}
		}

		JPanel card = card();
		card.add(headerRow(pill("FINISHED", MUTED), label(DATE.format(Instant.ofEpochMilli(c.endMs())), FontManager.getRunescapeSmallFont(), MUTED)));
		card.add(detailRow(c.isBoss() ? "Boss" : "Skill", c.metricName()));

		if (mine != null && mine.rank > 0)
		{
			JLabel youFinished = label("You finished", FontManager.getRunescapeSmallFont(), MUTED);
			youFinished.setHorizontalAlignment(SwingConstants.CENTER);
			JLabel place = label(ordinal(mine.rank), FontManager.getRunescapeBoldFont(), ACCENT);
			place.setHorizontalAlignment(SwingConstants.CENTER);
			String result = format(Math.max(0, mine.value)) + (c.isBoss() ? " kills" : " XP gained");
			JLabel score = label(result, FontManager.getRunescapeFont(), WHITE);
			score.setHorizontalAlignment(SwingConstants.CENTER);
			card.add(youFinished);
			card.add(place);
			card.add(score);
			card.add(verificationLine(mine.status, c, s.serverNowMs));
		}
		else if (s.leaderboardFor(c.id) == null)
		{
			card.add(wrapped("Loading results...", MUTED));
		}
		finishedBody.add(card);

		if (s.leaderboardFor(c.id) != null)
		{
			finishedBody.add(leaderboard("Final standings", c, rows, TOP_ROWS_FINISHED, true));
		}
		finishedBody.add(websiteLink(c.id));
	}

	// ------------------------------------------------------------------
	// Pieces
	// ------------------------------------------------------------------

	private JPanel buildHeader()
	{
		JPanel header = new JPanel(new BorderLayout());
		header.setOpaque(false);

		JLabel title = label("Clan Competitions", FontManager.getRunescapeBoldFont(), WHITE);
		try
		{
			title.setIcon(new ImageIcon(ImageUtil.loadImageResource(ClanXpPlugin.class, "icon.png")));
			title.setIconTextGap(6);
		}
		catch (Exception ignored)
		{
			// The title still works without the icon.
		}
		header.add(title, BorderLayout.CENTER);

		JButton refresh = new JButton(new RefreshIcon());
		refresh.setToolTipText("Refresh");
		refresh.getAccessibleContext().setAccessibleName("Refresh");
		refresh.setPreferredSize(new Dimension(24, 24));
		refresh.setBorder(BorderFactory.createEmptyBorder());
		refresh.setContentAreaFilled(false);
		refresh.setFocusPainted(false);
		refresh.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		refresh.addActionListener(e -> plugin.refreshNow());
		header.add(refresh, BorderLayout.EAST);

		return header;
	}

	private static void styleTab(JLabel tab, String text, boolean selected, boolean available)
	{
		tab.setText(text);
		tab.setBackground(selected ? ACCENT : CARD);
		tab.setForeground(selected ? new Color(0x1B1B1B) : available ? TEXT : GREY);
		tab.setCursor(Cursor.getPredefinedCursor(available && !selected ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
		tab.setToolTipText(available ? null : "No finished events of this kind yet");
	}

	private JPanel trackingOffCard()
	{
		JPanel card = card();
		card.setBorder(new EmptyBorder(14, 12, 14, 12));

		JLabel title = label("Online tracking is off", FontManager.getRunescapeBoldFont(), WHITE);
		title.setHorizontalAlignment(SwingConstants.CENTER);
		card.add(title);
		card.add(wrapped("To take part, the plugin sends your name and your XP or kill count to the clan server.", TEXT));
		card.add(wrapped("Turning it on shares your IP address with a server not run or checked by RuneLite.", MUTED));

		AccentButton button = new AccentButton("Turn on online tracking");
		button.addActionListener(e ->
		{
			int choice = JOptionPane.showConfirmDialog(this,
				PRIVACY_WARNING + "\n\nTurn on online tracking?",
				"Clan Competitions",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
			{
				plugin.turnOnTracking();
			}
		});
		card.add(button);
		card.add(wrapped("You can turn it off again in the plugin's settings.", MUTED));
		return card;
	}

	private JPanel howItWorksCard()
	{
		JPanel card = card();
		card.add(label("How it works", FontManager.getRunescapeBoldFont(), WHITE));
		card.add(stepRow("1", "Pick a competition and press Join."));
		card.add(stepRow("2", "Play as normal. Your XP or kills update about once a minute."));
		card.add(stepRow("3", "Results are checked against the official hiscores."));
		return card;
	}

	private JPanel playerCard(ClanXpState.Snapshot s, ApiModels.Competition c)
	{
		JPanel card = new JPanel(new BorderLayout(6, 0));
		card.setBackground(CARD);
		card.setBorder(new EmptyBorder(8, 10, 8, 10));

		JPanel text = new JPanel(new DynamicGridLayout(0, 1, 0, 0));
		text.setOpaque(false);

		if (s.loggedIn && !s.rsn.isEmpty())
		{
			text.add(label(s.rsn, FontManager.getRunescapeBoldFont(), WHITE));
			Integer score = s.scores.get(c.metricName());
			String suffix = score == null ? "" : " - " + format(score) + (c.isBoss() ? " KC" : " XP");
			text.add(label(c.metricName() + suffix, FontManager.getRunescapeSmallFont(), MUTED));
		}
		else
		{
			text.add(label("Not logged in", FontManager.getRunescapeBoldFont(), WHITE));
			text.add(label("Log in to join competitions", FontManager.getRunescapeSmallFont(), MUTED));
		}

		card.add(text, BorderLayout.CENTER);
		JLabel dot = new JLabel(new DotIcon(s.loggedIn ? GREEN : GREY, 8));
		dot.setToolTipText(s.loggedIn ? "Logged in" : "Logged out");
		card.add(dot, BorderLayout.EAST);
		return card;
	}

	private void addJoinButton(ClanXpState.Snapshot s, ApiModels.Competition c, String hint)
	{
		AccentButton button;
		if (!s.loggedIn || s.rsn.isEmpty())
		{
			button = new AccentButton("Log in to join");
			button.setEnabled(false);
		}
		else if (s.joining)
		{
			button = new AccentButton("Joining...");
			button.setEnabled(false);
		}
		else
		{
			button = new AccentButton("Join as " + s.rsn);
			final long id = c.id;
			button.addActionListener(e -> plugin.join(id));
		}
		body.add(button);
		body.add(wrapped(s.joinMessage != null ? s.joinMessage : hint, s.joinMessage != null ? AMBER : MUTED));
	}

	private JPanel enteredNote(String rsn, String detail)
	{
		JPanel card = card();
		JLabel title = label("You're in as " + rsn, FontManager.getRunescapeBoldFont(), GREEN);
		title.setIcon(new CheckIcon(GREEN));
		title.setIconTextGap(6);
		card.add(title);
		card.add(wrapped(detail, MUTED));
		return card;
	}

	private JPanel syncLine(ClanXpState.Snapshot s, ApiModels.Competition c)
	{
		JPanel line = new JPanel(new BorderLayout());
		line.setOpaque(false);
		line.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, TRACK),
			new EmptyBorder(6, 0, 0, 0)));

		JLabel text = label("", FontManager.getRunescapeSmallFont(), MUTED);
		text.setIconTextGap(6);
		line.add(text, BorderLayout.CENTER);

		CompetitionTracker.View v = s.progress.get(c.id);
		final String rsn = s.rsn;

		if (!s.loggedIn)
		{
			text.setIcon(new DotIcon(GREY, 7));
			text.setText("Log in to keep tracking");
		}
		else if (v != null && v.problem != null)
		{
			text.setIcon(new DotIcon(AMBER, 7));
			text.setText(v.problem);
			text.setToolTipText(v.problem);
		}
		else if (v != null && v.lastSuccessLocalMs > 0)
		{
			text.setIcon(new DotIcon(GREEN, 7));
			final long since = v.lastSuccessLocalMs;
			Runnable update = () -> text.setText("Synced " + ago(System.currentTimeMillis() - since) + " as " + rsn);
			update.run();
			secondTickers.add(update);
		}
		else if (v != null && v.startXp >= 0)
		{
			text.setIcon(new DotIcon(AMBER, 7));
			text.setText("Sending your first update...");
		}
		else
		{
			text.setIcon(new DotIcon(GREY, 7));
			text.setText(c.isBoss() ? "Waiting for your first kill..." : "Waiting for your XP...");
		}
		return line;
	}

	private JLabel verificationLine(String status, ApiModels.Competition c, long now)
	{
		JLabel line = label("", FontManager.getRunescapeSmallFont(), MUTED);
		line.setIconTextGap(6);
		line.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, TRACK),
			new EmptyBorder(6, 0, 0, 0)));

		if ("verified".equals(status))
		{
			line.setIcon(new CheckIcon(GREEN));
			line.setText("Checked against the hiscores");
		}
		else if ("needs_review".equals(status))
		{
			line.setIcon(new DotIcon(AMBER, 7));
			line.setText("Being checked by a clan admin");
		}
		else if (now - c.endMs() < 60 * 60_000L)
		{
			line.setIcon(new DotIcon(GREY, 7));
			line.setText("Hiscores check coming soon");
		}
		else
		{
			line.setIcon(new DotIcon(GREY, 7));
			line.setText("Not on the hiscores to check");
		}
		return line;
	}

	private JPanel leaderboard(String title, ApiModels.Competition c, List<Row> rows, int top, boolean showStatus)
	{
		JPanel list = new JPanel(new DynamicGridLayout(0, 1, 0, 2));
		list.setOpaque(false);

		JPanel heading = new JPanel(new BorderLayout());
		heading.setOpaque(false);
		heading.add(label(title, FontManager.getRunescapeBoldFont(), WHITE), BorderLayout.WEST);
		heading.add(label(c.isBoss() ? "Kills" : "XP gained", FontManager.getRunescapeSmallFont(), MUTED), BorderLayout.EAST);
		list.add(heading);

		if (rows.isEmpty())
		{
			list.add(label("Nobody has joined yet.", FontManager.getRunescapeSmallFont(), MUTED));
			return list;
		}

		// Always show your own row, even if you're outside the top rows.
		int myIndex = -1;
		for (int i = 0; i < rows.size(); i++)
		{
			if (rows.get(i).me)
			{
				myIndex = i;
			}
		}

		int limit = Math.min(top, rows.size());
		boolean anyReview = false;
		for (int i = 0; i < rows.size(); i++)
		{
			if (i < limit || i == myIndex)
			{
				list.add(leaderboardRow(rows.get(i), showStatus));
			}
			anyReview |= "needs_review".equals(rows.get(i).status);
		}

		int hidden = rows.size() - limit - (myIndex >= limit ? 1 : 0);
		long notStarted = rows.stream().filter(r -> r.value < 0).count();
		String footer = null;
		if (hidden > 0)
		{
			footer = hidden + " more";
			if (!showStatus && notStarted == hidden)
			{
				footer += " not started yet";
			}
		}
		if (showStatus && anyReview)
		{
			footer = (footer == null ? "" : footer + ", ") + "? = still being checked";
		}
		if (footer != null)
		{
			list.add(label(footer, FontManager.getRunescapeSmallFont(), MUTED));
		}
		return list;
	}

	private JPanel leaderboardRow(Row row, boolean showStatus)
	{
		JPanel panel = new JPanel(new BorderLayout(6, 0));
		panel.setBackground(row.me ? HIGHLIGHT : CARD);
		panel.setBorder(row.me
			? BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(ACCENT), new EmptyBorder(3, 5, 3, 5))
			: new EmptyBorder(4, 6, 4, 6));

		Color rankColor = row.rank == 1 ? GOLD : row.rank == 3 ? BRONZE : row.me ? WHITE : TEXT;
		JLabel rank = label(row.rank > 0 ? String.valueOf(row.rank) : "-", FontManager.getRunescapeSmallFont(), rankColor);
		rank.setPreferredSize(new Dimension(18, rank.getPreferredSize().height));
		panel.add(rank, BorderLayout.WEST);

		Font nameFont = row.me ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont();
		panel.add(label(row.rsn + (row.me ? " (you)" : ""), nameFont, WHITE), BorderLayout.CENTER);

		JPanel right = new JPanel(new BorderLayout(4, 0));
		right.setOpaque(false);
		right.add(label(row.value >= 0 ? format(row.value) : "-", FontManager.getRunescapeSmallFont(), row.me ? WHITE : TEXT), BorderLayout.CENTER);

		if (showStatus)
		{
			JLabel status = new JLabel();
			status.setPreferredSize(new Dimension(12, 12));
			if ("verified".equals(row.status))
			{
				status.setIcon(new CheckIcon(GREEN));
				status.setToolTipText("Checked against the hiscores");
			}
			else if ("needs_review".equals(row.status))
			{
				styleLabel(status, FontManager.getRunescapeBoldFont(), AMBER);
				status.setText("?");
				status.setToolTipText("Still being checked");
			}
			right.add(status, BorderLayout.EAST);
		}

		panel.add(right, BorderLayout.EAST);
		return panel;
	}

	private JPanel nameRow(String name, boolean me)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setBackground(me ? HIGHLIGHT : CARD);
		row.setBorder(new EmptyBorder(4, 6, 4, 6));
		row.add(label(name, FontManager.getRunescapeSmallFont(), WHITE), BorderLayout.CENTER);
		return row;
	}

	private JPanel stepRow(String number, String text)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		JLabel n = label(number, FontManager.getRunescapeBoldFont(), ACCENT);
		n.setVerticalAlignment(SwingConstants.TOP);
		row.add(n, BorderLayout.WEST);
		row.add(wrapped(text, TEXT, TEXT_WIDTH - 20), BorderLayout.CENTER);
		return row;
	}

	private JLabel websiteLink(long competitionId)
	{
		JLabel link = link("Full leaderboard on the website", () -> plugin.openWebsite(competitionId));
		link.setHorizontalAlignment(SwingConstants.CENTER);
		link.setToolTipText(ClanXpApi.BASE_URL);
		return link;
	}

	private static JLabel link(String text, Runnable action)
	{
		JLabel link = label(text, FontManager.getRunescapeSmallFont(), ACCENT);
		makeClickable(link, action);
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				link.setForeground(ACCENT_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				link.setForeground(ACCENT);
			}
		});
		return link;
	}

	private JPanel noticeCard(String text)
	{
		JPanel card = card();
		card.add(wrapped(text, MUTED));
		return card;
	}

	private JPanel stat(String caption, String value, Color valueColor, int align)
	{
		JPanel panel = new JPanel(new DynamicGridLayout(0, 1, 0, 0));
		panel.setOpaque(false);
		JLabel top = label(caption, FontManager.getRunescapeSmallFont(), MUTED);
		JLabel big = label(value, FontManager.getRunescapeBoldFont(), valueColor);
		top.setHorizontalAlignment(align);
		big.setHorizontalAlignment(align);
		panel.add(top);
		panel.add(big);
		return panel;
	}

	private JPanel headerRow(JComponent left, JComponent right)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		JPanel leftWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		leftWrap.setOpaque(false);
		leftWrap.add(left);
		row.add(leftWrap, BorderLayout.WEST);
		row.add(right, BorderLayout.EAST);
		return row;
	}

	private JPanel detailRow(String key, String value)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		JLabel k = label(key, FontManager.getRunescapeSmallFont(), MUTED);
		k.setPreferredSize(new Dimension(40, k.getPreferredSize().height));
		row.add(k, BorderLayout.WEST);
		row.add(label(value, FontManager.getRunescapeSmallFont(), WHITE), BorderLayout.CENTER);
		return row;
	}

	private JLabel pill(String text, Color color)
	{
		JLabel pill = label(text, FontManager.getRunescapeSmallFont(), color);
		pill.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(color, 1, true),
			new EmptyBorder(0, 6, 0, 6)));
		return pill;
	}

	private JLabel countdownLabel(long targetMs, String prefix, Font font, Color color)
	{
		JLabel label = label("", font, color);
		Runnable update = () -> label.setText(prefix + duration(targetMs - plugin.serverNow()));
		update.run();
		secondTickers.add(update);
		return label;
	}

	private static JPanel card()
	{
		JPanel card = new JPanel(new DynamicGridLayout(0, 1, 0, 6));
		card.setBackground(CARD);
		card.setBorder(new EmptyBorder(8, 10, 8, 10));
		return card;
	}

	private static JLabel label(String text, Font font, Color color)
	{
		JLabel label = new JLabel(text);
		styleLabel(label, font, color);
		return label;
	}

	private static void styleLabel(JLabel label, Font font, Color color)
	{
		label.setFont(font);
		label.setForeground(color);
	}

	private static JLabel wrapped(String text, Color color)
	{
		return wrapped(text, color, TEXT_WIDTH);
	}

	/** A label whose text wraps onto several lines. */
	private static JLabel wrapped(String text, Color color, int width)
	{
		String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return label("<html><div style='width:" + width + "px'>" + safe + "</div></html>",
			FontManager.getRunescapeSmallFont(), color);
	}

	private static void makeClickable(JComponent component, Runnable action)
	{
		component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		component.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}
		});
	}

	// ------------------------------------------------------------------
	// Data helpers
	// ------------------------------------------------------------------

	private void everySecond()
	{
		for (Runnable r : secondTickers)
		{
			r.run();
		}

		// A competition just started or ended: let the plugin move it.
		long now = plugin.serverNow();
		for (long boundary : boundaries)
		{
			if (now >= boundary && now - boundary < 1000)
			{
				plugin.boundaryPassed();
				return;
			}
		}
	}

	/** Score gained so far according to this client, or -1 if unknown. */
	private static long liveGain(ClanXpState.Snapshot s, ApiModels.Competition c)
	{
		CompetitionTracker.View v = s.progress.get(c.id);
		if (v == null || v.startXp < 0 || v.lastXp < v.startXp)
		{
			return -1;
		}
		return (long) v.lastXp - v.startXp;
	}

	/** The leaderboard, with your own live score filled in and ranks worked out. */
	private static List<Row> rows(ClanXpState.Snapshot s, ApiModels.Competition c, boolean joined, long liveGain)
	{
		List<Row> rows = new ArrayList<>();
		ApiModels.LeaderboardResponse board = s.leaderboardFor(c.id);
		boolean foundMe = false;

		if (board != null && board.leaderboard != null)
		{
			for (ApiModels.LeaderboardEntry e : board.leaderboard)
			{
				Row r = new Row();
				r.rsn = e.rsn == null ? "?" : e.rsn;
				r.value = e.xpGained == null ? -1 : e.xpGained;
				r.status = e.validationStatus;
				r.me = !s.rsn.isEmpty() && ClanXpPlugin.cleanName(r.rsn).equalsIgnoreCase(s.rsn);
				if (r.me)
				{
					foundMe = true;
					r.value = Math.max(r.value, liveGain);
				}
				rows.add(r);
			}
		}

		if (!foundMe && joined && !s.rsn.isEmpty() && board != null)
		{
			Row r = new Row();
			r.rsn = s.rsn;
			r.value = liveGain;
			r.me = true;
			rows.add(r);
		}

		rows.sort((a, b) ->
		{
			if (a.value != b.value)
			{
				return Long.compare(b.value, a.value);
			}
			return a.rsn.compareToIgnoreCase(b.rsn);
		});

		int rank = 0;
		long previous = Long.MIN_VALUE;
		for (int i = 0; i < rows.size(); i++)
		{
			Row r = rows.get(i);
			if (r.value < 0)
			{
				r.rank = 0;
				continue;
			}
			if (r.value != previous)
			{
				rank = i + 1;
				previous = r.value;
			}
			r.rank = rank;
		}
		return rows;
	}

	private static String format(long value)
	{
		return NumberFormat.getIntegerInstance(Locale.UK).format(value);
	}

	private static String duration(long ms)
	{
		long s = Math.max(0, ms) / 1000;
		long d = s / 86400;
		long h = (s % 86400) / 3600;
		long m = (s % 3600) / 60;
		long sec = s % 60;
		if (d > 0)
		{
			return String.format("%dd %02dh %02dm", d, h, m);
		}
		if (h > 0)
		{
			return String.format("%dh %02dm %02ds", h, m, sec);
		}
		return String.format("%dm %02ds", m, sec);
	}

	private static String ago(long ms)
	{
		long s = Math.max(0, ms) / 1000;
		if (s < 10)
		{
			return "just now";
		}
		if (s < 60)
		{
			return s + "s ago";
		}
		return (s / 60) + "m ago";
	}

	private static String ordinal(int n)
	{
		int mod100 = n % 100;
		if (mod100 >= 11 && mod100 <= 13)
		{
			return n + "th";
		}
		switch (n % 10)
		{
			case 1:
				return n + "st";
			case 2:
				return n + "nd";
			case 3:
				return n + "rd";
			default:
				return n + "th";
		}
	}

	// ------------------------------------------------------------------
	// Small drawn parts
	// ------------------------------------------------------------------

	/** The orange button. Painted by hand so RuneLite's theme keeps the colour. */
	private static class AccentButton extends JButton
	{
		AccentButton(String text)
		{
			super(text);
			setFont(FontManager.getRunescapeBoldFont());
			setForeground(new Color(0x1B1B1B));
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setRolloverEnabled(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setPreferredSize(new Dimension(100, 32));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Color fill = !isEnabled() ? GREY : getModel().isRollover() ? ACCENT_HOVER : ACCENT;
			g.setColor(fill);
			g.fillRect(0, 0, getWidth(), getHeight());
			setForeground(isEnabled() ? new Color(0x1B1B1B) : TEXT);
			super.paintComponent(g);
		}
	}

	private static class ProgressBar extends JComponent
	{
		private final double fraction;

		ProgressBar(double fraction)
		{
			this.fraction = Math.max(0, Math.min(1, fraction));
			setPreferredSize(new Dimension(100, 6));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			g.setColor(TRACK);
			g.fillRect(0, 0, getWidth(), getHeight());
			g.setColor(ACCENT);
			g.fillRect(0, 0, (int) Math.round(getWidth() * fraction), getHeight());
		}
	}

	private static class DotIcon implements Icon
	{
		private final Color color;
		private final int size;

		DotIcon(Color color, int size)
		{
			this.color = color;
			this.size = size;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			g2.fillOval(x, y, size, size);
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return size;
		}

		@Override
		public int getIconHeight()
		{
			return size;
		}
	}

	private static class CheckIcon implements Icon
	{
		private final Color color;

		CheckIcon(Color color)
		{
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.drawPolyline(new int[]{x + 2, x + 5, x + 10}, new int[]{y + 6, y + 9, y + 3}, 3);
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return 12;
		}

		@Override
		public int getIconHeight()
		{
			return 12;
		}
	}

	private static class RefreshIcon implements Icon
	{
		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(c.isEnabled() ? MUTED : GREY);
			g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.draw(new Arc2D.Double(x + 2, y + 2, 10, 10, 60, 280, Arc2D.OPEN));
			g2.drawPolyline(new int[]{x + 9, x + 12, x + 12}, new int[]{y + 2, y + 3, y}, 3);
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return 14;
		}

		@Override
		public int getIconHeight()
		{
			return 14;
		}
	}
}
