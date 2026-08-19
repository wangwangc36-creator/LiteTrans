package de.robv.android.xposed;

public final class XposedHelpers {
    private XposedHelpers() {}

    public static Object findAndHookMethod(String className, ClassLoader classLoader,
                                           String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static Object setAdditionalInstanceField(Object obj, String key, Object value) {
        return null;
    }

    public static Object getAdditionalInstanceField(Object obj, String key) {
        return null;
    }

    public static Object removeAdditionalInstanceField(Object obj, String key) {
        return null;
    }
}
