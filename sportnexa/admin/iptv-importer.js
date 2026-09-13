(() => {
  'use strict';

  const OWNER = 'DaniyalTamaz';
  const REPO = 'VideoGrabDownloader';
  const BRANCH = 'main';
  const DB_PATH = 'sportnexa/channels.json';
  const RAW_DB = `https://raw.githubusercontent.com/${OWNER}/${REPO}/${BRANCH}/${DB_PATH}`;
  const API_DB = `https://api.github.com/repos/${OWNER}/${REPO}/contents/${DB_PATH}`;
  const IPTV = 'https://iptv-org.github.io/api';
  const LIMIT = 300;
  const RASTER = new Set(['PNG', 'JPEG', 'JPG', 'WEBP', 'AVIF', 'APNG']);

  const state = {
    loaded: false,
    candidates: [],
    byUrl: new Map(),
    filtered: [],
    selected: new Set(),
    existing: new Set(),
    categories: new Map(),
    countries: new Map(),
    languages: new Map()
  };

  const $ = id => document.getElementById(id);
  const el = {
    catalog: $('statCatalog'), filtered: $('statFiltered'), selected: $('statSelected'), existing: $('statExisting'),
    meta: $('catalogMeta'), result: $('resultMeta'), list: $('candidateList'),
    country: $('countryFilter'), category: $('categoryFilter'), language: $('languageFilter'), format: $('formatFilter'),
    search: $('searchInput'), best: $('bestOnly'), hideHeaders: $('hideHeaders'), hideGeo: $('hideGeo'),
    token: $('tokenField'), enable: $('enableImmediately'), status: $('publishStatus'),
    publish: $('publishSelectedBtn'), reload: $('reloadCatalogBtn')
  };

  const s = value => typeof value === 'string' ? value.trim() : '';
  const isHttp = value => {
    try { const u = new URL(value); return u.protocol === 'http:' || u.protocol === 'https:'; }
    catch (_) { return false; }
  };
  const feedKey = (channel, feed) => `${channel || ''}::${feed || ''}`;
  const setStatus = (text, cls = '') => { el.status.textContent = text; el.status.className = `status ${cls}`.trim(); };

  function formatOf(url) {
    try {
      const p = new URL(url).pathname.toLowerCase();
      if (p.endsWith('.m3u8')) return 'HLS';
      if (p.endsWith('.mpd')) return 'DASH';
    } catch (_) {}
    return 'OTHER';
  }

  function qScore(q) {
    const m = s(q).match(/(\d{3,4})p/i);
    return m ? Number(m[1]) : 0;
  }

  async function json(url) {
    const r = await fetch(`${url}?t=${Date.now()}`, { cache: 'no-store' });
    if (!r.ok) throw new Error(`${url.split('/').pop()} HTTP ${r.status}`);
    return r.json();
  }

  async function loadExisting() {
    try {
      const r = await fetch(`${RAW_DB}?t=${Date.now()}`, { cache: 'no-store' });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const db = await r.json();
      state.existing = new Set((db.channels || []).map(c => s(c.primaryUrl || c.url)).filter(Boolean));
    } catch (_) {
      state.existing = new Set();
    }
  }

  function bestLogo(options, feedId) {
    let winner = '';
    let top = -1;
    for (const logo of options || []) {
      const f = s(logo.format).toUpperCase();
      if (!RASTER.has(f) || !isHttp(logo.url)) continue;
      let score = 100;
      if (logo.in_use === true) score += 40;
      if (feedId && logo.feed === feedId) score += 30;
      if (!logo.feed) score += 10;
      if (f === 'PNG') score += 5;
      score += Math.min(20, Math.floor((Number(logo.width) || 0) / 250));
      if (score > top) { top = score; winner = s(logo.url); }
    }
    return winner;
  }

  function categoryName(ids) {
    const list = Array.isArray(ids) ? ids : [];
    const id = ['sports', 'news', 'entertainment'].find(x => list.includes(x)) || list[0];
    return id ? (state.categories.get(id) || id[0].toUpperCase() + id.slice(1)) : 'Other';
  }

  function build(data) {
    state.categories = new Map(data.categories.map(x => [x.id, x.name]));
    state.countries = new Map(data.countries.map(x => [x.code, x.name]));
    state.languages = new Map(data.languages.map(x => [x.code, x.name]));

    const channelById = new Map(data.channels.map(x => [x.id, x]));
    const blocked = new Set(data.blocklist.map(x => x.channel).filter(Boolean));
    const feedByKey = new Map();
    const mainFeed = new Map();
    for (const f of data.feeds) {
      feedByKey.set(feedKey(f.channel, f.id), f);
      if (f.is_main === true && !mainFeed.has(f.channel)) mainFeed.set(f.channel, f);
    }

    const logosByChannel = new Map();
    for (const logo of data.logos) {
      if (!logo.channel) continue;
      if (!logosByChannel.has(logo.channel)) logosByChannel.set(logo.channel, []);
      logosByChannel.get(logo.channel).push(logo);
    }

    const countryLangs = new Map(data.countries.map(x => [x.code, Array.isArray(x.languages) ? x.languages : []]));
    const out = [];

    for (const stream of data.streams) {
      const channelId = s(stream.channel);
      if (!channelId || blocked.has(channelId)) continue;
      const channel = channelById.get(channelId);
      if (!channel || channel.is_nsfw === true) continue;
      const url = s(stream.url);
      if (!isHttp(url)) continue;

      const feedId = s(stream.feed);
      const feed = feedByKey.get(feedKey(channelId, feedId)) || mainFeed.get(channelId) || null;
      const cats = Array.isArray(channel.categories) ? channel.categories.filter(Boolean) : [];
      const countries = new Set();
      const country = s(channel.country);
      if (country) countries.add(country);
      if (feed && Array.isArray(feed.broadcast_area)) {
        feed.broadcast_area.forEach(a => {
          const m = s(a).match(/^c\/(.+)$/i);
          if (m) countries.add(m[1].toUpperCase());
        });
      }
      const langs = new Set(feed && Array.isArray(feed.languages) ? feed.languages : []);
      if (!langs.size && country) (countryLangs.get(country) || []).forEach(x => langs.add(x));

      const referrer = s(stream.referrer);
      const userAgent = s(stream.user_agent);
      const name = s(channel.name) || s(stream.title) || channelId;
      const title = s(stream.title) || name;
      const format = formatOf(url);
      const c = {
        channelId, feedId, name, title, url, format,
        categories: cats, category: categoryName(cats),
        countries: [...countries], languages: [...langs],
        quality: s(stream.quality), label: s(stream.label),
        hasHeaders: !!(referrer || userAgent),
        importable: !(referrer || userAgent),
        website: isHttp(channel.website) ? channel.website : '',
        logo: bestLogo(logosByChannel.get(channelId), feedId),
        existing: state.existing.has(url)
      };
      c.search = [name, title, channelId, s(channel.network), (channel.owners || []).join(' '), url, cats.join(' '), c.countries.join(' '), c.languages.join(' '), c.label].join(' ').toLowerCase();
      out.push(c);
    }
    state.candidates = out;
    state.byUrl = new Map(out.map(c => [c.url, c]));
  }

  function score(c) {
    return qScore(c.quality) + (c.format === 'HLS' ? 60 : c.format === 'DASH' ? 50 : 0) + (!c.label ? 30 : 0) + (c.logo ? 10 : 0) + (c.website ? 5 : 0);
  }

  function filterCandidates() {
    const country = el.country.value;
    const category = el.category.value;
    const language = el.language.value;
    const format = el.format.value;
    const query = el.search.value.trim().toLowerCase();
    let list = state.candidates.filter(c => {
      if (country && !c.countries.includes(country)) return false;
      if (category && !c.categories.includes(category)) return false;
      if (language && !c.languages.includes(language)) return false;
      if (format === 'streaming' && !['HLS', 'DASH'].includes(c.format)) return false;
      if (format === 'hls' && c.format !== 'HLS') return false;
      if (format === 'dash' && c.format !== 'DASH') return false;
      if (el.hideHeaders.checked && c.hasHeaders) return false;
      if (el.hideGeo.checked && /geo/i.test(c.label)) return false;
      return !query || c.search.includes(query);
    });
    if (el.best.checked) {
      const best = new Map();
      list.forEach(c => { const old = best.get(c.channelId); if (!old || score(c) > score(old)) best.set(c.channelId, c); });
      list = [...best.values()];
    }
    return list.sort((a, b) => a.name.localeCompare(b.name) || score(b) - score(a));
  }

  function badge(text, cls = '') {
    const x = document.createElement('span'); x.className = `badge ${cls}`.trim(); x.textContent = text; return x;
  }

  function names(codes, map, fallback) {
    return codes.length ? codes.slice(0, 3).map(x => map.get(x) || x).join(', ') : fallback;
  }

  function render() {
    if (!state.loaded) return;
    state.filtered = filterCandidates();
    const shown = state.filtered.slice(0, LIMIT);
    el.catalog.textContent = state.candidates.length;
    el.filtered.textContent = state.filtered.length;
    el.selected.textContent = state.selected.size;
    el.existing.textContent = state.candidates.filter(c => c.existing).length;
    el.result.textContent = state.filtered.length > LIMIT ? `${state.filtered.length} matches • first ${LIMIT} shown` : `${state.filtered.length} matches`;
    el.list.replaceChildren();

    if (!shown.length) {
      const x = document.createElement('div'); x.className = 'empty'; x.textContent = 'No IPTV-org streams match these filters.'; el.list.appendChild(x); return;
    }

    shown.forEach(c => {
      const row = document.createElement('div');
      row.className = `candidate${c.existing ? ' already' : ''}${state.selected.has(c.url) ? ' selected' : ''}`;
      const check = document.createElement('input');
      check.type = 'checkbox'; check.checked = state.selected.has(c.url); check.disabled = c.existing || !c.importable;
      check.title = c.existing ? 'Already in SportNexa' : (!c.importable ? 'Review only: custom stream headers are not supported yet' : 'Select for import');
      check.onchange = () => { check.checked ? state.selected.add(c.url) : state.selected.delete(c.url); render(); };

      const logo = document.createElement('div'); logo.className = 'candidate-logo';
      const initial = c.name.charAt(0).toUpperCase() || 'TV';
      if (c.logo) { const img = document.createElement('img'); img.src = c.logo; img.alt = ''; img.onerror = () => logo.replaceChildren(document.createTextNode(initial)); logo.appendChild(img); }
      else logo.textContent = initial;

      const main = document.createElement('div'); main.className = 'candidate-main';
      const name = document.createElement('div'); name.className = 'candidate-name'; name.textContent = c.name;
      const sub = document.createElement('div'); sub.className = 'candidate-sub'; sub.textContent = `${c.title} • ${names(c.countries, state.countries, 'Unknown country')} • ${names(c.languages, state.languages, 'Unknown language')}`;
      const url = document.createElement('div'); url.className = 'candidate-url'; url.textContent = c.url; url.title = c.url;
      const badges = document.createElement('div'); badges.className = 'candidate-badges';
      badges.append(badge(c.category), badge(c.format, 'format'));
      if (c.quality) badges.append(badge(c.quality));
      if (c.countries[0]) badges.append(badge(c.countries[0], 'country'));
      if (c.label) badges.append(badge(c.label, /geo/i.test(c.label) ? 'geo' : ''));
      if (c.hasHeaders) badges.append(badge('Review only • headers', 'headers'));
      if (c.existing) badges.append(badge('Already imported', 'existing'));
      main.append(name, sub, url, badges);

      const actions = document.createElement('div'); actions.className = 'candidate-actions';
      const copy = document.createElement('button'); copy.className = 'small-btn'; copy.textContent = 'Copy URL';
      copy.onclick = async () => { try { await navigator.clipboard.writeText(c.url); copy.textContent = 'Copied'; setTimeout(() => copy.textContent = 'Copy URL', 1000); } catch (_) { prompt('Copy stream URL:', c.url); } };
      actions.append(copy);
      if (c.website) { const a = document.createElement('a'); a.className = 'small-btn'; a.textContent = 'Website'; a.href = c.website; a.target = '_blank'; a.rel = 'noreferrer'; actions.append(a); }
      const stream = document.createElement('a'); stream.className = 'small-btn'; stream.textContent = 'Stream'; stream.href = c.url; stream.target = '_blank'; stream.rel = 'noreferrer'; actions.append(stream);

      row.append(check, logo, main, actions);
      row.onclick = e => { if (e.target.closest('button,a,input') || c.existing || !c.importable) return; state.selected.has(c.url) ? state.selected.delete(c.url) : state.selected.add(c.url); render(); };
      el.list.append(row);
    });
  }

  async function load() {
    el.reload.disabled = true;
    el.meta.textContent = 'Loading IPTV-org metadata…';
    el.list.textContent = 'Loading IPTV-org catalog…';
    setStatus('Refreshing discovery data…');
    try {
      await loadExisting();
      const [channels, feeds, streams, logos, blocklist, categories, countries, languages] = await Promise.all([
        json(`${IPTV}/channels.json`), json(`${IPTV}/feeds.json`), json(`${IPTV}/streams.json`), json(`${IPTV}/logos.json`),
        json(`${IPTV}/blocklist.json`), json(`${IPTV}/categories.json`), json(`${IPTV}/countries.json`), json(`${IPTV}/languages.json`)
      ]);
      build({ channels, feeds, streams, logos, blocklist, categories, countries, languages });
      state.loaded = true;
      el.meta.textContent = `Loaded ${new Date().toLocaleString()} • ${channels.length} channels • ${streams.length} raw streams`;
      setStatus('IPTV-org catalog loaded. Import only after source review.', 'ok');
      render();
    } catch (err) {
      state.loaded = false;
      el.meta.textContent = 'Catalog load failed.';
      el.list.replaceChildren(); const x = document.createElement('div'); x.className = 'empty'; x.textContent = `Load failed: ${err.message || err}`; el.list.append(x);
      setStatus(`Catalog load failed: ${err.message || err}`, 'err');
    } finally { el.reload.disabled = false; }
  }

  function b64encode(text) {
    const bytes = new TextEncoder().encode(text); let bin = '';
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    return btoa(bin);
  }
  function b64decode(value) {
    const bin = atob(String(value || '').replace(/\s/g, '')); const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder().decode(bytes);
  }

  async function publish() {
    const token = el.token.value.trim();
    const selected = [...state.selected].map(u => state.byUrl.get(u)).filter(c => c && c.importable);
    if (!token) return setStatus('Enter your restricted GitHub token first.', 'err');
    if (!selected.length) return setStatus('Select at least one importable candidate first.', 'err');
    const enabled = el.enable.checked;
    if (!confirm(`Import ${selected.length} channel(s)?\n\n${enabled ? 'They will be ENABLED immediately.' : 'They will be staged as DISABLED.'}\n\nIPTV-org presence is not proof of redistribution rights.`)) return;

    el.publish.disabled = true; setStatus('Reading live channels.json…');
    const headers = { Accept: 'application/vnd.github+json', Authorization: `Bearer ${token}`, 'X-GitHub-Api-Version': '2022-11-28' };
    try {
      const r = await fetch(`${API_DB}?ref=${BRANCH}`, { headers, cache: 'no-store' });
      if (!r.ok) throw new Error(`GitHub read failed (${r.status})`);
      const current = await r.json();
      const db = JSON.parse(b64decode(current.content));
      if (!Array.isArray(db.channels)) throw new Error('channels.json has no channels array');
      const urls = new Set(db.channels.map(c => s(c.primaryUrl || c.url)).filter(Boolean));
      let order = Math.max(0, ...db.channels.map(c => Number(c.order) || 0)) + 1, added = 0, skipped = 0;
      selected.forEach(c => {
        if (urls.has(c.url)) return skipped++;
        db.channels.push({ name: c.name, category: c.category || 'Other', primaryUrl: c.url, backupUrl: '', logo: c.logo || '', enabled, featured: false, order: order++ });
        urls.add(c.url); added++;
      });
      if (!added) return setStatus(`Nothing new to import; ${skipped} duplicate(s) skipped.`, 'err');
      const v = parseInt(String(db.version || '0'), 10); db.version = Number.isFinite(v) ? String(v + 1) : '1'; db.updatedAt = new Date().toISOString();
      const body = { message: `Import ${added} IPTV-org candidate${added === 1 ? '' : 's'} into SportNexa`, content: b64encode(JSON.stringify(db, null, 2) + '\n'), sha: current.sha, branch: BRANCH };
      const put = await fetch(API_DB, { method: 'PUT', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      if (!put.ok) throw new Error(`GitHub publish failed (${put.status}): ${(await put.text()).slice(0, 180)}`);
      selected.forEach(c => { c.existing = true; state.existing.add(c.url); }); state.selected.clear(); el.token.value = '';
      setStatus(`Imported ${added} channel(s)${skipped ? `; skipped ${skipped} duplicate(s)` : ''}. ${enabled ? 'Enabled now.' : 'Disabled for review in Channel Admin.'}`, 'ok'); render();
    } catch (err) { setStatus(err.message || String(err), 'err'); }
    finally { el.publish.disabled = false; }
  }

  [el.country, el.category, el.language, el.format, el.best, el.hideHeaders, el.hideGeo].forEach(x => x.addEventListener('change', render));
  el.search.addEventListener('input', render);
  document.querySelectorAll('.preset').forEach(b => b.addEventListener('click', () => { el.country.value = b.dataset.country || ''; el.category.value = b.dataset.category || ''; el.language.value = b.dataset.language || ''; render(); }));
  $('selectVisibleBtn').onclick = () => { state.filtered.slice(0, LIMIT).forEach(c => { if (!c.existing && c.importable) state.selected.add(c.url); }); render(); };
  $('clearSelectionBtn').onclick = () => { state.selected.clear(); render(); };
  el.publish.onclick = publish;
  el.reload.onclick = load;
  load();
})();
