const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {selectVerifiedRelease} = require('../docs/release.js');
const {buildCatalogue} = require('../scripts/sync_catalogue.cjs');
const records = JSON.parse(fs.readFileSync('app/src/main/assets/species.json', 'utf8'));
const safety = fs.readFileSync('app/src/main/java/com/example/util/FungiSafety.kt', 'utf8');
const manifest = {versionCode:7201, versionName:'7.2', sha256:'a'.repeat(64), sourceCommit:'b'.repeat(40), apkUrl:'https://github.com/thotsl4yer69/Myceliyum/releases/download/latest/app-debug.apk'};
function release(overrides = {}) {
  return {body:'<!-- MYCELIYUMS-RELEASE ' + JSON.stringify({...manifest, ...overrides}) + ' -->', assets:[{browser_download_url:manifest.apkUrl, state:'uploaded', size:1024, digest:'sha256:' + manifest.sha256}], target_commitish:manifest.sourceCommit};
}
test('legacy releases do not enable a download', () => {
  assert.equal(selectVerifiedRelease({body:'Old release', assets:[]}), null);
  assert.equal(selectVerifiedRelease(release({versionCode:66})), null);
});
test('a matching manifest and uploaded APK enable the release', () => {
  assert.deepEqual(selectVerifiedRelease(release()), manifest);
});
test('hash, source and asset mismatches remain blocked', () => {
  assert.equal(selectVerifiedRelease(release({sha256:'c'.repeat(64)})), null);
  assert.equal(selectVerifiedRelease(release({sourceCommit:'c'.repeat(40)})), null);
  assert.equal(selectVerifiedRelease(release({apkUrl:'https://example.com/untrusted.apk'})), null);
  const draft = release(); draft.draft = true;
  assert.equal(selectVerifiedRelease(draft), null);
});
test('website catalogue matches Android identities and seasonal windows', () => {
  const web = buildCatalogue(records, safety);
  assert.equal(web.length, records.length);
  assert.deepEqual(web.map(s => s.id), records.map(s => s.id));
  assert.deepEqual(web.map(s => s.season), records.map(s => [s.seasonStart, s.seasonEnd]));
  assert.equal(web.some(s => s.statusLabel === 'Edible'), false);
});
function loadApp(document) {
  const context = {document, window:{}, module:{exports:{}}};
  vm.runInNewContext(fs.readFileSync('docs/catalogue.js', 'utf8') + '\n' + fs.readFileSync('docs/app.js', 'utf8') + '\nmodule.exports = {seasonBar, initFilter};', context);
  return context.module.exports;
}
test('season display handles the December to May window', () => {
  const app = loadApp({readyState:'loading', addEventListener(){}});
  assert.equal((app.seasonBar(12, 5).match(/class="on"/g) || []).length, 6);
  assert.equal((app.seasonBar(4, 6).match(/class="on"/g) || []).length, 3);
});
test('catalogue filters update both visible cards and pressed state', () => {
  function chip(filter, active) {
    return {dataset:{filter}, attrs:{}, active,
      classList:{contains:()=>active, remove(){}, add(){}},
      setAttribute(k,v){this.attrs[k]=v;},
      addEventListener(_, fn){this.click=fn;}};
  }
  const chips=[chip('all',true),chip('deadly',false)];
  const cards=[{dataset:{tags:'deadly'},style:{}},{dataset:{tags:'reference'},style:{}}];
  const app = loadApp({readyState:'loading', addEventListener(){}, querySelectorAll:s=>s==='.chip'?chips:cards});
  app.initFilter(); chips[1].click();
  assert.equal(cards[0].style.display,'flex'); assert.equal(cards[1].style.display,'none');
  assert.equal(chips[0].attrs['aria-pressed'],'false'); assert.equal(chips[1].attrs['aria-pressed'],'true');
});
