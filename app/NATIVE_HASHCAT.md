# Bundled native Hashcat backend

AIRTOOLS launches Hashcat as a native executable stored in the APK.

The Hashcat runtime is not stored directly under `src/main`. It is built in the
separate `HASHCAT` project and imported into AIRTOOLS by:

```powershell
.\tools\import-hashcat-android.ps1
```

The import script writes generated files into:

```text
hashcat/build/generated/hashcatRuntime/jniLibs/arm64-v8a/libhashcat_exec.so
hashcat/build/generated/hashcatRuntime/assets/hashcat/
```

The `:hashcat` Android library module exposes that generated directory through
its Gradle `sourceSets`, and the app depends on the module with:

```kotlin
implementation(project(":hashcat"))
```

At runtime `HashcatRunner` copies the generated APK assets into app-private
storage and executes the extracted `libhashcat_exec.so` as a native process.

The executable is intentionally named `libhashcat_exec.so` so Android packages it
as a native library. `jniLibs.useLegacyPackaging = true` keeps it available as an
extracted file.