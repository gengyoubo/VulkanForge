package github.com.gengyoubo.vulkanforge.ae2experiment;

/** Negative reflection lookup cache for the exact AE2 controller class. */
public final class Ae2ControllerReflectionFastPath {
    private static final String TARGET = "appeng.blockentity.networking.ControllerBlockEntity";
    private static volatile boolean enabled = !Boolean.getBoolean("vulkanforge.disableAe2ControllerReflection");
    private static volatile boolean countHits = Boolean.getBoolean("serverBaseline.ae2ControllerReflection");
    private static long hits;
    private static ClassValue<Boolean> missingMethod(String name) {
        return new ClassValue<>() {
            protected Boolean computeValue(Class<?> type) {
                try { type.getMethod(name); return false; }
                catch (NoSuchMethodException absent) { return true; }
                catch (Throwable uncertain) { return false; }
            }
        };
    }
    private static final ClassValue<Boolean> NO_UPGRADES = missingMethod("getUpgrades");
    private static final ClassValue<Boolean> NO_HOST = missingMethod("getHost");
    private static final ClassValue<Boolean> NO_HOST_FIELD = new ClassValue<>() {
        protected Boolean computeValue(Class<?> type) {
            try { type.getDeclaredField("host"); return false; }
            catch (NoSuchFieldException absent) { return true; }
            catch (Throwable uncertain) { return false; }
        }
    };
    private Ae2ControllerReflectionFastPath() { }
    private static boolean skip(Object target, ClassValue<Boolean> missing) {
        if (!enabled || target == null || !TARGET.equals(target.getClass().getName()) || !missing.get(target.getClass())) return false;
        if (countHits) hits++;
        return true;
    }
    public static boolean skipUpgrades(Object target) { return skip(target, NO_UPGRADES); }
    public static boolean skipHostMethod(Object target, String name) { return "getHost".equals(name) && skip(target, NO_HOST); }
    public static boolean skipHostField(Object target, String name) { return "host".equals(name) && skip(target, NO_HOST_FIELD); }
    public static void setEnabled(boolean value) { enabled = value; }
    public static void setCountHits(boolean value) { countHits = value; }
    public static long hits() { return hits; }
}
