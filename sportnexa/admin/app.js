(() => {
  'use strict';

  const OWNER = 'DaniyalTamaz';
  const REPO = 'VideoGrabDownloader';
  const BRANCH = 'main';
  const JSON_PATH = 'sportnexa/channels.json';
  const RAW_URL = `https://raw.githubusercontent.com/${OWNER}/${REPO}/${BRANCH}/${JSON_PATH}`;
  const API_URL = `https://api.github.com/repos/${OWNER}/${REPO}/contents/${JSON_PATH}`;

  const state = {
    version: '1',
    updatedAt: '',
    channels: [],
    selected: null,
    dirty: false
  };

  const $ = id => document.getElementById(id);
  const els = {
    list: $('channelList'), search: $('searchInput'), filter: $('filterSelect'), meta: $('dbMeta'), count: $('visibleCount'),
    total: $('statTotal'), enabled: $('statEnabled'), disabled: $('statDisabled'), featured: $('statFeatured'),
    name: $('nameField'), category: $('categoryField'), order: $('orderField'), primary: $('primaryField'), backup: $('backupField'), logo: $('logoField'),
    enabledField: $('enabledField'), featuredField: $('featuredField'), editorTitle: $('editorTitle'), editorHelp: $('editorHelp'),
    token: $('tokenField'), publishStatus: $('publishStatus'), remotePath: $('remotePath')
  };

  els.remotePath.textContent = `${OWNER}/${REPO}/${JSON_PATH}`;

  function isHttpUrl(value) {
    if (!value) return false;
    try {
      const u = new URL(value);
      return u.protocol === 'https:' || u.protocol === 'http:';
    } catch (_) { return false; }
  }

  function safeText(value, fallback = '') {
    return typeof value === 'string' ? value.trim() : fallback;
  }

  function normalizeChannel(raw, index) {
    return {
      name: safeText(raw?.name, `Channel ${index + 1}`) || `Channel ${index + 1}`,
      category: safeText(raw?.category || raw?.group, 'Other') || 'Other',
      primaryUrl: safeText(raw?.primaryUrl || raw?.url),
      backupUrl: safeText(raw?.backupUrl || raw?.backup),
      logo: safeText(raw?.logo || raw?.logoUrl),
      enabled: raw?.enabled !== false,
      featured: raw?.featured === true,
      order: Number.isFinite(Number(raw?.order)) ? Math.max(1, Math.trunc(Number(raw.order))) : index + 1
    };
  }

  function sortChannels() {
    state.channels.sort((a, b) => (a.order - b.order) || a.name.localeCompare(b.name));
  }

  function databaseObject(incrementVersion = false) {
    const current = Number.parseInt(String(state.version), 10);
    const version = incrementVersion && Number.isFinite(current) ? String(current + 1) : String(state.version || '1');
    return {
      version,
      updatedAt: incrementVersion ? new Date().toISOString() : (state.updatedAt || new Date().toISOString()),
      channels: state.channels.map(c => ({
        name: c.name,
        category: c.category,
        primaryUrl: c.primaryUrl,
        backupUrl: c.backupUrl,
        logo: c.logo,
        enabled: !!c.enabled,
        featured: !!c.featured,
        order: Math.max(1, Math.trunc(Number(c.order) || 1))
      }))
    };
  }

  function setDirty(value = true) {
    state.dirty = value;
    document.title = value ? '• SportNexa Admin' : 'SportNexa Admin';
  }

  function setPublishStatus(message, type = '') {
    els.publishStatus.textContent = message;
    els.publishStatus.className = `status ${type}`.trim();
  }

  function updateStats() {
    els.total.textContent = state.channels.length;
    els.enabled.textContent = state.channels.filter(c => c.enabled).length;
    els.disabled.textContent = state.channels.filter(c => !c.enabled).length;
    els.featured.textContent = state.channels.filter(c => c.featured && c.enabled).length;
  }

  function makeBadge(text, cls = '') {
    const span = document.createElement('span');
    span.className = `badge ${cls}`.trim();
    span.textContent = text;
    return span;
  }

  function render() {
    sortChannels();
    updateStats();
    const q = els.search.value.trim().toLowerCase();
    const filter = els.filter.value;
    const shown = state.channels.filter(c => {
      if (filter === 'enabled' && !c.enabled) return false;
      if (filter === 'disabled' && c.enabled) return false;
      if (filter === 'featured' && !(c.enabled && c.featured)) return false;
      if (!q) return true;
      return `${c.name} ${c.category} ${c.primaryUrl} ${c.backupUrl}`.toLowerCase().includes(q);
    });

    els.list.replaceChildren();
    els.count.textContent = `${shown.length} shown`;
    if (!shown.length) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = 'No channels match this filter.';
      els.list.appendChild(empty);
      return;
    }

    for (const channel of shown) {
      const row = document.createElement('div');
      row.className = `channel${channel.enabled ? '' : ' disabled'}${state.selected === channel ? ' selected' : ''}`;

      const logo = document.createElement('div');
      logo.className = 'logo';
      const initial = (channel.name || 'TV').trim().charAt(0).toUpperCase() || 'TV';
      if (isHttpUrl(channel.logo)) {
        const img = document.createElement('img');
        img.src = channel.logo;
        img.alt = '';
        img.onerror = () => { logo.replaceChildren(document.createTextNode(initial)); };
        logo.appendChild(img);
      } else {
        logo.textContent = initial;
      }

      const meta = document.createElement('div');
      meta.className = 'meta';
      const name = document.createElement('div');
      name.className = 'name'; name.textContent = channel.name;
      const sub = document.createElement('div');
      sub.className = 'sub'; sub.textContent = `#${channel.order} • ${channel.category} • ${channel.backupUrl ? 'Primary + backup' : 'Primary only'}`;
      const badges = document.createElement('div'); badges.className = 'badges';
      badges.appendChild(makeBadge(channel.enabled ? 'Enabled' : 'Disabled', channel.enabled ? 'on' : 'off'));
      if (channel.featured) badges.appendChild(makeBadge('Featured', 'featured'));
      meta.append(name, sub, badges);

      const actions = document.createElement('div'); actions.className = 'row-actions';
      const edit = document.createElement('button'); edit.className = 'icon-btn'; edit.textContent = '✎'; edit.title = 'Edit';
      edit.onclick = e => { e.stopPropagation(); selectChannel(channel); };
      const up = document.createElement('button'); up.className = 'icon-btn'; up.textContent = '↑'; up.title = 'Move up';
      up.onclick = e => { e.stopPropagation(); moveChannel(channel, -1); };
      const down = document.createElement('button'); down.className = 'icon-btn'; down.textContent = '↓'; down.title = 'Move down';
      down.onclick = e => { e.stopPropagation(); moveChannel(channel, 1); };
      actions.append(edit, up, down);

      row.append(logo, meta, actions);
      row.onclick = () => selectChannel(channel);
      els.list.appendChild(row);
    }
  }

  function clearEditor() {
    state.selected = null;
    els.editorTitle.textContent = 'Add Channel';
    els.name.value = '';
    els.category.value = 'Sports';
    els.order.value = String(Math.max(0, ...state.channels.map(c => c.order || 0)) + 1);
    els.primary.value = '';
    els.backup.value = '';
    els.logo.value = '';
    els.enabledField.checked = true;
    els.featuredField.checked = false;
    els.editorHelp.textContent = 'Primary URL and channel name are required. Only add streams you are authorized to use.';
    els.editorHelp.className = 'help';
    render();
  }

  function selectChannel(channel) {
    state.selected = channel;
    els.editorTitle.textContent = `Edit: ${channel.name}`;
    els.name.value = channel.name;
    els.category.value = channel.category;
    els.order.value = channel.order;
    els.primary.value = channel.primaryUrl;
    els.backup.value = channel.backupUrl;
    els.logo.value = channel.logo;
    els.enabledField.checked = channel.enabled;
    els.featuredField.checked = channel.featured;
    els.editorHelp.textContent = 'Editing the selected remote channel.';
    els.editorHelp.className = 'help';
    render();
  }

  function editorData() {
    return {
      name: els.name.value.trim(),
      category: els.category.value.trim() || 'Other',
      order: Math.max(1, Math.trunc(Number(els.order.value) || 1)),
      primaryUrl: els.primary.value.trim(),
      backupUrl: els.backup.value.trim(),
      logo: els.logo.value.trim(),
      enabled: els.enabledField.checked,
      featured: els.featuredField.checked
    };
  }

  function validateChannel(channel) {
    if (!channel.name) return 'Channel name is required.';
    if (!isHttpUrl(channel.primaryUrl)) return 'Primary stream must be a valid http:// or https:// URL.';
    if (channel.backupUrl && !isHttpUrl(channel.backupUrl)) return 'Backup stream URL is not valid.';
    if (channel.logo && !isHttpUrl(channel.logo)) return 'Logo URL is not valid.';
    return '';
  }

  function saveChannel() {
    const data = editorData();
    const error = validateChannel(data);
    if (error) {
      els.editorHelp.textContent = error;
      els.editorHelp.className = 'help error';
      return;
    }
    if (state.selected) {
      Object.assign(state.selected, data);
    } else {
      state.channels.push(data);
      state.selected = data;
    }
    setDirty();
    sortChannels();
    els.editorHelp.textContent = 'Saved locally. Publish when you are ready to update SportNexa.';
    els.editorHelp.className = 'help';
    render();
  }

  function deleteSelected() {
    if (!state.selected) return;
    if (!confirm(`Delete “${state.selected.name}” from the remote database?`)) return;
    state.channels = state.channels.filter(c => c !== state.selected);
    setDirty();
    clearEditor();
  }

  function duplicateSelected() {
    if (!state.selected) return;
    const copy = { ...state.selected, name: `${state.selected.name} Copy`, order: Math.max(0, ...state.channels.map(c => c.order || 0)) + 1, featured: false };
    state.channels.push(copy);
    state.selected = copy;
    setDirty();
    selectChannel(copy);
  }

  function moveChannel(channel, direction) {
    sortChannels();
    const index = state.channels.indexOf(channel);
    const otherIndex = index + direction;
    if (index < 0 || otherIndex < 0 || otherIndex >= state.channels.length) return;
    const other = state.channels[otherIndex];
    const temp = channel.order;
    channel.order = other.order;
    other.order = temp;
    if (channel.order === other.order) {
      normalizeOrders();
      return;
    }
    setDirty(); render();
  }

  function normalizeOrders() {
    sortChannels();
    state.channels.forEach((c, i) => c.order = i + 1);
    setDirty(); render();
  }

  function validateDatabase() {
    if (!state.channels.length) return 'Database must contain at least one channel.';
    const seen = new Set();
    for (const c of state.channels) {
      const error = validateChannel(c);
      if (error) return `${c.name || 'Unnamed channel'}: ${error}`;
      if (seen.has(c.primaryUrl)) return `Duplicate primary URL found: ${c.primaryUrl}`;
      seen.add(c.primaryUrl);
    }
    return '';
  }

  async function loadLive() {
    if (state.dirty && !confirm('Discard your unsaved local edits and reload the live database?')) return;
    els.meta.textContent = 'Loading live database…';
    try {
      const response = await fetch(`${RAW_URL}?t=${Date.now()}`, { cache: 'no-store' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const db = await response.json();
      if (!Array.isArray(db.channels)) throw new Error('JSON does not contain a channels array');
      state.version = String(db.version ?? '1');
      state.updatedAt = String(db.updatedAt ?? db.updated_at ?? '');
      state.channels = db.channels.map(normalizeChannel);
      state.selected = null;
      setDirty(false);
      els.meta.textContent = `Live v${state.version}${state.updatedAt ? ` • ${state.updatedAt}` : ''}`;
      setPublishStatus('Live database loaded.', 'ok');
      clearEditor();
    } catch (error) {
      els.meta.textContent = 'Could not load live database';
      setPublishStatus(`Load failed: ${error.message}`, 'err');
    }
  }

  function exportJson() {
    const blob = new Blob([JSON.stringify(databaseObject(false), null, 2) + '\n'], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = 'channels.json'; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function importJson(file) {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const db = JSON.parse(String(reader.result));
        if (!Array.isArray(db.channels)) throw new Error('Missing channels array');
        state.version = String(db.version ?? state.version ?? '1');
        state.updatedAt = String(db.updatedAt ?? '');
        state.channels = db.channels.map(normalizeChannel);
        state.selected = null;
        setDirty();
        els.meta.textContent = `Imported v${state.version} • not published`;
        clearEditor();
        setPublishStatus('JSON imported locally. Review it before publishing.');
      } catch (error) {
        setPublishStatus(`Import failed: ${error.message}`, 'err');
      }
    };
    reader.readAsText(file);
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

  async function publish() {
    const token = els.token.value.trim();
    if (!token) {
      setPublishStatus('Enter a fine-grained GitHub token first.', 'err');
      return;
    }
    const dbError = validateDatabase();
    if (dbError) {
      setPublishStatus(`Cannot publish: ${dbError}`, 'err');
      return;
    }

    $('publishBtn').disabled = true;
    setPublishStatus('Checking current GitHub revision…');
    const headers = {
      'Accept': 'application/vnd.github+json',
      'Authorization': `Bearer ${token}`,
      'X-GitHub-Api-Version': '2022-11-28'
    };

    try {
      const currentResponse = await fetch(`${API_URL}?ref=${encodeURIComponent(BRANCH)}`, { headers, cache: 'no-store' });
      if (!currentResponse.ok) {
        const msg = await currentResponse.text();
        throw new Error(`GitHub read failed (${currentResponse.status}): ${msg.slice(0, 160)}`);
      }
      const current = await currentResponse.json();
      if (!current.sha) throw new Error('GitHub did not return the current file SHA.');

      const nextDb = databaseObject(true);
      const json = JSON.stringify(nextDb, null, 2) + '\n';
      setPublishStatus('Publishing remote database…');
      const updateResponse = await fetch(API_URL, {
        method: 'PUT',
        headers: { ...headers, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: `Update SportNexa remote channels v${nextDb.version}`,
          content: utf8ToBase64(json),
          sha: current.sha,
          branch: BRANCH
        })
      });
      if (!updateResponse.ok) {
        const msg = await updateResponse.text();
        throw new Error(`GitHub publish failed (${updateResponse.status}): ${msg.slice(0, 200)}`);
      }

      state.version = nextDb.version;
      state.updatedAt = nextDb.updatedAt;
      setDirty(false);
      els.meta.textContent = `Published v${state.version} • ${state.updatedAt}`;
      setPublishStatus(`Published successfully as database v${state.version}. SportNexa will receive it on refresh.`, 'ok');
      els.token.value = '';
    } catch (error) {
      setPublishStatus(error.message, 'err');
    } finally {
      $('publishBtn').disabled = false;
    }
  }

  $('reloadBtn').onclick = loadLive;
  $('addBtn').onclick = clearEditor;
  $('saveBtn').onclick = saveChannel;
  $('deleteBtn').onclick = deleteSelected;
  $('duplicateBtn').onclick = duplicateSelected;
  $('clearBtn').onclick = clearEditor;
  $('normalizeBtn').onclick = normalizeOrders;
  $('exportBtn').onclick = exportJson;
  $('importBtn').onclick = () => $('fileInput').click();
  $('fileInput').onchange = e => { importJson(e.target.files?.[0]); e.target.value = ''; };
  $('publishBtn').onclick = publish;
  els.search.oninput = render;
  els.filter.onchange = render;

  window.addEventListener('beforeunload', e => {
    if (!state.dirty) return;
    e.preventDefault();
    e.returnValue = '';
  });

  loadLive();
})();
