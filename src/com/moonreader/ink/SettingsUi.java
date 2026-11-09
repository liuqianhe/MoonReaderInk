package com.moonreader.ink;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置界面：模块 APK 自己的设置页（SettingsActivity）与目标 App 内嵌面板共用。
 *
 * 无 Root 设备用 LSPatch 等把模块打进目标 APK 时，模块 APK 并不作为独立应用安装，
 * 也就没有「模块设置」入口。此时由 EInkTheme 在目标 App 的设置页底部放一个入口，
 * 点击后用同一个 build() 在目标进程里弹对话框；开关写入目标 App 自己的 prefs，
 * Hook 用 XSharedPreferences 直接读该文件，不依赖模块 APK 是否安装。
 */
public final class SettingsUi {

    /** 模块 APK 私有 prefs（LSPosed 管理器进入的设置页使用）。 */
    public static final String PREFS = "settings";
    /** 目标 App 内嵌面板使用的 prefs（无 Root / LSPatch 场景）。 */
    public static final String IN_APP_PREFS = "moonreader_ink_settings";
    public static final String KEY_MASTER = "master";
    public static final String KEY_LINK_HOOK = "link_hook";
    public static final String KEY_INK_THEME = "ink_theme";
    /** 内嵌面板写过开关的标记，用于区分「没设置过」与「设成了默认值」。 */
    public static final String KEY_INIT = "__initialized";

    private SettingsUi() {}

    public static View build(final Context ctx, final SharedPreferences prefs) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(ctx, 20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("静读天下墨水屏模式");
        title.setTextColor(Color.BLACK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        root.addView(title);

        addSwitch(ctx, root, prefs, KEY_MASTER, "总开关（关闭后所有功能停用）");
        addSwitch(ctx, root, prefs, KEY_LINK_HOOK, "链接 Hook（注释弹窗链接改色 / 去下划线）");
        addSwitch(ctx, root, prefs, KEY_INK_THEME, "墨水屏主题（白底黑字，去掉蓝色 UI）");

        TextView hint = new TextView(ctx);
        hint.setText("改动在重启「静读天下」后生效。\n"
                + "无 Root 设备（LSPatch 等）可直接使用本页开关；\n"
                + "有 Root 时也能用系统属性强制覆盖：\n"
                + "adb shell setprop persist.moonreader.ink.master 0\n"
                + "属性：master / link_hook / theme，0 关 1 开");
        hint.setTextColor(Color.DKGRAY);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 18);
        root.addView(hint, lp);
        return root;
    }

    private static void addSwitch(final Context ctx, LinearLayout root,
                                  final SharedPreferences prefs,
                                  final String key, String label) {
        Switch sw = new Switch(ctx);
        sw.setText(label);
        sw.setTextColor(Color.BLACK);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setChecked(prefs.getBoolean(key, true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 20);
        root.addView(sw, lp);

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked)
                        .putBoolean(KEY_INIT, true).apply();
                Toast.makeText(ctx,
                        (isChecked ? "已开启 " : "已关闭 ") + key + "，重启静读天下生效",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private static int dp(Context ctx, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }
}
