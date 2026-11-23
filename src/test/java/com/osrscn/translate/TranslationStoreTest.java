package com.osrscn.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Wiring test for {@link TranslationStore#load()}: every other test injects the private
 * {@code glossary} field directly, so nothing would fail if load() stopped building the index from
 * the NAME table. This one drives the real load path against a local TSV and no network.
 */
public class TranslationStoreTest
{
	private static final String HEADER = "english\ttranslation\tcategory\tsub_category\tsource";

	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void loadBuildsTheGlossaryFromTheNameTable() throws Exception
	{
		File cacheDir = temporary.newFolder();
		Files.write(new File(cacheDir, "transcript_zh_name.tsv").toPath(), (HEADER + "\n"
				+ "Will\t威尔\tname\tnpc\tx\n"
				+ "Lumbridge\t仑桥镇\tname\tlocation\tx\n"
				+ "Bank\t银行\tname\tobject\tx\n").getBytes(StandardCharsets.UTF_8));

		TranslationStore store = new TranslationStore();
		// Swap the private final cache directory for the fixture one. baseUrl stays null, so
		// fetchRemoteHashes() returns null immediately and load() never issues an HTTP request.
		Field dir = TranslationStore.class.getDeclaredField("cacheDir");
		dir.setAccessible(true);
		dir.set(store, cacheDir);
		Method load = TranslationStore.class.getDeclaredMethod("load");
		load.setAccessible(true);
		load.invoke(store);

		assertTrue("load() must mark the store loaded", store.isLoaded());
		assertEquals("the fixture rows must be parsed", 3, store.size());
		// A reviewed common word is filtered out of the glossary by load() itself.
		assertEquals("", store.glossaryPrompt("Will you help me?"));
		// A real name proves the glossary was actually built from the NAME table.
		assertTrue("a NAME row must reach the prompt",
				store.glossaryPrompt("Meet me in Lumbridge.").contains("Lumbridge => 仑桥镇"));
		assertFalse("the reviewed word must not reach the prompt",
				store.glossaryPrompt("Will you help me?").contains("Will =>"));
		assertEquals("", store.glossaryPrompt("Nothing here."));
	}
}
