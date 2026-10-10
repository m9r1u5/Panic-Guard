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

# App build.gradle: dependencies for secure storage + biometrics
def deps(s):
    add = (
        '\n    implementation "androidx.security:security-crypto:1.1.0-alpha06"'
        '\n    implementation "androidx.biometric:biometric:1.1.0"'
        '\n    implementation "androidx.camera:camera-core:1.3.4"'
        '\n    implementation "androidx.camera:camera-camera2:1.3.4"'
        '\n    implementation "androidx.camera:camera-lifecycle:1.3.4"'
        '\n    implementation "androidx.camera:camera-video:1.3.4"'
        '\n    implementation "androidx.lifecycle:lifecycle-service:2.6.2"'
    )
    return s.replace("dependencies {", "dependencies {" + add, 1)
edit("android/app/build.gradle", deps)

# Manifest: permissions and optional hardware
PERMS = [
    "INTERNET", "ACCESS_NETWORK_STATE", "REQUEST_INSTALL_PACKAGES",
    "READ_CONTACTS", "SEND_SMS", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
    "ACCESS_BACKGROUND_LOCATION", "CAMERA", "RECORD_AUDIO", "POST_NOTIFICATIONS",
    "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "USE_BIOMETRIC", "VIBRATE",
    "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_LOCATION", "FOREGROUND_SERVICE_CAMERA", "FOREGROUND_SERVICE_MICROPHONE", "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK",
]
FEATURES = ["android.hardware.telephony", "android.hardware.camera", "android.hardware.location.gps"]

def manifest(s):
    add = ""
    for p in PERMS:
        if 'android.permission.' + p + '"' not in s:
            add += '    <uses-permission android:name="android.permission.' + p + '" />\n'
    for f in FEATURES:
        if f not in s:
            add += '    <uses-feature android:name="' + f + '" android:required="false" />\n'
    comps = ""
    if "PanicService" not in s:
        comps = (
            '        <service android:name=".PanicService" android:exported="false" '
            'android:foregroundServiceType="location|camera|microphone" />\n'
            '        <receiver android:name=".BootReceiver" android:exported="true">\n'
            '            <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>\n'
            '        </receiver>\n'
        )
    s = s.replace("</application>", comps + "    </application>", 1)
    return s.replace("</manifest>", add + "</manifest>", 1)
edit("android/app/src/main/AndroidManifest.xml", manifest)
print("Patched OK")
