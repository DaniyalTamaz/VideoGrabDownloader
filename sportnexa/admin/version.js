(() => {
  'use strict';

  const OWNER = 'DaniyalTamaz';
  const REPO = 'VideoGrabDownloader';
  const BRANCH = 'main';
  const JSON_PATH = 'sportnexa/app-config.json';
  const RAW_URL = `https://raw.githubusercontent.com/${OWNER}/${REPO}/${BRANCH}/${JSON_PATH}`;
  const API_URL = `https://api.github.com/repos/${OWNER}/${REPO}/contents/${JSON_PATH}`;

  const $ = id => document.getElementById(id);
  const els = {
    code: $('versionCodeField'), name: $('versionNameField'), url: $('updateUrlField'), message: $('messageField'),
    force: $('forceField'), enabled: $('enabledField'), token: $('tokenField'), publish: $('publishBtn'),
    publishStatus: $('publishStatus'), metaStatus: $('metaStatus'), remotePath: $('remotePath'),
    statCode: $('statCode'), statName: $('statName'), statPolicy: $('statPolicy'), statManifest: $('statManifest'),
    editorHelp: $('editorHelp')
  };

  let manifestVersion = '1';
  let updatedAt = '';

  els.remotePath.textContent = `${OWNER}/${REPO}/${JSON_PATH}`;

  function isHttpUrl(value) {
    if (!value) return false;
    try {
      const u = new URL(value);
      return u.protocol === 'https:' || u.protocol === 'http:';
    } catch (_) { return false; }
  }

  function setStatus(message, type = '') {
    els.publishStatus.textContent = message;
    els.publishStatus.className = `status ${type}`.trim();
  }

  function renderStats() {
    const enabled = els.enabled.checked;
    const code = Math.max(0, Math.trunc(Number(els.code.value) || 0));
    els.statCode.textContent = enabled ? String(code || '--') : 'OFF';
    els.statName.textContent = els.name.value.trim() || '--';
    els.statPolicy.textContent = enabled ? (els.force.checked ? 'REQUIRED' : 'RECOMMENDED') : 'DISABLED';
    els.statManifest.textContent = `v${manifestVersion}`;
  }

  function validate() {
    if (!els.enabled.checked) return '';
    const code = Math.trunc(Number(els.code.value));
    if (!Number.isFinite(code) || code < 1) return 'Latest version code must be 1 or higher.';
    if (!els.name.value.trim()) return 'Latest version name is required.';
    const url = els.url.value.trim();
    if (url && !isHttpUrl(url)) return 'Update URL must be a valid http:// or https:// URL.';
    if (els.force.checked && !url) return 'Force Update requires a working update URL.';
    return '';
  }

  function buildManifest(incrementVersion) {
    const current = Number.parseInt(String(manifestVersion), 10);
    const nextVersion = incrementVersion && Number.isFinite(current) ? String(current + 1) : String(manifestVersion || '1');
    const enabled = els.enabled.checked;
    return {
      version: nextVersion,
      updatedAt: incrementVersion ? new Date().toISOString() : (updatedAt || new Date().toISOString()),
      latestVersionCode: enabled ? Math.max(1, Math.trunc(Number(els.code.value) || 1)) : 0,
      latestVersionName: els.name.value.trim(),
      forceUpdate: enabled && els.force.checked,
      message: els.message.value.trim() || 'A newer SportNexa version is available.',
      updateUrl: enabled ? els.url.value.trim() : ''
    };
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

  async function loadLive() {
    els.metaStatus.textContent = 'Loading live manifest…';
    try {
      const response = await fetch(`${RAW_URL}?t=${Date.now()}`, { cache: 'no-store' });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const data = await response.json();
      manifestVersion = String(data.version ?? '1');
      updatedAt = String(data.updatedAt ?? '');
      const code = Math.max(0, Math.trunc(Number(data.latestVersionCode) || 0));
      els.code.value = code > 0 ? String(code) : '';
      els.name.value = String(data.latestVersionName ?? '');
      els.force.checked = data.forceUpdate === true;
      els.message.value = String(data.message ?? '');
      els.url.value = String(data.updateUrl ?? '');
      els.enabled.checked = code > 0;
      els.metaStatus.textContent = `Live manifest v${manifestVersion}${updatedAt ? ` • ${updatedAt}` : ''}`;
      setStatus('Live App Control loaded.', 'ok');
      renderStats();
    } catch (error) {
      els.metaStatus.textContent = 'Could not load remote app manifest.';
      setStatus(`Load failed: ${error.message}`, 'err');
    }
  }

  async function publish() {
    const token = els.token.value.trim();
    if (!token) {
      setStatus('Enter your restricted fine-grained GitHub token first.', 'err');
      return;
    }
    const error = validate();
    if (error) {
      els.editorHelp.textContent = error;
      els.editorHelp.className = 'help error';
      setStatus(`Cannot publish: ${error}`, 'err');
      return;
    }

    els.publish.disabled = true;
    setStatus('Checking current GitHub revision…');
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

      const next = buildManifest(true);
      const json = JSON.stringify(next, null, 2) + '\n';
      setStatus('Publishing App Control…');
      const updateResponse = await fetch(API_URL, {
        method: 'PUT',
        headers: { ...headers, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: `Update SportNexa app control manifest v${next.version}`,
          content: utf8ToBase64(json),
          sha: current.sha,
          branch: BRANCH
        })
      });
      if (!updateResponse.ok) {
        const msg = await updateResponse.text();
        throw new Error(`GitHub publish failed (${updateResponse.status}): ${msg.slice(0, 200)}`);
      }

      manifestVersion = next.version;
      updatedAt = next.updatedAt;
      els.token.value = '';
      els.editorHelp.textContent = 'App Control is published. Installed SportNexa apps will use it on their next version check.';
      els.editorHelp.className = 'help';
      els.metaStatus.textContent = `Published manifest v${manifestVersion} • ${updatedAt}`;
      setStatus('App Control published successfully.', 'ok');
      renderStats();
    } catch (error) {
      setStatus(error.message, 'err');
    } finally {
      els.publish.disabled = false;
    }
  }

  $('reloadBtn').onclick = loadLive;
  els.publish.onclick = publish;
  [els.code, els.name, els.url, els.message].forEach(el => el.addEventListener('input', renderStats));
  [els.force, els.enabled].forEach(el => el.addEventListener('change', renderStats));

  loadLive();
})();
