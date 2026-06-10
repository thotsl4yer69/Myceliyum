/* ============================================================
   MYCELIYUMS — interactivity
   ============================================================ */

/* Field catalogue — 28 taxa. Photos resolve live from Wikipedia/Wikimedia
   Commons (CC) per species and cache locally; plates shown until resolved. */
const SPECIES = [
  /* ---- DEADLY ---- */
  {
    sci: "Amanita phalloides", com: "Death Cap", genus: "Amanita", family: "Amanitaceae",
    habitat: "Oak & Beech Woodland", season: [2, 6], spore: "White", sporeHex: "#f3f1e9",
    status: "deadly", statusLabel: "Deadly", tags: ["deadly", "toxic"], capHex: "#9aa05a",
    marks: "Greenish-olive cap, pure white gills, skirt ring, large sack-like volva at the base.",
    look: "Mistaken for straw & paddy-straw mushrooms — causes most fatal poisonings worldwide. Amatoxins survive cooking."
  },
  {
    sci: "Amanita virosa", com: "Destroying Angel", genus: "Amanita", family: "Amanitaceae",
    habitat: "Mixed & Birch Forest", season: [1, 5], spore: "White", sporeHex: "#f3f1e9",
    status: "deadly", statusLabel: "Deadly", tags: ["deadly", "toxic"], capHex: "#e8e6da",
    marks: "Pure white throughout, shaggy stem, flimsy skirt, deep sack volva.",
    look: "Young buttons pass for edible puffballs — always slice buttons top-to-bottom to check for a hidden outline."
  },
  {
    sci: "Galerina marginata", com: "Funeral Bell", genus: "Galerina", family: "Hymenogastraceae",
    habitat: "Rotting Conifer Wood", season: [3, 7], spore: "Rusty brown", sporeHex: "#8a5a2e",
    status: "deadly", statusLabel: "Deadly", tags: ["deadly", "toxic"], capHex: "#b07a3a",
    marks: "Small honey-brown hygrophanous cap, ring zone on stem, clusters on dead logs.",
    look: "Deadly twin of honey fungus and wood-loving Psilocybe — same logs, same season. Rusty print = walk away."
  },
  {
    sci: "Cortinarius rubellus", com: "Deadly Webcap", genus: "Cortinarius", family: "Cortinariaceae",
    habitat: "Mossy Conifer Forest", season: [2, 6], spore: "Rust", sporeHex: "#a05a28",
    status: "deadly", statusLabel: "Deadly", tags: ["deadly", "toxic"], capHex: "#c06a2e",
    marks: "Tawny-orange conical cap, cobweb veil remnants, yellow zig-zag bands on stem.",
    look: "Picked in error for chanterelles — orellanine destroys kidneys weeks after the meal."
  },
  {
    sci: "Gyromitra esculenta", com: "False Morel", genus: "Gyromitra", family: "Discinaceae",
    habitat: "Sandy Pine Forest", season: [8, 11], spore: "Cream", sporeHex: "#e8dcc0",
    status: "deadly", statusLabel: "Deadly", tags: ["deadly", "toxic"], capHex: "#7a4a32",
    marks: "Brain-like reddish-brown folded cap; interior chambered, not hollow.",
    look: "True morels are honeycomb-PITTED and fully hollow. Gyromitrin is lethal raw and risky cooked."
  },

  /* ---- TOXIC ---- */
  {
    sci: "Amanita muscaria", com: "Fly Agaric", genus: "Amanita", family: "Amanitaceae",
    habitat: "Conifer & Birch Plantation", season: [4, 6], spore: "White", sporeHex: "#f3f1e9",
    status: "toxic", statusLabel: "Toxic", tags: ["toxic", "invasive"], capHex: "#c23b2e",
    img: "https://commons.wikimedia.org/wiki/Special:FilePath/Fliegenpilz_fly_agaric_Amanita_muscaria.JPG?width=600", rid: "spAmanita",
    marks: "Scarlet cap with white warts, white gills, bulbous base with scaly rings.",
    look: "Rain-faded caps resemble edible blushers — blushers flush pink when cut. Invasive in the southern hemisphere."
  },
  {
    sci: "Amanita pantherina", com: "Panther Cap", genus: "Amanita", family: "Amanitaceae",
    habitat: "Beech & Conifer Forest", season: [3, 6], spore: "White", sporeHex: "#f3f1e9",
    status: "toxic", statusLabel: "Toxic", tags: ["toxic"], capHex: "#8a6a4a",
    marks: "Bronze cap with PURE-white warts, collared bulb at stem base.",
    look: "Confused with the edible blusher — the blusher bruises pink, panther stays white. Stronger toxins than fly agaric."
  },
  {
    sci: "Chlorophyllum molybdites", com: "Green-spored Parasol", genus: "Chlorophyllum", family: "Agaricaceae",
    habitat: "Lawns & Grass Rings", season: [1, 4], spore: "Green", sporeHex: "#7a8c5a",
    status: "toxic", statusLabel: "Toxic", tags: ["toxic"], capHex: "#d9d0bb",
    marks: "Large white parasol in grass; gills age grey-green; double-edged ring.",
    look: "The #1 cause of mushroom poisoning on lawns — split from true parasols by its GREEN spore print."
  },
  {
    sci: "Agaricus xanthodermus", com: "Yellow Stainer", genus: "Agaricus", family: "Agaricaceae",
    habitat: "Parks, Gardens, Hedgerows", season: [2, 6], spore: "Chocolate brown", sporeHex: "#4a3328",
    status: "toxic", statusLabel: "Toxic", tags: ["toxic"], capHex: "#ddd8c8",
    marks: "Stains chrome-yellow instantly at the stem base when cut; ink / phenol smell.",
    look: "Passes for the field mushroom in every other way — the yellow flash and chemical smell give it away."
  },
  {
    sci: "Rubroboletus satanas", com: "Satan's Bolete", genus: "Rubroboletus", family: "Boletaceae",
    habitat: "Chalky Oak Woodland", season: [1, 4], spore: "Olive-brown", sporeHex: "#5d5230",
    status: "toxic", statusLabel: "Toxic", tags: ["toxic"], capHex: "#cfc8b8",
    marks: "Pale chalky cap, blood-red pores, swollen red-netted stem; flesh blues when cut.",
    look: "Treat every red-pored, blue-bruising bolete as suspect until keyed out properly."
  },
  {
    sci: "Gymnopilus junonius", com: "Spectacular Rustgill", genus: "Gymnopilus", family: "Hymenogastraceae",
    habitat: "Eucalypt Forest / Parks", season: [3, 7], spore: "Rusty orange", sporeHex: "#b5662e",
    status: "toxic", statusLabel: "Inedible", tags: ["toxic"], capHex: "#d98a2e",
    img: "https://commons.wikimedia.org/wiki/Special:FilePath/Gymnopilus_spectabilis_42904.jpg?width=600", rid: "spGymnopilus",
    marks: "Huge golden clusters at trunk bases; rusty spore dust collects on the ring.",
    look: "Resembles honey fungus at a glance — intensely bitter, rusty-orange print."
  },
  {
    sci: "Hypholoma fasciculare", com: "Sulphur Tuft", genus: "Hypholoma", family: "Strophariaceae",
    habitat: "Stumps & Dead Wood", season: [3, 8], spore: "Purple-brown", sporeHex: "#3a2b3a",
    status: "toxic", statusLabel: "Toxic", tags: ["toxic"], capHex: "#e0c04a",
    marks: "Dense sulphur-yellow tufts, greenish gills, slender curved stems.",
    look: "Shares stumps with honey fungus and edible brick caps — bitter taste and green gill tint mark it out."
  },
  {
    sci: "Omphalotus nidiformis", com: "Ghost Fungus", genus: "Omphalotus", family: "Omphalotaceae",
    habitat: "Eucalypt Woodland", season: [3, 7], spore: "White", sporeHex: "#f3f1e9",
    status: "bio", statusLabel: "Bioluminescent", tags: ["bio", "toxic"], capHex: "#d9d3c3",
    img: "https://commons.wikimedia.org/wiki/Special:FilePath/Omphalotus_nidiformis%2C_Ghost_Fungus%2C_Australia.jpg?width=600", rid: "spOmphalotus",
    marks: "Fan-shaped cream brackets at eucalypt bases; glows soft green in full darkness.",
    look: "Harvested in error as oyster mushroom — if it glows at night, drop it. Severe GI toxin."
  },
  {
    sci: "Omphalotus olearius", com: "Jack-o'-Lantern", genus: "Omphalotus", family: "Omphalotaceae",
    habitat: "Olive & Oak Stumps", season: [1, 5], spore: "Cream-yellow", sporeHex: "#e8d9a8",
    status: "toxic", statusLabel: "Toxic", tags: ["toxic", "bio"], capHex: "#e07a2a",
    marks: "Bright orange clusters; TRUE blade gills running down the stem; faint night glow.",
    look: "The classic chanterelle impostor — chanterelles have blunt forking ridges, never thin blade gills."
  },

  /* ---- EDIBLE (with field cautions) ---- */
  {
    sci: "Boletus edulis", com: "Porcini · Cep", genus: "Boletus", family: "Boletaceae",
    habitat: "Oak, Beech & Pine Forest", season: [2, 5], spore: "Olive-brown", sporeHex: "#5d5230",
    status: "edible", statusLabel: "Edible", tags: ["edible"], capHex: "#8a5a32",
    marks: "Bun-brown cap, pores white aging yellow-olive, fine white net on a swollen stem.",
    look: "Bitter bolete shares the build — pinkish pores and a DARK stem net; one ruins the whole pan."
  },
  {
    sci: "Cantharellus cibarius", com: "Chanterelle", genus: "Cantharellus", family: "Cantharellaceae",
    habitat: "Mossy Hardwood & Conifer", season: [2, 6], spore: "Pale yellow", sporeHex: "#e8d9a8",
    status: "edible", statusLabel: "Edible", tags: ["edible"], capHex: "#e8b53a",
    marks: "Egg-yolk colour throughout, blunt forking ridges (not gills), apricot smell.",
    look: "Check against jack-o'-lantern and false chanterelle — both carry true thin gills."
  },
  {
    sci: "Macrolepiota procera", com: "Parasol", genus: "Macrolepiota", family: "Agaricaceae",
    habitat: "Pasture Edge & Open Woods", season: [2, 5], spore: "White", sporeHex: "#f3f1e9",
    status: "edible", statusLabel: "Edible", tags: ["edible"], capHex: "#b09a72",
    marks: "Snakeskin-patterned stem, movable double ring, drumstick-shaped buttons.",
    look: "In green-spored regions ALWAYS print lawn finds — Chlorophyllum molybdites mimics it perfectly."
  },
  {
    sci: "Pleurotus ostreatus", com: "Oyster Mushroom", genus: "Pleurotus", family: "Pleurotaceae",
    habitat: "Dead Hardwood, Shelved", season: [4, 9], spore: "White-lilac", sporeHex: "#d9d3e3",
    status: "edible", statusLabel: "Edible", tags: ["edible"], capHex: "#9a9282",
    marks: "Shell-shaped caps in shelves, decurrent gills, stem rudimentary or absent.",
    look: "In Australia & NZ rule out ghost fungus first — same shelf habit, toxic, glows at night."
  },
  {
    sci: "Morchella esculenta", com: "Common Morel", genus: "Morchella", family: "Morchellaceae",
    habitat: "Disturbed Ground & Orchards", season: [8, 11], spore: "Cream", sporeHex: "#e8dcc0",
    status: "edible", statusLabel: "Edible", tags: ["edible"], capHex: "#b0a070",
    marks: "Honeycomb-pitted cap fused to the stem; completely hollow when split.",
    look: "False morel is wrinkled-brainy, chambered inside, and deadly. Always cook morels through."
  },
  {
    sci: "Lactarius deliciosus", com: "Saffron Milk Cap", genus: "Lactarius", family: "Russulaceae",
    habitat: "Pine Plantations", season: [2, 5], spore: "Cream", sporeHex: "#e8dcc0",
    status: "edible", statusLabel: "Edible", tags: ["edible"], capHex: "#d9822e",
    marks: "Carrot-orange latex when cut, zonate cap, bruises green with handling.",
    look: "Woolly milk cap weeps WHITE peppery latex — zonate too, but shaggy-rimmed."
  },
  {
    sci: "Coprinus comatus", com: "Shaggy Ink Cap", genus: "Coprinus", family: "Agaricaceae",
    habitat: "Roadsides & Disturbed Soil", season: [2, 6], spore: "Black", sporeHex: "#1a1a1a",
    status: "edible", statusLabel: "Edible", tags: ["edible"], capHex: "#e3e0d3",
    marks: "White shaggy torpedo caps that dissolve into black ink within a day.",
    look: "Common ink cap (smooth grey cap) reacts violently with alcohol — keep them separate."
  },
  {
    sci: "Suillus luteus", com: "Slippery Jack", genus: "Suillus", family: "Suillaceae",
    habitat: "Pine Plantations", season: [2, 6], spore: "Brown", sporeHex: "#6e4a2a",
    status: "edible", statusLabel: "Edible", tags: ["edible"], capHex: "#7a4a28",
    marks: "Slimy chestnut cap, purple-tinged ring, fine dotted yellow pores.",
    look: "Few risky twins under pine — peel the slime skin; it upsets some stomachs."
  },
  {
    sci: "Hydnum repandum", com: "Hedgehog Mushroom", genus: "Hydnum", family: "Hydnaceae",
    habitat: "Mossy Mixed Forest", season: [3, 7], spore: "White", sporeHex: "#f3f1e9",
    status: "edible", statusLabel: "Edible", tags: ["edible"], capHex: "#e0c090",
    marks: "Cream cap with soft SPINES underneath instead of gills or pores.",
    look: "No dangerous species carries spines — the safest beginner identification there is."
  },
  {
    sci: "Armillaria mellea", com: "Honey Fungus", genus: "Armillaria", family: "Physalacriaceae",
    habitat: "Stumps & Living Roots", season: [3, 6], spore: "White", sporeHex: "#f3f1e9",
    status: "edible", statusLabel: "Edible · cook well", tags: ["edible", "bio"], capHex: "#b08a4a",
    marks: "Honey-tan scaly caps in bursts, white ring, black bootlace rhizomorphs under bark; mycelium glows faintly.",
    look: "Deadly Galerina fruits on the same logs — Galerina prints rusty brown, Armillaria white."
  },

  /* ---- PSYCHOACTIVE ---- */
  {
    sci: "Psilocybe subaeruginosa", com: "Gold-top", genus: "Psilocybe", family: "Hymenogastraceae",
    habitat: "Eucalypt / Urban Mulch", season: [4, 8], spore: "Purple-brown", sporeHex: "#3a2b3a",
    status: "psy", statusLabel: "Psychoactive", tags: ["psy"], capHex: "#c08a4a",
    img: "https://inaturalist-open-data.s3.amazonaws.com/photos/518685963/medium.jpg", rid: "spPsilocybe",
    marks: "Caramel hygrophanous cap, intense blue-green bruising, pale fibrous stem.",
    look: "Galerina marginata fruits in the same mulch and is lethal — print every wood-lover."
  },
  {
    sci: "Psilocybe semilanceata", com: "Liberty Cap", genus: "Psilocybe", family: "Hymenogastraceae",
    habitat: "Sheep Pasture & Rank Grass", season: [4, 7], spore: "Purple-brown", sporeHex: "#3a2b3a",
    status: "psy", statusLabel: "Psychoactive", tags: ["psy"], capHex: "#b0985a",
    marks: "Nipple-topped conical cap, striate when damp, slender wavy stem.",
    look: "Small brown grassland species blur together — deadly Cortinarius and Galerina print rust, not purple-brown."
  },

  /* ---- BIOLUMINESCENT / INVASIVE ---- */
  {
    sci: "Mycena chlorophos", com: "Night-light Bonnet", genus: "Mycena", family: "Mycenaceae",
    habitat: "Subtropical Woody Litter", season: [1, 4], spore: "White", sporeHex: "#f3f1e9",
    status: "bio", statusLabel: "Bioluminescent", tags: ["bio"], capHex: "#c8d9c0",
    marks: "Tiny pale parasols; rim-lit green glow, strongest the first night after rain.",
    look: "Too small to eat, unmistakable to log — a flagship sighting for night surveys."
  },
  {
    sci: "Favolaschia calocera", com: "Orange Pore Fungus", genus: "Favolaschia", family: "Mycenaceae",
    habitat: "Rainforest / Damp Gully", season: [3, 8], spore: "White", sporeHex: "#f3f1e9",
    status: "invasive", statusLabel: "Invasive", tags: ["invasive"], capHex: "#e2752f",
    img: "https://commons.wikimedia.org/wiki/Special:FilePath/Favolaschia_calocera_38204.jpg?width=600", rid: "spFavolaschia",
    marks: "Bright orange ping-pong-bat caps with honeycomb pores on the underside.",
    look: "Nothing dangerous shares the look — log every sighting to help track its spread."
  }
];

