package com.osrscn.sprite;

import net.runelite.client.game.SpriteOverride;

/**
 * Sprites whose wording is <b>baked into the bitmap</b>, so translating widgets can never reach
 * them: the Adventure Path banners, the skill-guide mockups, the Leagues tutorial buttons, the
 * membership upsell buttons, and so on.
 *
 * <p>Each constant pairs a sprite id with a Chinese redraw of that exact sprite. The PNG lives next
 * to this enum under {@code src/main/resources/com/osrscn/sprite/} and is resolved relative to this
 * class, so {@link net.runelite.client.util.ImageUtil#loadImageResource(Class, String)} finds it by
 * bare file name. Adding a sprite is one line here plus one PNG; nothing else needs touching, because
 * {@link SpriteOverrides} installs the whole enum at once.
 *
 * <h2>Adding an entry</h2>
 * <ol>
 *   <li>Find the sprite id - the {@code archive} id that keys {@code client.getSpriteOverrides()}.
 *       {@link net.runelite.api.SpriteID} names many ids; none of the ones below are named, so they
 *       are written as plain integers with a descriptive constant name.</li>
 *   <li>Measure the original sprite and record its canvas size, then redraw the PNG at
 *       <b>exactly</b> that size. The sprite is blitted 1:1 and never scaled, so a 400x600 image for
 *       a 200x300 sprite covers the neighbouring interface - worse than leaving it English. Both
 *       {@link SpriteOverrides} and the unit test reject a mismatch.</li>
 *   <li>Keep the original artwork (frame, bevels, icons, textures) and repaint only the glyphs.
 *       Leave 2-3 px of clearance around text that sits near an edge: the realistic display scale is
 *       about 1.7x, and anything flush against the border reads as clipped.</li>
 *   <li>Save as {@code <5-digit id>_<slug>_zh.png} and tick the row in {@code progress.csv} of the
 *       sprite delivery folder so the same sprite is never redrawn twice.</li>
 * </ol>
 *
 * <h2>Drawing rules that are not obvious</h2>
 * <ul>
 *   <li>Alpha has to <b>match the original</b>. On an opaque panel (the skill-guide mockups) every
 *       pixel stays opaque - a half-transparent pixel there lets the game world show through. On a
 *       cut-out label (the Adventure Path banners, the Play Now text) the transparent background
 *       must stay transparent, because the label is drawn over game art.</li>
 *   <li>Hard edges, no antialiasing. The whole canvas is upscaled by the client, which smears
 *       antialiased text; 12-15 px CJK glyphs drawn 1-bit stay readable, antialiased ones do not.</li>
 *   <li>On textured faces, rebuild the texture from the <i>same row</i> of the same surface, and keep
 *       the donor slice clear of the artwork - sampling from an icon paints its colours into the
 *       button.</li>
 * </ul>
 */
public enum SpriteEntries implements SpriteOverride
{
	// A. Adventure Path banners - the five path titles on the path chooser.
	APPATH_COMBAT(2555, "02555_appath_combat_zh.png", 206, 49),
	APPATH_GATHER(2556, "02556_appath_gather_zh.png", 217, 49),
	APPATH_WARRIOR(2557, "02557_appath_warrior_zh.png", 216, 49),
	APPATH_RANGER(2558, "02558_appath_ranger_zh.png", 218, 49),
	APPATH_WIZARD(2559, "02559_appath_wizard_zh.png", 213, 60),

	// B. Membership upsell button. These two have to be changed together: 2592 is the
	//    hovered state, so replacing only one makes the label jump when the cursor moves on.
	UPGRADE_MEMBERSHIP_BUTTON(2591, "02591_upgrade_membership_button_zh.png", 153, 32),
	UPGRADE_MEMBERSHIP_BUTTON_HIGHLIGHT(2592, "02592_upgrade_membership_button_highlight_zh.png", 153, 32),

	// C. Leagues tutorial flow chart - the button column on the right, for Leagues 2 (25xx),
	//    3 (42xx) and 6 (83xx).
	LEAGUE_TUTORIAL_TASKS(2599, "02599_league_tutorial_2_zh.png", 213, 41),
	LEAGUE_TUTORIAL_AREAS(2600, "02600_league_tutorial_3_zh.png", 213, 41),
	LEAGUE_TUTORIAL_RELICS(2601, "02601_league_tutorial_4_zh.png", 213, 41),
	LEAGUE_TUTORIAL_INFO(2602, "02602_league_tutorial_5_zh.png", 213, 41),
	LEAGUE3_TUTORIAL_TASKS(4229, "04229_league3_tutorial_2_zh.png", 213, 41),
	LEAGUE3_TUTORIAL_UNLOCKS(4230, "04230_league3_tutorial_3_zh.png", 213, 41),
	LEAGUE3_TUTORIAL_FRAGMENTS(4231, "04231_league3_tutorial_4_zh.png", 213, 41),
	LEAGUE3_TUTORIAL_INFO(4232, "04232_league3_tutorial_5_zh.png", 213, 41),
	LEAGUE6_TUTORIAL_TASKS(8339, "08339_league6_tutorial_2_zh.png", 213, 41),
	LEAGUE6_TUTORIAL_AREAS(8340, "08340_league6_tutorial_3_zh.png", 213, 41),
	LEAGUE6_TUTORIAL_RELICS(8341, "08341_league6_tutorial_4_zh.png", 213, 41),
	LEAGUE6_TUTORIAL_DEMONIC_PACTS(8342, "08342_league6_tutorial_5_zh.png", 213, 41),

	// D. Skill-guide mockups - the panel that explains what each combat style does.
	SKILLPANEL_ATTACK(2228, "02228_getting_started_with_attack_zh.png", 175, 201),
	SKILLPANEL_STRENGTH(2231, "02231_getting_started_with_strength_zh.png", 175, 201),
	SKILLPANEL_DEFENCE(2234, "02234_getting_started_with_defence_zh.png", 175, 201),
	SKILLPANEL_MAGIC(2237, "02237_getting_started_with_magic_zh.png", 175, 201),
	SKILLPANEL_RANGED(2240, "02240_getting_started_with_ranged_zh.png", 175, 201),
	SKILLPANEL_WIZARD(2560, "02560_automatic_wizard_zh.png", 175, 201),

	// E. Login screen and the sailing tutorial slideshow.
	PLAY_NOW_TEXT(2133, "02133_play_now_text_zh.png", 78, 25),
	SAILING_ALPHA_CHARTING_SLIDESHOW_2(6981, "06981_sailing_alpha_charting_slideshow_2_zh.png", 160, 196),
	;

	private final int spriteId;
	private final String fileName;
	private final int width;
	private final int height;

	SpriteEntries(int spriteId, String fileName, int width, int height)
	{
		this.spriteId = spriteId;
		this.fileName = fileName;
		this.width = width;
		this.height = height;
	}

	@Override
	public int getSpriteId()
	{
		return spriteId;
	}

	@Override
	public String getFileName()
	{
		return fileName;
	}

	/** Canvas width of the original sprite, which the redraw must match exactly. */
	public int getWidth()
	{
		return width;
	}

	/** Canvas height of the original sprite, which the redraw must match exactly. */
	public int getHeight()
	{
		return height;
	}
}
