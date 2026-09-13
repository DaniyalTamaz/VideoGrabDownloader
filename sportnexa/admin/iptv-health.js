(() => {
  'use strict';

  const OWNER = 'DaniyalTamaz';
  const REPO = 'VideoGrabDownloader';
  const BRANCH = 'main';
  const QUEUE_PATH = 'sportnexa/health-queue.json';
  const RESULTS_PATH = 'sportnexa/health-results.json';
  const API_QUEUE = `https://api.github.com/repos/${OWNER}/${REPO}/contents/${QUEUE_PATH}`;
  const RAW_RESULTS = `https://raw.githubusercontent.com/${OWNER}/${REPO}/${BRANCH}/${RESULTS_PATH}`;
  const MAX_TEST = 25;
  const POLL_MS = 5000;
  const POLL_LIMIT = 36;

  const $ = id => document.getElementById(id);
  const el = {
    list: $('candidateList'),
    token: $('tokenField'),
    workingOnly: $('workingOnly'),
    testSelected: $('testSelectedBtn'),
    testVisible: $('testVisibleBtn'),
    reloadHealth: $('reloadHealthBtn'),
    healthStatus: $('healthStatus'),
    healthSummary: $('healthSummary')
  };

  const state = {
    results: new Map(),
    queueId: '',
    testedAt: '',
    polling: false,
    applyTimer: null
  };

  const s = v => typeof v === 'string' ? v.trim() : '';
  const isHttp = v => {
    try { const u = new URL(v); return u.protocol === 'http:' || u.protocol === 'https:'; }
    catch (_) { return false; }
  };

  function setHealthStatus(text, cls = '') {
    if (!el.healthStatus) return;
    el.healthStatus.textContent = text;
    el.healthStatus.className = `status ${cls}`.trim();
  }

  function b64encode(text) {
    const bytes = new TextEncoder().encode(text);
    let bin = '';
    for (let i = 0; i < bytes.length; i += 0x8000) {
      bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
    }
    return btoa(bin);
  }

  function badgeText(r) {
    const code = r && r.httpStatus ? ` ${r.httpStatus}` : '';
    const fmt = r && r.format ? ` • ${r.format}` : '';
    const ms = r && Number.isFinite(Number(r.latencyMs)) ? ` • ${Number(r.latencyMs)}ms` : '';
    if (!r) return 'Not tested';
    switch (r.status) {
      case 'working': return `✓ Working${code}${fmt}${ms}`;
      case 'restricted': return `Restricted${code}${ms}`;
      case 'dead': return `Dead${code}${ms}`;
      case 'timeout': return `Timeout${ms}`;
      case 'http_ok': return `HTTP OK${code} • Not manifest${ms}`;
      case 'invalid': return 'Invalid URL';
      case 'http_error': return `HTTP error${code}${ms}`;
      default: return `Error${code}${ms}`;
    }
  }

  function badgeClass(r) {
    if (!r) return 'health-unknown';
    if (r.status === 'working') return 'health-working';
    if (r.status === 'restricted') return 'health-restricted';
    if (r.status === 'dead' || r.status === 'http_error' || r.status === 'invalid') return 'health-dead';
    if (r.status === 'timeout') return 'health-timeout';
    return 'health-unknown';
  }

  function rowUrl(row) {
    const node = row.querySelector('.candidate-url');
    const value = node ? s(node.textContent) : '';
    return isHttp(value) ? value : '';
  }

  function rowName(row) {
    const node = row.querySelector('.candidate-name');
    return node ? s(node.textContent) : '';
  }

  function visibleRows() {
    return [...document.querySelectorAll('#candidateList .candidate')]
      .filter(row => row.style.display !== 'none');
  }

  function allStreamRows() {
    return [...document.querySelectorAll('#candidateList .candidate')]
      .map(row => ({ row, url: rowUrl(row), name: rowName(row) }))
      .filter(x => x.url);
  }

  function addHealthToRow(row, url, name) {
    const badges = row.querySelector('.candidate-badges');
    if (badges) {
      let healthBadge = badges.querySelector('.health-badge');
      if (!healthBadge) {
        healthBadge = document.createElement('span');
        healthBadge.className = 'badge health-badge health-unknown';
        badges.appendChild(healthBadge);
      }
      const result = state.results.get(url);
      healthBadge.textContent = badgeText(result);
      healthBadge.className = `badge health-badge ${badgeClass(result)}`;
      healthBadge.title = result && result.message ? result.message : 'No server-side health test result yet';
    }

    const actions = row.querySelector('.candidate-actions');
    if (actions && !actions.querySelector('.health-test-btn')) {
      const button = document.createElement('button');
      button.className = 'small-btn health-test-btn';
      button.textContent = 'Test Stream';
      button.type = 'button';
      button.onclick = event => {
        event.preventDefault();
        event.stopPropagation();
        queueHealth([{ url, name }]);
      };
      actions.prepend(button);
    }
  }

  function applyHealthToRows() {
    const workingOnly = !!(el.workingOnly && el.workingOnly.checked);
    let workingVisible = 0;
    let testedVisible = 0;
    let total = 0;

    document.querySelectorAll('#candidateList .candidate').forEach(row => {
      const url = rowUrl(row);
      if (!url) {
        if (workingOnly) row.style.display = 'none';
        else row.style.display = '';
        return;
      }
      total++;
      const name = rowName(row);
      addHealthToRow(row, url, name);
      const result = state.results.get(url);
      if (result) testedVisible++;
      if (result && result.status === 'working') workingVisible++;
      row.style.display = workingOnly && (!result || result.status !== 'working') ? 'none' : '';
    });

    if (el.healthSummary) {
      const age = state.testedAt ? ` • last results ${new Date(state.testedAt).toLocaleString()}` : '';
      el.healthSummary.textContent = `${workingVisible} working • ${testedVisible} tested in current list • ${total} stream rows${age}`;
    }
  }

  function scheduleApply() {
    clearTimeout(state.applyTimer);
    state.applyTimer = setTimeout(applyHealthToRows, 80);
  }

  async function fetchHealthResults() {
    const r = await fetch(`${RAW_RESULTS}?t=${Date.now()}`, { cache: 'no-store' });
    if (!r.ok) throw new Error(`Health results HTTP ${r.status}`);
    const data = await r.json();
    const results = Array.isArray(data.results) ? data.results : [];
    state.results = new Map(results.filter(x => isHttp(x.url)).map(x => [s(x.url), x]));
    state.queueId = s(data.queueId);
    state.testedAt = s(data.testedAt);
    scheduleApply();
    return data;
  }

  async function reloadHealth(silent = false) {
    try {
      const data = await fetchHealthResults();
      if (!silent) {
        const counts = data.counts || {};
        setHealthStatus(`Health results loaded: ${counts.working || 0} working of ${(data.results || []).length} tested.`, 'ok');
      }
    } catch (err) {
      if (!silent) setHealthStatus(`Could not load health results: ${err.message || err}`, 'err');
    }
  }

  function selectedRows() {
    return allStreamRows()
      .filter(x => {
        const checkbox = x.row.querySelector('input[type="checkbox"]');
        return checkbox && checkbox.checked;
      })
      .map(x => ({ url: x.url, name: x.name }));
  }

  function visibleStreamRows() {
    return visibleRows()
      .map(row => ({ url: rowUrl(row), name: rowName(row) }))
      .filter(x => x.url);
  }

  function uniqueStreams(streams) {
    const seen = new Set();
    const out = [];
    for (const item of streams) {
      const url = s(item.url);
      if (!isHttp(url) || seen.has(url)) continue;
      seen.add(url);
      out.push({ url, name: s(item.name) });
      if (out.length >= MAX_TEST) break;
    }
    return out;
  }

  async function queueHealth(streams) {
    const items = uniqueStreams(streams);
    if (!items.length) return setHealthStatus('Choose at least one stream to test.', 'err');
    const token = el.token ? el.token.value.trim() : '';
    if (!token) return setHealthStatus('Enter your restricted GitHub token in the token box first.', 'err');

    const queueId = `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;
    const payload = {
      version: '1',
      queueId,
      createdAt: new Date().toISOString(),
      streams: items
    };
    const headers = {
      Accept: 'application/vnd.github+json',
      Authorization: `Bearer ${token}`,
      'X-GitHub-Api-Version': '2022-11-28'
    };

    try {
      if (el.testSelected) el.testSelected.disabled = true;
      if (el.testVisible) el.testVisible.disabled = true;
      setHealthStatus(`Queueing ${items.length} stream test${items.length === 1 ? '' : 's'}…`);
      const currentResponse = await fetch(`${API_QUEUE}?ref=${BRANCH}`, { headers, cache: 'no-store' });
      if (!currentResponse.ok) throw new Error(`GitHub queue read failed (${currentResponse.status})`);
      const current = await currentResponse.json();
      const body = {
        message: `Queue ${items.length} SportNexa stream health test${items.length === 1 ? '' : 's'}`,
        content: b64encode(JSON.stringify(payload, null, 2) + '\n'),
        sha: current.sha,
        branch: BRANCH
      };
      const put = await fetch(API_QUEUE, {
        method: 'PUT',
        headers: { ...headers, 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
      });
      if (!put.ok) throw new Error(`GitHub queue publish failed (${put.status}): ${(await put.text()).slice(0, 160)}`);
      setHealthStatus(`Queued ${items.length} stream${items.length === 1 ? '' : 's'}. GitHub is testing them now…`);
      pollForQueue(queueId, items.length);
    } catch (err) {
      setHealthStatus(err.message || String(err), 'err');
      if (el.testSelected) el.testSelected.disabled = false;
      if (el.testVisible) el.testVisible.disabled = false;
    }
  }

  async function pollForQueue(queueId, count) {
    if (state.polling) return;
    state.polling = true;
    try {
      for (let attempt = 1; attempt <= POLL_LIMIT; attempt++) {
        await new Promise(resolve => setTimeout(resolve, POLL_MS));
        try {
          const data = await fetchHealthResults();
          if (s(data.queueId) !== queueId) {
            setHealthStatus(`Testing ${count} stream${count === 1 ? '' : 's'}… waiting for GitHub (${attempt * 5}s)`);
            continue;
          }
          const counts = data.counts || {};
          const summary = [
            `${counts.working || 0} working`,
            `${counts.restricted || 0} restricted`,
            `${counts.dead || 0} dead`,
            `${counts.timeout || 0} timeout`
          ].join(' • ');
          setHealthStatus(`Test complete: ${summary}.`, counts.working ? 'ok' : 'err');
          return;
        } catch (_) {
          setHealthStatus(`Testing ${count} stream${count === 1 ? '' : 's'}… waiting for results (${attempt * 5}s)`);
        }
      }
      setHealthStatus('Health test is taking longer than expected. Click Reload Health in a moment.', 'err');
    } finally {
      state.polling = false;
      if (el.testSelected) el.testSelected.disabled = false;
      if (el.testVisible) el.testVisible.disabled = false;
    }
  }

  if (el.workingOnly) el.workingOnly.addEventListener('change', scheduleApply);
  if (el.testSelected) el.testSelected.onclick = () => queueHealth(selectedRows());
  if (el.testVisible) el.testVisible.onclick = () => queueHealth(visibleStreamRows());
  if (el.reloadHealth) el.reloadHealth.onclick = () => reloadHealth(false);

  if (el.list) {
    new MutationObserver(scheduleApply).observe(el.list, { childList: true, subtree: true });
  }

  reloadHealth(true);
  scheduleApply();
})();
