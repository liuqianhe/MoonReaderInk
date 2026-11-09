#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""手工构建 Xposed 模块：javac -> d8 -> aapt2 -> zipalign -> apksigner。

不依赖 Gradle / 网络。工具链按顺序查找（可用环境变量覆盖，方便在别的机器上构建）：
  JDK bin      : MRI_JDK_BIN > local.properties(jdk.bin) > JAVA_HOME/bin > PATH 里的 javac
  Android SDK  : MRI_ANDROID_SDK / ANDROID_HOME / ANDROID_SDK_ROOT
                 > local.properties(sdk.dir) > 常见默认安装位置
  build-tools  : MRI_BUILD_TOOLS(版本号) > 35.0.0 > SDK 内可用的最高版本
  android.jar  : platforms/android-33 > SDK 内可用的最高 platform

用法：
  python build.py              # debug   -> out/MoonReaderInk-debug.apk
  python build.py --release    # 正式版 -> out/MoonReaderInk-release.apk

签名：
  debug   : keys/debug.keystore -> ~/.android/debug.keystore -> keytool 自动生成
  release : keys/release.properties（字段见 README.md；密钥与配置均不入库）
"""
import argparse
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WORK = ROOT / "work"
OUT = ROOT / "out"
XPOSED_INIT = "com.moonreader.ink.Hook"
EXE = ".exe" if os.name == "nt" else ""

# 本机工具链配置（local.properties 不入库，见 .gitignore / README.md）。
# 仓库本身不含任何个人路径；别的机器用环境变量或自备这个文件即可。
LOCAL = {}
_local_cfg = ROOT / "local.properties"
if _local_cfg.is_file():
    for _line in _local_cfg.read_text(encoding="utf-8").splitlines():
        _line = _line.strip()
        if _line and not _line.startswith("#") and "=" in _line:
            _k, _v = _line.split("=", 1)
            LOCAL[_k.strip()] = _v.strip()


def find_jdk_bin():
    """返回含 java / javac / keytool 的 JDK bin 目录。"""
    cands = []
    if os.environ.get("MRI_JDK_BIN"):
        cands.append(Path(os.environ["MRI_JDK_BIN"]))
    if LOCAL.get("jdk.bin"):
        cands.append(Path(LOCAL["jdk.bin"]))
    if os.environ.get("JAVA_HOME"):
        cands.append(Path(os.environ["JAVA_HOME"]) / "bin")
    javac = shutil.which("javac")
    if javac:
        cands.append(Path(javac).parent)
    for p in cands:
        if (p / ("javac" + EXE)).is_file():
            return p
    raise SystemExit(
        "找不到 JDK：设置 JAVA_HOME / MRI_JDK_BIN，或在 local.properties 写 jdk.bin")


def find_sdk():
    cands = [Path(os.environ[k]) for k in
             ("MRI_ANDROID_SDK", "ANDROID_HOME", "ANDROID_SDK_ROOT")
             if os.environ.get(k)]
    if LOCAL.get("sdk.dir"):
        cands.append(Path(LOCAL["sdk.dir"]))
    cands.append(Path.home() / "Android" / "Sdk")
    if os.environ.get("LOCALAPPDATA"):
        cands.append(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk")
    for p in cands:
        if (p / "platforms").is_dir():
            return p
    raise SystemExit(
        "找不到 Android SDK：设置 ANDROID_HOME / MRI_ANDROID_SDK，"
        "或在 local.properties 写 sdk.dir")


def find_build_tools(sdk):
    root = sdk / "build-tools"
    if not root.is_dir():
        raise SystemExit("SDK 里没有 build-tools：%s" % root)
    pref = root / (os.environ.get("MRI_BUILD_TOOLS")
                   or LOCAL.get("build.tools") or "35.0.0")
    if (pref / ("aapt2" + EXE)).is_file():
        return pref
    usable = sorted(p for p in root.iterdir()
                    if p.is_dir() and (p / ("aapt2" + EXE)).is_file())
    if not usable:
        raise SystemExit("SDK 里没有可用的 build-tools（需要 aapt2）：%s" % root)
    return usable[-1]


def find_android_jar(sdk):
    p = sdk / "platforms/android-33/android.jar"
    if p.is_file():
        return p
    usable = sorted(sdk.glob("platforms/android-*/android.jar"))
    if usable:
        return usable[-1]
    raise SystemExit("SDK 里没有 android.jar（需要 platforms/android-33 或更高）")


def find_keys_dir():
    """模块内 keys/ 优先；兼容旧工作区的父目录 keys/；都没有就新建模块内 keys/。"""
    for p in (ROOT / "keys", ROOT.parent / "keys"):
        if p.is_dir():
            return p
    p = ROOT / "keys"
    p.mkdir(exist_ok=True)
    return p


def ensure_debug_keystore(keys, jdk):
    """调试密钥三选一：仓库私有 keys/ -> Android SDK 默认 -> 用 keytool 现场生成。"""
    p = keys / "debug.keystore"
    if p.is_file():
        return p
    home = Path.home() / ".android" / "debug.keystore"
    if home.is_file():
        return home
    keytool = jdk / ("keytool" + EXE)
    if not keytool.is_file():
        raise SystemExit("缺少 keytool，无法生成调试密钥：%s" % keytool)
    print("生成调试密钥:", p, flush=True)
    subprocess.run([str(keytool), "-genkeypair", "-keystore", str(p),
                    "-storetype", "PKCS12", "-alias", "androiddebugkey",
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
                    "-storepass", "android", "-keypass", "android",
                    "-dname", "CN=Android Debug,O=Android,C=US", "-noprompt"],
                   check=True)
    return p


JDK = find_jdk_bin()
SDK = find_sdk()
BT = find_build_tools(SDK)
ANDROID = str(find_android_jar(SDK))
KEYS = find_keys_dir()
RELEASE_PROPERTIES = KEYS / "release.properties"

JAVA = str(JDK / ("java" + EXE))
JAVAC = str(JDK / ("javac" + EXE))
AAPT2 = str(BT / ("aapt2" + EXE))
ZIPALIGN = str(BT / ("zipalign" + EXE))
APKSIGNER = str(BT / "lib/apksigner.jar")
D8 = str(BT / "lib/d8.jar")


def clean_work():
    """清理构建缓存。某些运行时会把 shutil.rmtree 重定向到回收站并失败，
    这里退回系统命令；仍失败就复用目录（下面的 mkdir 用 exist_ok 兜底）。"""
    if not WORK.exists():
        return
    try:
        shutil.rmtree(WORK)
        return
    except OSError:
        pass
    if os.name == "nt":
        subprocess.run(["cmd", "/c", "rmdir", "/S", "/Q", str(WORK)], check=False)
    else:
        subprocess.run(["rm", "-rf", str(WORK)], check=False)
    if WORK.exists():
        print("警告: 构建缓存未能清空，将复用已有目录", flush=True)


def run(*args):
    print("  $", " ".join(str(a) for a in args), flush=True)
    subprocess.run([str(a) for a in args], check=True)


def sources(base):
    return sorted(str(p) for p in base.rglob("*.java"))


def read_properties(path):
    cfg = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        cfg[k.strip()] = v.strip()
    return cfg


def signing(a):
    """返回 (keystore, alias, store_pass, key_pass, 输出 APK)。"""
    if a.release:
        if not RELEASE_PROPERTIES.is_file():
            raise SystemExit(
                "缺少正式版签名配置: %s\n"
                "请在 keys/ 下自建 release.properties（不入库），字段见 README.md"
                % RELEASE_PROPERTIES)
        cfg = read_properties(RELEASE_PROPERTIES)
        for k in ("storeFile", "keyAlias", "storePassword"):
            if k not in cfg:
                raise SystemExit("release.properties 缺字段: " + k)
        keystore = KEYS / cfg["storeFile"]
        alias = cfg["keyAlias"]
        store_pass = "pass:" + cfg["storePassword"]
        key_pass = "pass:" + cfg.get("keyPassword", cfg["storePassword"])
        apk = OUT / "MoonReaderInk-release.apk"
    else:
        keystore = ensure_debug_keystore(KEYS, JDK)
        alias = "androiddebugkey"
        store_pass = key_pass = "pass:android"
        apk = OUT / "MoonReaderInk-debug.apk"
    return keystore, alias, store_pass, key_pass, apk


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", action="store_true", help="用正式版密钥签名并输出 release APK")
    a = ap.parse_args()

    keystore, alias, store_pass, key_pass, APK = signing(a)
    for p in (Path(ANDROID), Path(AAPT2), Path(D8), Path(APKSIGNER), Path(keystore)):
        if not p.exists():
            raise SystemExit("缺少构建工具: %s" % p)
    print("模式: %s | 密钥: %s" % ("release" if a.release else "debug", keystore))

    clean_work()
    OUT.mkdir(exist_ok=True)
    (WORK / "stubs").mkdir(parents=True, exist_ok=True)
    (WORK / "classes").mkdir(parents=True, exist_ok=True)
    (WORK / "dex").mkdir(parents=True, exist_ok=True)

    print("[1/7] 编译 Xposed 桩类")
    run(JAVAC, "-encoding", "UTF-8", "--release", "8", "-cp", ANDROID,
        "-d", WORK / "stubs", *sources(ROOT / "stubs"))

    print("[2/7] 编译模块源码")
    run(JAVAC, "-encoding", "UTF-8", "--release", "8", "-cp",
        str(ANDROID) + os.pathsep + str(WORK / "stubs"),
        "-d", WORK / "classes", *sources(ROOT / "src"))

    print("[3/7] 打包 classes.jar 并转 dex")
    classes_jar = WORK / "classes.jar"
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted((WORK / "classes").rglob("*.class")):
            z.write(f, f.relative_to(WORK / "classes").as_posix())
    run(JAVA, "-cp", D8, "com.android.tools.r8.D8", "--release",
        "--min-api", "29", "--lib", ANDROID, "--classpath", WORK / "stubs",
        "--output", WORK / "dex", classes_jar)

    print("[4/7] 编译资源")
    run(AAPT2, "compile", "--dir", ROOT / "res", "-o", WORK / "res.zip")
    run(AAPT2, "link", "-I", ANDROID, "--manifest", ROOT / "AndroidManifest.xml",
        "-A", ROOT / "assets", "--min-sdk-version", "29",
        "--target-sdk-version", "33", "-o", WORK / "unsigned.apk", WORK / "res.zip")

    print("[5/7] 写入 classes.dex")
    normalized = WORK / "normalized.apk"
    with zipfile.ZipFile(WORK / "unsigned.apk") as src, \
            zipfile.ZipFile(normalized, "w") as dst:
        for info in src.infolist():
            dst.writestr(info, src.read(info.filename))
        dst.write(WORK / "dex/classes.dex", "classes.dex", zipfile.ZIP_DEFLATED)

    print("[6/7] 对齐")
    aligned = WORK / "aligned.apk"
    run(ZIPALIGN, "-f", "4", normalized, aligned)

    print("[7/7] 签名并校验")
    if APK.exists():
        APK.unlink()
    run(JAVA, "-jar", APKSIGNER, "sign", "--v4-signing-enabled", "false",
        "--ks", keystore, "--ks-key-alias", alias,
        "--ks-pass", store_pass, "--key-pass", key_pass,
        "--out", APK, aligned)
    run(JAVA, "-jar", APKSIGNER, "verify", "--verbose", APK)

    with zipfile.ZipFile(APK) as z:
        names = z.namelist()
        assert "classes.dex" in names, "缺 classes.dex"
        assert z.read("assets/xposed_init").decode().strip() == XPOSED_INIT, \
            "xposed_init 不对"
    print("\n完成: %s (%d bytes)" % (APK, APK.stat().st_size))


if __name__ == "__main__":
    main()
