# Myceliyum — website + APK deployment

This ships the **Myceliyum** marketing site plus a one-shot CI workflow that
builds the Android APK **and** deploys the site on every push to `main`.

```
docs/
  index.html     ← the site (served at the repo's Pages URL)
  styles.css
  app.js
  DEPLOY.md      ← this file
.github/workflows/
  deploy.yml     ← builds APK → "latest" release + deploys docs/ to Pages
```

> Note: the site was designed & packaged here, but pushing to your remote and
> compiling the APK happen on GitHub. Everything below is copy-paste ready.

---

## 1 · Add these files to the repo & push

From a local clone of `thotsl4yer69/Myceliyum`, drop in the `docs/` folder and
the `.github/workflows/deploy.yml` file, then:

```bash
git add docs/ .github/workflows/deploy.yml
git commit -m "Add marketing site + auto deploy (Pages) & APK release workflow"
git push origin main
```

## 2 · Turn on GitHub Pages (one time)

Repo **Settings → Pages → Build and deployment → Source: GitHub Actions**.

(That's it — no branch/folder to pick. The workflow publishes the site itself.)

## 3 · Done — it's automatic from here

Every push to `main` now:

1. **Builds the debug APK** and publishes it to a rolling **`latest`** release.
   The site's "Download latest APK" button points at the stable asset URL
   `…/releases/download/latest/myceliyum-latest.apk`, so it always serves the
   freshest build with no further edits.
2. **Deploys `docs/`** to GitHub Pages at:

   ```
   https://thotsl4yer69.github.io/Myceliyum/
   ```

You can also trigger the whole thing by hand from the **Actions** tab
(**Deploy site & APK → Run workflow**).

> The map tiles, fonts, and species photos load from public CDNs, so the live
> page needs internet — that's inherent to the interactive map.

---

## Building the APK locally (optional)

The app builds from the repo root with the bundled Gradle wrapper — no setup
beyond Android Studio or the SDK + JDK 17.

```bash
./gradlew assembleDebug
#  → app/build/outputs/apk/debug/app-debug.apk

# install on a connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release signing (optional)

The bundled `android-release.yml` workflow produces a release APK. To get a
**release-signed** build, set these repository secrets (Settings → Secrets and
variables → Actions); without them it falls back to a debug-signed APK that
still installs for testing:

- `RELEASE_KEYSTORE_BASE64` — base64 of your `.jks` keystore
- `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, `RELEASE_KEY_ALIAS`
