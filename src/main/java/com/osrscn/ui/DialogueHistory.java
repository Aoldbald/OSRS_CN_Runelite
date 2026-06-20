package com.osrscn.ui;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Singleton;

/**
 * Rolling log of recent dialogue lines (NPC and player), shown in the side panel so the player can
 * scroll back and re-read story they clicked past - the core "watch the story" use case.
 */
@Singleton
public class DialogueHistory
{
	private static final int MAX = 300;

	public static final class Entry
	{
		public final String speakerZh; // speaker name in Chinese (or English if untranslated); may be empty
		public final String speakerEn; // speaker name in English; may be empty
		public final String zh;        // translated line, or null if not translated
		public final String en;        // original English line

		Entry(String speakerZh, String speakerEn, String zh, String en)
		{
			this.speakerZh = speakerZh;
			this.speakerEn = speakerEn;
			this.zh = zh;
			this.en = en;
		}
	}

	private final Deque<Entry> entries = new ConcurrentLinkedDeque<>();
	private final AtomicInteger version = new AtomicInteger();

	public void add(String speakerZh, String speakerEn, String zh, String en)
	{
		if (en == null || en.isEmpty())
		{
			return;
		}
		String spZh = speakerZh == null ? "" : speakerZh;
		String spEn = speakerEn == null ? "" : speakerEn;
		Entry last = entries.peekLast();
		if (last != null && en.equals(last.en) && spEn.equals(last.speakerEn))
		{
			return; // same line captured again (dialogue widget re-set): skip the duplicate
		}
		entries.addLast(new Entry(spZh, spEn, zh, en));
		while (entries.size() > MAX)
		{
			entries.pollFirst();
		}
		version.incrementAndGet();
	}

	/** Oldest-first snapshot, for rendering newest at the bottom. */
	public List<Entry> snapshot()
	{
		return new ArrayList<>(entries);
	}

	/** Bumped on every change, so the panel only rebuilds when there is something new. */
	public int version()
	{
		return version.get();
	}

	public void clear()
	{
		entries.clear();
		version.incrementAndGet();
	}
}
