package com.osrscn.hooks;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.ScriptID;
import net.runelite.api.gameval.InterfaceID;

/**
 * Declarative routing table for interface translation: one row per surface (widget group) carrying its
 * translation policy, plus a script-id -> targets map for surfaces a build script rewrites.
 *
 * <p>This collapses what used to be five scattered {@code Set}s (excluded / small / per-frame-excluded /
 * reconstruct / chatbox) and a hand-written {@code onScriptPostFired} if/else chain into one place. Adding
 * a newly-discovered flashing surface is now one builder line instead of edits in several methods, and an
 * unlisted group falls through to {@link #DEFAULT} (ordinary per-tick + per-frame UI text) automatically.
 */
final class SurfaceRegistry
{
	/** How a script hook reaches the widget(s) it must re-translate. */
	enum Reach
	{
		GROUP_ROOT,  // translateGroup(componentId): walk from this root component
		GROUP_ID,    // translateGroupId(groupId): walk every loaded root of this group
		COMPONENT    // translateComponent(componentId): walk just this component's subtree
	}

	static final class Target
	{
		final Reach reach;
		final int id;

		Target(Reach reach, int id)
		{
			this.reach = reach;
			this.id = id;
		}
	}

	/** Per-group translation policy. All flags default false (= ordinary per-tick + per-frame UI text). */
	static final class Surface
	{
		boolean excluded;         // never translated by the generic walk (player names, chatbox, new guide)
		boolean perFrameExcluded; // skipped by the per-frame pass (hit-test-sensitive / big list); tick only
		boolean small;            // render at the smaller info-box / tooltip glyph size
		boolean noAi;             // never AI-translate misses (HP bar: don't guess monster names)
		boolean reconstruct;      // owned by reconstructJournals (whole-task reflow), not the generic walk
	}

	private static final Surface DEFAULT = new Surface();

	private final Map<Integer, Surface> byGroup = new HashMap<>();
	private final Map<Integer, List<Target>> byScript = new HashMap<>();

	/** Policy for a widget group; never null (unlisted groups get the default UI-text policy). */
	Surface forGroup(int groupId)
	{
		return byGroup.getOrDefault(groupId, DEFAULT);
	}

	/** Targets to re-translate when {@code scriptId} fired, or null if no surface cares about it. */
	List<Target> forScript(int scriptId)
	{
		return byScript.get(scriptId);
	}

	private Surface row(int groupId)
	{
		return byGroup.computeIfAbsent(groupId, k -> new Surface());
	}

	private void script(int scriptId, Target... targets)
	{
		byScript.put(scriptId, Arrays.asList(targets));
	}

	private static int grp(int componentId)
	{
		return componentId >>> 16;
	}

	static SurfaceRegistry build()
	{
		SurfaceRegistry r = new SurfaceRegistry();

		// ===== EXCLUDE: never translated by the generic walk =====
		// player-name lists (names are never translated) + dialogue/chat groups owned elsewhere.
		for (int g : new int[]{
				grp(InterfaceID.ChatLeft.NAME), grp(InterfaceID.ChatRight.NAME), grp(InterfaceID.Chatmenu.OPTIONS),
				grp(InterfaceID.Friends.UNIVERSE), grp(InterfaceID.Ignore.UNIVERSE), grp(InterfaceID.PmChat.CONTAINER),
				grp(InterfaceID.ChatchannelCurrent.UNIVERSE), grp(InterfaceID.SideChannels.UNIVERSE),
				grp(InterfaceID.SideChannelsLarge.UNIVERSE), grp(InterfaceID.ClansMembers.UNIVERSE),
				grp(InterfaceID.ClansSidepanel.UNIVERSE), grp(InterfaceID.ClansGuestSidepanel.UNIVERSE),
				860, // new-style skill guide: prose split into per-word widgets, unreadable word-by-word
				grp(InterfaceID.Chatbox.UNIVERSE) // messages -> ChatHandler; static tabs -> translateChatTabs()
		})
		{
			r.row(g).excluded = true;
		}

		// ===== SMALL: hover info-box / tooltip glyph size =====
		for (int g : new int[]{
				grp(InterfaceID.Stats.UNIVERSE), grp(InterfaceID.Prayerbook.UNIVERSE),
				grp(InterfaceID.MagicSpellbook.UNIVERSE), grp(InterfaceID.Emote.UNIVERSE),
				grp(InterfaceID.HpbarHud.UNIVERSE)
		})
		{
			r.row(g).small = true;
		}
		r.row(grp(InterfaceID.HpbarHud.UNIVERSE)).noAi = true; // HP bar: table only, never guess monster names

		// ===== PER-FRAME EXCLUDED: hit-test-sensitive / big list -> tick backstop + script hook only =====
		r.row(grp(InterfaceID.AccountSummarySidepanel.UNIVERSE)).perFrameExcluded = true; // SUMMARY_CLICK_LAYER hover
		r.row(grp(InterfaceID.CaTasks.UNIVERSE)).perFrameExcluded = true;                  // big scrollable task list

		// ===== RECONSTRUCT: whole-task reflow owns these (tick, debounced) =====
		r.row(grp(InterfaceID.Journalscroll.UNIVERSE)).reconstruct = true;
		r.row(grp(InterfaceID.Questjournal.UNIVERSE)).reconstruct = true;

		// ===== SCRIPT hooks: a build script writes these on open/switch -> translate the instant it fires =====
		// Account summary: named TEXT_FORMAT(3948)/SECTION_FORMAT(3950) + the unnamed XP/play-time siblings 3947/3949.
		for (int s = ScriptID.ACCOUNT_SUMMARY_TEXT_FORMAT - 1; s <= ScriptID.ACCOUNT_SUMMARY_SECTION_FORMAT; s++)
		{
			r.script(s, new Target(Reach.GROUP_ROOT, InterfaceID.AccountSummarySidepanel.UNIVERSE));
		}
		// Old skill guide build script (9350): list slots via group-id walk + TITLE/CATEGORIES (outside that root).
		r.script(9350,
				new Target(Reach.GROUP_ID, InterfaceID.SKILL_GUIDE),
				new Target(Reach.COMPONENT, InterfaceID.SkillGuide.TITLE),
				new Target(Reach.COMPONENT, InterfaceID.SkillGuide.CATEGORIES));
		// Prayer hover tooltip draw-time rewrite (9444).
		r.script(9444, new Target(Reach.COMPONENT, InterfaceID.Prayerbook.TOOLTIP));

		return r;
	}
}
