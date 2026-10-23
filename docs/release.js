/* Verified Android release gate: never direct users to an unverified APK. */
const ReleaseVerification = (() => {
  const base = 'https://github.com/thotsl4yer69/Myceliyum/releases/download/';
  function selectVerifiedRelease(release) {
    if (!release || release.draft || release.prerelease || !Array.isArray(release.assets)) return null;
    const body = typeof release.body === 'string' ? release.body : '';
    const match = body.match(/MYCELIYUMS-RELEASE\s+({[^\n]*})/);
    if (!match) return null;
    let details;
    try { details = JSON.parse(match[1]); } catch (_) { return null; }
    if (!Number.isInteger(details.versionCode) || details.versionCode < 7201 ||
        typeof details.versionName !== 'string' || !details.versionName.trim() ||
        !/^[a-f0-9]{64}$/i.test(details.sha256 || '') ||
        !/^[a-f0-9]{40}$/i.test(details.sourceCommit || '') ||
        typeof details.apkUrl !== 'string' || !details.apkUrl.startsWith(base) ||
        !details.apkUrl.endsWith('.apk')) return null;
    if (/^[a-f0-9]{40}$/i.test(release.target_commitish || '') &&
        release.target_commitish.toLowerCase() !== details.sourceCommit.toLowerCase()) return null;
    const asset = release.assets.find(item => item.browser_download_url === details.apkUrl);
    if (!asset || asset.state !== 'uploaded' || !(asset.size > 0) ||
        String(asset.digest).toLowerCase() !== 'sha256:' + details.sha256.toLowerCase()) return null;
    return details;
  }
  return { selectVerifiedRelease };
})();
if (typeof module !== 'undefined' && module.exports) module.exports = ReleaseVerification;

async function initReleaseDownload() {
  const links = Array.from(document.querySelectorAll('[data-release-download]'));
  const status = document.getElementById('releaseStatus');
  for (const link of links) {
    link.addEventListener('click', event => {
      if (link.getAttribute('aria-disabled') === 'true') event.preventDefault();
    });
  }
  try {
    const response = await fetch('https://api.github.com/repos/thotsl4yer69/Myceliyum/releases/tags/latest', {
      headers: { Accept: 'application/vnd.github+json' }, cache: 'no-store'
    });
    if (!response.ok) throw new Error('Release unavailable');
    const release = ReleaseVerification.selectVerifiedRelease(await response.json());
    if (!release) throw new Error('Verified release not available');
    for (const link of links) {
      link.href = release.apkUrl;
      link.setAttribute('aria-disabled', 'false');
      link.textContent = 'Download Android ' + release.versionName;
    }
    if (status) status.textContent = 'Verified Android ' + release.versionName +
      ' (build ' + release.versionCode + '). Back up your field records before updating.';
  } catch (_) {
    for (const link of links) {
      link.setAttribute('aria-disabled', 'true');
      link.removeAttribute('href');
      link.textContent = 'Verified Android build pending';
    }
    if (status) status.textContent = 'No verified Android download is currently available. Keep your installed app and field records while release checks finish.';
  }
}
