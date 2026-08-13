# Security Policy — Myceliyum

**Reviewed:** 2026-08-13

Myceliyum is a public Android application prototype. This document records the security boundaries that matter when building or distributing it.

## Never commit credentials or signing material

Do not commit:

- API keys or bearer tokens;
- `.env` / `local.properties` containing real values;
- Android release/debug keystores;
- base64-encoded keystores;
- private keys or certificates containing private key material;
- service-account JSON files;
- backend credentials.

Use local secret storage and GitHub Actions Secrets for CI. The repository `.gitignore` is a guardrail, not a substitute for reviewing staged files before commit.

## 2026-08-13 credential rotation required

During repository cleanup, two items were removed from the public tree:

1. a Google API key that had been hard-coded as an Android build fallback;
2. a base64-encoded Android debug signing keystore used by rolling development APKs.

Because both existed in public Git history, **removing them from the current branch does not make them secret again**.

Required operational response:

- revoke/rotate the exposed Google API key in Google Cloud;
- do not reuse the removed debug signing identity for future trusted distribution;
- generate a new signing identity for rolling development builds if that channel is retained;
- use a private release keystore for real release distribution;
- store new keystore material only outside the repository / in Actions Secrets;
- expect APKs signed with the old debug identity to be incompatible with APKs signed by a new identity unless the old application is uninstalled or an appropriate signed migration path exists.

Do not publish old key material, fingerprints or encoded keystores in issues, documentation or release notes.

## Android client secrets are not secrets

Values compiled into `BuildConfig`, resources, assets, native libraries or other APK content must be treated as extractable by a user who has the APK.

This includes:

- Google/API keys;
- static backend bearer tokens;
- base URLs;
- feature/configuration values.

Provider API keys used from an Android application should use every restriction the provider supports (API scoping, application/package/signing restrictions where applicable, quotas and billing caps). Do not use an extractable client value as the only control protecting a sensitive or billable backend.

## Environmental-layers backend

The optional Cloud Run / Earth Engine backend historically accepted a static `X-Api-Token`. That token can deter casual unauthorised calls for a private build, but **it is not strong authentication when the same token is distributed inside an APK**.

Public CI/release workflows therefore do not inject `BACKEND_TOKEN` or the private backend configuration into distributed APKs. Public builds use the application's free/keyless fallback path unless a client-safe authentication design is introduced.

A production backend should instead use controls appropriate to its exposure, such as:

- authenticated users and short-lived server-issued credentials;
- app/device attestation where appropriate;
- rate limits and abuse detection;
- strict per-user/per-origin quotas;
- budget/billing caps;
- server-side authorisation and audit logging.

No single mechanism should be described as sufficient unless it has been tested against the intended threat model.

## Signing in CI

### Rolling development builds

`.github/workflows/android-ci.yml` can restore a stable private debug/development key from GitHub Actions Secrets.

Required before a rolling `main` APK can be published:

- `DEBUG_KEYSTORE_BASE64`

Optional when the key does not use the standard Android debug credentials:

- `DEBUG_KEYSTORE_PASSWORD`
- `DEBUG_KEY_ALIAS`
- `DEBUG_KEY_PASSWORD`

If the stable key secret is absent, PR/test builds may use the runner-local debug identity, but the workflow deliberately refuses to publish a rolling `main` build.

### Release builds

`.github/workflows/android-release.yml` fails closed unless these secrets exist:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_PASSWORD`
- `RELEASE_KEY_ALIAS`

Release signing material should be backed up securely outside GitHub as well. Losing the private release key can break the ability to update an existing distribution identity.

## Local development

`build.ps1` never restores a signing key from a repository file. For a stable local development identity, place a private `debug.keystore` in the project root (it is ignored) or provide `DEBUG_KEYSTORE_BASE64` through the local environment.

For ordinary local testing, the normal machine-local Android debug identity is sufficient.

## Logging and network data

Avoid logging credentials, authorization headers, precise private user locations or sensitive local records. Debug HTTP body logging should be reviewed before release builds and should not expose secrets or private user content.

## Reporting

If you discover a credential, signing key, private user record or other sensitive material committed to this repository:

1. treat the value as compromised;
2. revoke/rotate it before relying on it again;
3. remove it from the current tree;
4. assess whether Git history needs rewriting;
5. invalidate caches/artifacts/releases containing it where feasible;
6. document the incident without repeating the secret itself.

History rewriting reduces accidental discovery but does not revoke a credential that has already been public.
