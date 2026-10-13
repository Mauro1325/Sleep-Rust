package net.minecraft.server.level;
public class ServerPlayer extends net.minecraft.world.entity.player.Player {
    public static class Profile { public String getName() { return "Steve"; } }
    public Profile getGameProfile() { return new Profile(); }
}
