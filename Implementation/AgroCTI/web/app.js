// Agro-CTI dashboard: polls the Java server and draws the farm network, incidents and charts.
'use strict';

const $ = (id) => document.getElementById(id);
const SEV = ['NORMAL', 'INFO', 'SUSPECT', 'ALERT', 'CRITICAL'];
let selected = parseInt((location.hash.match(/node=(\d+)/) || [0, 0])[1], 10) || 0;   // e.g. #node=5
let state = null;

// api() and el() come from common.js; a 401 there sends the user to the login page.
const getJson = (url, opts) => api(url, opts);

const pct = (v, d = 1) => (100 * v).toFixed(d) + '%';

// ------------------------------------------------------------------ farm network
function buildRegions(nodes) {
  const box = $('regions');
  box.textContent = '';
  const regions = [...new Set(nodes.map((n) => n.region))];
  regions.forEach((r) => {
    const card = el('div', 'region');
    card.dataset.region = r;
    const h = el('h3', null, r);
    h.appendChild(el('small', null, ''));
    card.appendChild(h);
    const tiles = el('div', 'tiles');
    nodes.filter((n) => n.region === r).forEach((n) => {
      const t = el('button', 'tile');
      t.type = 'button';
      t.id = 'tile' + n.id;
      t.appendChild(el('b', null, n.name));
      t.appendChild(el('span', null, ''));
      t.addEventListener('click', () => { selected = n.id; refreshTiles(); loadSeries(); });
      tiles.appendChild(t);
    });
    card.appendChild(tiles);
    box.appendChild(card);
  });
}

function refreshTiles() {
  if (!state) return;
  const alarmsByRegion = {};
  state.nodes.forEach((n) => {
    const t = $('tile' + n.id);
    t.className = 'tile ' + n.severity + (n.id === selected ? ' selected' : '');
    const label = n.severity === 'NORMAL' ? 'score ' + n.score.toFixed(2)
      : n.severity === 'INFO' ? 'explained by context' : n.guess || n.severity.toLowerCase();
    t.querySelector('span').textContent = label;
    if (n.severity === 'ALERT' || n.severity === 'CRITICAL' || n.severity === 'SUSPECT') {
      alarmsByRegion[n.region] = (alarmsByRegion[n.region] || 0) + 1;
    }
  });
  document.querySelectorAll('.region').forEach((c) => {
    const k = alarmsByRegion[c.dataset.region] || 0;
    c.classList.toggle('campaign', k >= 3);
    c.querySelector('small').textContent = k ? k + ' flagged' : 'all clear';
  });
}

// ------------------------------------------------------------------ state + controls
async function loadState() {
  try {
    state = await getJson('/api/state');
  } catch (e) {
    $('progressText').textContent = 'Server not reachable - retrying';
    return;
  }
  if (!$('tile0')) buildRegions(state.nodes);
  refreshTiles();
  $('simTime').textContent = state.time;
  $('progressBar').style.width = pct(state.progress, 2);
  const day = Math.min(14, Math.floor(state.progress * 14) + 1);
  $('progressText').textContent = (state.paused ? 'Paused - ' : '') + 'test day ' + day + ' of 14 (' + pct(state.progress, 0) + ')';
  $('btnPause').textContent = state.paused ? 'Resume' : 'Pause';
  $('speed').textContent = (1000 / state.delayMs * 0.25).toFixed(1) + ' h per s';
  const alarms = state.nodes.filter((n) => n.severity === 'ALERT' || n.severity === 'CRITICAL').length;
  $('kAlarms').textContent = alarms;
  const raised = state.tp + state.fp, attacks = state.tp + state.fn;
  $('kPrecision').textContent = raised ? pct(state.tp / raised, 0) : '--';
  $('kRecall').textContent = attacks ? pct(state.tp / attacks, 0) : '--';
}

async function control(action) {
  try {
    state = await getJson('/api/control?action=' + action, { method: 'POST' });
  } catch (e) { /* ignored, next poll shows the state */ }
  loadState();
}
$('btnPause').addEventListener('click', () => control(state && state.paused ? 'resume' : 'pause'));
$('btnFaster').addEventListener('click', () => control('faster'));
$('btnSlower').addEventListener('click', () => control('slower'));

