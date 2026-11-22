package com.moonreader.ink;

import android.content.SharedPreferences;
import android.content.res.Resources;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 全局功能：屏蔽系统字体（移植自 JDRead1 6.5 的「忽略系统字体」）。
 *
 * 与「链接 Hook / 墨水屏主题」不同，这个功能不属于某个 App 的主题：
 * 只要进程里存在 com.flyersoft.tools.A#getLocalFontfaces，就直接注册 hook，
 * 不受目标包名限制（静读天下原版 / 共存版 / 其它改包名版本都生效）。
 *
 * 字体来源（反编译 A.getLocalFontfaces 确认）：
 *   ① Resources.getTextArray(R.array.font_faces)  —— 内置字体名
 *   ② A.getAssetFonts()                          —— assets 里的字体
 *   ③ A.outerFontsFolder                         —— 用户指定字体目录（保留）
 *   ④ A.SYSTEM_FONT_PATH (/system/fonts)         —— 系统字体
 * 开关打开后：①② 返回空、④ 的结果从列表里移除，只剩 ③。
 * 开关读取仍走「系统属性 > 内嵌面板 prefs > 模块设置页 prefs > 默认开启」。
 */
public final class GlobalFonts {

    private static final String TAG = "MoonReaderInk/Fonts";
    private static final String MODULE = "com.moonreader.ink";
    private static final String PROP = "persist.moonreader.ink.block_font";
    private static final String FONT_CLASS = "com.flyersoft.tools.A";
    private static volatile boolean sInstalled = false;

    private GlobalFonts() {}

    /** 对所有包调用；内部自行判断类是否存在与开关状态。 */
    static void tryInstall(ClassLoader cl, String pkg) {
        if (cl == null || !enabled(pkg)) return;
        try {
            Class<?> fontClass = Class.forName(FONT_CLASS, false, cl);
            FontHooks.install(cl, fontClass);
            if (!sInstalled) {
                sInstalled = true;
                XposedBridge.log(TAG + ": installed for " + pkg);
            }
        } catch (ClassNotFoundException ignored) {
            // 绝大多数 App 没有这个类，正常跳过
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": install failed for " + pkg + ": " + t);
        }
    }

    private static boolean enabled(String pkg) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, PROP, "");
            if ("0".equals(v)) return false;
            if ("1".equals(v)) return true;
        } catch (Throwable ignored) {}
        try {
            XSharedPreferences inApp = new XSharedPreferences(
                    pkg, SettingsUi.IN_APP_PREFS);
            inApp.reload();
            if (inApp.getBoolean(SettingsUi.KEY_INIT, false)) {
                return inApp.getBoolean(SettingsUi.KEY_BLOCK_FONT, true);
            }
        } catch (Throwable ignored) {}
        try {
            XSharedPreferences prefs = new XSharedPreferences(MODULE, SettingsUi.PREFS);
            prefs.reload();
            return prefs.getBoolean(SettingsUi.KEY_BLOCK_FONT, true);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": prefs 不可读，按默认开启: " + t);
            return true;
        }
    }

    private static final class FontHooks {
        static void install(final ClassLoader cl, Class<?> fontClass) {
            // ① 内置字体名数组返回空
            final int fontFacesId = fontFacesArrayId(cl);
            if (fontFacesId != 0) {
                XposedHelpers.findAndHookMethod("android.content.res.Resources", cl,
                        "getTextArray", int.class, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam p) {
                                try {
                                    if (p.args[0] instanceof Integer
                                            && (Integer) p.args[0] == fontFacesId) {
                                        p.setResult(new CharSequence[0]);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
            }
            // ② assets 字体返回空
            try {
                XposedHelpers.findAndHookMethod(FONT_CLASS, cl, "getAssetFonts",
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam p) {
                                p.setResult(new ArrayList<String>());
                            }
                        });
            } catch (Throwable ignored) {}

            // ④ 最终结果里剔除 /system/fonts 的文件名
            XposedHelpers.findAndHookMethod(FONT_CLASS, cl, "getLocalFontfaces",
                    String.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                Object r = p.getResult();
                                if (!(r instanceof List)) return;
                                List<?> list = (List<?>) r;
                                Set<String> system = systemFontNames(fontClass);
                                list.removeAll(system);
                                XposedBridge.log(TAG + ": filtered " + list.size()
                                        + " fonts (removed " + system.size() + " system fonts)");
                            } catch (Throwable ignored) {}
                        }
                    });
        }

        private static int fontFacesArrayId(ClassLoader cl) {
            try {
                Class<?> r = Class.forName("com.flyersoft.moonreaderp.R$array", false, cl);
                Object v = r.getField("font_faces").get(null);
                return v instanceof Integer ? (Integer) v : 0;
            } catch (Throwable ignored) {
                return 0;
            }
        }

        private static Set<String> systemFontNames(Class<?> fontClass) {
            Set<String> names = new HashSet<String>();
            try {
                java.lang.reflect.Field f = fontClass.getField("SYSTEM_FONT_PATH");
                f.setAccessible(true);
                String path = (String) f.get(null);
                File dir = new File(path == null ? "/system/fonts" : path);
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isFile()) names.add(file.getName());
                    }
                }
            } catch (Throwable ignored) {}
            return names;
        }
    }
}
