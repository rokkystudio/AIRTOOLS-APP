# Bundled native Hashcat backend

AIRTOOLS launches Hashcat as a native executable stored in the APK.

Expected payload layout:

```text
app/src/main/jniLibs/arm64-v8a/libhashcat_exec.so
app/src/main/jniLibs/armeabi-v7a/libhashcat_exec.so
app/src/main/jniLibs/x86_64/libhashcat_exec.so
```

The executable is intentionally named `libhashcat_exec.so` so Android treats it
as a native library, extracts it at install time, and exposes it through:

```kotlin
context.applicationInfo.nativeLibraryDir
```

`app/build.gradle.kts` enables `jniLibs.useLegacyPackaging = true`, which keeps
this backend available as an extracted file. `HashcatRunner` executes:

```text
<applicationInfo.nativeLibraryDir>/libhashcat_exec.so
```

Companion native dependencies for the same ABI can be placed in the same ABI
folder. Runtime data that is not a native `.so` cannot be automatically extracted
by Android from `jniLibs`; keep only real native `.so` files in ABI folders.
