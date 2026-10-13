package net.minecraftforge.fml;
public class ModList {
    public static boolean PLAYERREVIVE_LOADED = true;
    private static final ModList I = new ModList();
    public static ModList get() { return I; }
    public boolean isLoaded(String id) { return "playerrevive".equals(id) && PLAYERREVIVE_LOADED; }
}