// ------------------------------------------------------------------ incidents
async function loadIncidents() {
  let list;
  try { list = await getJson('/api/incidents?limit=60'); } catch (e) { return; }
  const ol = $('incidentList');
  ol.textContent = '';
  if (!list.length) ol.appendChild(el('li', 'empty', 'No incidents yet.'));
  list.forEach((a) => {
    const li = el('li');
    const top = el('div', 'inc-top');
    top.appendChild(el('span', 'pill ' + a.severity, a.severity));
    top.appendChild(el('span', 'inc-node', a.nodeName));
    top.appendChild(el('span', 'muted', a.region));
    top.appendChild(el('span', 'inc-time', a.time.slice(5, 16) + ' · ' + a.durationMin + ' min'));
    li.appendChild(top);
    li.appendChild(el('div', 'inc-guess', a.guess));
    li.appendChild(el('div', 'inc-reason', a.reasons.length > 150 ? a.reasons.slice(0, 150) + '…' : a.reasons));
    const real = !a.truth.startsWith('no attack');
    const tags = el('div', 'inc-tags');
    tags.appendChild(el('span', 'status ' + a.status, a.status.replace('_', ' ').toLowerCase()));
    tags.appendChild(el('span', 'truth-tag ' + (real ? 'real' : 'false'), 'Ground truth: ' + a.truth));
    li.appendChild(tags);
    li.addEventListener('click', () => { selected = a.node; refreshTiles(); loadSeries(); });
    ol.appendChild(li);
  });
  $('kIncidents').textContent = list.length >= 60 ? '60+' : list.length;
  $('incCount').textContent = 'newest first';
}

// ------------------------------------------------------------------ chart
async function loadSeries() {
  let pts;
  try { pts = await getJson('/api/series?node=' + selected); } catch (e) { return; }
  const node = state && state.nodes[selected];
  $('chartTitle').textContent = (node ? node.name + ' (' + node.region + ')' : 'Farm') + ' - last 48 hours';
  drawChart(pts);
}

function drawChart(pts) {
  const c = $('chart');
  const dpr = window.devicePixelRatio || 1;
  const W = c.clientWidth, H = 330;
  c.width = W * dpr; c.height = H * dpr;
  const g = c.getContext('2d');
  g.scale(dpr, dpr);
  g.clearRect(0, 0, W, H);
  const css = getComputedStyle(document.documentElement);
  const L = 44, R = 44, T = 10, B = 26, w = W - L - R, h = H - T - B;
  g.font = '11px system-ui, sans-serif';
  if (!pts.length) { g.fillStyle = '#888'; g.fillText('waiting for data...', L, T + 20); return; }
  const n = 192;
  const x = (i) => L + (i + (n - pts.length)) * w / (n - 1);
  const smVals = pts.map((p) => p[1]);
  let lo = Math.floor(Math.min(...smVals) - 2), hi = Math.ceil(Math.max(...smVals) + 2);
  const ys = (v) => T + h - (v - lo) / (hi - lo) * h;
  const maxScore = Math.max(3, ...pts.map((p) => p[3])) * 1.05;
  const yr = (v) => T + h - Math.min(v, maxScore) / maxScore * h;

  // ground-truth attack windows
  g.fillStyle = css.getPropertyValue('--truth');
  pts.forEach((p, i) => { if (p[5]) g.fillRect(x(i) - w / n / 2, T, w / n + 0.5, h); });
  // grid + axes
  g.strokeStyle = '#e6ebe3'; g.lineWidth = 1; g.fillStyle = '#6b766e';
  for (let k = 0; k <= 4; k++) {
    const yy = T + h * k / 4;
    g.beginPath(); g.moveTo(L, yy); g.lineTo(L + w, yy); g.stroke();
    g.textAlign = 'right'; g.fillText((hi - (hi - lo) * k / 4).toFixed(0) + '%', L - 6, yy + 4);
    g.textAlign = 'left'; g.fillText((maxScore - maxScore * k / 4).toFixed(1), L + w + 6, yy + 4);
  }
  g.textAlign = 'center';
  pts.forEach((p, i) => {
    const step = p[0];
    if (step % 24 === 0) {
      const hour = (step % 96) / 4;
      g.fillText(String(hour).padStart(2, '0') + ':00', x(i), H - 8);
    }
  });
  // thresholds
  if (state) {
    g.setLineDash([4, 4]); g.strokeStyle = 'rgba(224,84,47,0.6)';
    g.beginPath(); g.moveTo(L, yr(state.tau)); g.lineTo(L + w, yr(state.tau)); g.stroke();
    g.setLineDash([]);
  }
  // flow bars
  g.fillStyle = 'rgba(91,141,239,0.25)';
  pts.forEach((p, i) => { if (p[2] > 0.5) { const bh = Math.min(h * 0.3, p[2] * 2.5); g.fillRect(x(i) - 1.5, T + h - bh, 3, bh); } });
  // lines
  const line = (vals, yf, color, width) => {
    g.strokeStyle = color; g.lineWidth = width; g.beginPath();
    vals.forEach((v, i) => (i ? g.lineTo(x(i), yf(v)) : g.moveTo(x(i), yf(v))));
    g.stroke();
  };
  line(pts.map((p) => p[3]), yr, css.getPropertyValue('--green'), 1.4);
  line(smVals, ys, css.getPropertyValue('--soil'), 2);
  // alarms
  pts.forEach((p, i) => {
    if (p[4] >= 3) {
      g.fillStyle = p[4] === 4 ? css.getPropertyValue('--critical') : css.getPropertyValue('--alert');
      g.beginPath(); g.arc(x(i), T + 6, 3.2, 0, 2 * Math.PI); g.fill();
    }
  });
}

