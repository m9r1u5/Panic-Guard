import os, re, shutil, sys

PKG_DIR = "android/app/src/main/java/com/m9r1u5/panicguard"
os.makedirs(PKG_DIR, exist_ok=True)

# Remove the Java MainActivity Capacitor generates, use our Kotlin files
for f in os.listdir(PKG_DIR):
    if f.endswith(".java"):
        os.remove(os.path.join(PKG_DIR, f))
for f in os.listdir("."):
    if f.endswith(".kt"):
        shutil.copy(f, os.path.join(PKG_DIR, f))

# FileProvider paths (for installing the downloaded update)
os.makedirs("android/app/src/main/res/xml", exist_ok=True)
shutil.copy("file_paths.xml", "android/app/src/main/res/xml/file_paths.xml")

def edit(path, fn):
    with open(path) as fh:
        s = fh.read()
    new = fn(s)
    if new == s:
        print("WARNING: no change made to", path)
    with open(path, "w") as fh:
        fh.write(new)

# Root build.gradle: add Kotlin plugin
def root(s):
    return re.sub(
        r"(classpath ['\"]com\.android\.tools\.build:gradle:[^'\"]+['\"])",
        r"\1\n        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22'",
        s, count=1)
edit("android/build.gradle", root)

# App build.gradle: Kotlin + version numbers
def app(s):
    vc = os.environ.get("VERSION_CODE", "1")
    s = s.replace("apply plugin: 'com.android.application'",
                  "apply plugin: 'com.android.application'\napply plugin: 'kotlin-android'", 1)
    s = re.sub(r"versionCode\s+\d+", "versionCode " + vc, s, count=1)
    s = re.sub(r'versionName\s+"[^"]*"', 'versionName "1.0.' + vc + '"', s, count=1)
    s = s.replace("android {", "android {\n    kotlinOptions { jvmTarget = '17' }", 1)
    return s
edit("android/app/build.gradle", app)

# Manifest: permissions needed for Stage 1
def manifest(s):
    perms = (
        '    <uses-permission android:name="android.permission.INTERNET" />\n'
        '    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />\n'
        '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n'
    )
    for line in perms.splitlines():
        name = re.search(r'name="([^"]+)"', line).group(1)
        if name in s:
            perms = perms.replace(line + "\n", "")
    return s.replace("</manifest>", perms + "</manifest>", 1)
edit("android/app/src/main/AndroidManifest.xml", manifest)
print("Patched OK")
