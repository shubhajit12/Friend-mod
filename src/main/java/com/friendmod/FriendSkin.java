package com.friendmod;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.util.UUID;

/**
 * FriendSkin
 * ==========
 * Builds the GameProfile used when spawning Friend as a fake player.
 * The GameProfile holds her UUID, name, and skin texture property.
 *
 * The skin texture property is a base64-encoded JSON blob telling
 * Minecraft where to fetch the skin PNG from (must be an HTTPS URL).
 *
 * To apply the rainbow skin:
 *   1. Upload friend_skin.png to imgur.com
 *   2. Set skin_url=https://i.imgur.com/yourId.png in config
 *   3. Restart — Friend loads with the rainbow skin
 */
public class FriendSkin {

    // Fixed UUID — Friend is always the same "player" identity
    public static final UUID FRIEND_UUID = UUID.fromString("a8e3a0f2-d0b0-4b3e-9c1a-2f7e6d5c4b3a");

    /**
     * Build a GameProfile for Friend.
     * If skin_url is set in config, applies it as the skin texture.
     */
    public static GameProfile buildProfile() {
        String name    = FriendConfig.getBotName();
        String skinUrl = FriendConfig.getSkinUrl();

        GameProfile profile = new GameProfile(FRIEND_UUID, name);

        if (skinUrl != null && !skinUrl.isBlank()) {
            // Build Mojang texture JSON
            String textureJson = "{\"textures\":{\"SKIN\":{\"url\":\"" + skinUrl
                + "\",\"metadata\":{\"model\":\"slim\"}}}}";
            String textureValue = java.util.Base64.getEncoder()
                .encodeToString(textureJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            profile.getProperties().put("textures", new Property("textures", textureValue, ""));
            FriendMod.LOGGER.info("[FriendMod] Skin applied from: " + skinUrl);
        } else {
            FriendMod.LOGGER.info("[FriendMod] No skin_url set — Friend uses default skin.");
            FriendMod.LOGGER.info("[FriendMod] Upload friend_skin.png to imgur and set skin_url= in config.");
        }

        return profile;
    }

    public static net.minecraft.util.Identifier getBundledSkinId() {
        return net.minecraft.util.Identifier.of("friendmod", "textures/entity/friend_skin.png");
    }
}
