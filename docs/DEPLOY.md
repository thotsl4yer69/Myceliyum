# Myceliyum — website deployment (Cloudflare Pages)

The **Myceliyum** marketing site is a plain static site. It deploys via
**Cloudflare Pages** connected to this GitHub repo — every push to `main`
auto-deploys, and Cloudflare handles DNS + SSL for `myceliyums.xyz`.

```
docs/
  index.html     ← the site (Cloudflare Pages "build output directory")
  styles.css
  app.js
  DEPLOY.md      ← this file
```

> No build step, no framework, no CNAME file — Cloudflare Pages manages the
> custom domain itself.

---

## 1 · Push the site to the repo

From a local clone of `thotsl4yer69/Myceliyum`:

```bash
git add docs/
git commit -m "Myceliyum marketing site"
git push origin main
```

## 2 · Create the Cloudflare Pages project (one time)

Cloudflare dashboard → **Workers & Pages → Create → Pages → Connect to Git** →
authorize and select **`thotsl4yer69/Myceliyum`**.

**Build settings:**

| Setting | Value |
|---|---|
| Framework preset | None |
| Build command | *(empty)* |
| Build output directory | `docs` |
| Root directory | `/` (default) |

**Save and Deploy.** A `*.pages.dev` URL goes live in ~30s.

## 3 · Attach the domain

Pages project → **Custom domains → Set up a custom domain** → `myceliyums.xyz`
(and optionally `www.myceliyums.xyz`). Because the domain is already in this
Cloudflare account, the DNS records + SSL cert are created automatically.

> If the domain isn't "Active" in Cloudflare yet, point the registrar's
> **nameservers** at the two Cloudflare nameservers shown on the domain's
> Overview page. That nameserver change is the *only* thing done at the
> registrar — all DNS records live in Cloudflare.

## 4 · Done — automatic from here

Every push to `main` redeploys the site. `https://myceliyums.xyz` is live.

---

## Notes

- **Do not** also enable GitHub Pages with this domain — the two will fight
  over it. If it was turned on earlier: repo **Settings → Pages → Source: None**.
- Map tiles, fonts and species photos load from public CDNs, so the live page
  needs internet — inherent to the interactive map.
- A self-contained `Myceliyum (standalone).html` (everything inlined) exists in
  the project root for offline sharing; it is not part of the web deploy.

---

## The Android app & Earth Engine backend (separate from this site)

The website does **not** host or affect the app. For reference:

- The APK builds from the repo root: `./gradlew assembleDebug`
  → `app/build/outputs/apk/debug/app-debug.apk`
- The Earth Engine environmental backend runs on **Google Cloud Run**
  (`myceliyum-env`, region `australia-southeast1`) and is wired into the app
  build — it is independent of the marketing site and the domain.
