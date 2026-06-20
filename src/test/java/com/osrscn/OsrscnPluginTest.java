package com.osrscn;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class OsrscnPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OsrscnPlugin.class);
		RuneLite.main(args);
	}
}
