(() => {
  'use strict';

  const API = 'https://iptv-org.github.io/api';
  const LIMIT = 100;
  const $ = id => document.getElementById(id);
  const els = {
    filtered: $('statFiltered'),
    list: $('candidateList'),
    result: $('resultMeta'),
    country: $('countryFilter'),
    category: $('categoryFilter'),
    language: $('languageFilter'),
    search: $('searchInput')
  };

  let loaded = false;
  let loading = false;
  let channels = [];
  let categories = new Map();
  let countries = new Map();
  let languages = new Map();
  let logosByChannel = new Map();
  let timer = null;

  const s = v => typeof v === 'string' ? v.trim() : '';
  const isHttp = v => {
    try { const u = new URL(v); return u.protocol === 'http:' || u.protocol === 'https:'; }
    catch (_) { return false; }
  };

  async function json(name) {
    const r = await fetch(`${API}/${name}.json?t=${Date.now()}`, { cache: 'no-store' });
    if (!r.ok) throw new Error(`${name}.json HTTP ${r.status}`);
    return r.json();
  }

  async function ensureLoaded() {
    if (loaded || loading) return;
    loading = true;
    try {
      const [c, cats, cos, langs, logos] = await Promise.all([
        json('channels'), json('categories'), json('countries'), json('languages'), json('logos')
      ]);
      channels = c;
      categories = new Map(cats.map(x => [x.id, x.name]));
      countries = new Map(cos.map(x => [x.code, x.name]));
      languages = new Map(langs.map(x => [x.code, x.name]));
      logosByChannel = new Map();
      for (const logo of logos) {
        if (!logo.channel || !isHttp(logo.url)) continue;
        if (!logosByChannel.has(logo.channel)) logosByChannel.set(logo.channel, []);
        logosByChannel.get(logo.channel).push(logo);
      }
      loaded = true;
    } catch (e) {
      console.warn('SportNexa metadata fallback could not load', e);
    } finally {
      loading = false;
    }
  }

  function bestLogo(id) {
    const list = logosByChannel.get(id) || [];
    const preferred = list.find(x => x.in_use === true) || list[0];
    return preferred && isHttp(preferred.url) ? preferred.url : '';
  }

  function countryCodes(channel) {
    const out = new Set();
    if (s(channel.country)) out.add(s(channel.country).toUpperCase());
    (Array.isArray(channel.broadcast_area) ? channel.broadcast_area : []).forEach(area => {
      const m = s(area).match(/^c\/(.+)$/i);
      if (m) out.add(m[1].toUpperCase());
    });
    return [...out];
  }

  function categoryName(ids) {
    const list = Array.isArray(ids) ? ids : [];
    const id = ['sports', 'news', 'entertainment'].find(x => list.includes(x)) || list[0];
    return id ? (categories.get(id) || id) : 'Other';
  }

  function makeBadge(text, cls = '') {
    const span = document.createElement('span');
    span.className = `badge ${cls}`.trim();
    span.textContent = text;
    return span;
  }

  function currentMatches() {
    const country = els.country.value;
    const category = els.category.value;
    const language = els.language.value;
    const q = els.search.value.trim().toLowerCase();
    return channels.filter(ch => {
      if (ch.is_nsfw === true) return false;
      const cc = countryCodes(ch);
      const cats = Array.isArray(ch.categories) ? ch.categories : [];
      const langs = Array.isArray(ch.languages) ? ch.languages : [];
      if (country && !cc.includes(country)) return false;
      if (category && !cats.includes(category)) return false;
      if (language && !langs.includes(language)) return false;
      if (q) {
        const hay = [ch.id, ch.name, ...(ch.alt_names || []), ch.network, ...(ch.owners || []), ch.website, cats.join(' '), cc.join(' '), langs.join(' ')].join(' ').toLowerCase();
        if (!hay.includes(q)) return false;
      }
      return true;
    }).sort((a, b) => s(a.name).localeCompare(s(b.name)));
  }

  async function renderFallback() {
    if (!els.filtered || !els.list) return;
    if (String(els.filtered.textContent).trim() !== '0') return;
    await ensureLoaded();
    if (!loaded || String(els.filtered.textContent).trim() !== '0') return;

    const matches = currentMatches();
    if (!matches.length) return;

    els.list.replaceChildren();
    const note = document.createElement('div');
    note.className = 'empty';
    note.style.padding = '18px';
    note.innerHTML = `<strong>${matches.length} channel metadata match${matches.length === 1 ? '' : 'es'}, but IPTV-org currently has no playable stream for these filters.</strong><br><span style="color:var(--muted)">These are review-only and cannot be imported until a valid authorized HLS/DASH stream is available.</span>`;
    els.list.appendChild(note);

    matches.slice(0, LIMIT).forEach(ch => {
      const row = document.createElement('div');
      row.className = 'candidate';

      const check = document.createElement('input');
      check.type = 'checkbox';
      check.disabled = true;
      check.title = 'Metadata only — no current IPTV-org stream';

      const logo = document.createElement('div');
      logo.className = 'candidate-logo';
      const image = bestLogo(ch.id);
      const initial = (s(ch.name) || 'TV').charAt(0).toUpperCase();
      if (image) {
        const img = document.createElement('img');
        img.src = image; img.alt = '';
        img.onerror = () => logo.replaceChildren(document.createTextNode(initial));
        logo.appendChild(img);
      } else logo.textContent = initial;

      const main = document.createElement('div');
      main.className = 'candidate-main';
      const name = document.createElement('div');
      name.className = 'candidate-name';
      name.textContent = s(ch.name) || ch.id;
      const cc = countryCodes(ch);
      const langNames = (Array.isArray(ch.languages) ? ch.languages : []).slice(0, 3).map(x => languages.get(x) || x);
      const sub = document.createElement('div');
      sub.className = 'candidate-sub';
      sub.textContent = `${cc.map(x => countries.get(x) || x).join(', ') || 'Unknown country'} • ${langNames.join(', ') || 'Unknown language'}`;
      const info = document.createElement('div');
      info.className = 'candidate-url';
      info.textContent = 'No current IPTV-org stream URL';
      const badges = document.createElement('div');
      badges.className = 'candidate-badges';
      badges.append(makeBadge(categoryName(ch.categories)), makeBadge('METADATA ONLY', 'headers'));
      if (cc[0]) badges.append(makeBadge(cc[0], 'country'));
      main.append(name, sub, info, badges);

      const actions = document.createElement('div');
      actions.className = 'candidate-actions';
      if (isHttp(ch.website)) {
        const a = document.createElement('a');
        a.className = 'small-btn';
        a.textContent = 'Official Website';
        a.href = ch.website;
        a.target = '_blank';
        a.rel = 'noreferrer';
        actions.appendChild(a);
      }

      row.append(check, logo, main, actions);
      els.list.appendChild(row);
    });

    if (els.result) els.result.textContent = `0 playable streams • ${matches.length} metadata-only channel${matches.length === 1 ? '' : 's'}`;
  }

  function schedule() {
    clearTimeout(timer);
    timer = setTimeout(renderFallback, 250);
  }

  [els.country, els.category, els.language].forEach(x => x && x.addEventListener('change', schedule));
  if (els.search) els.search.addEventListener('input', schedule);
  document.querySelectorAll('.preset').forEach(x => x.addEventListener('click', schedule));

  if (els.filtered) {
    new MutationObserver(schedule).observe(els.filtered, { childList: true, characterData: true, subtree: true });
  }
  schedule();
})();