const MONTHS = ["J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D"];

function seasonBar(start, end) {
  let cells = "";
  for (let m = 1; m <= 12; m++) {
    const on = m >= start && m <= end ? " on" : "";
    cells += `<span class="${on.trim()}"></span>`;
  }
  return cells;
}

function plateArt(capHex) {
  return `<div class="plate" style="--cap:${capHex}">
    <svg viewBox="0 0 64 64" fill="none" aria-hidden="true">
      <path d="M32 8C18 8 8 17 8 26.5C8 28.4 9.3 29.5 11.4 29.5H52.6C54.7 29.5 56 28.4 56 26.5C56 17 46 8 32 8Z" fill="var(--cap)"/>
      <ellipse cx="21" cy="21" rx="3" ry="2.4" fill="rgba(9,13,11,0.55)"/>
      <ellipse cx="32" cy="17" rx="3.4" ry="2.7" fill="rgba(9,13,11,0.55)"/>
      <ellipse cx="43" cy="21" rx="3" ry="2.4" fill="rgba(9,13,11,0.55)"/>
      <path d="M26 30H38L36 52C35.7 55 33.8 57 32 57C30.2 57 28.3 55 28 52L26 30Z" fill="var(--cap)" opacity="0.72"/>
    </svg>
    <span class="plate-tag">Field plate — illustrative</span>
  </div>`;
}

