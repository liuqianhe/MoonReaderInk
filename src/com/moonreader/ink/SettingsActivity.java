package com.moonreader.ink;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;

/**
 * 模块设置页（LSPosed 的 MODULE_SETTINGS 入口）。
 * 界面与无 Root 场景下目标 App 内的嵌面板共用 SettingsUi。
 *
 * 本身也按墨水屏风格处理：去掉系统 ActionBar（改用 SettingsUi 自绘的白底黑字顶栏），
 * 窗口 / 状态栏 / 导航栏统一纯白，状态栏与导航栏图标改黑。
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        w.setStatusBarColor(Color.WHITE);
        w.setNavigationBarColor(Color.WHITE);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);

        setContentView(SettingsUi.build(this,
                getSharedPreferences(SettingsUi.PREFS, MODE_PRIVATE), true));
    }
}
