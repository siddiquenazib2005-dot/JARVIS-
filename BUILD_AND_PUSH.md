# AURIX: build and push guide

Repository target:

```text
https://github.com/siddiquenazib2005-dot/JARVIS-
```

## Push from phone/PC

```bash
git remote set-url origin https://github.com/siddiquenazib2005-dot/JARVIS-.git
git branch -M main
git push -u origin main
```

If GitHub says the remote already has different code and you want to replace it:

```bash
git push -u origin main --force
```

## Automatic APK build

This project includes `.github/workflows/android-debug-apk.yml`.
After pushing to GitHub:

1. Open the GitHub repository.
2. Go to **Actions**.
3. Open **Build AURIX Debug APK**.
4. Download the artifact named **AURIX-debug-apk**.

The workflow uses GitHub's Ubuntu runner, JDK 17, Gradle 8.9, and runs:

```bash
gradle :app:assembleDebug --no-daemon --stacktrace
```

APK output path:

```text
app/build/outputs/apk/debug/*.apk
```

## Local Android Studio build

1. Open the extracted project in Android Studio.
2. Let Gradle sync finish.
3. Run **Build > Build APK(s)** or terminal:

```bash
gradle :app:assembleDebug
```

## Notes

- Package name remains `com.jarvis.ai` for compatibility with the existing codebase.
- Visible app branding is now **AURIX**.
- Existing class names like `JarvisViewModel` remain unchanged to avoid risky mass refactors.
