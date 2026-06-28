package com.friendmod;

/**
 * CommandParser v3.1
 * ==================
 * Detects action intent from natural player messages.
 * Covers every real-player action Friend can now perform.
 */
public class CommandParser {

    public enum CommandType {
        FOLLOW, STOP, FIGHT, MINE, BUILD, EXPLORE,
        FORGET, STATUS, CRAFT, SLEEP, INVENTORY,
        EMOTE, HELP, COME, RIDE, DISMOUNT, GIVE,
        OPEN_CONTAINER, EAT, CHAT
    }

    public static CommandType parse(String text) {
        String t = text.toLowerCase().trim();

        if (anyMatch(t, "follow", "come with me", "walk with me", "stay close", "stick with me"))
            return CommandType.FOLLOW;
        if (anyMatch(t, "come here", "come to me", "get over here", "teleport to me", "come back"))
            return CommandType.COME;
        if (anyMatch(t, "stop", "wait", "stay", "halt", "freeze", "don't move", "hold on"))
            return CommandType.STOP;
        if (anyMatch(t, "fight", "attack", "kill those", "help me fight", "defend", "protect me",
                        "battle", "destroy them", "smite", "take them out"))
            return CommandType.FIGHT;
        if (anyMatch(t, "mine", "dig", "excavate", "get ore", "get coal", "get iron", "get gold",
                        "get diamond", "find diamond", "find iron", "find ore", "gather resources",
                        "collect resources", "drill"))
            return CommandType.MINE;
        if (anyMatch(t, "build", "construct", "make a house", "make a shelter", "build a wall",
                        "build a base", "build a home", "build shelter", "build us"))
            return CommandType.BUILD;
        if (anyMatch(t, "explore", "scout", "look around", "wander", "go check", "roam", "venture out"))
            return CommandType.EXPLORE;
        if (anyMatch(t, "forget", "reset", "clear memory", "forget everything", "wipe memory", "start fresh"))
            return CommandType.FORGET;
        if (anyMatch(t, "what are you doing", "status", "what's your status", "what are you up to"))
            return CommandType.STATUS;
        if (anyMatch(t, "craft", "make me", "create a", "craft me", "make a sword", "make armor",
                        "make tools", "craft tools", "craft sword", "craft pickaxe"))
            return CommandType.CRAFT;
        if (anyMatch(t, "sleep", "go to bed", "it's night", "night time", "place a bed",
                        "put down a bed", "let's sleep"))
            return CommandType.SLEEP;
        if (anyMatch(t, "inventory", "what do you have", "your items", "what's in your bag",
                        "show inventory", "check bag", "what are you carrying"))
            return CommandType.INVENTORY;
        if (anyMatch(t, "dance", "wave", "bow", "spin", "jump around", "emote", "celebrate",
                        "do a trick", "show off"))
            return CommandType.EMOTE;
        if (anyMatch(t, "help", "what can you do", "commands", "abilities", "list commands", "how do i"))
            return CommandType.HELP;
        if (anyMatch(t, "get on the boat", "ride the boat", "ride my horse", "mount up", "get in the boat",
                        "ride the horse", "sit in the boat", "hop in", "get on the minecart", "ride the minecart"))
            return CommandType.RIDE;
        if (anyMatch(t, "get off", "dismount", "get out", "stop riding"))
            return CommandType.DISMOUNT;
        if (anyMatch(t, "give me", "drop your", "drop the", "hand me", "throw me", "drop some"))
            return CommandType.GIVE;
        if (anyMatch(t, "open the chest", "open that chest", "open the door", "open it", "open the furnace",
                        "check the chest", "use the chest", "open the gate"))
            return CommandType.OPEN_CONTAINER;
        if (anyMatch(t, "eat something", "eat food", "have a snack", "you hungry", "go eat"))
            return CommandType.EAT;

        return CommandType.CHAT;
    }

    private static boolean anyMatch(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