// ------------------------------------------------------------------ results + feed
async function loadResults() {
  let r;
  try { r = await getJson('/api/results'); } catch (e) { $('resNote').textContent = e.message; return; }
  const t = $('resultsTable');
  t.textContent = '';
  const head = el('tr');
  ['Detector', 'F1', 'Precision', 'Recall', 'False positive rate', 'Memory', 'Attacks found'].forEach((h, i) =>
    head.appendChild(el('th', i ? 'num' : '', h)));
  t.appendChild(head);
  r.models.forEach((m) => {
    const tr = el('tr', m.kind === 'proposed' ? 'proposed' : '');
    tr.appendChild(el('td', '', m.name));
    const f1 = el('td', 'num');
    const bar = el('span', 'bar'); bar.style.width = (m.f1 * 70).toFixed(0) + 'px';
    f1.appendChild(bar); f1.appendChild(document.createTextNode(pct(m.f1)));
    tr.appendChild(f1);
    tr.appendChild(el('td', 'num', pct(m.precision)));
    tr.appendChild(el('td', 'num', pct(m.recall)));
    const fpr = el('td', 'num');
    const fb = el('span', 'bar fpr'); fb.style.width = Math.min(70, m.fpr * 700).toFixed(0) + 'px';
    fpr.appendChild(fb); fpr.appendChild(document.createTextNode(pct(m.fpr, 2)));
    tr.appendChild(fpr);
    tr.appendChild(el('td', 'num', (m.memoryBytes / 1024).toFixed(1) + ' KB'));
    tr.appendChild(el('td', 'num', pct(m.episodeRate, 0)));
    t.appendChild(tr);
  });
  $('resNote').textContent = r.testReadings.toLocaleString() + ' windows, ' + r.episodes + ' attacks';
}

async function loadFeed() {
  let f;
  try { f = await getJson('/api/feed'); } catch (e) { return; }
  const ul = $('localFeed');
  ul.textContent = '';
  if (!f.local.length) ul.appendChild(el('li', 'empty', 'None yet.'));
  f.local.slice().reverse().forEach((s) => {
    ul.appendChild(el('li', '', s.id + ' · ' + s.region + ' · ' + s.value + ' · confidence ' + s.confidence));
  });
  const t = $('feedTable');
  if (t.childElementCount) return;
  const head = el('tr');
  ['ID', 'Type', 'Value', 'Region', 'Conf.', 'Source'].forEach((h) => head.appendChild(el('th', '', h)));
  t.appendChild(head);
  f.external.forEach((i) => {
    const tr = el('tr');
    [i.id, i.type, i.value, i.region || '-', i.confidence, i.source].forEach((v) => tr.appendChild(el('td', '', String(v))));
    t.appendChild(tr);
  });
  $('feedNote').textContent = f.external.length + ' external indicators';
}

// ------------------------------------------------------------------ polling
async function tick() {
  await loadState();
  loadIncidents();
  loadSeries();
}
requireLogin().then(() => {
  markNav();
  loadResults();
  loadFeed();
  tick();
  setInterval(tick, 1000);
  setInterval(loadFeed, 5000);
  window.addEventListener('resize', loadSeries);
}).catch(() => { /* api() already went to the login page */ });
