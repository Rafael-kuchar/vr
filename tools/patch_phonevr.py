#!/usr/bin/env python3
"""Apply the VR Phone Bridge ARCore/MJPEG overlay to PhoneVR's noGvr client.

This intentionally fails closed when upstream source structure changes. It does not
silently produce an APK with the old Cardboard-only tracking path.
"""
from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one {label} anchor, found {count}")
    return text.replace(old, new, 1)


def patch_activity(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    # Upstream ALVRActivity already imports Manifest, PackageManager and ActivityCompat.
    text = replace_once(
        text,
        "    private static final int PERMISSIONS_REQUEST_CODE = 2;\n",
        "    private static final int PERMISSIONS_REQUEST_CODE = 2;\n"
        "    private static final int BRIDGE_CAMERA_REQUEST_CODE = 41;\n",
        "camera permission request code",
    )
    text = replace_once(
        text,
        "    private GLSurfaceView glView;\n",
        "    private GLSurfaceView glView;\n"
        "    private BridgeTracking bridgeTracking;\n",
        "BridgeTracking field",
    )
    text = replace_once(
        text,
        "        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);\n",
        "        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);\n"
        "        bridgeTracking = new BridgeTracking(this);\n"
        "        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)\n"
        "                != PackageManager.PERMISSION_GRANTED) {\n"
        "            ActivityCompat.requestPermissions(this,\n"
        "                    new String[] {Manifest.permission.CAMERA}, BRIDGE_CAMERA_REQUEST_CODE);\n"
        "        }\n",
        "BridgeTracking initialization",
    )
    text = replace_once(
        text,
        "        glView.onPause();\n",
        "        glView.onPause();\n"
        "        if (bridgeTracking != null) bridgeTracking.pause();\n",
        "camera pause lifecycle hook after GL pause",
    )
    text = replace_once(
        text,
        "        bMonitor.startMonitoring(this);\n",
        "        bMonitor.startMonitoring(this);\n"
        "        if (bridgeTracking != null\n"
        "                && ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)\n"
        "                        == PackageManager.PERMISSION_GRANTED) {\n"
        "            bridgeTracking.resume();\n"
        "        }\n",
        "camera resume lifecycle hook",
    )
    text = replace_once(
        text,
        "        Log.d(TAG, \"Destroying ALVR Activity\");\n",
        "        Log.d(TAG, \"Destroying ALVR Activity\");\n"
        "        if (bridgeTracking != null) bridgeTracking.close();\n",
        "camera destroy lifecycle hook",
    )
    text = replace_once(
        text,
        "        public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {\n"
        "            surfaceCreatedNative();\n"
        "        }\n",
        "        public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {\n"
        "            surfaceCreatedNative();\n"
        "            bridgeTracking.onSurfaceCreated();\n"
        "        }\n",
        "GL surface-created hook",
    )
    text = replace_once(
        text,
        "        public void onDrawFrame(GL10 gl10) {\n"
        "            renderNative();\n"
        "        }\n",
        "        public void onDrawFrame(GL10 gl10) {\n"
        "            bridgeTracking.onDrawFrame();\n"
        "            renderNative();\n"
        "        }\n",
        "GL frame tracking hook",
    )
    text = replace_once(
        text,
        "    public void onRequestPermissionsResult(\n"
        "            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {\n"
        "        super.onRequestPermissionsResult(requestCode, permissions, grantResults);\n",
        "    public void onRequestPermissionsResult(\n"
        "            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {\n"
        "        super.onRequestPermissionsResult(requestCode, permissions, grantResults);\n"
        "        if (requestCode == BRIDGE_CAMERA_REQUEST_CODE) {\n"
        "            if (grantResults.length > 0\n"
        "                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {\n"
        "                if (bridgeTracking != null) bridgeTracking.resume();\n"
        "            } else {\n"
        "                Log.w(TAG, \"Camera permission denied; ALVR will use its built-in 3DOF fallback\");\n"
        "            }\n"
        "            return;\n"
        "        }\n",
        "camera permission callback branch",
    )
    text = replace_once(
        text,
        "    private native void initializeNative(\n",
        "    public void submitExternalPose(float x, float y, float z,\n"
        "            float qx, float qy, float qz, float qw, long timestampNs, boolean valid) {\n"
        "        setExternalPoseNative(x, y, z, qx, qy, qz, qw, timestampNs, valid);\n"
        "    }\n\n"
        "    private native void setExternalPoseNative(float x, float y, float z,\n"
        "            float qx, float qy, float qz, float qw, long timestampNs, boolean valid);\n\n"
        "    private native void initializeNative(\n",
        "external pose JNI declarations",
    )
    path.write_text(text, encoding="utf-8")


def patch_native(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        "#include <map>\n",
        "#include <map>\n#include <cmath>\n#include <mutex>\n",
        "C++ mutex include",
    )
    state_block = r'''
struct BridgeExternalPose {
    bool valid = false;
    bool hasOrigin = false;
    uint64_t updatedNs = 0;
    float position[3] = {0.0f, 0.0f, 0.0f};
    float origin[3] = {0.0f, 0.0f, 0.0f};
    AlvrQuat orientation = {0.0f, 0.0f, 0.0f, 1.0f};
    AlvrQuat originOrientation = {0.0f, 0.0f, 0.0f, 1.0f};
};

std::mutex g_bridgePoseMutex;
BridgeExternalPose g_bridgePose;

AlvrQuat multiplyQuat(AlvrQuat a, AlvrQuat b) {
    return {
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
    };
}

extern "C" JNIEXPORT void JNICALL
Java_viritualisres_phonevr_ALVRActivity_setExternalPoseNative(
    JNIEnv *, jobject, jfloat x, jfloat y, jfloat z,
    jfloat qx, jfloat qy, jfloat qz, jfloat qw,
    jlong timestampNs, jboolean valid) {
    std::lock_guard<std::mutex> guard(g_bridgePoseMutex);
    if (!valid) {
        g_bridgePose.valid = false;
        return;
    }

    AlvrQuat q = {qx, qy, qz, qw};
    const float norm = std::sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
    if (norm < 0.0001f) {
        g_bridgePose.valid = false;
        return;
    }
    q.x /= norm;
    q.y /= norm;
    q.z /= norm;
    q.w /= norm;

    if (!g_bridgePose.hasOrigin) {
        g_bridgePose.origin[0] = x;
        g_bridgePose.origin[1] = y;
        g_bridgePose.origin[2] = z;
        g_bridgePose.originOrientation = q;
        g_bridgePose.hasOrigin = true;
    }
    g_bridgePose.position[0] = x;
    g_bridgePose.position[1] = y;
    g_bridgePose.position[2] = z;
    g_bridgePose.orientation = q;
    g_bridgePose.updatedNs = static_cast<uint64_t>(timestampNs);
    g_bridgePose.valid = true;
}
'''
    text = replace_once(
        text,
        "AlvrPose getPose(uint64_t timestampNs) {\n",
        state_block + "\nAlvrPose getPose(uint64_t timestampNs) {\n",
        "external pose state and JNI bridge",
    )
    old_get_pose = re.compile(
        r"AlvrPose\s+getPose\s*\(\s*uint64_t\s+timestampNs\s*\)\s*\{\s*"
        r"AlvrPose\s+pose\s*=\s*\{\}\s*;\s*"
        r"float\s+pos\s*\[\s*3\s*\]\s*;\s*"
        r"float\s+q\s*\[\s*4\s*\]\s*;\s*"
        r"CardboardHeadTracker_getPose\s*\(\s*CTX\.headTracker\s*,\s*"
        r"\(int64_t\)\s*timestampNs\s*,\s*kLandscapeLeft\s*,\s*pos\s*,\s*q\s*\)\s*;\s*"
        r"auto\s+inverseOrientation\s*=\s*AlvrQuat\s*\{\s*q\[0\]\s*,\s*q\[1\]\s*,\s*q\[2\]\s*,\s*q\[3\]\s*\}\s*;\s*"
        r"pose\.orientation\s*=\s*inverseQuat\s*\(\s*inverseOrientation\s*\)\s*;\s*"
        r"return\s+pose\s*;\s*\}\s*",
        re.MULTILINE,
    )
    new_get_pose = (
        "AlvrPose getPose(uint64_t timestampNs) {\n"
        "    AlvrPose pose = {};\n"
        "    BridgeExternalPose external;\n"
        "    {\n"
        "        std::lock_guard<std::mutex> guard(g_bridgePoseMutex);\n"
        "        external = g_bridgePose;\n"
        "    }\n"
        "    const uint64_t nowNs = static_cast<uint64_t>(GetBootTimeNano());\n"
        "    if (external.valid && nowNs >= external.updatedNs\n"
        "            && nowNs - external.updatedNs < 500000000ULL && external.hasOrigin) {\n"
        "        const AlvrQuat recenter = inverseQuat(external.originOrientation);\n"
        "        float delta[3] = {\n"
        "            external.position[0] - external.origin[0],\n"
        "            external.position[1] - external.origin[1],\n"
        "            external.position[2] - external.origin[2]\n"
        "        };\n"
        "        quatVecMultiply(recenter, delta, pose.position);\n"
        "        pose.orientation = multiplyQuat(recenter, external.orientation);\n"
        "        return pose;\n"
        "    }\n\n"
        "    // Safe fallback when ARCore is unavailable or temporarily not tracking.\n"
        "    float pos[3];\n"
        "    float q[4];\n"
        "    CardboardHeadTracker_getPose(CTX.headTracker, (int64_t) timestampNs, kLandscapeLeft, pos, q);\n"
        "    auto inverseOrientation = AlvrQuat{q[0], q[1], q[2], q[3]};\n"
        "    pose.orientation = inverseQuat(inverseOrientation);\n"
        "    return pose;\n"
        "}\n"
    )
    # Upstream formats this file with blank lines between many statements, so
    # match the function structurally rather than relying on exact whitespace.
    if new_get_pose.strip() not in text:
        text, count = old_get_pose.subn(new_get_pose, text, count=1)
        if count != 1:
            raise RuntimeError(f"Expected exactly one original Cardboard getPose function, found {count}")
    path.write_text(text, encoding="utf-8")


def patch_gradle(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    text = replace_once(
        text,
        'applicationId "viritualisres.phonevr"',
        'applicationId "com.vrhands.bridge"',
        "PhoneVR applicationId rename",
    )
    if "com.google.ar:core:1.44.0" not in text:
        text = replace_once(
            text,
            "implementation 'com.google.cardboard.sdk:cardboard-sdk:1.17.0@aar'",
            "implementation 'com.google.cardboard.sdk:cardboard-sdk:1.17.0@aar'\n"
            "    implementation 'com.google.ar:core:1.44.0'",
            "ARCore Gradle dependency",
        )
    path.write_text(text, encoding="utf-8")


def patch_manifest(path: Path) -> None:
    """Declare the camera/network capabilities used by the bridge.

    The upstream ALVR client does not need camera access by itself. The bridge
    uses ARCore both for the external 6DOF pose and for the optional MJPEG hand
    camera, so requesting the runtime permission without declaring it in the
    manifest would always fail on Android.
    """
    text = path.read_text(encoding="utf-8")

    manifest_open = re.search(r"<manifest\b[^>]*>", text, flags=re.DOTALL)
    if not manifest_open:
        raise RuntimeError(f"Could not find <manifest> root in {path}")

    permissions = [
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.CAMERA",
    ]
    additions = []
    for permission in permissions:
        marker = f'android:name="{permission}"'
        if marker not in text:
            additions.append(f'    <uses-permission android:name="{permission}" />')

    feature = '<uses-feature android:name="android.hardware.camera.any" android:required="false" />'
    if feature not in text:
        additions.append(feature)

    if additions:
        insert_at = manifest_open.end()
        text = text[:insert_at] + "\n" + "\n".join(additions) + text[insert_at:]

    if 'android:name="com.google.ar.core"' not in text:
        application_open = re.search(r"<application\b[^>]*>", text, flags=re.DOTALL)
        if not application_open:
            raise RuntimeError(f"Could not find <application> in {path}")
        metadata = '\n        <meta-data android:name="com.google.ar.core" android:value="optional" />'
        insert_at = application_open.end()
        text = text[:insert_at] + metadata + text[insert_at:]

    path.write_text(text, encoding="utf-8")


def patch_strings(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    updated, count = re.subn(
        r'(<string\b(?=[^>]*\bname="app_name")[^>]*>).*?(</string>)',
        r'\1VR Phone Bridge\2',
        text,
        count=1,
        flags=re.DOTALL,
    )
    if count != 1:
        raise RuntimeError(f"Could not find app_name string in {path}")
    path.write_text(updated, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--phonevr", required=True, type=Path)
    parser.add_argument("--overlay", required=True, type=Path)
    args = parser.parse_args()

    root = args.phonevr.resolve()
    app = root / "app"
    java_dir = app / "src/main/java/viritualisres/phonevr"
    native_file = app / "src/main/cpp/alvr_main.cpp"
    activity_file = java_dir / "ALVRActivity.java"
    gradle_file = app / "build.gradle"
    manifest_file = app / "src/main/AndroidManifest.xml"
    strings_file = app / "src/main/res/values/strings.xml"

    required = [native_file, activity_file, gradle_file, manifest_file, strings_file]
    missing = [str(p) for p in required if not p.is_file()]
    if missing:
        raise FileNotFoundError("PhoneVR source layout changed; missing: " + ", ".join(missing))

    patch_activity(activity_file)
    patch_native(native_file)
    patch_gradle(gradle_file)
    patch_manifest(manifest_file)
    patch_strings(strings_file)

    for name in ("BridgeTracking.java", "BridgeMjpegServer.java"):
        source = args.overlay / name
        if not source.is_file():
            raise FileNotFoundError(f"Missing overlay source: {source}")
        shutil.copy2(source, java_dir / name)

    print("PhoneVR ALVR noGvr source patched for VR Phone Bridge (ARCore + MJPEG).")


if __name__ == "__main__":
    main()
