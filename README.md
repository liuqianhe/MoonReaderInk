# 静读天下墨水屏模式（MoonReaderInk）

一个基于 **LSPosed / Xposed** 的 UI 适配模块：把「静读天下」阅读器里偏蓝、偏灰的界面改成适合电子墨水屏的**白底黑字**，并把 EPUB 注释弹窗里的链接文字改黑、去掉下划线。

> 个人学习与技术练习项目 · 非官方 · 非商业 · 不含目标应用任何代码或资源

---

## ⚠️ 版权与免责声明（请先阅读）

- 本项目是作者为学习 **Android View 体系、资源机制与 Xposed Hook 技术** 而编写的实验性模块，
  **仅供个人学习、研究与技术交流使用，不得用于商业用途**。
- 本项目**不是**静读天下（Moon+ Reader，包名 `com.flyersoft.moonreaderp` 等）的官方产品，
  与该应用的开发者、发行方及版权方**没有任何关联、授权或背书**。
- 本仓库**不包含**目标应用的 APK、DEX、源代码、布局文件、图片资源、字体，也不包含任何书籍内容；
  **不提供目标应用的下载、不修改、不重打包、不分发**目标应用，也不提供任何破解、去广告、
  绕过授权或版权保护的功能。
- 模块只在用户**已合法安装**目标应用的设备上通过运行时 Hook 调整界面配色与链接文字的表现，
  不改动应用本体的任何文件，也不读取或上传用户数据、书籍内容或账号信息。
- 代码中出现的包名、类名、资源名与资源 id，仅是在已安装应用上定位控件所需的兼容性信息，
  来源于运行时的公开可观察信息；相关名称、商标、代码与资源的权利**归各自权利人所有**。
  提及它们只为说明兼容范围，不构成任何授权或关联。
- 模块内嵌的书本图标（`BookIconData.java` 中的 Base64 PNG）是项目作者提供的简单线框图形，
  仅用于替换界面图标；**不含目标应用的任何图标或素材**。
- 使用第三方 Xposed / LSPosed 框架由使用者自行决定，本项目不分发该框架。
  `stubs/` 目录只是编译期的最小占位声明，运行时由框架提供真实实现，**不是官方 Xposed 库**。
- 使用本模块**可能违反目标应用的使用条款**，也可能因应用更新而失效；由此产生的设备、数据、
  账号或兼容性问题，由使用者自行承担，作者不承担任何责任。
- 本仓库原创代码以学习交流为目的提供，**未附加开源许可证**；请勿将本项目或其中涉及第三方
  应用的信息用于商业目的。若权利人认为仓库内容不妥，请通过 GitHub Issue 联系，将及时删除或调整。
- 以上声明不构成法律意见；如需公开分发或用于任何非个人学习场景，请自行确认适用法律与授权。

---

## 功能

| 功能 | 说明 |
| --- | --- |
| 链接 Hook | 把 EPUB 注释弹窗里 `Html.fromHtml()` 生成的原生 `URLSpan` 换成自定义 Span，跟随 App 自身的「链接颜色」并去掉下划线，保留点击 |
| 墨水屏主题 | 彩色 / 灰阶背景转纯白、文字与图标转黑，状态栏改白底黑图标，顶部加黑色分割线，开关 / 单选 / 分段按钮换成黑白样式，蓝色默认封面与悬浮按钮重绘 |
| 总开关 | 一键停用上面两个功能；关闭后模块一个 hook 都不注册 |

三个功能各有独立开关，默认全部开启。默认作用域（推荐列表）包含原版
`com.flyersoft.moonreaderp` 与改包名的共存版 `com.flyersoft.moonreaderps`，两者互不干扰。

## 环境要求与构建

不依赖 Gradle、Android Studio 或网络，也不会下载任何依赖。

| 依赖 | 说明 |
| --- | --- |
| Python | 3.9+，仅标准库 |
| JDK | 17（需要 `java` / `javac` / `keytool`） |
| Android SDK | 任一 `platforms/android-33`（或更高）的 `android.jar`、`build-tools` 35.0.0（或含 `aapt2` 的其它版本） |

工具链按顺序自动查找，可用环境变量覆盖：

| 变量 | 用途 |
| --- | --- |
| `JAVA_HOME` / `MRI_JDK_BIN` | JDK 根目录 / 直接指向 `bin` |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `MRI_ANDROID_SDK` | Android SDK 根目录 |
| `MRI_BUILD_TOOLS` | 指定 build-tools 版本，如 `35.0.0` |

