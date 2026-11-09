package com.moonreader.ink;

import android.app.Activity;
import android.os.Bundle;

/**
 * 模块设置页（LSPosed 的 MODULE_SETTINGS 入口）。
 * 界面与无 Root 场景下目标 App 内的嵌面板共用 SettingsUi。
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(SettingsUi.build(this,
                getSharedPreferences(SettingsUi.PREFS, MODE_PRIVATE)));
    }
}
