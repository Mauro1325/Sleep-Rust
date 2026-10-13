package com.mojang.logging;
import org.slf4j.Logger;
public final class LogUtils {
    public static final java.util.List<String> LINES = new java.util.ArrayList<>();
    public static Logger getLogger() {
        return new Logger() {
            private void log(String lvl, String m, Object[] a) {
                String s = "[" + lvl + "] " + m;
                if (a != null && a.length > 0 && a[a.length - 1] instanceof Throwable t) s += "  <<exception: " + t + ">>";
                LINES.add(s);
                System.out.println("      " + s);
            }
            public void info(String m, Object... a) { log("INFO", m, a); }
            public void warn(String m, Object... a) { log("WARN", m, a); }
            public void error(String m, Object... a) { log("ERROR", m, a); }
        };
    }
}
