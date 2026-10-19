/* Fail closed until the release metadata and uploaded APK agree. */
const ReleaseVerification = (() => {
  const PREFIX = 'https://github.com/thotsl4yer69/Myceliyum/releases/download/';
  function selectVerifiedRelease(release) {
    if (!release || release.draft || release.prerelease) return null;
    const match = (release.body || '').match(/<!-- MYCELIYUMS-RELEASE ([\s\S]*?) -->/);
    if (!match) return null;
    let manifest;
    try { manifest = JSON.parse(match[1]); } catch (_) { return null; }
    if (!Number.isInteger(manifest.versionCode) || manifest.versionCode < 7201 ||
        typeof manifest.versionName !== 'string' || !manifest.versionName ||
        !/^[a-f0-9]{64}$/i.test(manifest.sha256 || '') ||
        !/^[a-f0-9]{40}$/i.test(manifest.sourceCommit || '') ||
        typeof manifest.apkUrl !== 'string' || !manifest.apkUrl.startsWith(PREFIX) ||
        !manifest.apkUrl.endsWith('.apk')) return null;
    if (/^[a-f0-9]{40}$/i.test(release.target_commitish || '') &&
        release.target_commitish.toLowerCase() !== manifest.sourceCommit.toLowerCase()) return null;
    const asset = (release.assets || []).find(a => a.browser_download_url === manifest.apkUrl);
    if (!asset || asset.state !== 'uploaded' || !(asset.size > 0) ||
        String(asset.digest).toLowerCase() !== 'sha256:' + manifest.sha256.toLowerCase()) return null;
    return manifest;
  }
  return { selectVerifiedRelease };
})();
if (typeof module !== 'undefined' && module.exports) module.exports = ReleaseVerification;

async function initReleaseDownload() {
  const links = [...document.querySelectorAll('[data-release-download]')];
  const status = document.getElementById('releaseStatus');
  links.forEach(link => link.addEventListener('click', event => {
    if (link.getAttribute('aria-disabled') === 'true') event.preventDefault();
  }));
  try {
    const response = await fetch('https://api.github.com/repos/thotsl4yer69/Myceliyum/releases/tags/latest', {
      headers: { Accept: 'application/vnd.github+json' }, cache: 'no-store'
    });
    if (!response.ok) throw new Error('Release unavailable');
    const release = ReleaseVerification.selectVerifiedRelease(await response.json());
    if (!release) throw new Error('Release verification pending');
    links.forEach(link => {
      link.href = release.apkUrl;
      link.setAttribute('aria-disabled', 'false');
      link.textContent = 'Download Android ' + release.versionName;
    });
    if (status) status.textContent = 'Verified release metadata: ' + release.versionName + ', build ' + release.versionCode + ', source ' + release.sourceCommit.slice(0, 7) + '. Back up records before updating. The app also verifies APK integrity and signing compatibility.';
  } catch (_) {
    links.forEach(link => { link.setAttribute('aria-disabled', 'true'); link.textContent = 'Verified build pending'; });
    if (status) status.textContent = 'A verified download is not available. Keep your existing installation and records; check release notes for progress.';
  }
}