也可以在仓库根目录放一个 **`local.properties`**（本机私有，已被 `.gitignore` 排除），
写死本机路径，优先级低于环境变量：

```properties
jdk.bin=/path/to/jdk/bin
sdk.dir=/path/to/android-sdk
build.tools=35.0.0
```

仓库本身不含任何个人路径或凭据。

```bash
python build.py              # 调试版 -> out/MoonReaderInk-debug.apk
python build.py --release    # 正式版 -> out/MoonReaderInk-release.apk
```

链路：`javac` → `d8` → `aapt2` → `zipalign` → `apksigner`，最后校验签名与
`assets/xposed_init`。

**签名说明**

- 调试版依次尝试 `keys/debug.keystore` → `~/.android/debug.keystore` → 用 `keytool`
  现场生成（alias `androiddebugkey`、密码 `android`），开箱即可构建。
- 正式版需要自备 `keys/release.properties`（**不入库**）：

  ```properties
  storeFile=release.keystore
  keyAlias=your-alias
  storePassword=your-store-password
  keyPassword=your-key-password
  ```

- `keys/`、`*.keystore`、`*.jks`、`release.properties` 与 `local.properties`
  均已被 `.gitignore` 排除。**不要把任何私钥、密码或本机配置提交到仓库**；
  正式版密钥一旦丢失，已安装用户将无法升级。

## 安装与使用

1. 设备需已 root 并装有 LSPosed（或兼容的 Xposed 实现）；本项目在 Android 10 上验证。
2. 安装构建出的 APK，在框架管理器里启用模块，并**手动勾选作用域**——推荐列表
   （`res/values/arrays.xml`）只是建议值，不会自动写进已保存的作用域。
3. 强制停止并重新打开目标阅读器。
4. 在模块设置页（框架的「模块设置」入口）切换三个开关；改动需重启目标 App 生效。

设置页里提到的 `tools/ink_switch.py` 是作者本机的外围脚本，**不随本仓库分发**。
等价的强制覆盖命令（root 下，优先级高于设置页）：

```bash
adb shell setprop persist.moonreader.ink.master 0       # 总开关，0 关 / 1 开
adb shell setprop persist.moonreader.ink.link_hook 0    # 链接 Hook
adb shell setprop persist.moonreader.ink.theme 0        # 墨水屏主题
adb shell setprop persist.moonreader.ink.master ""      # 清空，交还设置页
```

## 目录结构

```
AndroidManifest.xml        模块声明（包名 com.moonreader.ink、Xposed 元数据、推荐作用域）
assets/xposed_init         Xposed 入口：com.moonreader.ink.Hook
src/com/moonreader/ink/
  Hook.java                入口、总开关、链接 Hook
  EInkTheme.java           墨水屏主题的全部 Hook 与自绘 Drawable
  SettingsActivity.java    纯代码设置页（三个开关）
  BookIconData.java        内嵌书本图标（Base64 PNG）
res/values/                应用名、模块描述、推荐作用域数组
stubs/                    Xposed API 的编译期占位声明（非官方库）
build.py                  手工构建脚本
```

## 实现要点与已知限制

- 主题覆盖 `Resources` 取色 / `getDrawable`、View 与 TextView / ImageView 的 setter、
  状态栏，并在 `Activity.onResume`、`Dialog.show`、`View.onAttachedToWindow` 时扫描控件树。
- 蓝底白字的徽标**原地改像素**而不是替换 Bitmap 对象（Fresco 会复用 `inBitmap`）；
  MaterialButton 只做 tint 不替换 background（App 内部会强转具体类型）。
- 顶部分割线**只按资源 id** 定位面包屑容器，不用屏幕坐标判断——列表滚动后条目会滚到
  屏幕上方，按坐标猜会不断给条目误加黑线。
- 模块代码运行在目标进程里读不到自身 `res/`，因此图标以 Base64 内嵌、资源 id 同时按
  资源名解析兜底。
- **仅针对静读天下 10.8 做过验证**，属于版本相关的实验性 Hook；目标应用更新后可能失效，
  也可能出现样式错位。请优先在备用设备 / 共存版上试用。
- 书籍真实封面、书内图片与正文内容都不会被改动；模块不写入目标应用的任何文件。
- 分割线固定在面包屑容器底部；没有该容器的页面不会自动加线。

## 反馈

欢迎通过 Issue 交流技术细节。请勿在 Issue 中提交目标应用的 APK、反编译产物、
受版权保护的书籍内容或任何私钥。
