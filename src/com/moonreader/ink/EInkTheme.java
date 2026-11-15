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
import android.view.Window;
import android.view.WindowManager;
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
    // 文件夹列表橙底白文件夹图标与系统复选框资源：只解析一次 id，避免每次
    // getDrawable 都做 getResourceEntryName 查询。
    private static volatile int FOLDER_ICON = 0x7f0801cb;
    private static volatile int CB_CHECK_ANIM = 0x7f080031;
    private static volatile int CB_CHECK = 0x7f080030;
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
    // id/fileName：书架网格与文件列表里的书名。App 默认 #424242 深灰，
    // 在墨水屏上偏淡，统一压到纯黑（fileDate 的 #9e9e9e 日期保持不动）。
    private static volatile int BOOK_NAME_TV = 0x7f09024d;
    // id/myBookName：书架网格（全部书籍）里的书名，App 默认 #616161 中灰
    private static volatile int SHELF_NAME_TV = 0x7f0903bd;
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
                    // 文件夹列表的橙色圆底白文件夹：Fresco 缓存 drawable，
                    // View 层 tint / colorFilter 都盖不住，和 arrow2 一样从资源返回处替换。
                    if (p.thisObject instanceof Resources && p.args != null
                            && p.args.length > 0 && p.args[0] instanceof Integer
                            && isFolderIconRes((Resources) p.thisObject,
                                    (Integer) p.args[0])) {
                        p.setResult(new FolderIconDrawable());
                        return;
                    }
                    // 系统复选框资源：换成按状态切换的黑白方框 drawable，
                    // 覆盖 ImageView / 自定义 View 那种不走 CompoundButton 的情况。
                    if (p.thisObject instanceof Resources && p.args != null
                            && p.args.length > 0 && p.args[0] instanceof Integer
                            && isCheckBoxRes((Resources) p.thisObject,
                                    (Integer) p.args[0])) {
                        p.setResult(checkBoxStates());
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
                    if (b == null) return;
                    // 橙底白文件夹：Fresco 用 inBitmap 复用，绝不替换 Bitmap 对象，
                    // 只把像素原地重绘成「白底黑矩形边框 + 空心文件夹」。
                    if (looksLikeOrangeFolder(b)) { redrawFolderInPlace(b); return; }
                    if (looksLikeBlueBadge(b)) recolorInPlace(b);
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
                            && isForceBlackLabel(((TextView) p.thisObject).getId())) {
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
                            && isForceBlackLabel(((TextView) p.thisObject).getId())) {
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
                    if (isForceBlackLabel(t.getId()) || isDrawerBottomText(t.getText())) {
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

        // CheckBox / CheckedTextView：App 每次改状态或上色都强制刷成黑白 drawable，
        // 否则系统默认灰框与彩色/白色勾在墨水屏上几乎看不见。
        final XC_MethodHook checkedHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    View v = (View) p.thisObject;
                    if (v instanceof CompoundButton) fixCheckBox((CompoundButton) v);
                    else fixCheckedTextView(v);
                } catch (Throwable ignored) {}
            }
        };
        hook(cl, "android.widget.CompoundButton", "setChecked", boolean.class, checkedHook);
        tryHook(cl, "android.widget.CheckedTextView", "setChecked", boolean.class, checkedHook);
        tryHook(cl, "android.widget.CompoundButton", "setButtonTintList",
                ColorStateList.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        try {
                            CompoundButton b = (CompoundButton) p.thisObject;
                            if (b.getClass().getName().contains("CheckBox")) p.args[0] = null;
                        } catch (Throwable ignored) {}
                    }
                });

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
                    final Activity act = (Activity) p.thisObject;
                    final View decor = act.getWindow().getDecorView();
                    // 有些页面（文件夹快速访问列表等）是「对话框式 Activity」：窗口不到
                    // 状态栏，状态栏位置显示的是被 FLAG_DIM_BEHIND 压暗的背景，加上黑色
                    // 状态栏图标就成了「一层透明黑」。这里去掉压暗、并让窗口自己把状态栏
                    // 画成纯白，图标才看得清。
                    try {
                        final Window win = act.getWindow();
                        WindowManager.LayoutParams lp = win.getAttributes();
                        if ((lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                            win.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                        }
                        win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                        win.setStatusBarColor(WHITE);
                    } catch (Throwable ignored) {}
                    decor.post(new Runnable() {
                        @Override public void run() { scan(decor); }
                    });
                } catch (Throwable ignored) {}
            }
        });
        hook(cl, "android.app.Dialog", "show", new XC_MethodHook() {
            @Override protected void afterHookedMethod(final MethodHookParam p) {
                try {
                    final Dialog dlg = (Dialog) p.thisObject;
                    final View decor = dlg.getWindow().getDecorView();
                    // 全屏对话框（文件夹快速访问列表 / 文件夹选择页）的窗口不到状态栏，
                    // 后面那层 FLAG_DIM_BEHIND 压暗会把状态栏也涂成半透明黑，和黑色
                    // 状态栏图标叠在一起看不清。直接去掉压暗：对话框本身已有白底黑框。
                    try {
                        final Window dw = dlg.getWindow();
                        if ((dw.getAttributes().flags
                                & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                            dw.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                        }
                        dw.setDimAmount(0f);
                    } catch (Throwable ignored) {}
                    decor.post(new Runnable() {
                        @Override public void run() { scan(decor); }
                    });
                } catch (Throwable ignored) {}
            }
        });
        // 顶部「本地文件」下拉是 PopupWindow，不是 Dialog，Dialog.show 的扫描够不到它。
        final XC_MethodHook popupHook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(final MethodHookParam p) {
                try {
                    final View cv = ((android.widget.PopupWindow) p.thisObject).getContentView();
                    if (cv == null) return;
                    cv.postDelayed(new Runnable() {
                        @Override public void run() { scan(cv.getRootView()); }
                    }, 120);
                } catch (Throwable ignored) {}
            }
        };
        tryHook(cl, "android.widget.PopupWindow", "showAtLocation",
                View.class, int.class, int.class, int.class, popupHook);
        tryHook(cl, "android.widget.PopupWindow", "showAsDropDown", View.class, popupHook);
        tryHook(cl, "android.widget.PopupWindow", "showAsDropDown",
                View.class, int.class, int.class, popupHook);
        tryHook(cl, "android.widget.PopupWindow", "showAsDropDown",
                View.class, int.class, int.class, int.class, popupHook);
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
                BOOK_NAME_TV = lookup(res, "fileName", "id", BOOK_NAME_TV);
                SHELF_NAME_TV = lookup(res, "myBookName", "id", SHELF_NAME_TV);
                FOLDER_ICON = lookup(res, "iconfolder", "drawable", FOLDER_ICON);
                CB_CHECK_ANIM = lookup(res, "abc_btn_check_material_anim",
                        "drawable", CB_CHECK_ANIM);
                CB_CHECK = lookup(res, "abc_btn_check_material",
                        "drawable", CB_CHECK);
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
                if (isForceBlackLabel(id)) t.setTextColor(BLACK);
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
                fixCheckBox((CompoundButton) v);
            }
            fixCheckedTextView(v);
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

    /**
     * 圆形徽标 → 「白底 + 黑描边 + 黑图标」的新 Bitmap；不是圆形徽标就返回 null。
     *
     * 判定用 36 条射线量轮廓半径：齿轮 / 星星 / 方块这类带齿或非圆的图标半径波动大，
     * 一律跳过，避免把本来就正常的线框图标改成圆圈。另外要求四角透明，
     * 圆底不能是纯黑（黑底黑字的图标保持原样）。
     * 圆底内与底色亮度不同的像素、以及圆底里镂空的像素都算图标本体，画成黑色。
     */
    private static Bitmap badgeOutline(Bitmap src) {
        try {
            int w = src.getWidth(), h = src.getHeight();
            if (w < 32 || h < 32 || w > 640 || h > 640) return null;
            if (Math.abs(w - h) > Math.min(w, h) / 8) return null;
            int[] px = new int[w * h];
            src.getPixels(px, 0, w, 0, 0, w, h);
            // 四角必须透明：矩形图标 / 书籍封面直接排除
            if (Color.alpha(px[2 * w + 2]) > 40 || Color.alpha(px[2 * w + w - 3]) > 40
                    || Color.alpha(px[(h - 3) * w + 2]) > 40
                    || Color.alpha(px[(h - 3) * w + w - 3]) > 40) return null;

            int cx = w / 2, cy = h / 2;
            float limit = Math.min(w, h) / 2f - 1f;
            float rMin = limit, rMax = 0f;
            for (int i = 0; i < 36; i++) {
                double ang = Math.PI * 2 * i / 36;
                float found = 0f;
                for (float t = limit; t > limit * 0.35f; t -= 1f) {
                    int x = Math.round(cx + (float) Math.cos(ang) * t);
                    int y = Math.round(cy + (float) Math.sin(ang) * t);
                    if (x < 0 || y < 0 || x >= w || y >= h) continue;
                    if (Color.alpha(px[y * w + x]) > 60) { found = t; break; }
                }
                if (found <= 0f) return null;
                rMin = Math.min(rMin, found);
                rMax = Math.max(rMax, found);
            }
            if (rMax - rMin > rMax * 0.10f) return null;   // 不是圆
            float R = (rMin + rMax) / 2f;

            // 圆底主色：0.68R 环带上的 72 点平均
            int sr = 0, sg = 0, sb = 0, cnt = 0;
            for (int i = 0; i < 72; i++) {
                double ang = Math.PI * 2 * i / 72;
                int x = Math.round(cx + (float) Math.cos(ang) * R * 0.68f);
                int y = Math.round(cy + (float) Math.sin(ang) * R * 0.68f);
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                int c = px[y * w + x];
                if (Color.alpha(c) < 100) continue;
                sr += Color.red(c);
                sg += Color.green(c);
                sb += Color.blue(c);
                cnt++;
            }
            if (cnt < 12) return null;
            int ground = Color.argb(255, sr / cnt, sg / cnt, sb / cnt);
            int gl = lum(ground);
            // 只处理彩色圆底或浅色圆底；纯深色圆底（本来就在墨水屏上可见）不动
            if (!isChromatic(ground) && gl < 60) return null;

            int[] out = new int[w * h];
            float ring = Math.max(2f, R * 0.10f);
            int glyph = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float dx = x + 0.5f - cx, dy = y + 0.5f - cy;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist > R) { out[i] = 0; continue; }
                    if (dist > R - ring) { out[i] = BLACK; continue; }
                    int c = px[i];
                    int a = Color.alpha(c);
                    int l = lum(c);
                    // 图标本体三种来源都算：
                    // ① 圆底上的浅色图标（彩色圆底 + 白色图标）
                    // ② 圆底上的深色图标（浅色圆底 + 深色线条图标）
                    // ③ 圆底里抠空的图标（白色圆底 + 透明镂空字形，App 再整体上色）
                    if (a < 90 || l > gl + 28 || l < gl - 28) {
                        out[i] = BLACK;
                        glyph++;
                    } else {
                        out[i] = WHITE;
                    }
                }
            }
            // 图标本体至少要占圆底的 2%，否则说明抠不出图标（会变成空圆圈），保持原样
            if (glyph * 50 < (int) (Math.PI * R * R)) return null;
            Bitmap nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            nb.setPixels(out, 0, w, 0, 0, w, h);
            logOnce("badge", w + "x" + h + " ground=" + Integer.toHexString(ground)
                    + " glyph=" + (glyph * 100 / (int) (Math.PI * R * R)) + "%");
            return nb;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 圆形徽标的替换 drawable：保留原位图的自适应尺寸，只换绘制内容。 */
    private static class BadgeOutlineDrawable extends BitmapDrawable {
        private final int w, h;

        BadgeOutlineDrawable(Resources res, Bitmap b, int w, int h) {
            super(res, b);
            this.w = w;
            this.h = h;
        }

        @Override public int getIntrinsicWidth() { return w; }
        @Override public int getIntrinsicHeight() { return h; }
    }

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
        if (d == null) return;
        if (d instanceof BadgeOutlineDrawable) {
            iv.setColorFilter(null);
            return;
        }
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            // 圆形徽标（「本地文件」下拉里的 电脑 / FTP、快速访问列表里的
            // Dropbox / Gdrive / WebDav / FTP、文件夹选择页顶部那三个圆按钮）
            // 原来是「彩色或浅色圆底 + 白色图标」，SRC_IN 染黑后整块糊成黑圆饼。
            // 统一改画成「白底 + 黑描边 + 黑图标」，只换 ImageView 上的 drawable 对象，
            // 不改位图本身（资源位图是共享且 immutable 的）。
            if (b != null && iv.getWidth() <= 130 && iv.getHeight() <= 130
                    && !isCoverClass(iv)
                    && (iv.getWidth() > 0 ? true : d.getIntrinsicWidth() <= 300)) {
                Bitmap outline = badgeOutline(b);
                if (outline != null) {
                    iv.setColorFilter(null);
                    iv.setImageTintList(null);
                    iv.setImageDrawable(new BadgeOutlineDrawable(iv.getResources(), outline,
                            d.getIntrinsicWidth(), d.getIntrinsicHeight()));
                    return;
                }
            }
            // 大图 / 封面路径：仍按像素原地改色（Fresco 复用 inBitmap，绝不换对象）。
            // immutable 位图不能原地改，直接跳过，别把异常抛到扫描日志里。
            if (b != null && b.isMutable() && looksLikeBlueBadge(b)) {
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

    /**
     * CheckBox（自定义阅读器工具栏等）：默认灰框 + 彩色/白色勾，墨水屏上糊成一片。
     * 换成自绘黑白 drawable：未选中白底纯黑边空心框，选中黑框内铺一个黑色小方块。
     * 不用 StateListDrawable：实测自定义 track/thumb 的状态分发不可靠，
     * 状态变化由 setChecked hook 主动重设最稳。
     */
    private static void fixCheckBox(CompoundButton b) {
        if (!b.getClass().getName().contains("CheckBox")) return;
        try {
            b.setButtonTintList(null);
            Drawable old = b.getButtonDrawable();
            if (!(old instanceof CheckBoxDrawable)
                    || ((CheckBoxDrawable) old).checked != b.isChecked()) {
                b.setButtonDrawable(new CheckBoxDrawable(b.isChecked()));
            }
            logOnce("checkbox", b.getClass().getName());
        } catch (Throwable ignored) {}
    }

    /** 多选列表里用 CheckedTextView 的情况：checkMark 同样换成黑白方框。 */
    private static void fixCheckedTextView(View v) {
        if (!(v instanceof android.widget.CheckedTextView)) return;
        try {
            android.widget.CheckedTextView t = (android.widget.CheckedTextView) v;
            Drawable old = t.getCheckMarkDrawable();
            if (!(old instanceof CheckBoxDrawable)
                    || ((CheckBoxDrawable) old).checked != t.isChecked()) {
                t.setCheckMarkDrawable(new CheckBoxDrawable(t.isChecked()));
            }
            logOnce("checkedTextView", v.getClass().getName());
        } catch (Throwable ignored) {}
    }

    private static volatile boolean sLoggedCheckbox = false;
    private static volatile boolean sLoggedFolder = false;
    private static volatile boolean sLoggedBadge = false;

    private static void logOnce(String kind, String detail) {
        if ("folder".equals(kind)) {
            if (sLoggedFolder) return;
            sLoggedFolder = true;
        } else if ("badge".equals(kind)) {
            if (sLoggedBadge) return;
            sLoggedBadge = true;
        } else {
            if (sLoggedCheckbox) return;
            sLoggedCheckbox = true;
        }
        XposedBridge.log(TAG + ": " + kind + " fixed -> " + detail);
    }

    private static Drawable checkBoxStates() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_checked}, new CheckBoxDrawable(true));
        s.addState(new int[]{}, new CheckBoxDrawable(false));
        return s;
    }

    // drawableHook 开头已解析过 id，这里不再调用 resolveIds，避免重入。
    private static boolean isCheckBoxRes(Resources res, int id) {
        return id == CB_CHECK_ANIM || id == CB_CHECK;
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

    /** 一律强制纯黑的文字：抽屉底部四个标签 + 书架/文件列表的书名。 */
    private static boolean isForceBlackLabel(int id) {
        return isDrawerBottomLabel(id) || id == BOOK_NAME_TV || id == SHELF_NAME_TV;
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
            // 面板整体自绘黑白，不依赖目标 App / 系统主题（标题与按钮都可能带主题色）。
            View panel = SettingsUi.build(ctx, prefs, true);
            GradientDrawable frame = new GradientDrawable();
            frame.setColor(WHITE);
            frame.setStroke(3, BLACK);
            panel.setBackground(frame);
            AlertDialog d = new AlertDialog.Builder(ctx)
                    .setView(panel)
                    .setPositiveButton("关闭", null)
                    .create();
            if (d.getWindow() != null) {
                // 与模块设置页一致：纯白窗口、不压暗背景（墨水屏避免深色遮罩）
                d.getWindow().setBackgroundDrawable(new ColorDrawable(WHITE));
                d.getWindow().setDimAmount(0f);
            }
            d.show();
            try {
                TextView btn = d.getButton(AlertDialog.BUTTON_POSITIVE);
                if (btn != null) btn.setTextColor(BLACK);
            } catch (Throwable ignored) {}
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
        if (isCoverClass(v)) return true;
        Drawable d = ((ImageView) v).getDrawable();
        return d != null && (d.getIntrinsicWidth() > 400 || d.getIntrinsicHeight() > 400);
    }

    /** 只看 View 类型：Shelf / Drawee 这类才是真封面；徽标图标虽然位图 512x512，
     *  但显示尺寸只有 64~108，不能按 intrinsic 判封面，否则会被漏掉。 */
    private static boolean isCoverClass(View v) {
        String n = v.getClass().getName();
        return n.contains("Shelf") || n.contains("Cover") || n.contains("BookImage")
                || n.contains("Drawee") || n.contains("Photo");
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

    // 同上：id 已由 drawableHook 开头的 resolveIds 解析。
    private static boolean isFolderIconRes(Resources res, int id) {
        return id == FOLDER_ICON;
    }

    /** 橙底白文件夹：高橙占比 + 明显白色区域 + 近方形小图，避免误伤书籍真实封面。 */
    private static boolean looksLikeOrangeFolder(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0 || w > 200 || h > 200) return false;
        if (Math.abs(w - h) > Math.min(w, h) / 3) return false;
        int step = Math.max(1, Math.min(w, h) / 20);
        int orange = 0, light = 0, total = 0;
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int c = b.getPixel(x, y);
                if (Color.alpha(c) < 8) continue;
                total++;
                int r = Color.red(c), g = Color.green(c), bl = Color.blue(c);
                if (r > 190 && g > 90 && g < 210 && bl < 120 && r - g > 40) orange++;
                else if (lum(c) > 215) light++;
            }
        }
        return total > 0 && orange * 100 / total > 40 && light * 100 / total > 6;
    }

    /** 原地重绘位图：对象不变（Fresco inBitmap 复用安全），只换像素内容。 */
    private static void redrawFolderInPlace(Bitmap b) {
        try {
            Canvas c = new Canvas(b);
            b.eraseColor(WHITE);
            drawFolderIcon(c, new Rect(0, 0, b.getWidth(), b.getHeight()));
            logOnce("folder", "Bitmap " + b.getWidth() + "x" + b.getHeight());
        } catch (Throwable ignored) {}
    }

    /** 白底 + 纯黑矩形描边 + 黑色空心（仅描边）文件夹轮廓。 */
    private static void drawFolderIcon(Canvas c, Rect r) {
        float w = r.width(), h = r.height();
        float sw = Math.max(2f, Math.min(w, h) * 0.055f);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(WHITE);
        fill.setStyle(Paint.Style.FILL);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(sw);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        float ins = sw;
        c.drawRect(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins, fill);
        c.drawRect(r.left + ins, r.top + ins, r.right - ins, r.bottom - ins, stroke);
        float l = r.left + w * 0.24f, rr = r.right - w * 0.24f;
        float t = r.top + h * 0.32f, bot = r.bottom - h * 0.32f;
        float tabW = w * 0.28f, tabH = h * 0.11f;
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(l, t + tabH);
        p.lineTo(l, bot);
        p.lineTo(rr, bot);
        p.lineTo(rr, t + tabH);
        p.lineTo(l + tabW, t + tabH);
        p.lineTo(l + tabW, t);
        p.lineTo(l, t);
        p.close();
        c.drawPath(p, stroke);
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

    /** CheckBox：未选中白底纯黑边空心方框；选中黑框内基本铺满黑色小方块。 */
    private static class CheckBoxDrawable extends Drawable {
        final boolean checked;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        CheckBoxDrawable(boolean checked) {
            this.checked = checked;
            fill.setColor(WHITE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            dot.setColor(BLACK);
            dot.setStyle(Paint.Style.FILL);
        }

        @Override public void draw(Canvas c) {
            Rect r = getBounds();
            float w = r.width(), h = r.height();
            float sw = Math.max(2f, Math.min(w, h) * 0.09f);
            stroke.setStrokeWidth(sw);
            float ins = Math.max(sw, Math.min(w, h) * 0.12f);
            // 列表末项的 drawable 底部会被父容器裁剪：把方框收进 Canvas 实际
            // 可见区域内，保证四条边都完整；其它行 clip 足够大，外观不变。
            float l = r.left + ins, t = r.top + ins;
            float rr = r.right - ins, b = r.bottom - ins;
            Rect clip = c.getClipBounds();
            if (clip != null) {
                l = Math.max(l, clip.left + ins);
                t = Math.max(t, clip.top + ins);
                rr = Math.min(rr, clip.right - ins);
                b = Math.min(b, clip.bottom - ins);
            }
            if (rr <= l || b <= t) return;
            float size = Math.min(rr - l, b - t);
            rr = l + size;
            b = t + size;
            c.drawRect(l, t, rr, b, fill);
            c.drawRect(l, t, rr, b, stroke);
            if (checked) {
                float p = size * 0.22f;
                c.drawRect(l + p, t + p, rr - p, b - p, dot);
            }
        }

        @Override public int getIntrinsicWidth() { return 60; }
        @Override public int getIntrinsicHeight() { return 48; }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    /** 文件夹图标：白底 + 纯黑矩形描边 + 空心文件夹（bounds 由 App setBounds 提供）。 */
    private static class FolderIconDrawable extends Drawable {
        @Override public void draw(Canvas c) {
            drawFolderIcon(c, getBounds());
            logOnce("folder", "FolderIconDrawable");
        }
        @Override public int getIntrinsicWidth() { return 96; }
        @Override public int getIntrinsicHeight() { return 96; }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
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