function speciesCard(s, i) {
  const sbClass = { deadly: "sb-deadly", toxic: "sb-toxic", psy: "sb-psy", bio: "sb-bio", invasive: "sb-invasive", edible: "sb-edible" }[s.status];
  const resolved = (window.__resources && s.rid && window.__resources[s.rid]) || s.img;
  const media = resolved
    ? `<img src="${resolved}" alt="${s.sci}" loading="lazy" onerror="this.closest('.ph').classList.add('is-plate'); this.remove();">`
    : plateArt(s.capHex);
  const specno = 'SPC-' + String(i + 1).padStart(2, '0');
  return `
  <article class="species" data-tags="${s.tags.join(' ')}" data-i="${i}">
    <div class="ph${resolved ? '' : ' is-plate'}" title="Expand specimen photo">
      <span class="status-badge ${sbClass}">${s.statusLabel}</span>
      <span class="specno">${specno}</span>
      ${media}
    </div>
    <div class="body">
      <div>
        <div class="sci">${s.sci}</div>
        <div class="com">${s.com}</div>
      </div>
      <p class="tax">${s.genus} · ${s.family}</p>
      <div class="spec-meta">
        <div class="r"><span class="k">Habitat</span><span class="v">${s.habitat}</span></div>
        <div class="r"><span class="k">Spore Print</span><span class="v"><span class="spore-dot" style="background:${s.sporeHex}"></span>${s.spore}</span></div>
        <div>
          <div class="r"><span class="k">Fruiting Window</span><span class="k">${MONTHS[s.season[0]-1]}–${MONTHS[s.season[1]-1]}</span></div>
          <div class="season">${seasonBar(s.season[0], s.season[1])}</div>
          <div class="season-row">${MONTHS.map(m => `<span>${m}</span>`).join('')}</div>
        </div>
      </div>
      <div class="spec-notes">
        ${s.marks ? `<div class="fm"><b>Field marks</b>${s.marks}</div>` : ''}
        ${s.look ? `<div class="fm warn"><b>Lookalike warning</b>${s.look}</div>` : ''}
      </div>
    </div>
  </article>`;
}

