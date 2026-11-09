package com.moonreader.ink;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块设置页（纯代码 UI，不依赖布局资源 / AppCompat）。
 * 一个功能一个开关，另有一个模块级总开关；后续墨水屏功能往这里加即可。
 */
public class SettingsActivity extends Activity {

    private static final String PREFS = "settings";
    private static final String KEY_MASTER = "master";
    private static final String KEY_LINK_HOOK = "link_hook";
    private static final String KEY_INK_THEME = "ink_theme";

    private SharedPreferences prefs;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("静读天下墨水屏模式");
        title.setTextColor(Color.BLACK);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        root.addView(title);

        addSwitch(KEY_MASTER, "总开关（关闭后所有功能停用）");
        addSwitch(KEY_LINK_HOOK, "链接 Hook（注释弹窗链接改色 / 去下划线）");
        addSwitch(KEY_INK_THEME, "墨水屏主题（白底黑字，去掉蓝色 UI）");

        TextView hint = new TextView(this);
        hint.setText("总开关关闭后，链接 Hook 与墨水屏主题全部停用。\n"
                + "墨水屏主题：彩色 / 深灰背景转白，文字与图标转黑，封面和正文不动。\n"
                + "链接 Hook 关闭后完全不注册 hook（官方版修复后可用）。\n"
                + "原版 com.flyersoft.moonreaderp 与共存版 com.flyersoft.moonreaderps\n"
                + "都要在 LSPosed 作用域里勾选，改动重启对应 App 生效。\n\n"
                + "adb 强制覆盖（LSPosed 读不到设置页时最可靠）：\n"
                + "python tools/ink_switch.py master off\n"
                + "python tools/ink_switch.py [theme] off|on|status|clear");
        hint.setTextColor(Color.DKGRAY);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(18);
        root.addView(hint, hintLp);

        setContentView(root);
    }

    private void addSwitch(final String key, String label) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(Color.BLACK);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sw.setChecked(prefs.getBoolean(key, true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        root.addView(sw, lp);

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                prefs.edit().putBoolean(key, isChecked).apply();
                Toast.makeText(SettingsActivity.this,
                        (isChecked ? "已开启 " : "已关闭 ") + key + "，重启静读天下生效",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
