package com.moonreader.ink;

/** 用户提供的 F7Book.png（黑色线框打开书本），Base64 内嵌，运行时解码成 Bitmap。
 *  模块代码跑在目标 App 进程里，读不到自身 res/，所以不能用资源 id。 */
public final class BookIconData {
    private BookIconData() {}
    public static final String PNG =
        "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" +
        "rkJggg==";
}
