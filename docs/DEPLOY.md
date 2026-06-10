# Myceliyum — website + APK deployment

How the site and the Android APK actually ship. Both are automatic on every
push to `main` — no manual steps.

```
docs/
  index.html     ← the site (served by Cloudflare Workers)
  styles.css
  app.js
  DEPLOY.md      ← this file
wrangler.jsonc   ← Cloudflare Workers config (serves docs/ as static assets)
.github/workflows/
  android-ci.yml      ← tests + debug APK on every PR/push; on main it also
                         refreshes the rolling "latest" GitHub release
  android-release.yml ← manual, optionally release-signed APK build
```

## Website — Cloudflare Workers (automatic)

The repo is connected to Cloudflare Workers Builds. Every push to `main`
deploys `docs/` as a static site per `wrangler.jsonc` (`assets.directory:
"docs"`). The custom domain (`myceliyums.xyz`) is managed in the Cloudflare
dashboard under the `myceliyum` Worker.

To preview or deploy by hand:

```bash
npx wrangler dev      # local preview
npx wrangler deploy   # manual production deploy
```

## APK — rolling "latest" release (automatic)

Every push to `main`, the `publish-latest` job in `android-ci.yml` uploads the
freshly built debug APK to the rolling **`latest`** GitHub release. The site's
"Download APK" button points at `…/releases/latest`, so it always serves the
newest build.

`versionCode` is set from the CI run number (`BUILD_NUMBER`), so each published
build is uniquely versioned; `versionName` tracks milestones (currently 7.0).

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

The `android-release.yml` workflow (Actions tab → run manually) produces a
release APK. To get a **release-signed** build, set these repository secrets
(Settings → Secrets and variables → Actions); without them it falls back to a
debug-signed APK that still installs for testing:

- `RELEASE_KEYSTORE_BASE64` — base64 of your `.jks` keystore
- `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`, `RELEASE_KEY_ALIAS`
