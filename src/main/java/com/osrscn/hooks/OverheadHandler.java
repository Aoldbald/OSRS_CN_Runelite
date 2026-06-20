package com.osrscn.hooks;

import com.osrscn.translate.Translator;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.events.OverheadTextChanged;

/**
 * Translates NPC speech bubbles (overhead text). Player chat is left alone.
 *
 * <p>The lookup table answers instantly; AI answers asynchronously, so a bubble that misses the
 * table shows English at first. We keep such bubbles in a pending set and retry each tick until the
 * AI result is cached (then re-apply it to the still-showing bubble) or it times out.
 */
@Singleton
public class OverheadHandler
{
	private static final int OVERHEAD_COLOR = 0xffff00; // yellow, like the native bubble
	private static final long TIMEOUT_MS = 10_000;

	@Inject
	private Translator translator;

	// actor -> (english source, deadline) for bubbles still awaiting an async translation
	private final ConcurrentMap<Actor, Pending> pending = new ConcurrentHashMap<>();

	private static final class Pending
	{
		final String english;
		final long deadline;

		Pending(String english, long deadline)
		{
			this.english = english;
			this.deadline = deadline;
		}
	}

	public void handle(OverheadTextChanged event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			pending.remove(actor);
			return; // NPC bubbles only - never translate player chat
		}
		String english = event.getOverheadText();
		if (english == null || english.isEmpty())
		{
			pending.remove(actor);
			return;
		}
		String zh = translator.translate(english, OVERHEAD_COLOR, 0);
		if (zh != null)
		{
			actor.setOverheadText(zh);
			pending.remove(actor);
		}
		else
		{
			pending.put(actor, new Pending(english, System.currentTimeMillis() + TIMEOUT_MS));
		}
	}

	/** Retry bubbles whose translation wasn't ready yet; call each tick. */
	public void tick()
	{
		if (pending.isEmpty())
		{
			return;
		}
		long now = System.currentTimeMillis();
		for (Iterator<ConcurrentMap.Entry<Actor, Pending>> it = pending.entrySet().iterator(); it.hasNext(); )
		{
			ConcurrentMap.Entry<Actor, Pending> e = it.next();
			Actor actor = e.getKey();
			Pending p = e.getValue();
			String current = actor.getOverheadText();
			// give up if it timed out or the bubble is gone / replaced by new (already-img) text
			if (now > p.deadline || current == null || current.isEmpty() || current.contains("<img="))
			{
				it.remove();
				continue;
			}
			String zh = translator.translate(p.english, OVERHEAD_COLOR, 0);
			if (zh != null)
			{
				actor.setOverheadText(zh);
				it.remove();
			}
		}
	}

	public void clear()
	{
		pending.clear();
	}
}
