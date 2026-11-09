package de.robv.android.xposed;

/**
 * 仅用于本机编译的桩，运行时由 Xposed / LSPosed 框架提供真实实现
 * （真实类 implements android.content.SharedPreferences）。
 * Hook.java 只用到下面三个方法；保持方法签名一致即可。
 */
public class XSharedPreferences {

    public XSharedPreferences(String packageName, String prefFileName) {
        throw new RuntimeException("compile-time stub");
    }

    public void reload() {
        throw new RuntimeException("compile-time stub");
    }

    public boolean getBoolean(String key, boolean defValue) {
        throw new RuntimeException("compile-time stub");
    }
}
