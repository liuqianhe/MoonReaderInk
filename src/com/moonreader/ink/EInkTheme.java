package com.moonreader.ink;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「墨水屏主题」：消掉彩色，落到「大部分背景纯白、主要按钮 / 书名黑字、边缘纯黑」，
 * 参考开源阅读标准版 com.legado.app.release 的 E-Ink 主题（不用 max 分支的灰度滤镜）。
 *
 * 踩过的坑（勿再犯）：
 * ① 白底上漏染一个白图标就「看不见」：所有非封面 ImageView、TextView compound
 *    drawables、动态 setImageDrawable / setImageResource 都要补染黑。
 * ② 蓝底白字徽标（EPUB 默认封面）必须**原地改像素**，绝不 setResult 换 Bitmap：
 *    Fresco 用 inBitmap 复用，替换对象会让整块图标不显示。
 * ③ 悬浮按钮不是 FloatingActionButton：是 fabLay / fabBottom / fabIcon 三个 id。
 *    圆和图标必须画在**同一个 Drawable** 里（fabIcon 的 144x144 bounds），
 *    否则两个 View 尺寸不同，圆圈和前景永远对不上。
 * ④ Dialog 是独立 Window，Activity 的 decor 遍历不到：必须 hook Dialog.show()，
 *    否则筛选弹窗里的 RadioButton / MaterialButton 一个都改不到。
 * ⑤ 状态栏改白后必须持续补 SYSTEM_UI_FLAG_LIGHT_STATUS_BAR，否则白色图标看不见。
 */
public final class EInkTheme {

    private static final String TAG = "MoonReaderInk/Theme";
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    // 原版与共存版（Apktool M 只改 manifest 包名 / 名称）的资源 id 已静态核对一致。
    // 仍按资源名解析一次作为兜底：以后若用完整反编译重打包导致 id 重排也能自动适配；
    // 解析失败则保留下面的默认值。
    private static volatile int FAB_LAY = 0x7f09023c;
    private static volatile int FAB_BOTTOM = 0x7f090239;
    private static volatile int FAB_ICON = 0x7f09023b;
    // 面包屑小三角：R.drawable.arrow2 -> ActivityMain.dFolderArrow
    private static volatile int ARROW2 = 0x7f08009b;
    // id/horizontalScrollView1：面包屑所在的固定容器，分割线只画在它底部
    private static volatile int FOLDERS_SCROLL = 0x7f0902c6;
    // id/drawerBottom：侧边抽屉底部那排按钮（设置选项 / 软件说明 / 晚上模式 / 退出）
    private static volatile int DRAWER_BOTTOM = 0x7f09020e;
    // 这排按钮的文字用 ColorStateList 上色，App 会把扫描时改的黑字覆盖回灰色，
    // 所以还要在 setTextColor 的 hook 里按 id 强制黑色。
    private static volatile int DRAWER_OPT_TV = 0x7f090402;
    private static volatile int DRAWER_ABOUT_TV = 0x7f090038;
    private static volatile int DRAWER_DAY_TV = 0x7f0901cd;
    private static volatile int DRAWER_EXIT_TV = 0x7f090232;
    // id/pvTopLine：选项页 ScrollView 的内容容器，模块设置入口挂在它末尾
    private static volatile int SETTINGS_ROOT = 0x7f0904e7;
    private static final String ENTRY_TAG = "moonreaderink_settings_entry";

    private static volatile long sLastScan = 0L;
    private static volatile String sTargetPkg = "com.flyersoft.moonreaderp";
    private static volatile boolean sIdsResolved = false;
    private static volatile boolean sDrawerLogged = false;

    private EInkTheme() {}