/* ---- live photo resolution — Wikipedia REST summary (CC imagery) ---- */
const IMG_CACHE_KEY = 'myc_taxa_img_v1';

function applySpeciesImage(i, thumb, full) {
  const card = document.querySelector(`.species[data-i="${i}"]`);
  if (!card) return;
  const ph = card.querySelector('.ph');
  if (full) ph.dataset.full = full;
  let img = ph.querySelector('img');
  if (img) { if (!SPECIES[i].img) img.src = thumb; return; }
  img = document.createElement('img');
  img.alt = SPECIES[i].sci;
  img.loading = 'lazy';
  img.onerror = function () { ph.classList.add('is-plate'); img.remove(); };
  img.src = thumb;
  const plate = ph.querySelector('.plate');
  if (plate) plate.remove();
  ph.classList.remove('is-plate');
  ph.appendChild(img);
}

async function resolveImages() {
  let cache = {};
  try { cache = JSON.parse(localStorage.getItem(IMG_CACHE_KEY) || '{}'); } catch (e) {}
  let dirty = false;
  const jobs = SPECIES.map(async (s, i) => {
    const key = s.sci;
    if (cache[key] && cache[key].t) { applySpeciesImage(i, cache[key].t, cache[key].f); return; }
    try {
      const r = await fetch('https://en.wikipedia.org/api/rest_v1/page/summary/' + encodeURIComponent(s.sci.replace(/ /g, '_')));
      if (!r.ok) return;
      const j = await r.json();
      const src = j.thumbnail && j.thumbnail.source;
      if (!src) return;
      const thumb = src.replace(/\/(\d+)px-/, '/640px-');
      const full = src.replace(/\/(\d+)px-/, '/1280px-');
      cache[key] = { t: thumb, f: full };
      dirty = true;
      applySpeciesImage(i, thumb, full);
    } catch (e) { /* offline — plates remain */ }
  });
  await Promise.allSettled(jobs);
  if (dirty) { try { localStorage.setItem(IMG_CACHE_KEY, JSON.stringify(cache)); } catch (e) {} }
}

