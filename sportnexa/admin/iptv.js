(() => {
  'use strict';

  const OWNER = 'DaniyalTamaz';
  const REPO = 'VideoGrabDownloader';
  const BRANCH = 'main';
  const JSON_PATH = 'sportnexa/channels.json';
  const RAW_URL = `https://raw.githubusercontent.com/${OWNER}/${REPO}/${BRANCH}/${JSON_PATH}`;
  const API_URL = `https://api.github.com/repos/${OWNER}/${REPO}/contents/${JSON_PATH}`;

  const IPTV_API = 'https://iptv-org.github.io/api';
  const ENDPOINTS = {
    channels: `${IPTV_API}/channels.json`,
    feeds: `${IPTV_API}/feeds.json`,
    streams: `${IPTV_API}/streams.json`,
    logos: `${IPTV_API}/logos.json`,
    blocklist: `${IPTV_API}/blocklist.json`,
    categories: `${IPTV_API}/categories.json`,
    countries: `${IPTV_API}/countries.json`,
    languages: `${IPTV_API}/languages.json`
  };

  const RENDER_LIMIT = 300;
  const RASTER_LOGO_FORMATS = new Set(['PNG', 'JPEG', 'JPG', 'WEBP', 'AVIF', 'APNG']);

  const state = {
    loaded: false,
    candidates: [],
    candidateByUrl: new Map(),
    filtered: [],
    selected: new Set(),
    currentUrls: new Set(),
    currentNames: new Set(),
    categoryNames: new Map(),
    countryNames: new Map(),
    languageNames: new Map(),
    lastLoadedAt: null
  };

  const $ = id => document.getElementById(id);
  const els = {
    catalog: $('statCatalog'), filtered: $('statFiltered'), selected: $('statSelected'), existing: $('statExisting'),
    catalogMeta: $('catalogMeta'), resultMeta: $('resultMeta'), list: $('candidateList'),
    country: $('countryFilter'), category: $('categoryFilter'), language: $('languageFilter'), format: $('formatFilter'),
    search: $('searchInput'), bestOnly: $('bestOnly'), hideHeaders: $('hideHeaders'), hideGeo: $('hideGeo'),
    token: $('tokenField'), enableImmediately: $('enableImmediately'), publishStatus: $('publishStatus'),
    publishSelected: $('publishSelectedBtn'), reload: $('reloadCatalogBtn')
  };

  function safeString(value) {
    return typeof value === 'string' ? value.trim() : '';
  }

  function isHttpUrl(value) {
    try {
      const url = new URL(value);
      return url.protocol === 'https:' || url.protocol === 'http:';
    } catch (_) {
      return false;
    }
  }

  function escapeForSearch(value) {
    return safeString(value).toLowerCase();
  }

  function keyForFeed(channelId, feedId) {
    return `${channelId || ''}::${feedId || ''}`;
  }

  function streamFormat(url) {
    try {
      const pathname = new URL(url).pathname.toLowerCase();
      if (pathname.endsWith('.m3u8')) return 'HLS';
      if (pathname.endsWith('.mpd')) return 'DASH';
    } catch (_) {
      const lower = safeString(url).toLowerCase().split(/[?#]/)[0];
      if (lower.endsWith('.m3u8')) return 'HLS';
      if (lower.endsWith('.mpd')) return 'DASH';
    }
    return 'OTHER';
  }

  function qualityScore(value) {
    const match = safeString(value).match(/(\d{3,4})p/i);
    return match ? Number(match[1]) : 0;
  }

  function setStatus(message, type = '') {
    els.publishStatus.textContent = message;
    els.publishStatus.className = `status ${type}`.trim();
  }

  async function fetchJson(url) {
    const response = await fetch(`${url}?t=${Date.now()}`, { cache: 'no-store' });
    if (!response.ok) throw new Error(`${url.split('/').pop()}: HTTP ${response.status}`);
    return response.json();
  }

  async function loadCurrentDatabase() {
    try {
      const response = await fetch(`${RAW_URL}?t=${Date.now()}`, { cache: 'no-store' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const db = await response.json();
      const channels = Array.isArray(db.channels) ? db.channels : [];
      state.currentUrls = new Set(channels.map(c => safeString(c.primaryUrl || c.url)).filter(Boolean));
      state.currentNames = new Set(channels.map(c => safeString(c.name).toLowerCase()).filter(Boolean));
    } catch (error) {
      state.currentUrls = new Set();
      state.currentNames = new Set();
      setStatus(`SportNexa database check failed: ${error.message}. Catalog can still be reviewed.`, 'err');
    }
  }

  function chooseRasterLogo(logos, channelId, feedId) {
    const options = logos.filter(logo => logo.channel === channelId && isHttpUrl(logo.url));
    if (!options.length) return '';

    const scored = options.map(logo => {
      const format = safeString(logo.format).toUpperCase();
      const raster = RASTER_LOGO_FORMATS.has(format);
      let score = raster ? 100 : -1000;
      if (logo.in_use === true) score += 40;
      if (feedId && logo.feed === feedId) score += 30;
      if (!logo.feed) score += 10;
      if (format === 'PNG') score += 5;
      if (format === 'WEBP') score += 4;
      score += Math.min(20, Math.round((Number(logo.width) || 0) / 250));
      return { logo, score, raster };
    }).filter(item => item.raster);

    scored.sort((a, b) => b.score - a.score);
    return scored.length ? safeString(scored[0].logo.url) : '';
  }

  function displayCategory(categoryIds) {
    const ids = Array.isArray(categoryIds) ? categoryIds : [];
    if (!ids.length) return 'Other';
    const preferred = ['sports', 'news', 'entertainment'].find(id => ids.includes(id));
    const id = preferred || ids[0];
    return state.categoryNames.get(id) || id.charAt(0).toUpperCase() + id.slice(1);
  }

  function buildCandidates(data) {
    state.categoryNames = new Map((data.categories || []).map(item => [item.id, item.name]));
    state.countryNames = new Map((data.countries || []).map(item => [item.code, item.name]));
    state.languageNames = new Map((data.languages || []).map(item => [item.code, item.name]));

    const channelById = new Map((data.channels || []).map(channel => [channel.id, channel]));
    const blocked = new Set((data.blocklist || []).map(item => item.channel).filter(Boolean));
    const feedByKey = new Map();
    const mainFeedByChannel = new Map();

    for (const feed of data.feeds || []) {
      feedByKey.set(keyForFeed(feed.channel, feed.id), feed);
      if (feed.is_main === true && !mainFeedByChannel.has(feed.channel)) {
        mainFeedByChannel.set(feed.channel, feed);
      }
    }

    const countryLanguages = new Map((data.countries || []).map(country => [country.code, Array.isArray(country.languages) ? country.languages : []]));
    const candidates = [];

    for (const stream of data.streams || []) {
      const channelId = safeString(stream.channel);
      if (!channelId || blocked.has(channelId)) continue;

      const channel = channelById.get(channelId);
      if (!channel || channel.is_nsfw === true) continue;

      const url = safeString(stream.url);
      if (!isHttpUrl(url)) continue;

      const feedId = safeString(stream.feed);
      const feed = feedByKey.get(keyForFeed(channelId, feedId)) || mainFeedByChannel.get(channelId) || null;
      const categoryIds = Array.isArray(channel.categories) ? channel.categories.filter(Boolean) : [];
      const countries = new Set();
      const channelCountry = safeString(channel.country);
      if (channelCountry) countries.add(channelCountry);

      if (feed && Array.isArray(feed.broadcast_area)) {
        for (const area of feed.broadcast_area) {
          const match = safeString(area).match(/^c\/(.+)$/i);
          if (match) countries.add(match[1].toUpperCase());
        }
      }

      const languages = new Set();
      if (feed && Array.isArray(feed.languages)) {
        for (const lang of feed.languages) if (lang) languages.add(lang);
      }
      if (!languages.size && channelCountry && countryLanguages.has(channelCountry)) {
        for (const lang of countryLanguages.get(channelCountry)) languages.add(lang);
      }

      const format = streamFormat(url);
      const referrer = safeString(stream.referrer);
      const userAgent = safeString(stream.user_agent);
      const label = safeString(stream.label);
      const channelName = safeString(channel.name) || safeString(stream.title) || channelId;
      const streamTitle = safeString(stream.title) || channelName;
      const ownerText = Array.isArray(channel.owners) ? channel.owners.join(', ') : '';
      const network = safeString(channel.network);
      const website = isHttpUrl(channel.website) ? channel.website : '';
      const logo = chooseRasterLogo(data.logos || [], channelId, feedId);
      const countryList = [...countries];
      const languageList = [...languages];

      const candidate = {
        channelId,
        feedId,
        channelName,
        streamTitle,
        url,
        categoryIds,
        category: displayCategory(categoryIds),
        countries: countryList,
        languages: languageList,
        network,
        owners: ownerText,
        website,
        logo,
        quality: safeString(stream.quality),
        label,
        referrer,
        userAgent,
        hasHeaders: !!(referrer || userAgent),
        format,
        alreadyImported: state.currentUrls.has(url)
      };

      candidate.searchText = [
        channelName, streamTitle, channelId, network, ownerText, url,
        categoryIds.join(' '), countryList.join(' '), languageList.join(' '), label
      ].map(escapeForSearch).join(' ');

      candidates.push(candidate);
    }

    state.candidates = candidates;
    state.candidateByUrl = new Map(candidates.map(candidate => [candidate.url, candidate]));
  }

  function candidateScore(candidate) {
    let score = qualityScore(candidate.quality);
    if (candidate.format === 'HLS') score += 60;
    if (candidate.format === 'DASH') score += 50;
    if (!candidate.hasHeaders) score += 40;
    if (!candidate.label) score += 30;
    if (candidate.logo) score += 10;
    if (candidate.website) score += 5;
    return score;
  }

  function getFilteredCandidates() {
    const country = els.country.value;
    const category = els.category.value;
    const language = els.language.value;
    const format = els.format.value;
    const query = els.search.value.trim().toLowerCase();
    const hideHeaders = els.hideHeaders.checked;
    const hideGeo = els.hideGeo.checked;

    let filtered = state.candidates.filter(candidate => {
      if (country && !candidate.countries.includes(country)) return false;
      if (category && !candidate.categoryIds.includes(category)) return false;
      if (language && !candidate.languages.includes(language)) return false;
      if (format === 'streaming' && !['HLS', 'DASH'].includes(candidate.format)) return false;
      if (format === 'hls' && candidate.format !== 'HLS') return false;
      if (format === 'dash' && candidate.format !== 'DASH') return false;
      if (hideHeaders && candidate.hasHeaders) return false;
      if (hideGeo && /geo/i.test(candidate.label)) return false;
      if (query && !candidate.searchText.includes(query)) return false;
      return true;
    });

    if (els.bestOnly.checked) {
      const best = new Map();
      for (const candidate of filtered) {
        const current = best.get(candidate.channelId);
        if (!current || candidateScore(candidate) > candidateScore(current)) {
          best.set(candidate.channelId, candidate);
        }
      }
      filtered = [...best.values()];
    }

    filtered.sort((a, b) => a.channelName.localeCompare(b.channelName) || candidateScore(b) - candidateScore(a));
    return filtered;
  }

  function createBadge(text, cls = '') {
    const badge = document.createElement('span');
    badge.className = `badge ${cls}`.trim();
    badge.textContent = text;
    return badge;
  }

  function readableCountries(codes) {
    if (!codes.length) return 'Unknown country';
    return codes.slice(0, 3).map(code => state.countryNames.get(code) || code).join(', ');
  }

  function readableLanguages(codes) {
    if (!codes.length) return 'Unknown language';
    return codes.slice(0, 3).map(code => state.languageNames.get(code) || code).join(', ');
  }

  function render() {
    if (!state.loaded) return;

    state.filtered = getFilteredCandidates();
    const displayed = state.filtered.slice(0, RENDER_LIMIT);
    const existingCount = state.candidates.filter(candidate => candidate.alreadyImported).length;

    els.catalog.textContent = String(state.candidates.length);
    els.filtered.textContent = String(state.filtered.length);
    els.selected.textContent = String(state.selected.size);
    els.existing.textContent = String(existingCount);
    els.resultMeta.textContent = state.filtered.length > RENDER_LIMIT
      ? `${state.filtered.length} matches • first ${RENDER_LIMIT} shown`
      : `${state.filtered.length} matches`;

    els.list.replaceChildren();
    if (!displayed.length) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = 'No IPTV-org streams match the current filters.';
      els.list.appendChild(empty);
      return;
    }

    for (const candidate of displayed) {
      const row = document.createElement('div');
      row.className = `candidate${candidate.alreadyImported ? ' already' : ''}${state.selected.has(candidate.url) ? ' selected' : ''}`;

      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.checked = state.selected.has(candidate.url);
      checkbox.disabled = candidate.alreadyImported;
      checkbox.title = candidate.alreadyImported ? 'Already in SportNexa' : 'Select for import';
      checkbox.onchange = () => {
        if (checkbox.checked) state.selected.add(candidate.url);
        else state.selected.delete(candidate.url);
        render();
      };

      const logo = document.createElement('div');
      logo.className = 'candidate-logo';
      const initial = candidate.channelName.charAt(0).toUpperCase() || 'TV';
      if (candidate.logo) {
        const img = document.createElement('img');
        img.src = candidate.logo;
        img.alt = '';
        img.onerror = () => logo.replaceChildren(document.createTextNode(initial));
        logo.appendChild(img);
      } else {
        logo.textContent = initial;
      }

      const main = document.createElement('div');
      main.className = 'candidate-main';
      const name = document.createElement('div');
      name.className = 'candidate-name';
      name.textContent = candidate.channelName;
      const sub = document.createElement('div');
      sub.className = 'candidate-sub';
      sub.textContent = `${candidate.streamTitle} • ${readableCountries(candidate.countries)} • ${readableLanguages(candidate.languages)}`;
      const url = document.createElement('div');
      url.className = 'candidate-url';
      url.textContent = candidate.url;
      url.title = candidate.url;
      const badges = document.createElement('div');
      badges.className = 'candidate-badges';
      badges.appendChild(createBadge(candidate.category || 'Other'));
      badges.appendChild(createBadge(candidate.format, 'format'));
      if (candidate.quality) badges.appendChild(createBadge(candidate.quality));
      if (candidate.countries[0]) badges.appendChild(createBadge(candidate.countries[0], 'country'));
      if (candidate.label) badges.appendChild(createBadge(candidate.label, /geo/i.test(candidate.label) ? 'geo' : ''));
      if (candidate.hasHeaders) badges.appendChild(createBadge('Custom headers', 'headers'));
      if (candidate.alreadyImported) badges.appendChild(createBadge('Already imported', 'existing'));
      main.append(name, sub, url, badges);

      const actions = document.createElement('div');
      actions.className = 'candidate-actions';

      const copy = document.createElement('button');
      copy.className = 'small-btn';
      copy.textContent = 'Copy URL';
      copy.onclick = async () => {
        try {
          await navigator.clipboard.writeText(candidate.url);
          copy.textContent = 'Copied';
          setTimeout(() => copy.textContent = 'Copy URL', 1200);
        } catch (_) {
          prompt('Copy stream URL:', candidate.url);
        }
      };
      actions.appendChild(copy);

      if (candidate.website) {
        const website = document.createElement('a');
        website.className = 'small-btn';
        website.textContent = 'Website';
        website.href = candidate.website;
        website.target = '_blank';
        website.rel = 'noreferrer';
        actions.appendChild(website);
      }

      const stream = document.createElement('a');
      stream.className = 'small-btn';
      stream.textContent = 'Stream';
      stream.href = candidate.url;
      stream.target = '_blank';
      stream.rel = 'noreferrer';
      actions.appendChild(stream);

      row.append(checkbox, logo, main, actions);
      row.onclick = event => {
        if (event.target.closest('button,a,input')) return;
        if (candidate.alreadyImported) return;
        if (state.selected.has(candidate.url)) state.selected.delete(candidate.url);
        else state.selected.add(candidate.url);
        render();
      };
      els.list.appendChild(row);
    }
  }

  async function loadCatalog() {
    els.reload.disabled = true;
    els.catalogMeta.textContent = 'Loading IPTV-org channels, feeds, streams, logos and safety metadata…';
    els.list.innerHTML = '<div class="loader">Loading IPTV-org catalog…</div>';
    setStatus('Refreshing production discovery data…');

    try {
      await loadCurrentDatabase();
      const [channels, feeds, streams, logos, blocklist, categories, countries, languages] = await Promise.all([
        fetchJson(ENDPOINTS.channels),
        fetchJson(ENDPOINTS.feeds),
        fetchJson(ENDPOINTS.streams),
        fetchJson(ENDPOINTS.logos),
        fetchJson(ENDPOINTS.blocklist),
        fetchJson(ENDPOINTS.categories),
        fetchJson(ENDPOINTS.countries),
        fetchJson(ENDPOINTS.languages)
      ]);

      buildCandidates({ channels, feeds, streams, logos, blocklist, categories, countries, languages });
      state.loaded = true;
      state.lastLoadedAt = new Date();
      els.catalogMeta.textContent = `Loaded ${state.lastLoadedAt.toLocaleString()} • ${channels.length} channels • ${streams.length} raw streams`;
      setStatus('IPTV-org catalog loaded. Review candidates before importing.', 'ok');
      render();
    } catch (error) {
      state.loaded = false;
      els.catalogMeta.textContent = 'Could not load IPTV-org catalog.';
      els.list.innerHTML = `<div class="empty">Catalog load failed: ${String(error.message || error)}</div>`;
      setStatus(`Catalog load failed: ${error.message || error}`, 'err');
    } finally {
      els.reload.disabled = false;
    }
  }

  function selectVisible() {
    for (const candidate of state.filtered.slice(0, RENDER_LIMIT)) {
      if (!candidate.alreadyImported) state.selected.add(candidate.url);
    }
    render();
  }

  function clearSelection() {
    state.selected.clear();
    render();
  }

  function utf8ToBase64(text) {
    const bytes = new TextEncoder().encode(text);
    let binary = '';
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
      binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
    }
    return btoa(binary);
  }

  function base64ToUtf8(base64) {
    const binary = atob(String(base64 || '').replace(/\s/g, ''));
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return new TextDecoder().decode(bytes);
  }

  async function publishSelected() {
    const token = els.token.value.trim();
    if (!token) {
      setStatus('Enter your restricted fine-grained GitHub token first.', 'err');
      return;
    }
    if (!state.selected.size) {
      setStatus('Select at least one candidate first.', 'err');
      return;
    }

    const selectedCandidates = [...state.selected]
      .map(url => state.candidateByUrl.get(url))
      .filter(Boolean);
    if (!selectedCandidates.length) {
      setStatus('The selected candidates are no longer available. Reload the catalog.', 'err');
      return;
    }

    const enabled = els.enableImmediately.checked;
    const approval = confirm(
      `Import ${selectedCandidates.length} selected candidate(s) into SportNexa?\n\n` +
      `${enabled ? 'They will be ENABLED immediately.' : 'They will be staged as DISABLED for review.'}\n\n` +
      'IPTV-org listing does not itself establish redistribution rights. Continue only for channels you intend to review/verify.'
    );
    if (!approval) return;

    els.publishSelected.disabled = true;
    setStatus('Reading the current SportNexa channel database…');
    const headers = {
      'Accept': 'application/vnd.github+json',
      'Authorization': `Bearer ${token}`,
      'X-GitHub-Api-Version': '2022-11-28'
    };

    try {
      const response = await fetch(`${API_URL}?ref=${encodeURIComponent(BRANCH)}`, { headers, cache: 'no-store' });
      if (!response.ok) {
        const text = await response.text();
        throw new Error(`GitHub read failed (${response.status}): ${text.slice(0, 180)}`);
      }
      const current = await response.json();
      if (!current.sha || !current.content) throw new Error('GitHub did not return the current channels.json content.');

      const db = JSON.parse(base64ToUtf8(current.content));
      if (!Array.isArray(db.channels)) throw new Error('Live channels.json does not contain a channels array.');

      const existingUrls = new Set(db.channels.map(c => safeString(c.primaryUrl || c.url)).filter(Boolean));
      let nextOrder = Math.max(0, ...db.channels.map(c => Math.trunc(Number(c.order) || 0))) + 1;
      let added = 0;
      let skipped = 0;

      for (const candidate of selectedCandidates) {
        if (existingUrls.has(candidate.url)) {
          skipped++;
          continue;
        }
        db.channels.push({
          name: candidate.channelName,
          category: candidate.category || 'Other',
          primaryUrl: candidate.url,
          backupUrl: '',
          logo: candidate.logo || '',
          enabled,
          featured: false,
          order: nextOrder++
        });
        existingUrls.add(candidate.url);
        added++;
      }

      if (!added) {
        setStatus(`Nothing new to import. ${skipped} selected stream(s) already exist in SportNexa.`, 'err');
        return;
      }

      const currentVersion = Number.parseInt(String(db.version || '0'), 10);
      db.version = Number.isFinite(currentVersion) ? String(currentVersion + 1) : '1';
      db.updatedAt = new Date().toISOString();

      const json = JSON.stringify(db, null, 2) + '\n';
      setStatus(`Publishing ${added} staged channel(s) to SportNexa…`);
      const updateResponse = await fetch(API_URL, {
        method: 'PUT',
        headers: { ...headers, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: `Import ${added} IPTV-org candidate${added === 1 ? '' : 's'} into SportNexa`,
          content: utf8ToBase64(json),
          sha: current.sha,
          branch: BRANCH
        })
      });

      if (!updateResponse.ok) {
        const text = await updateResponse.text();
        throw new Error(`GitHub publish failed (${updateResponse.status}): ${text.slice(0, 220)}`);
      }

      for (const candidate of selectedCandidates) {
        if (existingUrls.has(candidate.url)) {
          state.currentUrls.add(candidate.url);
          candidate.alreadyImported = true;
        }
      }
      state.selected.clear();
      els.token.value = '';
      setStatus(
        `Imported ${added} channel(s) successfully${skipped ? `; skipped ${skipped} duplicate(s)` : ''}. ` +
        `${enabled ? 'They are enabled now.' : 'They are disabled for review in Channel Admin.'}`,
        'ok'
      );
      render();
    } catch (error) {
      setStatus(error.message || String(error), 'err');
    } finally {
      els.publishSelected.disabled = false;
    }
  }

  function applyPreset(button) {
    els.country.value = button.dataset.country || '';
    els.category.value = button.dataset.category || '';
    els.language.value = button.dataset.language || '';
    render();
  }

  const rerenderInputs = [
    els.country, els.category, els.language, els.format,
    els.bestOnly, els.hideHeaders, els.hideGeo
  ];
  rerenderInputs.forEach(el => el.addEventListener('change', render));
  els.search.addEventListener('input', render);
  document.querySelectorAll('.preset').forEach(button => button.addEventListener('click', () => applyPreset(button)));
  $('selectVisibleBtn').addEventListener('click', selectVisible);
  $('clearSelectionBtn').addEventListener('click', clearSelection);
  els.publishSelected.addEventListener('click', publishSelected);
  els.reload.addEventListener('click', loadCatalog);

  loadCatalog();
})();