    static void install(ClassLoader cl, String targetPkg) {
        // 模块代码按进程加载：每个目标 App 进程各有自己的一份静态字段。
        sTargetPkg = targetPkg;
        sIdsResolved = false;
        // ---- 1. 资源取色
        XC_MethodHook colorHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    int c = (Integer) p.getResult();
                    if (isChromatic(c)) p.setResult(WHITE);
                } catch (Throwable ignored) {}
            }
        };
        hook(cl, "android.content.res.Resources", "getColor", int.class, colorHook);
        tryHook(cl, "android.content.res.Resources", "getColor",
                int.class, Resources.Theme.class, colorHook);

        XC_MethodHook cslHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    ColorStateList l = (ColorStateList) p.getResult();
                    if (l != null && isChromatic(l.getDefaultColor())) {
                        p.setResult(ColorStateList.valueOf(WHITE));
                    }
                } catch (Throwable ignored) {}
            }
        };
        hook(cl, "android.content.res.Resources", "getColorStateList", int.class, cslHook);
        tryHook(cl, "android.content.res.Resources", "getColorStateList",
                int.class, Resources.Theme.class, cslHook);

        XC_MethodHook drawableHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof Resources) resolveIds((Resources) p.thisObject);
                    // 面包屑淡灰小三角 = R.drawable.arrow2（作为 TextView 右 compound
                    // drawable 被缓存复用），colorFilter / tint 都盖不住它。
                    // 直接在资源返回处换成自绘的纯黑 chevron。
                    if (p.args != null && p.args.length > 0
                            && p.args[0] instanceof Integer
                            && (Integer) p.args[0] == ARROW2) {
                        p.setResult(new ChevronDrawable());
                        return;
                    }
                    Object r = p.getResult();
                    if (r instanceof ColorDrawable) {
                        int c = ((ColorDrawable) r).getColor();
                        if (isChromatic(c)) p.setResult(new ColorDrawable(WHITE));
                    } else if (r instanceof BitmapDrawable) {
                        Bitmap b = ((BitmapDrawable) r).getBitmap();
                        if (b != null && looksLikeBlueBadge(b)) recolorInPlace(b);
                    }
                } catch (Throwable ignored) {}
            }
        };
        hook(cl, "android.content.res.Resources", "getDrawable", int.class, drawableHook);
        tryHook(cl, "android.content.res.Resources", "getDrawable",
                int.class, Resources.Theme.class, drawableHook);

        XC_MethodHook bitmapHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Bitmap b = (Bitmap) p.getResult();
                    if (b != null && looksLikeBlueBadge(b)) recolorInPlace(b);
                } catch (Throwable ignored) {}
            }
        };
        hook(cl, "android.graphics.BitmapFactory", "decodeStream",
                InputStream.class, bitmapHook);
        hook(cl, "android.graphics.BitmapFactory", "decodeStream",
                InputStream.class, Rect.class, BitmapFactory.Options.class, bitmapHook);

        // ---- 2. View / TextView / ImageView setter
        XC_MethodHook bgColorHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.args[0] = bgColor((Integer) p.args[0]);
            }
        };
        hook(cl, "android.view.View", "setBackgroundColor", int.class, bgColorHook);

        XC_MethodHook bgHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.args[0] = bgDrawable((Drawable) p.args[0]);
            }
        };
        hook(cl, "android.view.View", "setBackground", Drawable.class, bgHook);
        hook(cl, "android.view.View", "setBackgroundDrawable", Drawable.class, bgHook);

        XC_MethodHook bgTintHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    ColorStateList l = (ColorStateList) p.args[0];
                    if (l != null && isChromatic(l.getDefaultColor())) {
                        p.args[0] = ColorStateList.valueOf(WHITE);
                    }
                } catch (Throwable ignored) {}
            }
        };
        hook(cl, "android.view.View", "setBackgroundTintList",
                ColorStateList.class, bgTintHook);

        XC_MethodHook textHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof TextView
                            && isDrawerBottomLabel(((TextView) p.thisObject).getId())) {
                        p.args[0] = BLACK;
                        return;
                    }
                } catch (Throwable ignored) {}
                p.args[0] = textColor((Integer) p.args[0]);
            }
        };
        hook(cl, "android.widget.TextView", "setTextColor", int.class, textHook);

        XC_MethodHook textCslHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof TextView
                            && isDrawerBottomLabel(((TextView) p.thisObject).getId())) {
                        p.args[0] = ColorStateList.valueOf(BLACK);
                        return;
                    }
                    ColorStateList l = (ColorStateList) p.args[0];
                    if (l != null && isChromatic(l.getDefaultColor())) {
                        p.args[0] = ColorStateList.valueOf(BLACK);
                    }
                } catch (Throwable ignored) {}
            }
        };
        hook(cl, "android.widget.TextView", "setTextColor", ColorStateList.class, textCslHook);

        // 抽屉底部四个文字由 XML / ColorStateList 上色，扫描时改黑可能被 App 覆盖回去；
        // 在每次绘制之前直接把画笔设成纯黑。id 与文字内容双重判断，
        // 兼容资源 id 重排或不同语言之外的自定义控件。
        hook(cl, "android.widget.TextView", "onDraw", Canvas.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    TextView t = (TextView) p.thisObject;
                    if (isDrawerBottomLabel(t.getId()) || isDrawerBottomText(t.getText())) {
                        t.getPaint().setColor(BLACK);
                        if (!sDrawerLogged) {
                            sDrawerLogged = true;
                            XposedBridge.log(TAG + ": drawer label black: "
                                    + t.getText() + " / " + Integer.toHexString(t.getId()));
                        }
                    }
                } catch (Throwable ignored) {}
            }
        });

        XC_MethodHook iconTintHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof View && isFab((View) p.thisObject)) return;
                    ColorStateList l = (ColorStateList) p.args[0];
                    if (l != null) {
                        int c = l.getDefaultColor();
                        if (isChromatic(c) || lum(c) > 190) {
                            p.args[0] = ColorStateList.valueOf(BLACK);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        };
        hook(cl, "android.widget.ImageView", "setImageTintList",
                ColorStateList.class, iconTintHook);
        tryHook(cl, "android.widget.TextView", "setCompoundDrawableTintList",
                ColorStateList.class, iconTintHook);

        XC_MethodHook tintHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof View && isFab((View) p.thisObject)) return;
                    Object a = p.args[0];
                    if (a instanceof Integer && isChromatic((Integer) a)) p.args[0] = BLACK;
                } catch (Throwable ignored) {}
            }
        };
        hook(cl, "android.widget.ImageView", "setColorFilter", int.class, tintHook);
        tryHook(cl, "android.widget.ImageView", "setColorFilter",
                int.class, PorterDuff.Mode.class, tintHook);

        final XC_MethodHook lateHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    if (p.thisObject instanceof ImageView) {
                        ImageView iv = (ImageView) p.thisObject;
                        if (iv.getId() == FAB_ICON) return;
                        fixImageView(iv);
                    } else if (p.thisObject instanceof TextView) {
                        for (Object a : p.args) {
                            if (a instanceof Drawable) tintBlack((Drawable) a);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        };
        hook(cl, "android.widget.ImageView", "setImageDrawable", Drawable.class, lateHook);
        hook(cl, "android.widget.ImageView", "setImageResource", int.class, lateHook);
        hook(cl, "android.widget.TextView", "setCompoundDrawables",
                Drawable.class, Drawable.class, Drawable.class, Drawable.class, lateHook);
        tryHook(cl, "android.widget.TextView", "setCompoundDrawablesRelative",
                Drawable.class, Drawable.class, Drawable.class, Drawable.class, lateHook);

        // Switch / SwitchCompat 的 track / thumb tint：App 每次上色都强制换成黑白，
        // 否则开启态蓝色资源被改白后会变成「白轨道 + 白滑块」而整个消失。
        final XC_MethodHook swTintHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                try {
                    boolean isTrack = p.method.getName().contains("Track");
                    p.args[0] = isTrack ? switchTrackCsl() : switchThumbCsl();
                } catch (Throwable ignored) {}
            }
        };
        for (String cls : new String[]{"android.widget.Switch",
                "androidx.appcompat.widget.SwitchCompat"}) {
            tryHook(cl, cls, "setTrackTintList", ColorStateList.class, swTintHook);
            tryHook(cl, cls, "setThumbTintList", ColorStateList.class, swTintHook);
        }

        // ---- 3. 状态栏
        hook(cl, "android.view.View", "setSystemUiVisibility", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.args[0] = ((Integer) p.args[0]) | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
        });
        tryHook(cl, "android.view.Window", "setStatusBarColor", int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.args[0] = bgColor((Integer) p.args[0]);
            }
        });

        // ---- 4. Activity 与 Dialog 都要扫 decor view
        hook(cl, "android.app.Activity", "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(final MethodHookParam p) {
                try {
                    final View decor = ((Activity) p.thisObject).getWindow().getDecorView();
                    decor.post(new Runnable() {
                        @Override public void run() { scan(decor); }
                    });
                } catch (Throwable ignored) {}
            }
        });
        hook(cl, "android.app.Dialog", "show", new XC_MethodHook() {
            @Override protected void afterHookedMethod(final MethodHookParam p) {
                try {
                    final View decor = ((Dialog) p.thisObject).getWindow().getDecorView();
                    decor.post(new Runnable() {
                        @Override public void run() { scan(decor); }
                    });
                } catch (Throwable ignored) {}
            }
        });
        // 设置页是 ActivityMain 内部视图切换（不触发 onResume），
        // 靠 View 附着到窗口时补一次扫描；250ms 节流避免反复遍历。
        hook(cl, "android.view.View", "onAttachedToWindow", new XC_MethodHook() {
            @Override protected void afterHookedMethod(final MethodHookParam p) {
                try {
                    long now = System.currentTimeMillis();
                    if (now - sLastScan < 250) return;
                    sLastScan = now;
                    final View v = (View) p.thisObject;
                    v.post(new Runnable() {
                        @Override public void run() { scan(v.getRootView()); }
                    });
                } catch (Throwable ignored) {}
            }
        });

        XposedBridge.log(TAG + ": installed");
    }

    /**
     * 按资源名解析硬编码 id（每个目标进程只做一次）。
     * 原版与 Apktool M 共存版的 id 完全一致；这层兜底用于防止以后重打包导致 id 重排。
     */
    private static void resolveIds(Resources res) {
        if (sIdsResolved || res == null) return;
        synchronized (EInkTheme.class) {
            if (sIdsResolved) return;
            try {
                FAB_LAY = lookup(res, "fabLay", "id", FAB_LAY);
                FAB_BOTTOM = lookup(res, "fabBottom", "id", FAB_BOTTOM);
                FAB_ICON = lookup(res, "fabIcon", "id", FAB_ICON);
                ARROW2 = lookup(res, "arrow2", "drawable", ARROW2);
                FOLDERS_SCROLL = lookup(res, "horizontalScrollView1", "id",
                        FOLDERS_SCROLL);
                DRAWER_BOTTOM = lookup(res, "drawerBottom", "id", DRAWER_BOTTOM);
                SETTINGS_ROOT = lookup(res, "pvTopLine", "id", SETTINGS_ROOT);
                DRAWER_OPT_TV = lookup(res, "optionsTv", "id", DRAWER_OPT_TV);
                DRAWER_ABOUT_TV = lookup(res, "aboutTv", "id", DRAWER_ABOUT_TV);
                DRAWER_DAY_TV = lookup(res, "dayTv", "id", DRAWER_DAY_TV);
                DRAWER_EXIT_TV = lookup(res, "exitTv", "id", DRAWER_EXIT_TV);
                XposedBridge.log(TAG + ": ids resolved for " + sTargetPkg);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": 资源 id 解析失败，保留默认值: " + t);
            }
            sIdsResolved = true;
        }
    }

    private static int lookup(Resources res, String name, String type, int fallback) {
        int id = res.getIdentifier(name, type, sTargetPkg);
        return id != 0 ? id : fallback;
    }

    private static void scan(View decor) {
        try {
            decor.setSystemUiVisibility(
                    decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            applyTo(decor);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": scan failed: " + t);
        }
    }

    /** 递归处理 View 树；幂等。 */
    private static void applyTo(View v) {
        if (v == null) return;
        resolveIds(v.getResources());
        final int id = v.getId();
        if (id == FAB_BOTTOM) {
            v.setBackground(new ColorDrawable(0)); // 圆改画在 fabIcon 里，避免两个 View 尺寸不一致
            return;
        }
        if (id == FAB_ICON) { fixFabIcon(v); return; }
        try {
            if (id == DRAWER_BOTTOM) blackenDrawerLabels(v);
            if (id == SETTINGS_ROOT && v instanceof ViewGroup) {
                addSettingsEntry((ViewGroup) v);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": entry/drawer skipped: " + t);
        }

        try {
            Drawable bg = v.getBackground();
            Drawable fixed = bgDrawable(bg);
            if (fixed != null && fixed != bg) v.setBackground(fixed);

            if (v instanceof TextView) {
                TextView t = (TextView) v;
                int c = t.getCurrentTextColor();
                int n = textColor(c);
                if (n != c) t.setTextColor(n);
                if (isDrawerBottomLabel(id)) t.setTextColor(BLACK);
                for (Drawable d : t.getCompoundDrawables()) tintBlack(d);
                for (Drawable d : t.getCompoundDrawablesRelative()) tintBlack(d);
                // 面包屑里的小三角 / 箭头（短文本、浅灰）改成纯黑
                CharSequence cs = t.getText();
                if (cs != null && cs.length() <= 3) {
                    int tc = t.getCurrentTextColor();
                    int l = lum(tc);
                    if (!isChromatic(tc) && l > 100 && l < 215) {
                        t.setTextColor(BLACK);
                    }
                }
            }
            if (v instanceof CompoundButton) {
                fixSwitch((CompoundButton) v);
                fixRadio((CompoundButton) v);
            }
            fixMaterialButton(v);
            if (v instanceof ImageView) fixImageView((ImageView) v);
            maybeDivider(v);
            blackenSmallTopView(v);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": view skipped: " + t);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyTo(g.getChildAt(i));
        }
    }

    /** 悬浮按钮：白圆 + 粗黑边 + 黑色书本，全部画在同一个 drawable 里。 */
    private static void fixFabIcon(View v) {
        if (!(v instanceof ImageView)) return;
        ImageView iv = (ImageView) v;
        if (iv.getDrawable() instanceof FabBookDrawable) return;
        iv.setColorFilter(null);
        iv.setImageTintList(null);
        iv.setImageDrawable(new FabBookDrawable());
    }

    private static void fixImageView(ImageView iv) {
        if (isFab(iv)) return;
        Drawable d = iv.getDrawable();
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && looksLikeBlueBadge(b)) {
                recolorInPlace(b);
                iv.setColorFilter(null);
                return;
            }
        }
        if (isCoverView(iv) || iv.getWidth() > 110 || iv.getHeight() > 110) return;
        iv.setColorFilter(BLACK, PorterDuff.Mode.SRC_IN);
    }

    /**
     * MaterialButtonToggleGroup 里的布局切换按钮：选中 = 黑底白图标，
     * 未选中 = 白底黑边黑图标。MaterialShapeDrawable 不归 bgDrawable 管，
     * 所以直接换背景。
     */
    private static void fixMaterialButton(View v) {
        if (!(v instanceof android.widget.Button)) return; // 含 MaterialButton / AppCompatButton
        try {
            // MaterialShapeDrawable 不读 colorFilter 但认 tintList，用按状态的
            // backgroundTintList 上色：选中黑底、未选中白底；绝不替换 background
            // 对象（App 内部会强转回 MaterialShapeDrawable，换对象会崩）。
            ColorStateList bg = new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_checked},
                            new int[]{android.R.attr.state_selected},
                            new int[]{}
                    },
                    new int[]{BLACK, BLACK, WHITE});
            v.setBackgroundTintList(bg);
            v.setBackgroundTintMode(PorterDuff.Mode.SRC_IN);
            if (v instanceof TextView) {
                ColorStateList icon = new ColorStateList(
                        new int[][]{
                                new int[]{android.R.attr.state_checked},
                                new int[]{android.R.attr.state_selected},
                                new int[]{}
                        },
                        new int[]{WHITE, WHITE, BLACK});
                ((TextView) v).setCompoundDrawableTintList(icon);
            }
            // MaterialButton 的图标是独立 icon drawable，不走 compound drawables：
            // 选中态染白（黑底上才看得见），未选中染黑
            boolean on = v.isSelected()
                    || (v instanceof Checkable && ((Checkable) v).isChecked());
            try {
                Method m = v.getClass().getMethod("getIcon");
                Drawable ic = (Drawable) m.invoke(v);
                if (ic != null) tint(ic, on ? WHITE : BLACK);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static ColorStateList switchTrackCsl() {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{BLACK, WHITE});
    }

    private static ColorStateList switchThumbCsl() {
        return new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{WHITE, BLACK});
    }

    /** Switch / SwitchCompat：OFF 白底黑边 + 黑滑块；ON 黑轨道 + 白滑块。 */
    private static void fixSwitch(CompoundButton b) {
        if (!b.getClass().getName().contains("Switch")) return;
        try {
            Method mt = b.getClass().getMethod("setTrackTintList", ColorStateList.class);
            Method mh = b.getClass().getMethod("setThumbTintList", ColorStateList.class);
            mt.invoke(b, switchTrackCsl());
            mh.invoke(b, switchThumbCsl());
            try {
                Method gt = b.getClass().getMethod("getTrackDrawable");
                Drawable track = (Drawable) gt.invoke(b);
                if (track instanceof GradientDrawable) {
                    ((GradientDrawable) track).setStroke(2, BLACK);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /** RadioButton：灰色待选框直接原地染成纯黑，保留圆环形状。 */
    private static void fixRadio(CompoundButton b) {
        if (!b.getClass().getName().contains("Radio")) return;
        try {
            Drawable bd = b.getButtonDrawable();
            if (bd != null) {
                bd.mutate().setColorFilter(BLACK, PorterDuff.Mode.SRC_IN);
            }
        } catch (Throwable ignored) {}
    }

    /** 面包屑区域（屏幕上方、矮条状）：淡灰 -> 纯黑，白底仍白、黑字仍黑。
     *  用高对比 ColorMatrix 而不是 SRC_IN，避免把白底整块染黑。 */
    private static void blackenSmallTopView(View v) {
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0 || h > 80 || w > 700) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        if (loc[1] > 260) return;
        try {
            ColorMatrix cm = new ColorMatrix(new float[]{
                    8, 0, 0, 0, -1700,
                    0, 8, 0, 0, -1700,
                    0, 0, 8, 0, -1700,
                    0, 0, 0, 1, 0
            });
            Paint p = new Paint();
            p.setColorFilter(new ColorMatrixColorFilter(cm));
            v.setLayerType(View.LAYER_TYPE_SOFTWARE, p);
        } catch (Throwable ignored) {}
    }

    /**
     * 顶部栏：白底 + 明显黑色分割线，只画在面包屑容器 id/horizontalScrollView1 底部。
     * 绝不能用 getLocationOnScreen 猜「屏幕上方」：列表滚动后书籍条目 ViewGroup 会
     * 滚到顶部区域被误判，每扫一次就多一条黑线（用户实测拖动几次后满屏横线）。
     * 顺带把旧版本误加在列表项上的 DividerDrawable 关掉黑线，保留白底、不破坏背景对象。
     */
    private static void maybeDivider(View v) {
        if (!(v instanceof ViewGroup)) return;
        Drawable bg = v.getBackground();
        if (v.getId() == FOLDERS_SCROLL) {
            if (bg instanceof DividerDrawable) {
                ((DividerDrawable) bg).setShowLine(true);
                v.invalidate();
            } else {
                v.setBackground(new DividerDrawable());
            }
            return;
        }
        if (bg instanceof DividerDrawable) {
            ((DividerDrawable) bg).setShowLine(false);
            v.invalidate();
        }
    }

    private static boolean isDrawerBottomLabel(int id) {
        return id == DRAWER_OPT_TV || id == DRAWER_ABOUT_TV
                || id == DRAWER_DAY_TV || id == DRAWER_EXIT_TV;
    }

    private static boolean isDrawerBottomText(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().trim();
        return s.equals("设置选项") || s.equals("软件说明")
                || s.equals("晚上模式") || s.equals("退出");
    }

    /** 抽屉底部「设置选项 / 软件说明 / 晚上模式 / 退出」等文字改成纯黑。 */
    private static void blackenDrawerLabels(View v) {
        try {
            if (!(v instanceof ViewGroup)) return;
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                if (c instanceof TextView) {
                    ((TextView) c).setTextColor(BLACK);
                } else if (c instanceof ViewGroup) {
                    blackenDrawerLabels(c);
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 在「选项」页内容容器（id/pvTopLine）末尾放模块设置入口。
     * 用 post 延迟添加，避免在 applyTo 递归遍历时改动 View 树；带 tag 判重幂等。
     */
    private static void addSettingsEntry(final ViewGroup root) {
        if (hasEntry(root)) return;
        root.post(new Runnable() {
            @Override public void run() {
                try {
                    if (hasEntry(root)) return;
                    Resources res = root.getResources();
                    TextView entry = new TextView(root.getContext());
                    entry.setTag(ENTRY_TAG);
                    entry.setText("墨水屏模式 · 模块设置");
                    entry.setTextColor(BLACK);
                    entry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    entry.setGravity(Gravity.CENTER);
                    int pad = dp(res, 16);
                    entry.setPadding(pad, dp(res, 18), pad, dp(res, 18));
                    GradientDrawable g = new GradientDrawable();
                    g.setCornerRadius(dp(res, 22));
                    g.setColor(WHITE);
                    g.setStroke(2, BLACK);
                    entry.setBackground(g);
                    entry.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            openSettings(v.getContext());
                        }
                    });
                    ViewGroup.LayoutParams lp;
                    if (root instanceof LinearLayout) {
                        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        int m = dp(res, 22);
                        llp.setMargins(m, dp(res, 12), m, dp(res, 32));
                        lp = llp;
                    } else {
                        lp = new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                    }
                    root.addView(entry, lp);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": 设置入口添加失败: " + t);
                }
            }
        });
    }

    private static boolean hasEntry(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            if (ENTRY_TAG.equals(root.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    /**
     * 优先拉起模块 APK 的设置页；LSPatch 等无 Root 场景模块 APK 未单独安装，
     * startActivity 会抛 ActivityNotFoundException，此时在目标 App 进程里直接弹
     * 内嵌设置面板（开关写入目标 App 自己的 prefs，Hook 可直接读）。
     */
    private static void openSettings(Context ctx) {
        try {
            Intent i = new Intent();
            i.setClassName("com.moonreader.ink", "com.moonreader.ink.SettingsActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(
                    SettingsUi.IN_APP_PREFS, Context.MODE_PRIVATE);
            new AlertDialog.Builder(ctx)
                    .setTitle("静读天下墨水屏模式")
                    .setView(SettingsUi.build(ctx, prefs))
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": 内嵌设置面板失败: " + t);
            try {
                Toast.makeText(ctx, "模块设置不可用：请安装模块 APK 或用框架管理器进入",
                        Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) {}
        }
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

    private static int dp(Resources res, int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, res.getDisplayMetrics());
    }

    private static void tint(Drawable d, int color) {
        if (d == null) return;
        try {
            d.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        } catch (Throwable ignored) {}
    }

    private static void tintBlack(Drawable d) {
        tint(d, BLACK);
    }

    private static boolean isFab(View v) {
        int id = v.getId();
        return id == FAB_LAY || id == FAB_BOTTOM || id == FAB_ICON
                || v.getClass().getName().contains("FloatingActionButton");
    }

    private static boolean isCoverView(View v) {
        String n = v.getClass().getName();
        if (n.contains("Shelf") || n.contains("Cover") || n.contains("BookImage")
                || n.contains("Drawee") || n.contains("Photo")) return true;
        Drawable d = ((ImageView) v).getDrawable();
        return d != null && (d.getIntrinsicWidth() > 400 || d.getIntrinsicHeight() > 400);
    }

    private static boolean looksLikeBlueBadge(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0 || w > 400 || h > 400) return false;
        int step = Math.max(1, Math.min(w, h) / 20);
        int blue = 0, light = 0, total = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = b.getPixel(x, y);
                if (Color.alpha(c) < 8) continue;
                total++;
                if (isChromatic(c) && Color.blue(c) > Color.red(c) + 30) blue++;
                else if (lum(c) > 190) light++;
            }
        }
        return total > 0 && blue * 100 / total > 25 && light * 100 / total > 3;
    }

    private static void recolorInPlace(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int a = Color.alpha(c);
            if (a < 8) { px[i] = 0; continue; }
            if (isChromatic(c) && Color.blue(c) > Color.red(c) + 30) {
                px[i] = Color.argb(a, 255, 255, 255);
            } else if (lum(c) > 190) {
                px[i] = Color.argb(a, 0, 0, 0);
            } else {
                int g = lum(c);
                px[i] = Color.argb(a, g, g, g);
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static boolean isChromatic(int c) {
        if (Color.alpha(c) < 8) return false;
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 24;
    }

    private static int lum(int c) {
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
    }

    private static int bgColor(int c) {
        if (Color.alpha(c) == 0) return c;
        if (isChromatic(c)) return WHITE;
        return lum(c) <= 2 ? c : WHITE;
    }

    private static int textColor(int c) {
        if (Color.alpha(c) == 0) return c;
        if (isChromatic(c) || lum(c) > 190) return BLACK;
        return c;
    }

    private static Drawable bgDrawable(Drawable d) {
        if (d == null) return null;
        if (d instanceof ColorDrawable) {
            int c = ((ColorDrawable) d).getColor();
            int n = bgColor(c);
            return n == c ? d : new ColorDrawable(n);
        }
        if (d instanceof StateListDrawable) {
            StateListDrawable s = (StateListDrawable) d;
            for (int i = 0; i < s.getStateCount(); i++) {
                Drawable child = s.getStateDrawable(i);
                if (!(child instanceof GradientDrawable)) continue;
                GradientDrawable g = (GradientDrawable) child;
                try {
                    int[] colors = g.getColors();
                    boolean on = colors != null && colors.length > 0 && isChromatic(colors[0]);
                    if (on) g.setColor(BLACK);
                    else { g.setColor(WHITE); g.setStroke(2, BLACK); }
                } catch (Throwable ignored) {}
            }
            return d;
        }
        if (d instanceof GradientDrawable) {
            try {
                GradientDrawable g = (GradientDrawable) d;
                int[] colors = g.getColors();
                boolean keep = colors != null && colors.length > 0
                        && (Color.alpha(colors[0]) < 8 || lum(colors[0]) <= 2);
                if (!keep) {
                    g.setColor(WHITE);
                    g.setStroke(2, BLACK);
                }
            } catch (Throwable ignored) {}
            return d;
        }
        // 小尺寸灰色图标（面包屑小三角、下拉箭头等）：原地染成纯黑。
        // 只针对位图 / 矢量类，避免误伤 MaterialShapeDrawable 等自定义形状。
        String n = d.getClass().getName();
        if (n.contains("BitmapDrawable") || n.contains("VectorDrawable")
                || n.contains("NinePatchDrawable")) {
            int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
            if (w > 0 && h > 0 && w <= 96 && h <= 96) {
                try {
                    d.mutate().setColorFilter(BLACK, PorterDuff.Mode.SRC_IN);
                } catch (Throwable ignored) {}
            }
        }
        return d;
    }

    private static void hook(ClassLoader cl, String cls, String method, Object... args) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, args);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + cls + "." + method + " failed: " + t);
        }
    }

    private static void tryHook(ClassLoader cl, String cls, String method, Object... args) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, args);
        } catch (Throwable ignored) {}
    }

    /** 面包屑小三角：替换 R.drawable.arrow2 的纯黑 chevron（bounds 由 App 调用 setBounds 设置）。 */
    private static class ChevronDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ChevronDrawable() {
            paint.setColor(BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public void draw(Canvas c) {
            Rect r = getBounds();
            if (r.width() <= 0 || r.height() <= 0) return;
            paint.setStrokeWidth(Math.max(2f, r.height() * 0.09f));
            android.graphics.Path path = new android.graphics.Path();
            float x1 = r.left + r.width() * 0.32f;
            float x2 = r.left + r.width() * 0.68f;
            float y1 = r.top + r.height() * 0.22f;
            float ym = r.centerY();
            float y2 = r.bottom - r.height() * 0.22f;
            path.moveTo(x1, y1);
            path.lineTo(x2, ym);
            path.lineTo(x1, y2);
            c.drawPath(path, paint);
        }

        @Override public int getIntrinsicWidth() { return 24; }
        @Override public int getIntrinsicHeight() { return 48; }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 顶部栏白底 + 底部黑线；showLine=false 时只留白底（用于清理误加的分割线）。 */
    private static class DividerDrawable extends Drawable {
        private final Paint p = new Paint();
        private boolean showLine = true;

        DividerDrawable() { p.setColor(BLACK); }

        void setShowLine(boolean show) {
            showLine = show;
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            Rect r = getBounds();
            c.drawColor(WHITE);
            if (showLine) c.drawRect(r.left, r.bottom - 3, r.right, r.bottom, p);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /**
     * 悬浮按钮：大白圆 + 与 F7Book 线条一致的纯黑描边，圆内居中画 F7Book.png，
     * 两者同框同比例，尺寸恢复接近原按钮。
     */
    private static class FabBookDrawable extends Drawable {
        private final Bitmap book;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmpPaint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        FabBookDrawable() {
            Bitmap b = null;
            try {
                byte[] png = android.util.Base64.decode(
                        BookIconData.PNG, android.util.Base64.DEFAULT);
                b = BitmapFactory.decodeByteArray(png, 0, png.length);
            } catch (Throwable ignored) {}
            book = b;
            fill.setColor(WHITE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(6);
        }

        @Override public void draw(Canvas c) {
            Rect r = getBounds();
            float cx = r.centerX(), cy = r.centerY();
            float rad = Math.min(r.width(), r.height()) * 0.42f;
            c.drawCircle(cx, cy, rad, fill);
            c.drawCircle(cx, cy, rad, stroke);
            if (book == null) return;
            float half = rad * 0.72f;
            Rect dst = new Rect((int) (cx - half), (int) (cy - half),
                    (int) (cx + half), (int) (cy + half));
            c.drawBitmap(book, null, dst, bmpPaint);
        }

        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