/* ---- specimen lens — click a photo to expand for field comparison ---- */
function initLens() {
  const grid = document.getElementById('catGrid');
  if (!grid) return;
  const lens = document.createElement('div');
  lens.className = 'lens';
  lens.innerHTML =
    '<div class="lens-card">' +
      '<div class="lens-ph"><img alt=""><span class="lens-no"></span></div>' +
      '<div class="lens-info">' +
        '<div><span class="lens-badge"></span></div>' +
        '<div><div class="lens-sci"></div><div class="lens-com"></div></div>' +
        '<p class="lens-tax"></p>' +
        '<div class="lens-marks"></div>' +
        '<div class="lens-look"></div>' +
        '<div class="lens-hint">PHOTO © WIKIMEDIA COMMONS / INATURALIST CONTRIBUTORS · ESC OR CLICK OUTSIDE TO CLOSE · NEVER EAT ON A PHOTO MATCH ALONE</div>' +
      '</div>' +
      '<button class="lens-close" aria-label="Close">&#10005;</button>' +
    '</div>';
  document.body.appendChild(lens);

  function close() { lens.classList.remove('open'); document.body.style.overflow = ''; }
  lens.addEventListener('click', (e) => { if (e.target === lens) close(); });
  lens.querySelector('.lens-close').addEventListener('click', close);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });

  grid.addEventListener('click', (e) => {
    const ph = e.target.closest('.ph');
    if (!ph || ph.classList.contains('is-plate')) return;
    const card = ph.closest('.species');
    const s = SPECIES[+card.dataset.i];
    const img = ph.querySelector('img');
    if (!s || !img) return;
    const sbClass = { deadly: 'sb-deadly', toxic: 'sb-toxic', psy: 'sb-psy', bio: 'sb-bio', invasive: 'sb-invasive', edible: 'sb-edible' }[s.status];
    lens.querySelector('.lens-ph img').src = ph.dataset.full || img.src;
    lens.querySelector('.lens-no').textContent = 'SPC-' + String(+card.dataset.i + 1).padStart(2, '0') + ' · FIELD COMPARISON VIEW';
    const badge = lens.querySelector('.lens-badge');
    badge.textContent = s.statusLabel;
    badge.className = 'lens-badge status-badge ' + sbClass;
    lens.querySelector('.lens-sci').textContent = s.sci;
    lens.querySelector('.lens-com').textContent = s.com;
    lens.querySelector('.lens-tax').textContent = s.genus + ' · ' + s.family + ' · ' + s.habitat;
    lens.querySelector('.lens-marks').innerHTML = s.marks ? '<b>Field marks</b>' + s.marks : '';
    lens.querySelector('.lens-look').innerHTML = s.look ? '<b>Lookalike warning</b>' + s.look : '';
    lens.classList.add('open');
    document.body.style.overflow = 'hidden';
  });
}

function renderCatalogue() {
  const grid = document.getElementById('catGrid');
  if (grid) grid.innerHTML = SPECIES.map((s, i) => speciesCard(s, i)).join('');
}

function initFilter() {
  const chips = document.querySelectorAll('.chip');
  chips.forEach(chip => {
    chip.addEventListener('click', () => {
      chips.forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      const f = chip.dataset.filter;
      document.querySelectorAll('.species').forEach(card => {
        const show = f === 'all' || card.dataset.tags.split(' ').includes(f);
        card.style.display = show ? 'flex' : 'none';
      });
    });
  });
}

/* hotspot map — sample geography with probability heat, terrain & habitat signals.
   Hotspots are abstract grid sectors; the live app centres on the user's GPS fix. */
