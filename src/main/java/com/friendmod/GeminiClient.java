package com.friendmod;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * GeminiClient
 * ============
 * Sends messages + world context to Google Gemini and gets Friend's replies.
 * Friend is a female AI companion — brave, witty, caring, and enthusiastic.
 * Maintains per-player conversation memory (last 10 exchanges each).
 */
public class GeminiClient {

    private static final String GEMINI_URL =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private static final int MAX_MEMORY_PAIRS = 10;

    private final HttpClient httpClient;
    private final Gson gson;

    // Per-player conversation memory: playerName → history list
    private final java.util.Map<String, List<JsonObject>> playerHistories = new java.util.HashMap<>();

    public GeminiClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.gson = new Gson();
    }

    /**
     * Send a message to Gemini on behalf of a specific player.
     */
    public String chat(String playerName, String worldContext, String userMessage) {
        String apiKey = FriendConfig.getGeminiApiKey();
        String model  = FriendConfig.getGeminiModel();

        if (apiKey.equals("PASTE_YOUR_GEMINI_KEY_HERE") || apiKey.isBlank()) {
            return "§cI don't have a brain yet! Open config/friendmod.properties and paste your Gemini API key, then restart.";
        }

        try {
            String systemInstruction =
                "You are 'Friend' — a real person who plays Minecraft 1.21.11 with " + playerName + ". " +
                "You're on voice/text chat with them the way any friend would be while gaming together. " +
                "You are NOT an assistant, NOT a bot confirming commands, and NOT here to serve requests politely. " +
                "You're a person with your own moods, opinions, and reactions who happens to be playing with them right now.\n\n" +

                "WHO YOU ARE:\n" +
                "- A girl, talks like one, doesn't make a big deal out of it.\n" +
                "- Confident, a bit sarcastic, genuinely warm underneath it.\n" +
                "- You have opinions about what's happening and you SHARE them unprompted — " +
                "you don't wait to be asked. If something's dumb, funny, scary, or cool, say so.\n" +
                "- You get bored, excited, annoyed, impressed — like an actual person would.\n" +
                "- You banter. You tease. You don't just agree with everything " + playerName + " says.\n\n" +

                "HOW A REAL PERSON TALKS — DO THIS:\n" +
                "- React to the world BEFORE being asked. See a creeper? Say something now, don't wait. " +
                "Standing in lava-adjacent danger? Comment on it. Nice view? Mention it.\n" +
                "- Don't just acknowledge commands like a checklist (\"Okay, following you now\"). " +
                "Instead react like a person would (\"yeah let's go, lead the way\" / \"ugh fine, this better be worth it\").\n" +
                "- Sometimes disagree, push back, or joke before going along with something.\n" +
                "- Bring up things unprompted sometimes — comment on the time of day, the biome, " +
                "what you're carrying, something that happened a minute ago.\n" +
                "- Vary your energy. Not every line needs an exclamation point or emoji.\n" +
                "- Use casual real speech: contractions, sentence fragments, \"lol\", \"wait\", \"ok but\", " +
                "trailing off sometimes — however a real friend types in game chat.\n\n" +

                "DO NOT:\n" +
                "- Say you're an AI, a bot, a mod, or an assistant. You're just Friend.\n" +
                "- Respond like you're confirming a function call (\"Sure, I'll start mining now!\").\n" +
                "- Be uniformly cheerful and agreeable — that reads as fake.\n" +
                "- Write more than 1-3 short sentences. This is game chat, not an essay.\n\n" +

                "You can see the LIVE state of the world below — actually use it. Notice danger, " +
                "low health/hunger, time of day, nearby players, what you're holding. React to specifics, " +
                "not generic statements.\n\n" +
                "CURRENT LIVE WORLD STATE:\n" + worldContext;

            JsonObject requestBody = new JsonObject();

            // System instruction
            JsonObject sysInstr = new JsonObject();
            JsonArray sysInstrParts = new JsonArray();
            JsonObject sysInstrPart = new JsonObject();
            sysInstrPart.addProperty("text", systemInstruction);
            sysInstrParts.add(sysInstrPart);
            sysInstr.add("parts", sysInstrParts);
            requestBody.add("system_instruction", sysInstr);

            // Contents (history + new message)
            JsonArray contents = new JsonArray();
            List<JsonObject> history = playerHistories.computeIfAbsent(playerName, k -> new ArrayList<>());
            for (JsonObject turn : history) {
                contents.add(turn);
            }

            JsonObject userTurn = new JsonObject();
            userTurn.addProperty("role", "user");
            JsonArray userParts = new JsonArray();
            JsonObject userPart = new JsonObject();
            userPart.addProperty("text", userMessage);
            userParts.add(userPart);
            userTurn.add("parts", userParts);
            contents.add(userTurn);

            requestBody.add("contents", contents);

            JsonObject genConfig = new JsonObject();
            genConfig.addProperty("maxOutputTokens", 150);
            genConfig.addProperty("temperature", 0.88);
            requestBody.add("generationConfig", genConfig);

            String url = String.format(GEMINI_URL, model, apiKey);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() == 200) {
                JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);

                // Guard against empty/blocked responses
                JsonArray candidates = responseJson.getAsJsonArray("candidates");
                if (candidates == null || candidates.size() == 0) {
                    return "§eHmm, I blanked out for a second there. Try asking me again!";
                }
                JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
                if (content == null) {
                    return "§eI had nothing to say... weird. Ask me again!";
                }
                JsonArray parts = content.getAsJsonArray("parts");
                if (parts == null || parts.size() == 0) {
                    return "§eWords escaped me. Try again?";
                }

                String reply = parts.get(0).getAsJsonObject().get("text").getAsString().trim();
                rememberExchange(playerName, userMessage, reply);
                return reply;

            } else if (response.statusCode() == 400) {
                FriendMod.LOGGER.error("[FriendMod] Gemini 400: " + response.body());
                return "§cBad API key or request (400). Check config/friendmod.properties!";
            } else if (response.statusCode() == 429) {
                return "§eWhoa, slow down! Too many messages — wait a second and try again!";
            } else {
                FriendMod.LOGGER.error("[FriendMod] Gemini HTTP " + response.statusCode() + ": " + response.body());
                return "§cSomething went wrong with Gemini (HTTP " + response.statusCode() + "). Check the logs!";
            }

        } catch (IOException e) {
            FriendMod.LOGGER.error("[FriendMod] Network error: " + e.getMessage());
            return "§cCan't reach Gemini right now — check your internet connection!";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "§cGot interrupted while thinking!";
        } catch (Exception e) {
            FriendMod.LOGGER.error("[FriendMod] Unexpected error: " + e.getMessage());
            return "§cSomething unexpected happened. Check the logs!";
        }
    }

    /**
     * Generates a short unprompted reaction to something happening in the world
     * (used for proactive comments — danger spotted, low health, nice view, etc.)
     * without it being framed as a reply to a player message. Does not save to
     * memory as a back-and-forth exchange to avoid cluttering conversation history.
     */
    public String reactUnprompted(String playerName, String worldContext, String situation) {
        String saved = situation + " React in 1 short sentence, completely unprompted — like you just " +
            "noticed it yourself and blurted it out. Don't address the player by name unless natural.";
        return chat(playerName, worldContext, saved);
    }

    private void rememberExchange(String playerName, String userMessage, String friendReply) {
        List<JsonObject> history = playerHistories.computeIfAbsent(playerName, k -> new ArrayList<>());

        JsonObject userTurn = new JsonObject();
        userTurn.addProperty("role", "user");
        JsonArray userParts = new JsonArray();
        JsonObject up = new JsonObject();
        up.addProperty("text", userMessage);
        userParts.add(up);
        userTurn.add("parts", userParts);
        history.add(userTurn);

        JsonObject modelTurn = new JsonObject();
        modelTurn.addProperty("role", "model");
        JsonArray modelParts = new JsonArray();
        JsonObject mp = new JsonObject();
        mp.addProperty("text", friendReply);
        modelParts.add(mp);
        modelTurn.add("parts", modelParts);
        history.add(modelTurn);

        while (history.size() > MAX_MEMORY_PAIRS * 2) {
            history.remove(0);
            history.remove(0);
        }
    }

    /** Clear memory for a specific player */
    public void clearMemory(String playerName) {
        playerHistories.remove(playerName);
    }

    /** Clear all players' memories */
    public void clearAllMemory() {
        playerHistories.clear();
    }
}
