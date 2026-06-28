package com.friendmod;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * FriendConfig
 * ============
 * Loads settings from .minecraft/config/friendmod.properties
 * Includes Gemini API key, model, skin URL, and bot name.
 */
public class FriendConfig {

    private static final Path CONFIG_PATH = Paths.get("config", "friendmod.properties");

    private static String geminiApiKey = "PASTE_YOUR_GEMINI_KEY_HERE";
    private static String geminiModel   = "gemini-1.5-flash";
    private static String skinUrl       = ""; // optional custom skin URL
    private static String botName       = "Friend";

    public static void load() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            if (Files.exists(CONFIG_PATH)) {
                Properties props = new Properties();
                try (InputStream in = Files.newInputStream(CONFIG_PATH)) {
                    props.load(in);
                }
                geminiApiKey = props.getProperty("gemini_api_key", geminiApiKey);
                geminiModel  = props.getProperty("gemini_model",   geminiModel);
                skinUrl      = props.getProperty("skin_url",       skinUrl);
                botName      = props.getProperty("bot_name",       botName);

                if (geminiApiKey.equals("PASTE_YOUR_GEMINI_KEY_HERE")) {
                    FriendMod.LOGGER.warn("[FriendMod] ⚠ No API key set! Open config/friendmod.properties.");
                } else {
                    FriendMod.LOGGER.info("[FriendMod] Config loaded. Model: " + geminiModel);
                }
            } else {
                save();
                FriendMod.LOGGER.info("[FriendMod] Created config at: " + CONFIG_PATH.toAbsolutePath());
            }
        } catch (IOException e) {
            FriendMod.LOGGER.error("[FriendMod] Could not load config: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            Properties props = new Properties();
            props.setProperty("gemini_api_key", geminiApiKey);
            props.setProperty("gemini_model",   geminiModel);
            props.setProperty("skin_url",       skinUrl);
            props.setProperty("bot_name",       botName);

            try (OutputStream out = Files.newOutputStream(CONFIG_PATH)) {
                props.store(out,
                    " FriendMod Configuration\n" +
                    " ========================\n" +
                    " gemini_api_key  = your key from aistudio.google.com\n" +
                    " gemini_model    = gemini-1.5-flash (fast) or gemini-1.5-pro (smarter)\n" +
                    " skin_url        = URL to a 64x64 PNG skin (optional)\n" +
                    " bot_name        = display name for Friend (default: Friend)"
                );
            }
        } catch (IOException e) {
            FriendMod.LOGGER.error("[FriendMod] Could not save config: " + e.getMessage());
        }
    }

    public static String getGeminiApiKey() { return geminiApiKey; }
    public static String getGeminiModel()   { return geminiModel; }
    public static String getSkinUrl()       { return skinUrl; }
    public static String getBotName()       { return botName; }
}
