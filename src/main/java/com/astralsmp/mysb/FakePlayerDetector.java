package com.astralsmp.mysb;

import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class FakePlayerDetector {

    private static final String[] CARPET_FAKE_PLAYER_CLASSES = {
            "carpet.patches.EntityPlayerMPFake",
            "carpet.helpers.EntityPlayerActionPack",
    };

    private static final Pattern FAKE_PLAYER_NAME_PATTERN = Pattern.compile("fake[_\\-].*");

    private static final Set<String> knownFakePlayerNames = ConcurrentHashMap.newKeySet();

    private static volatile Class<?>[] fakePlayerClasses;
    private static volatile boolean reflectionInitialized = false;

    private FakePlayerDetector() {}

    public static boolean isFakePlayer(ServerPlayerEntity player) {
        if (player == null) {
            return false;
        }
        if (matchesFakePlayerClass(player)) {
            String name = player.getName().getString();
            if (knownFakePlayerNames.add(name)) {
                ServerScoreboardLogger.info("Carpet fake player を検出しました: " + name + " (class=" + player.getClass().getName() + ")");
            }
            return true;
        }
        return isFakePlayerName(player.getName().getString());
    }

    public static boolean isFakePlayerName(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return false;
        }
        if (knownFakePlayerNames.contains(playerName)) {
            return true;
        }
        return matchesFakePlayerNamePattern(playerName);
    }

    public static void registerIfFakePlayer(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        if (matchesFakePlayerClass(player)) {
            String name = player.getName().getString();
            if (knownFakePlayerNames.add(name)) {
                ServerScoreboardLogger.info("Carpet fake player を登録しました: " + name);
            }
        }
    }

    public static Set<String> getKnownFakePlayerNames() {
        return Set.copyOf(knownFakePlayerNames);
    }

    public static void addKnownFakePlayerName(String name) {
        if (name != null && !name.isBlank()) {
            knownFakePlayerNames.add(name);
        }
    }

    private static boolean matchesFakePlayerNamePattern(String playerName) {
        String lower = playerName.toLowerCase();
        if (lower.contains("fake_") || lower.startsWith("fake")) {
            return true;
        }
        if (lower.contains("_bot") || lower.endsWith("bot")) {
            return true;
        }
        return FAKE_PLAYER_NAME_PATTERN.matcher(lower).matches();
    }

    private static boolean matchesFakePlayerClass(ServerPlayerEntity player) {
        Class<?>[] classes = ensureFakePlayerClasses();
        if (classes.length == 0) {
            return false;
        }
        for (Class<?> cls : classes) {
            if (cls.isInstance(player)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?>[] ensureFakePlayerClasses() {
        if (!reflectionInitialized) {
            synchronized (FakePlayerDetector.class) {
                if (!reflectionInitialized) {
                    java.util.List<Class<?>> resolved = new java.util.ArrayList<>();
                    for (String className : CARPET_FAKE_PLAYER_CLASSES) {
                        try {
                            resolved.add(Class.forName(className));
                            ServerScoreboardLogger.info("Carpet fake player クラスを検出: " + className);
                        } catch (ClassNotFoundException ignored) {
                            // Carpet が導入されていない場合は無視
                        } catch (Throwable t) {
                            ServerScoreboardLogger.warn("Carpet fake player クラスの解決に失敗: " + className + " - " + t.getMessage());
                        }
                    }
                    fakePlayerClasses = resolved.toArray(new Class<?>[0]);
                    reflectionInitialized = true;
                }
            }
        }
        return fakePlayerClasses;
    }
}
