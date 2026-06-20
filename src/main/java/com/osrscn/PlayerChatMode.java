package com.osrscn;

/** How other players' public chat is handled. Player names are never translated in any mode. */
public enum PlayerChatMode
{
	/** Leave player chat in English (recoloured OSRS blue). */
	OFF("不翻译"),
	/** Translate the message in place (the English line becomes Chinese). */
	INLINE("翻译"),
	/** Keep the English line and add a separate Chinese line below it (e.g. "OSRS_CN: Name: 你好"). */
	INSERT("翻译并另起一行");

	private final String label;

	PlayerChatMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