function renderHeatmap() {
  const el = document.getElementById('hotmap');
  if (!el || typeof L === 'undefined') return;

  const station = { name: 'Field Grid Station', lat: -37.8425, lng: 145.3445, elev: 505 };
  const spots = [
    { name: 'Sector N-04', lat: -37.8810, lng: 145.3575, p: 0.94, band: 'HIGH FLUSH',
      elev: 480, rain: 62, moist: 88, temp: 12.5, humid: 92, dew: 11.2, wind: 6, dry: 1, conf: 0.91, obs: 18, canopy: 'Old-growth wet forest', sub: 'Mulch & woody debris' },
    { name: 'Sector E-12', lat: -37.8745, lng: 145.3310, p: 0.78, band: 'MODERATE',
      elev: 540, rain: 55, moist: 79, temp: 11.8, humid: 86, dew: 9.8, wind: 9, dry: 2, conf: 0.84, obs: 11, canopy: 'Wet sclerophyll', sub: 'Woodchip beds' },
    { name: 'Sector NE-09', lat: -37.8665, lng: 145.3690, p: 0.71, band: 'MODERATE',
      elev: 510, rain: 51, moist: 76, temp: 12.2, humid: 83, dew: 9.1, wind: 7, dry: 2, conf: 0.80, obs: 9, canopy: 'Mountain ash gully', sub: 'Fallen timber & bark' },
    { name: 'Sector S-07', lat: -37.8355, lng: 145.3625, p: 0.64, band: 'MODERATE',
      elev: 455, rain: 48, moist: 71, temp: 13.1, humid: 80, dew: 8.6, wind: 11, dry: 3, conf: 0.76, obs: 7, canopy: 'Fern gully', sub: 'Decaying logs & litter' },
    { name: 'Sector SW-15', lat: -37.8290, lng: 145.3350, p: 0.55, band: 'LOW',
      elev: 565, rain: 44, moist: 64, temp: 12.7, humid: 74, dew: 7.9, wind: 13, dry: 4, conf: 0.69, obs: 5, canopy: 'Mixed sclerophyll', sub: 'Leaf litter & moss' },
    { name: 'Sector W-03', lat: -37.8180, lng: 145.3600, p: 0.47, band: 'LOW',
      elev: 600, rain: 40, moist: 58, temp: 12.0, humid: 70, dew: 7.1, wind: 15, dry: 5, conf: 0.63, obs: 3, canopy: 'Woodland margin', sub: 'Leaf litter' }
  ];
  const colFor = p => p > 0.66 ? '#FF4D4D' : p > 0.4 ? '#FFC04D' : '#4DDFAC';

  const dark = L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
    maxZoom: 19, subdomains: 'abcd', attribution: '© OpenStreetMap, © CARTO'
  });
  const topo = L.tileLayer('https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png', {
    maxZoom: 17, subdomains: 'abc', attribution: '© OpenTopoMap (CC-BY-SA)'
  });
  const hillshade = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Elevation/World_Hillshade/MapServer/tile/{z}/{y}/{x}', {
    maxZoom: 19, opacity: 0.4, attribution: 'Hillshade © Esri'
  });

  const map = L.map(el, { zoomControl: true, attributionControl: true, scrollWheelZoom: false, layers: [dark, hillshade] })
    .setView([-37.852, 145.347], 13);

  /* ---- probability heat surface ---- */
  const heatPts = [];
  spots.forEach(s => {
    heatPts.push([s.lat, s.lng, s.p]);
    for (let k = 0; k < 12; k++) {
      const a = (k / 12) * Math.PI * 2;
      const d = 0.0016 + (k % 4) * 0.0011;
      const fall = s.p * (1 - d * 230);
      if (fall > 0.08) heatPts.push([s.lat + Math.sin(a) * d, s.lng + Math.cos(a) * d * 1.25, fall]);
    }
  });
  const heat = L.heatLayer(heatPts, {
    radius: 38, blur: 28, maxZoom: 17, max: 1.0, minOpacity: 0.32,
    gradient: { 0.2: '#1F8A5B', 0.45: '#4DDFAC', 0.68: '#FFC04D', 0.86: '#FF4D4D' }
  }).addTo(map);

  /* ---- hotspot pins + likelihood rings ---- */
  const hotspots = L.layerGroup().addTo(map);
  spots.forEach(s => {
    const c = colFor(s.p);
    L.circle([s.lat, s.lng], { radius: 220 + s.p * 700, color: c, weight: 1, opacity: 0.45, fillColor: c, fillOpacity: 0.06 }).addTo(hotspots);
    const icon = L.divIcon({
      className: 'hp-marker',
      html: `<div class="hp-pin" style="--c:${c}"><span class="hp-dot"></span><span class="hp-sco">${s.p.toFixed(2)}</span><span class="hp-nm">${s.name}</span></div>`,
      iconSize: [170, 16], iconAnchor: [8, 8]
    });
    const m = L.marker([s.lat, s.lng], { icon }).addTo(hotspots)
      .bindPopup(`<b>${s.name}</b><br><span class="pp-band" style="color:${c}">${s.band} · P=${s.p.toFixed(2)}</span><br>${s.elev} m · ${s.temp}°C · ${s.moist}% moisture<br>${s.obs} recent observations · ${s.canopy}`);
    m.on('click', () => setDetail(s));
    m.on('mouseover', () => setDetail(s));
  });

  const sIcon = L.divIcon({
    className: 'hp-marker',
    html: `<div class="hp-station"><div class="hp-pulse"></div><span></span></div>`,
    iconSize: [16, 16], iconAnchor: [8, 8]
  });
  L.marker([station.lat, station.lng], { icon: sIcon }).addTo(map)
    .bindPopup(`<b>${station.name}</b><br><span class="pp-band" style="color:#54E0A0">CURRENT FIELD GRID STATION</span><br>Auto-set from your GPS fix`);

  /* ---- layer switcher ---- */
  L.control.layers(
    { 'Dark map': dark, 'Terrain (topo)': topo },
    { 'Probability heat': heat, 'Elevation relief': hillshade, 'Hotspot pins': hotspots },
    { collapsed: true, position: 'topright' }
  ).addTo(map);

  /* ---- coordinate readout ---- */
  const ref = document.getElementById('mapRef');
  if (ref) {
    const base = 'Move over scope for coordinates';
    map.on('mousemove', e => { ref.innerHTML = `LAT <b>${e.latlng.lat.toFixed(4)}</b> \u00B7 LNG <b>${e.latlng.lng.toFixed(4)}</b>`; });
    el.addEventListener('mouseleave', () => { ref.textContent = base; });
  }

  /* ---- habitat / terrain signals panel ---- */
  const detail = document.getElementById('hotDetail');
  function setDetail(s) {
    if (!detail) return;
    const c = colFor(s.p);
    const pin = (icon) => icon;
    detail.innerHTML =
      `<div class="hd-head"><span class="hd-name">${s.name}</span><span class="hd-score" style="color:${c}">P=${s.p.toFixed(2)} · ${s.band}</span></div>` +
      `<div class="hd-grid">` +
        `<div class="hd-cell"><div class="k">${ICON.elev} Elevation</div><div class="v">${s.elev} m ASL</div></div>` +
        `<div class="hd-cell"><div class="k">${ICON.temp} Soil temp</div><div class="v">${s.temp}°C</div></div>` +
        `<div class="hd-cell"><div class="k">${ICON.rain} 7-day rainfall</div><div class="v">${s.rain} mm</div><div class="hd-bar"><i style="width:${Math.min(100, s.rain * 1.4)}%;background:#5fd0e0"></i></div></div>` +
        `<div class="hd-cell"><div class="k">${ICON.moist} Soil moisture</div><div class="v">${s.moist}%</div><div class="hd-bar"><i style="width:${s.moist}%;background:${c}"></i></div></div>` +
        `<div class="hd-cell"><div class="k">${ICON.humid} Humidity</div><div class="v">${s.humid}%</div><div class="hd-bar"><i style="width:${s.humid}%;background:#8BBFA4"></i></div></div>` +
        `<div class="hd-cell"><div class="k">${ICON.humid} Dewpoint</div><div class="v">${s.dew}°C</div></div>` +
        `<div class="hd-cell"><div class="k">${ICON.temp} Days since rain</div><div class="v">${s.dry} <span style="font-size:11px;color:var(--ink-dim)">d</span></div></div>` +
        `<div class="hd-cell"><div class="k">${ICON.obs} Recent sightings</div><div class="v">${s.obs} <span style="font-size:11px;color:var(--ink-dim)">iNat · 30d</span></div></div>` +
        `<div class="hd-cell"><div class="k">${ICON.elev} Model confidence</div><div class="v">${Math.round(s.conf * 100)}%</div><div class="hd-bar"><i style="width:${Math.round(s.conf * 100)}%;background:${c}"></i></div></div>` +
        `<div class="hd-cell wide"><div class="k">${ICON.canopy} Canopy</div><div class="v">${s.canopy}</div></div>` +
        `<div class="hd-cell wide"><div class="k">${ICON.sub} Substrate</div><div class="v">${s.sub}</div></div>` +
      `</div>` +
      `<div class="hd-hint">▸ Representative sample sweep · the live unit centres on your GPS fix. Click a pin or toggle layers (top-right) for terrain &amp; the probability surface.</div>`;
  }
  setDetail(spots[0]);

  /* ---- stats strip ---- */
  const stats = document.getElementById('mapStats');
  if (stats) {
    const peak = Math.max.apply(null, spots.map(s => s.p));
    const totObs = spots.reduce((a, s) => a + s.obs, 0);
    const avgConf = Math.round(spots.reduce((a, s) => a + s.conf, 0) / spots.length * 100);
    stats.innerHTML =
      `<div class="s"><div class="n hot">${Math.round(peak * 100)}%</div><div class="k">Peak likelihood</div></div>` +
      `<div class="s"><div class="n">${spots.length}</div><div class="k">Active hotspots</div></div>` +
      `<div class="s"><div class="n">${totObs}</div><div class="k">Obs ingested · 30d</div></div>` +
      `<div class="s"><div class="n">${avgConf}%</div><div class="k">Model confidence</div></div>` +
      `<div class="s"><div class="n">5</div><div class="k">API endpoints</div></div>` +
      `<div class="s"><div class="n">8<span style="font-size:11px"> km</span></div><div class="k">Sweep radius</div></div>`;
  }

  /* ---- robust sizing — prevents heat/tile misalignment glitch on load & resize ---- */
  const fix = () => map.invalidateSize({ animate: false });
  map.whenReady(fix);
  [60, 200, 500, 900].forEach(t => setTimeout(fix, t));
  if (window.ResizeObserver) { new ResizeObserver(fix).observe(el); }
  window.addEventListener('resize', fix);
  map.on('zoomend moveend', () => heat.redraw());
}

