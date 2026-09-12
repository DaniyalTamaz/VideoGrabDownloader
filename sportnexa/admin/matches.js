(() => {
  'use strict';

  const OWNER = 'DaniyalTamaz';
  const REPO = 'VideoGrabDownloader';
  const BRANCH = 'main';
  const MATCH_PATH = 'sportnexa/matches.json';
  const CHANNEL_PATH = 'sportnexa/channels.json';
  const MATCH_RAW = `https://raw.githubusercontent.com/${OWNER}/${REPO}/${BRANCH}/${MATCH_PATH}`;
  const CHANNEL_RAW = `https://raw.githubusercontent.com/${OWNER}/${REPO}/${BRANCH}/${CHANNEL_PATH}`;
  const MATCH_API = `https://api.github.com/repos/${OWNER}/${REPO}/contents/${MATCH_PATH}`;

  const state = { version: '1', updatedAt: '', matches: [], channels: [], selected: null, dirty: false };
  const $ = id => document.getElementById(id);
  const els = {
    list: $('matchList'), search: $('searchInput'), filter: $('filterSelect'), meta: $('dbMeta'), count: $('visibleCount'),
    total: $('statTotal'), enabled: $('statEnabled'), live: $('statLive'), upcoming: $('statUpcoming'),
    home: $('homeField'), away: $('awayField'), sport: $('sportField'), league: $('leagueField'), start: $('startField'), end: $('endField'),
    status: $('statusField'), order: $('orderField'), channel: $('channelField'), homeLogo: $('homeLogoField'), awayLogo: $('awayLogoField'),
    enabledField: $('enabledField'), keepLive: $('keepLiveField'), title: $('editorTitle'), help: $('editorHelp'), token: $('tokenField'), publishStatus: $('publishStatus')
  };

  $('remotePath').textContent = `${OWNER}/${REPO}/${MATCH_PATH}`;

  function safe(value) { return typeof value === 'string' ? value.trim() : ''; }
  function isHttpUrl(value) {
    if (!value) return false;
    try { const u = new URL(value); return u.protocol === 'https:' || u.protocol === 'http:'; }
    catch (_) { return false; }
  }

  function effectiveStatus(match, now = Date.now()) {
    const forced = safe(match.status).toLowerCase();
    if (['live','upcoming','finished'].includes(forced)) return forced;
    const start = Date.parse(match.startTime || '');
    const end = Date.parse(match.endTime || '');
    if (!Number.isFinite(start) || now < start) return 'upcoming';
    if (Number.isFinite(end) && now >= end) return 'finished';
    return 'live';
  }

  function normalizeMatch(raw, i) {
    return {
      id: safe(raw?.id) || `match-${Date.now()}-${i}`,
      homeTeam: safe(raw?.homeTeam || raw?.team1),
      awayTeam: safe(raw?.awayTeam || raw?.team2),
      homeLogo: safe(raw?.homeLogo || raw?.team1Logo),
      awayLogo: safe(raw?.awayLogo || raw?.team2Logo),
      sport: safe(raw?.sport) || 'Sport',
      league: safe(raw?.league) || 'Other',
      startTime: safe(raw?.startTime || raw?.start),
      endTime: safe(raw?.endTime || raw?.end),
      status: ['auto','live','upcoming','finished'].includes(safe(raw?.status).toLowerCase()) ? safe(raw.status).toLowerCase() : 'auto',
      channelName: safe(raw?.channelName),
      channelUrl: safe(raw?.channelUrl || raw?.streamUrl),
      enabled: raw?.enabled !== false,
      order: Number.isFinite(Number(raw?.order)) ? Math.max(1, Math.trunc(Number(raw.order))) : i + 1
    };
  }

  function sortMatches() { state.matches.sort((a,b) => (a.order-b.order) || Date.parse(a.startTime)-Date.parse(b.startTime)); }
  function setDirty(v=true) { state.dirty = v; document.title = v ? '• SportNexa Matches' : 'SportNexa Matches'; }
  function setStatus(message, type='') { els.publishStatus.textContent = message; els.publishStatus.className = `status ${type}`.trim(); }
  function badge(text, cls='') { const s=document.createElement('span'); s.className=`badge ${cls}`.trim(); s.textContent=text; return s; }

  function updateStats() {
    const enabled = state.matches.filter(m => m.enabled);
    els.total.textContent = state.matches.length;
    els.enabled.textContent = enabled.length;
    els.live.textContent = enabled.filter(m => effectiveStatus(m)==='live').length;
    els.upcoming.textContent = enabled.filter(m => effectiveStatus(m)==='upcoming').length;
  }

  function formatTime(iso) {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? 'Invalid time' : d.toLocaleString([], {dateStyle:'medium', timeStyle:'short'});
  }

  function render() {
    sortMatches(); updateStats();
    const q = els.search.value.trim().toLowerCase();
    const filter = els.filter.value;
    const shown = state.matches.filter(m => {
      const status = effectiveStatus(m);
      if (filter === 'disabled' && m.enabled) return false;
      if (filter !== 'all' && filter !== 'disabled' && (!m.enabled || status !== filter)) return false;
      if (!q) return true;
      return `${m.homeTeam} ${m.awayTeam} ${m.league} ${m.sport} ${m.channelName}`.toLowerCase().includes(q);
    });

    els.list.replaceChildren(); els.count.textContent = `${shown.length} shown`;
    if (!shown.length) { const e=document.createElement('div'); e.className='empty'; e.textContent='No matches match this filter.'; els.list.appendChild(e); return; }

    for (const match of shown) {
      const row=document.createElement('div'); row.className=`channel${match.enabled?'':' disabled'}${state.selected===match?' selected':''}`;
      const logo=document.createElement('div'); logo.className='logo'; logo.textContent=(match.sport||'M').charAt(0).toUpperCase();
      const meta=document.createElement('div'); meta.className='meta';
      const name=document.createElement('div'); name.className='name'; name.textContent=`${match.homeTeam} vs ${match.awayTeam}`;
      const sub=document.createElement('div'); sub.className='sub'; sub.textContent=`#${match.order} • ${match.league} • ${formatTime(match.startTime)}${match.channelName ? ` • ${match.channelName}` : ''}`;
      const badges=document.createElement('div'); badges.className='badges';
      const status=effectiveStatus(match);
      badges.appendChild(badge(match.enabled?'Enabled':'Disabled', match.enabled?'on':'off'));
      badges.appendChild(badge(status.toUpperCase(), status==='live'?'featured':(status==='finished'?'off':'on')));
      if (match.status!=='auto') badges.appendChild(badge(`Forced ${match.status}`));
      meta.append(name,sub,badges);
      const actions=document.createElement('div'); actions.className='row-actions';
      const edit=document.createElement('button'); edit.className='icon-btn'; edit.textContent='✎'; edit.title='Edit'; edit.onclick=e=>{e.stopPropagation();selectMatch(match)};
      const up=document.createElement('button'); up.className='icon-btn'; up.textContent='↑'; up.onclick=e=>{e.stopPropagation();moveMatch(match,-1)};
      const down=document.createElement('button'); down.className='icon-btn'; down.textContent='↓'; down.onclick=e=>{e.stopPropagation();moveMatch(match,1)};
      actions.append(edit,up,down); row.append(logo,meta,actions); row.onclick=()=>selectMatch(match); els.list.appendChild(row);
    }
  }

  function toLocalInput(iso) {
    const d = new Date(iso); if (Number.isNaN(d.getTime())) return '';
    const local = new Date(d.getTime() - d.getTimezoneOffset()*60000);
    return local.toISOString().slice(0,16);
  }
  function fromLocalInput(value) { if (!value) return ''; const d=new Date(value); return Number.isNaN(d.getTime()) ? '' : d.toISOString(); }

  function refreshChannelOptions(selectedUrl='') {
    els.channel.replaceChildren();
    const none=document.createElement('option'); none.value=''; none.textContent='No linked channel'; els.channel.appendChild(none);
    for (const c of state.channels.filter(c=>c.enabled!==false)) {
      const opt=document.createElement('option'); opt.value=safe(c.primaryUrl||c.url); opt.textContent=safe(c.name)||'Unnamed channel'; opt.dataset.name=safe(c.name); els.channel.appendChild(opt);
    }
    if (selectedUrl && ![...els.channel.options].some(o=>o.value===selectedUrl)) {
      const opt=document.createElement('option'); opt.value=selectedUrl; opt.textContent='Saved channel (not in current channel DB)'; els.channel.appendChild(opt);
    }
    els.channel.value=selectedUrl||'';
  }

  function clearEditor() {
    state.selected=null; els.title.textContent='Add Match'; els.home.value=''; els.away.value=''; els.sport.value='Football'; els.league.value='';
    const now=new Date(); now.setMinutes(now.getMinutes()-now.getTimezoneOffset()+60); els.start.value=now.toISOString().slice(0,16);
    const end=new Date(now.getTime()+2*60*60*1000); els.end.value=end.toISOString().slice(0,16);
    els.status.value='auto'; els.order.value=String(Math.max(0,...state.matches.map(m=>m.order||0))+1); refreshChannelOptions('');
    els.homeLogo.value=''; els.awayLogo.value=''; els.enabledField.checked=true; els.keepLive.checked=false;
    els.help.textContent='Times use your browser local timezone and are published as ISO timestamps. Link only authorized channels.'; els.help.className='help'; render();
  }

  function selectMatch(m) {
    state.selected=m; els.title.textContent=`Edit: ${m.homeTeam} vs ${m.awayTeam}`; els.home.value=m.homeTeam; els.away.value=m.awayTeam;
    els.sport.value=m.sport; els.league.value=m.league; els.start.value=toLocalInput(m.startTime); els.end.value=toLocalInput(m.endTime); els.status.value=m.status;
    els.order.value=m.order; refreshChannelOptions(m.channelUrl); els.homeLogo.value=m.homeLogo; els.awayLogo.value=m.awayLogo; els.enabledField.checked=m.enabled; els.keepLive.checked=m.status==='live'; render();
  }

  function editorData() {
    const selectedOption=els.channel.options[els.channel.selectedIndex];
    const status=els.keepLive.checked ? 'live' : els.status.value;
    return {
      id: state.selected?.id || `match-${Date.now()}`,
      homeTeam: els.home.value.trim(), awayTeam: els.away.value.trim(), homeLogo: els.homeLogo.value.trim(), awayLogo: els.awayLogo.value.trim(),
      sport: els.sport.value.trim()||'Sport', league: els.league.value.trim()||'Other', startTime: fromLocalInput(els.start.value), endTime: fromLocalInput(els.end.value),
      status, channelName: selectedOption?.value ? selectedOption.textContent : '', channelUrl: els.channel.value, enabled: els.enabledField.checked,
      order: Math.max(1,Math.trunc(Number(els.order.value)||1))
    };
  }

  function validateMatch(m) {
    if (!m.homeTeam || !m.awayTeam) return 'Both team names are required.';
    const start=Date.parse(m.startTime); if (!Number.isFinite(start)) return 'A valid start time is required.';
    const end=Date.parse(m.endTime); if (m.endTime && !Number.isFinite(end)) return 'End time is invalid.';
    if (Number.isFinite(end) && end<=start) return 'End time must be after start time.';
    if (m.channelUrl && !isHttpUrl(m.channelUrl)) return 'Linked channel URL is invalid.';
    if (m.homeLogo && !isHttpUrl(m.homeLogo)) return 'Home logo URL is invalid.';
    if (m.awayLogo && !isHttpUrl(m.awayLogo)) return 'Away logo URL is invalid.';
    return '';
  }

  function saveMatch() {
    const data=editorData(); const error=validateMatch(data); if (error){els.help.textContent=error;els.help.className='help error';return;}
    if (state.selected) Object.assign(state.selected,data); else {state.matches.push(data); state.selected=data;}
    setDirty(); els.help.textContent='Saved locally. Publish Matches when ready.'; els.help.className='help'; render();
  }
  function deleteSelected(){if(!state.selected)return;if(!confirm(`Delete “${state.selected.homeTeam} vs ${state.selected.awayTeam}”?`))return;state.matches=state.matches.filter(m=>m!==state.selected);setDirty();clearEditor();}
  function duplicateSelected(){if(!state.selected)return;const copy={...state.selected,id:`match-${Date.now()}`,order:Math.max(0,...state.matches.map(m=>m.order||0))+1,status:'auto'};state.matches.push(copy);state.selected=copy;setDirty();selectMatch(copy);}
  function moveMatch(m,d){sortMatches();const i=state.matches.indexOf(m),j=i+d;if(i<0||j<0||j>=state.matches.length)return;const other=state.matches[j];const t=m.order;m.order=other.order;other.order=t;if(m.order===other.order){normalizeOrders();return;}setDirty();render();}
  function normalizeOrders(){sortMatches();state.matches.forEach((m,i)=>m.order=i+1);setDirty();render();}

  function databaseObject(increment=false){const current=parseInt(String(state.version),10);return{version:increment&&Number.isFinite(current)?String(current+1):String(state.version||'1'),updatedAt:increment?new Date().toISOString():(state.updatedAt||new Date().toISOString()),matches:state.matches.map(m=>({...m,order:Math.max(1,Math.trunc(Number(m.order)||1))}))};}

  async function loadChannels(){try{const r=await fetch(`${CHANNEL_RAW}?t=${Date.now()}`,{cache:'no-store'});if(r.ok){const db=await r.json();state.channels=Array.isArray(db.channels)?db.channels:[];refreshChannelOptions(state.selected?.channelUrl||'');}}catch(_){} }
  async function loadLive(){if(state.dirty&&!confirm('Discard local match edits and reload live schedule?'))return;els.meta.textContent='Loading live match database…';try{const r=await fetch(`${MATCH_RAW}?t=${Date.now()}`,{cache:'no-store'});if(!r.ok)throw new Error(`HTTP ${r.status}`);const db=await r.json();if(!Array.isArray(db.matches))throw new Error('Missing matches array');state.version=String(db.version??'1');state.updatedAt=String(db.updatedAt??'');state.matches=db.matches.map(normalizeMatch);state.selected=null;setDirty(false);els.meta.textContent=`Live v${state.version}${state.updatedAt?` • ${state.updatedAt}`:''}`;setStatus('Live match database loaded.','ok');await loadChannels();clearEditor();}catch(e){els.meta.textContent='Could not load match database';setStatus(`Load failed: ${e.message}`,'err');}}

  function exportJson(){const blob=new Blob([JSON.stringify(databaseObject(false),null,2)+'\n'],{type:'application/json'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='matches.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
  function utf8ToBase64(text){const bytes=new TextEncoder().encode(text);let binary='';for(let i=0;i<bytes.length;i+=0x8000)binary+=String.fromCharCode(...bytes.subarray(i,i+0x8000));return btoa(binary);}

  async function publish(){const token=els.token.value.trim();if(!token){setStatus('Enter the restricted GitHub token first.','err');return;}for(const m of state.matches){const error=validateMatch(m);if(error){setStatus(`${m.homeTeam||'Match'}: ${error}`,'err');return;}}
    $('publishBtn').disabled=true;setStatus('Checking current GitHub revision…');const headers={'Accept':'application/vnd.github+json','Authorization':`Bearer ${token}`,'X-GitHub-Api-Version':'2022-11-28'};
    try{const currentResponse=await fetch(`${MATCH_API}?ref=${encodeURIComponent(BRANCH)}`,{headers,cache:'no-store'});if(!currentResponse.ok)throw new Error(`GitHub read failed (${currentResponse.status})`);const current=await currentResponse.json();if(!current.sha)throw new Error('GitHub did not return file SHA.');const next=databaseObject(true);const json=JSON.stringify(next,null,2)+'\n';setStatus('Publishing match schedule…');const update=await fetch(MATCH_API,{method:'PUT',headers:{...headers,'Content-Type':'application/json'},body:JSON.stringify({message:`Update SportNexa matches v${next.version}`,content:utf8ToBase64(json),sha:current.sha,branch:BRANCH})});if(!update.ok){const msg=await update.text();throw new Error(`GitHub publish failed (${update.status}): ${msg.slice(0,160)}`);}state.version=next.version;state.updatedAt=next.updatedAt;setDirty(false);els.meta.textContent=`Published v${state.version} • ${state.updatedAt}`;setStatus(`Published successfully as match DB v${state.version}. SportNexa will receive it on Home refresh.`,'ok');els.token.value='';}catch(e){setStatus(e.message,'err');}finally{$('publishBtn').disabled=false;}}

  $('reloadBtn').onclick=loadLive; $('addBtn').onclick=clearEditor; $('saveBtn').onclick=saveMatch; $('deleteBtn').onclick=deleteSelected; $('duplicateBtn').onclick=duplicateSelected; $('clearBtn').onclick=clearEditor; $('normalizeBtn').onclick=normalizeOrders; $('exportBtn').onclick=exportJson; $('publishBtn').onclick=publish; els.search.oninput=render; els.filter.onchange=render; els.keepLive.onchange=()=>{if(els.keepLive.checked)els.status.value='live';};
  window.addEventListener('beforeunload',e=>{if(!state.dirty)return;e.preventDefault();e.returnValue='';});
  loadLive();
})();
