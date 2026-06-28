package com.friendmod;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * ItemNameParser
 * ==============
 * Turns free text like "make me a diamond sword" or "craft me some sticks"
 * into a real Minecraft item ID by matching against the actual item registry,
 * instead of a hardcoded keyword list — so it works for any item that exists
 * in vanilla, not just a handful we anticipated.
 */
public class ItemNameParser {

    // Words that show up in crafting requests but aren't part of the item name.
    private static final Set<String> STOPWORDS = Set.of(
        "friend", "make", "me", "a", "an", "some", "craft", "please", "can",
        "you", "the", "i", "want", "need", "give", "us", "for", "to", "build",
        "create", "get", "us", "now", "and"
    );

    /**
     * Finds the best-matching item for the free-text request.
     * Matches against real item registry names (e.g. "diamond sword" -> minecraft:diamond_sword),
     * including partial/out-of-order matches, so word order or extra fluff doesn't break it.
     *
     * @return the matched Item, or null if nothing in the registry matches well enough.
     */
    public static Item findItem(String requestText) {
        List<String> words = cleanWords(requestText);
        if (words.isEmpty()) return null;

        String joined = String.join("_", words);

        // 1. Exact id match first (handles "diamond_sword" or "diamond sword" cleanly).
        Identifier exact = Identifier.tryParse("minecraft:" + joined);
        if (exact != null && Registries.ITEM.containsId(exact)) {
            return Registries.ITEM.get(exact);
        }

        // 2. Best partial match: find the registry item whose path shares the most
        // request words, preferring the item whose name uses ALL the given words.
        Item bestMatch = null;
        int bestScore = 0;

        for (Item candidate : Registries.ITEM) {
            Identifier id = Registries.ITEM.getId(candidate);
            if (id == null || id.getPath().isEmpty()) continue;
            String[] idWords = id.getPath().split("_");
            Set<String> idWordSet = new HashSet<>(Arrays.asList(idWords));

            int score = 0;
            boolean allPresent = true;
            for (String w : words) {
                if (idWordSet.contains(w)) score++;
                else allPresent = false;
            }

            // Require every request word to appear in the item's id — avoids
            // "sword" alone matching every sword type unpredictably, while still
            // allowing "diamond sword" to match minecraft:diamond_sword exactly.
            if (allPresent && score == words.size()) {
                // Prefer the shortest id (closest literal match) when several qualify,
                // e.g. "stone" should match minecraft:stone, not minecraft:stone_brick_stairs.
                if (bestMatch == null || idWords.length < Registries.ITEM.getId(bestMatch).getPath().split("_").length) {
                    bestMatch = candidate;
                    bestScore = score;
                }
            }
        }

        return bestScore > 0 ? bestMatch : null;
    }

    /** Convenience: build a 1-count ItemStack of the best match, or null. */
    public static ItemStack findItemStack(String requestText) {
        Item item = findItem(requestText);
        return item == null ? null : new ItemStack(item);
    }

    private static List<String> cleanWords(String text) {
        String[] raw = text.toLowerCase()
            .replaceAll("[^a-z0-9\\s]", " ")
            .trim()
            .split("\\s+");

        List<String> words = new ArrayList<>();
        for (String w : raw) {
            if (w.isBlank() || STOPWORDS.contains(w)) continue;
            words.add(w);
        }
        return words;
    }
}
