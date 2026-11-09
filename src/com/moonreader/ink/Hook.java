package com.moonreader.ink;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.URLSpan;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 「静读天下墨水屏模式」入口，两个功能：
 *  ① 「链接」：EPUB 注释弹窗里的原生 URLSpan 换成 FixedLinkSpan，改色 + 去下划线；
 *  ② 「墨水屏主题」：EInkTheme 把彩色 / 深灰 UI 转成白底黑字。
 *
 * 同时支持两个包（Apktool M 只改 manifest 包名 / 名称，dex 类名与资源 id 不变）：
 *  com.flyersoft.moonreaderp   原版 10.8
 *  com.flyersoft.moonreaderps  用户改包名 / 名称做出来的共存版 10.8
 *
 * 开关优先级：总开关 > 各功能开关；每个开关都是
 * 「persist 系统属性 > 目标 App 内嵌面板 prefs > 模块设置页 prefs > 默认开启」。
 * 内嵌面板供 LSPatch 等无 Root 场景使用：模块 APK 未单独安装时，开关写在目标 App
 * 自己的 SharedPreferences 里，Hook 用 XSharedPreferences 直接读该文件即可。
 * 总开关关闭时 handleLoadPackage 直接返回，一个 hook 都不注册。
 * 改动需重启对应的「静读天下」App 生效。
 */
public class Hook implements IXposedHookLoadPackage {

    private static final String TAG = "MoonReaderInk";
    private static final String TARGET_MAIN = "com.flyersoft.moonreaderp";
    private static final String TARGET_COEXIST = "com.flyersoft.moonreaderps";
    private static final String MODULE = "com.moonreader.ink";
    private static final String PREFS = "settings";
    private static final String KEY_MASTER = "master";
    private static final String KEY_LINK_HOOK = "link_hook";
    private static final String KEY_INK_THEME = "ink_theme";
    private static final String PROP_MASTER = "persist.moonreader.ink.master";
    private static final String PROP_LINK_HOOK = "persist.moonreader.ink.link_hook";
    private static final String PROP_INK_THEME = "persist.moonreader.ink.theme";
    private static final int FALLBACK = 0xFF000000;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        final String pkg = lpparam.packageName;
        if (!TARGET_MAIN.equals(pkg) && !TARGET_COEXIST.equals(pkg)) return;

        if (!featureEnabled(PROP_MASTER, KEY_MASTER, pkg)) {
            XposedBridge.log(TAG + ": 总开关已关闭，不注册任何 hook (" + pkg + ")");
            return;
        }

        if (featureEnabled(PROP_INK_THEME, KEY_INK_THEME, pkg)) {
            try {
                EInkTheme.install(lpparam.classLoader, pkg);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": theme failed: " + t);
            }
        } else {
            XposedBridge.log(TAG + ": 墨水屏主题已关闭 (" + pkg + ")");
        }

        if (!featureEnabled(PROP_LINK_HOOK, KEY_LINK_HOOK, pkg)) {
            XposedBridge.log(TAG + ": 链接 Hook 已关闭，跳过注册（重启静读天下生效）");
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.flyersoft.views.PopupNoteLay",
                    lpparam.classLoader,
                    "setText",
                    CharSequence.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                fix(param, lpparam.classLoader);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": fix failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked " + pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed: " + t);
        }
    }

    /**
     * 总开关与两个功能共用的开关读取，优先级：
     *  ① persist 系统属性（0/1，root 下 setprop，最可靠）
     *  ② 目标 App 内嵌面板 prefs（无 Root / LSPatch 场景，模块 APK 未单独安装也能生效）
     *  ③ 模块设置页 prefs（LSPosed 的 MODULE_SETTINGS 入口）
     *  ④ 都读不到时默认开启，避免开关本身出问题导致功能悄悄失效。
     * 属性：persist.moonreader.ink.master / .link_hook / .theme
     */
    private static boolean featureEnabled(String prop, String prefKey, String pkg) {
        try {
            Class<?> sysProps = Class.forName("android.os.SystemProperties");
            String v = (String) sysProps.getMethod("get", String.class, String.class)
                    .invoke(null, prop, "");
            if ("0".equals(v)) return false;
            if ("1".equals(v)) return true;
        } catch (Throwable ignored) {
            // 取不到属性就往下走
        }
        try {
            XSharedPreferences inApp = new XSharedPreferences(
                    pkg, SettingsUi.IN_APP_PREFS);
            inApp.reload();
            if (inApp.getBoolean(SettingsUi.KEY_INIT, false)) {
                return inApp.getBoolean(prefKey, true);
            }
        } catch (Throwable ignored) {
            // 内嵌面板没用过或文件不可读，继续看模块设置页
        }
        try {
            XSharedPreferences prefs = new XSharedPreferences(MODULE, PREFS);
            prefs.reload();
            return prefs.getBoolean(prefKey, true);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": prefs 不可读，" + prefKey + " 按默认开启: " + t);
            return true;
        }
    }

    private static void fix(XC_MethodHook.MethodHookParam param, ClassLoader cl) {
        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof CharSequence)) return;
        CharSequence cs = (CharSequence) param.args[0];

        Spannable sp;
        if (cs instanceof Spannable) {
            sp = (Spannable) cs;
        } else if (cs instanceof Spanned) {
            // 不可变 Spanned：复制一份，copySpans 会带上原有 URLSpan
            sp = new SpannableString(cs);
        } else {
            return; // 纯文本，本来就没有链接色
        }

        URLSpan[] spans = sp.getSpans(0, sp.length(), URLSpan.class);
        if (spans.length == 0) return;

        int color = resolveColor(cl);
        boolean changed = false;
        for (URLSpan u : spans) {
            if (u instanceof FixedLinkSpan) continue;
            int start = sp.getSpanStart(u);
            int end = sp.getSpanEnd(u);
            int flags = sp.getSpanFlags(u);
            sp.removeSpan(u);
            sp.setSpan(new FixedLinkSpan(u.getURL(), color), start, end, flags);
            changed = true;
        }
        if (changed && param.args[0] != sp) param.args[0] = sp;
    }

    /** 读 App 自己的「链接颜色」，白天/晚上分别取 linkColor / linkColorNight。 */
    private static int resolveColor(ClassLoader cl) {
        try {
            Class<?> a = Class.forName("com.flyersoft.tools.A", false, cl);
            boolean night = false;
            try {
                Method m = a.getDeclaredMethod("isNightState");
                m.setAccessible(true);
                night = Boolean.TRUE.equals(m.invoke(null));
            } catch (Throwable ignored) {
                // 老版本没有这个方法就按白天处理
            }
            Field f = a.getDeclaredField(night ? "linkColorNight" : "linkColor");
            f.setAccessible(true);
            int c = f.getInt(null);
            return c == 0 ? FALLBACK : c;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": color fallback: " + t);
            return FALLBACK;
        }
    }

    /** 继承 URLSpan 是为了让 ActivityTxt 的点击判断（instanceof URLSpan）继续成立。 */
    private static class FixedLinkSpan extends URLSpan {
        private final int color;

        FixedLinkSpan(String url, int color) {
            super(url);
            this.color = color;
        }

        @Override
        public void updateDrawState(TextPaint ds) {
            ds.setColor(color);
            ds.setUnderlineText(false);
        }
    }
}