/* small inline icons for the signals panel */
const ICON = {
  elev: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 20h18L14 7l-3 5-2-3-6 11Z"/></svg>',
  rain: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M7 14a4 4 0 0 1 .8-7.9A6 6 0 0 1 19 8a3.5 3.5 0 0 1-.5 7H7Z"/><path d="M9 18l-1 2m4-2-1 2m4-2-1 2"/></svg>',
  moist: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11Z"/></svg>',
  temp: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3a2.5 2.5 0 0 0-2.5 2.5v8.2a4 4 0 1 0 5 0V5.5A2.5 2.5 0 0 0 12 3Z"/></svg>',
  humid: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11Z"/><path d="M9.5 14a2.5 2.5 0 0 0 2.5 2.5"/></svg>',
  obs: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="2.5"/></svg>',
  canopy: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22V10m0 0a5 5 0 1 0-4.5-7A4 4 0 0 0 5 10h7Zm0 0a5 5 0 1 1 4.5-7A4 4 0 0 1 19 10h-7Z"/></svg>',
  sub: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 7h18M3 12h18M3 17h18"/></svg>'
};

/* scroll reveal */
function initReveal() {
  const io = new IntersectionObserver((entries) => {
    entries.forEach(e => { if (e.isIntersecting) { e.target.classList.add('in'); io.unobserve(e.target); } });
  }, { threshold: 0.12 });
  document.querySelectorAll('.reveal').forEach(el => io.observe(el));
}

