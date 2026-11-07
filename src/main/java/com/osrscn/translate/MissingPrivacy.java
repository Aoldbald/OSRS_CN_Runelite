package com.osrscn.translate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared collection/upload checks. This is not a general-purpose identity detector. */
final class MissingPrivacy
{
    static final String VERSION = "missing-privacy-1";
    private static final String MARKER = "# " + VERSION + " ";
    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList(
        "dialogue", "interface", "gameText", "journal", "name", "examine", "actions",
        "inventoryActions", "lvlUp", "supplement"));
    private static final Pattern BREAKS = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");
    private static final Pattern IDENTITIES = Pattern.compile(
        "(?i)\\btop three\\b.{0,80}\\bwere\\b|\\btop crab crusher\\b"
        + "|\\btrading with\\s*:|\\bhas [0-9,]+ free inventory slots\\b|\\boffers\\s*:$"
        + "|(?:'|\u2019)s group is\\s*:|^showing items\\s*:|^unable to find\\b"
        + "|\\brequesting access to chat as guest of\\b"
        + "|\\bnow talking in chat-channel\\b|\\byou are now a guest of\\b");

    private MissingPrivacy() { }

    /** Null means allowed by this bounded policy; reasons contain no input text. */
    static String rejection(String row)
    {
        if (row == null || row.length() > 600) return "row_size";
        String[] fields = row.split("\t", -1);
        if (fields.length != 5) return "columns";
        if (fields[0].trim().isEmpty() || !fields[1].isEmpty()) return "not_missing";
        if (!CATEGORIES.contains(fields[2])) return "category";
        for (String field : fields)
        {
            for (int i = 0; i < field.length(); i++)
            {
                char c = field.charAt(i);
                if (Character.isISOControl(c)) return "control_character";
                if (Character.isHighSurrogate(c))
                {
                    if (i + 1 >= field.length() || !Character.isLowSurrogate(field.charAt(++i)))
                        return "invalid_unicode";
                }
                else if (Character.isLowSurrogate(c)) return "invalid_unicode";
            }
            if (identityContext(field)) return "identity_context";
        }
        return null;
    }

    static boolean identityContext(String text)
    {
        if (text == null) return false;
        String plain = TAGS.matcher(BREAKS.matcher(text).replaceAll(" ")).replaceAll("");
        return IDENTITIES.matcher(SPACES.matcher(plain).replaceAll(" ").trim()).find();
    }

    static String marker(String row) { return MARKER + hash(row); }

    static boolean attested(String previousLine, String row)
    {
        return marker(row).equals(previousLine);
    }

    static String hash(String value)
    {
        try
        {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : bytes) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return out.toString();
        }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
