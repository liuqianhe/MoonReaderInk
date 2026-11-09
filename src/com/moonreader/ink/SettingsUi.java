package com.moonreader.ink;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
 *
 * 整个界面自绘为墨水屏风格：纯白底 / 纯黑字 / 纯黑描边，开关为
 * OFF 白轨道黑边 + 黑圆钮、ON 黑轨道 + 白圆钮，不依赖系统或目标 App 的主题色。
 * 底部「关于」按钮显示软件名 / 包名 / 版本，并附二改与风险免责说明。
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

    public static final String MODULE_NAME = "静读天下墨水屏模式";
    public static final String MODULE_PACKAGE = "com.moonreader.ink";
    /** 兜底版本号，需与 AndroidManifest.xml 同步；模块 APK 已安装时优先读 PackageInfo。 */
    public static final String FALLBACK_VERSION = "1.6";
    public static final int FALLBACK_VERSION_CODE = 7;
    /** 关于页可点击跳转的项目主页（公开仓库）。 */
    public static final String REPO_URL = "https://github.com/liuqianhe/MoonReaderInk";

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    private SettingsUi() {}

    /** 兼容内嵌面板：不带自绘顶栏（对话框容器已有标题）。 */
    public static View build(Context ctx, SharedPreferences prefs) {
        return build(ctx, prefs, false);
    }

    /**
     * @param withHeader true = 模块自己的 Activity 用，额外画一条白底黑字顶栏 + 纯黑分割线；
     *                   false = 目标 App 内嵌对话框用，只画内容区。
     */
    public static View build(final Context ctx, final SharedPreferences prefs,
                             boolean withHeader) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(WHITE);

        if (withHeader) {
            TextView header = new TextView(ctx);
            header.setText(MODULE_NAME);
            header.setTextColor(BLACK);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            int hp = dp(ctx, 20);
            header.setPadding(hp, dp(ctx, 24), hp, dp(ctx, 18));
            header.setBackgroundColor(WHITE);
            root.addView(header, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            View line = new View(ctx);
            line.setBackgroundColor(BLACK);
            root.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 2)));
        }

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(WHITE);
        int pad = dp(ctx, 20);
        body.setPadding(pad, pad, pad, pad);
        root.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addSwitch(ctx, body, prefs, KEY_MASTER, "总开关（关闭后所有功能停用）");
        addSwitch(ctx, body, prefs, KEY_LINK_HOOK, "链接 Hook（注释弹窗链接改色 / 去下划线）");
        addSwitch(ctx, body, prefs, KEY_INK_THEME, "墨水屏主题（白底黑字，去掉蓝色 UI）");

        TextView hint = new TextView(ctx);
        hint.setText("改动在重启「静读天下」后生效。\n"
                + "无 Root 设备（LSPatch 等）可直接使用本页开关；\n"
                + "有 Root 时也能用系统属性强制覆盖：\n"
                + "adb shell setprop persist.moonreader.ink.master 0\n"
                + "属性：master / link_hook / theme，0 关 1 开");
        hint.setTextColor(BLACK);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 18);
        body.addView(hint, lp);

        TextView about = outlineButton(ctx, "关于");
        about.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showAbout(ctx); }
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.topMargin = dp(ctx, 24);
        body.addView(about, ap);
        return root;
    }

    /** 白底黑边圆角、纯黑文字的按钮（关于 / 关闭共用）。 */
    private static TextView outlineButton(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(BLACK);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setGravity(Gravity.CENTER);
        int p = dp(ctx, 14);
        tv.setPadding(p, p, p, p);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(ctx, 20));
        g.setColor(WHITE);
        g.setStroke(2, BLACK);
        tv.setBackground(g);
        return tv;
    }

    /** 关于对话框：软件信息 + 二改 / 风险免责。整体自绘黑白，不受主题影响。 */
    private static void showAbout(Context ctx) {
        String ver = FALLBACK_VERSION;
        long code = FALLBACK_VERSION_CODE;
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(MODULE_PACKAGE, 0);
            if (pi.versionName != null) ver = pi.versionName;
            code = pi.getLongVersionCode();
        } catch (Throwable ignored) {}
        String msg = "软件名称：" + MODULE_NAME + "\n"
                + "包名：" + MODULE_PACKAGE + "\n"
                + "版本：" + ver + "（versionCode " + code + "）\n\n"
                + "—— 风险与免责 ——\n"
                + "本模块仅供个人学习与技术练习，非官方、非商业。\n"
                + "任何二次修改、二次分发、商业化使用，以及由此引发的版权纠纷、"
                + "设备或数据损失、账号异常等后果，均由使用者或二改者自行承担，"
                + "与原作者无关；原作者不提供任何担保或支持。\n"
                + "详见仓库 README「二次修改与风险说明」。";
        try {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            // 黑框画在内容 View 上：系统窗口背景会盖住设在 Window 上的描边。
            GradientDrawable frame = new GradientDrawable();
            frame.setColor(WHITE);
            frame.setStroke(3, BLACK);
            box.setBackground(frame);
            int bp = dp(ctx, 22);
            box.setPadding(bp, dp(ctx, 20), bp, bp);

            TextView title = new TextView(ctx);
            title.setText("关于");
            title.setTextColor(BLACK);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            box.addView(title);

            View line = new View(ctx);
            line.setBackgroundColor(BLACK);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 2));
            llp.topMargin = dp(ctx, 14);
            box.addView(line, llp);

            TextView body = new TextView(ctx);
            body.setText(msg);
            body.setTextColor(BLACK);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(ctx, 16);
            box.addView(body, blp);

            // 可点击的仓库链接：纯黑 + 下划线，墨水屏上也能看清完整地址。
            TextView repo = new TextView(ctx);
            repo.setText("项目主页（点击打开）：\n" + REPO_URL);
            repo.setTextColor(BLACK);
            repo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            repo.setPaintFlags(repo.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            int rp = dp(ctx, 12);
            repo.setPadding(0, rp, 0, rp);
            repo.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openGitHub(ctx); }
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(ctx, 18);
            box.addView(repo, rlp);

            final TextView close = outlineButton(ctx, "关闭");
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(ctx, 22);
            box.addView(close, clp);

            ScrollView sv = new ScrollView(ctx);
            sv.setBackgroundColor(WHITE);
            sv.addView(box);

            final AlertDialog d = new AlertDialog.Builder(ctx).setView(sv).create();
            if (d.getWindow() != null) {
                // 纯白窗口 + 不压暗背景，墨水屏上避免整屏深灰遮罩造成残影；
                // 黑框由内容 View 提供，不会被窗口背景覆盖。
                d.getWindow().setBackgroundDrawable(new ColorDrawable(WHITE));
                d.getWindow().setDimAmount(0f);
            }
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { d.dismiss(); }
            });
            d.show();
        } catch (Throwable t) {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        }
    }

    /** 在浏览器打开项目仓库；无浏览器时提示完整地址，不让点击变成静默失败。 */
    private static void openGitHub(Context ctx) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(ctx, "无法打开浏览器：" + REPO_URL, Toast.LENGTH_LONG).show();
        }
    }

    private static void addSwitch(final Context ctx, LinearLayout root,
                                  final SharedPreferences prefs,
                                  final String key, String label) {
        final Switch sw = new Switch(ctx);
        sw.setText(label);
        sw.setTextColor(BLACK);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        boolean initial = prefs.getBoolean(key, true);
        sw.setChecked(initial);
        styleSwitch(sw, ctx, initial);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(ctx, 20);
        root.addView(sw, lp);

        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                styleSwitch(sw, ctx, isChecked);
                prefs.edit().putBoolean(key, isChecked)
                        .putBoolean(KEY_INIT, true).apply();
                Toast.makeText(ctx,
                        (isChecked ? "已开启 " : "已关闭 ") + key + "，重启静读天下生效",
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 系统 Switch 在 Material 主题下是彩色轨道 + 彩色滑块，墨水屏上会糊成深色块。
     * 按 checked 直接换成自绘黑白 drawable，并清掉主题自带的 accent tint。
     * 不用 StateListDrawable：实测 Switch 不会把 checked 状态可靠分发给自定义
     * track/thumb，状态改变时由 OnCheckedChangeListener 主动重设最稳。
     */
    private static void styleSwitch(Switch sw, Context ctx, boolean checked) {
        try {
            int tw = dp(ctx, 40), th = dp(ctx, 22), ts = dp(ctx, 20);
            sw.setTrackDrawable(checked ? pill(tw, th, BLACK, 0)
                                        : pill(tw, th, WHITE, BLACK));
            sw.setThumbDrawable(disc(ts, checked ? WHITE : BLACK));
            sw.setTrackTintList(null);
            sw.setThumbTintList(null);
            sw.setShowText(false);
            sw.setThumbTextPadding(0);
            sw.setSplitTrack(false);
        } catch (Throwable ignored) {}
    }

    private static GradientDrawable pill(int w, int h, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(h / 2f);
        g.setSize(w, h);
        g.setColor(fill);
        if (stroke != 0) g.setStroke(2, stroke);
        return g;
    }

    private static GradientDrawable disc(int size, int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setSize(size, size);
        g.setColor(fill);
        return g;
    }

    private static int dp(Context ctx, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }
}