/* mobile nav */
function initNav() {
  const toggle = document.getElementById('navToggle');
  const links = document.getElementById('navLinks');
  if (toggle && links) {
    toggle.addEventListener('click', () => {
      const open = links.classList.toggle('open');
      toggle.classList.toggle('open', open);
      toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    links.querySelectorAll('a').forEach(a => a.addEventListener('click', () => {
      links.classList.remove('open');
      toggle.classList.remove('open');
      toggle.setAttribute('aria-expanded', 'false');
    }));
  }
}

/* launch waitlist — persisted locally */
function initWaitlist() {
  const form = document.getElementById('waitlistForm');
  if (!form) return;
  const input = document.getElementById('wlEmail');
  const msg = document.getElementById('wlMsg');
  const KEY = 'myceliyum_waitlist';

  const saved = JSON.parse(localStorage.getItem(KEY) || '[]');
  if (saved.length && saved.includes(input.value)) { /* noop */ }

  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const email = input.value.trim();
    const valid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
    if (!valid) {
      form.classList.add('error');
      msg.textContent = '> Enter a valid field address to continue.';
      msg.className = 'wl-msg err';
      input.focus();
      return;
    }
    form.classList.remove('error');
    const list = JSON.parse(localStorage.getItem(KEY) || '[]');
    if (list.includes(email)) {
      msg.textContent = '> Already on the list — dispatches incoming.';
      msg.className = 'wl-msg ok';
      return;
    }
    list.push(email);
    localStorage.setItem(KEY, JSON.stringify(list));
    form.reset();
    msg.textContent = '> Logged. You\u2019re #' + list.length + ' on the field roster.';
    msg.className = 'wl-msg ok';
  });
}

/* copy build command */
function initCopy() {
  const box = document.getElementById('buildCmd');
  if (!box) return;
  const text = document.getElementById('buildCmdText').textContent.trim();
  const label = document.getElementById('bcCopy');
  const original = label.innerHTML;
  const doCopy = () => {
    const done = () => {
      box.classList.add('copied');
      label.innerHTML = '\u2713 Copied';
      setTimeout(() => { box.classList.remove('copied'); label.innerHTML = original; }, 1800);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(() => fallbackCopy(text, done));
    } else { fallbackCopy(text, done); }
  };
  box.addEventListener('click', doCopy);
  box.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); doCopy(); } });
}
function fallbackCopy(text, cb) {
  const ta = document.createElement('textarea');
  ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
  document.body.appendChild(ta); ta.select();
  try { document.execCommand('copy'); } catch (e) {}
  document.body.removeChild(ta); cb();
}

/* live UTC clock in the status strip */
function initClock() {
  const el = document.getElementById('utcClock');
  if (!el) return;
  const tick = () => {
    const d = new Date();
    const p = n => String(n).padStart(2, '0');
    el.textContent = `UTC\u00A0${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`;
  };
  tick();
  setInterval(tick, 1000);
}

/* scrolling telemetry readout */
function initTicker() {
  const el = document.getElementById('tickerTrack');
  if (!el) return;
  const items = [
    ['RAIN 7D', '42 mm', 0], ['SOIL MOIST', '88%', 0], ['SOIL TEMP', '12.4\u00B0C', 0],
    ['HUMIDITY', '91%', 0], ['CANOPY', '82%', 1], ['NDVI', '0.52', 1],
    ['OBS 30D', '18', 0], ['PEAK P', '0.94', 1], ['GPS', 'FIX 3D \u00B7 11 SV', 0],
    ['EARTH ENGINE', 'LIVE', 1], ['SAT RES', '30 m', 0], ['ENGINE', 'ONLINE', 1]
  ];
  const seq = items.map(function (it) {
    return '<span class="tm' + (it[2] ? ' on' : '') + '"><b>' + it[0] + '</b>' + it[1] + '</span>';
  }).join('');
  el.innerHTML = '<div class="ticker-seq">' + seq + '</div><div class="ticker-seq" aria-hidden="true">' + seq + '</div>';
}

function boot() {
  renderCatalogue();
  initFilter();
  resolveImages();
  initLens();
  renderHeatmap();
  initReveal();
  initNav();
  initWaitlist();
  initCopy();
  initClock();
  initTicker();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot);
} else {
  boot();
}
