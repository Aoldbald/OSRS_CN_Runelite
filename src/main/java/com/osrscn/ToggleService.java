package com.osrscn;

import javax.inject.Singleton;

/** Holds the current display language. Chinese on by default; toggled to English and back. */
@Singleton
public class ToggleService
{
	private boolean chineseEnabled = true;

	public boolean isChineseEnabled()
	{
		return chineseEnabled;
	}

	public boolean toggle()
	{
		chineseEnabled = !chineseEnabled;
		return chineseEnabled;
	}
}
