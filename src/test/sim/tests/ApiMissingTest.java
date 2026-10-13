import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import com.example.persistentbodies.compat.PlayerReviveBridge;
public class ApiMissingTest {
    public static void main(String[] a) {
        int fail = 0;
        ServerPlayer p = new ServerPlayer();
        boolean loaded = PlayerReviveBridge.isLoaded();
        boolean r = PlayerReviveBridge.kill(p);
        boolean c1 = loaded, c2 = !r, c3 = p.isAlive() && p.dieCalls == 0, c4 = LogUtils.LINES.stream().anyMatch(l -> l.startsWith("[ERROR]"));
        System.out.println("    " + (c1 ? "PASS" : "FAIL") + "  isLoaded()==true (mod present, API unusable)");
        System.out.println("    " + (c2 ? "PASS" : "FAIL") + "  kill() returns false instead of throwing");
        System.out.println("    " + (c3 ? "PASS" : "FAIL") + "  no plain player.kill()/die() was issued (alive, dieCalls=0)");
        System.out.println("    " + (c4 ? "PASS" : "FAIL") + "  ERROR logged");
        System.exit(c1 && c2 && c3 && c4 ? 0 : 1);
    }
}
