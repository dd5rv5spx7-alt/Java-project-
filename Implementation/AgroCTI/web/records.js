// Records page: list, search, sort, create, update and delete for the five modules.
'use strict';

const ATTACKS = ['Drift / bias injection', 'Replay attack', 'Spoofed rain reading', 'Unauthorised valve command',
  'Sensor freeze (stuck value)', 'Flow meter tampering', 'Reported by farmer'];

/**
 * One entry per module. columns: what the table shows (key = sort key on the server).
 * fields: the form. A field with 'options' gets a drop-down; 'from' loads the options from /api/options/{from}.
 */
const MODULES = {
  nodes: {
    title: 'Farm nodes', single: 'farm node', owner: 'Module 1 · M. Sai Chandogya (AP25110090046)',
    info: 'Sensor-and-valve nodes registered on each farm. The 24 monitored nodes send live readings.',
    columns: [
      { key: 'id', label: 'ID', num: true }, { key: 'code', label: 'Code' }, { key: 'region', label: 'Region' },
      { key: 'crop', label: 'Crop' }, { key: 'gatewayIp', label: 'Gateway IP' }, { key: 'firmware', label: 'Firmware' },
      { key: 'status', label: 'Status', pill: true }, { key: 'ownerName', label: 'Owner' },
      { key: 'monitored', label: 'Live data', sort: false, fmt: (v) => (v ? 'yes' : 'no') }],
    fields: [
      { key: 'code', label: 'Node code', required: true, placeholder: 'GUN-F7', pattern: '[A-Za-z]{3}-[Ff][0-9]{1,2}', help: '3 letters, -F, farm number' },
      { key: 'region', label: 'Region', required: true, options: REGIONS },
      { key: 'crop', label: 'Crop', required: true, placeholder: 'Paddy', maxlength: 30 },
      { key: 'gatewayIp', label: 'Gateway IP', required: true, placeholder: '10.1.7.1' },
      { key: 'firmware', label: 'Firmware', required: true, placeholder: 'v1.4.2', pattern: 'v[0-9]+\\.[0-9]+\\.[0-9]+' },
      { key: 'status', label: 'Status', required: true, options: ['ACTIVE', 'MAINTENANCE', 'RETIRED'] },
      { key: 'ownerId', label: 'Owner (farmer)', from: 'users', empty: '- none -' }],
    canDeleteRow: (r) => !r.monitored,   // monitored nodes are retired instead (the server enforces this too)
    deleteText: (r) => `Delete farm node ${r.code}? This cannot be undone.`,
  },
  indicators: {
    title: 'Threat indicators', single: 'threat indicator', owner: 'Module 2 · N. Sri Varalaxmi (AP25110090062)',
    info: 'Cyber threat intelligence used by the detector: malicious IP addresses and regional advisories. Changes apply to the live detector at once.',
    columns: [
      { key: 'id', label: 'ID' }, { key: 'type', label: 'Type' }, { key: 'value', label: 'Value' },
      { key: 'region', label: 'Region', fmt: (v) => v || '-' }, { key: 'confidence', label: 'Confidence', num: true, fmt: (v) => v + ' %' },
      { key: 'source', label: 'Source' },
      { key: 'validFrom', label: 'Valid', fmt: (v, r) => (r.validFromTime ? r.validFromTime.replace('T', ' ').slice(5) + ' → ' + r.validToTime.replace('T', ' ').slice(5) : 'always') }],
    fields: [
      { key: 'id', label: 'ID', placeholder: 'leave empty for the next free id', createOnly: true, help: 'e.g. ind-040' },
      { key: 'type', label: 'Type', required: true, options: ['ipv4', 'regional-advisory'] },
      { key: 'value', label: 'Value', required: true, placeholder: '203.0.113.45', help: 'IP address, or the advisory pattern (e.g. spoofing)' },
      { key: 'region', label: 'Region', options: REGIONS, showIf: (f) => f.type === 'regional-advisory', required: true },
      { key: 'validFromTime', label: 'Valid from', type: 'datetime-local', showIf: (f) => f.type === 'regional-advisory', required: true },
      { key: 'validToTime', label: 'Valid to', type: 'datetime-local', showIf: (f) => f.type === 'regional-advisory', required: true },
      { key: 'confidence', label: 'Confidence (0-100)', type: 'number', min: 0, max: 100, required: true },
      { key: 'source', label: 'Source', required: true, placeholder: 'AgriCERT', maxlength: 40 }],
    canEditRow: (r) => !r.local,
    deleteText: (r) => `Delete indicator ${r.id} (${r.value})? The detector stops using it immediately.`,
  },
  incidents: {
    title: 'Incidents', single: 'incident', owner: 'Module 3 · M. Mohana Manogna (AP25110090170)',
    info: 'Raised automatically by the detector, or reported by hand. Analysts set the status, assign and close them.',
    columns: [
      { key: 'id', label: 'ID', num: true }, { key: 'time', label: 'Start', fmt: (v) => v.slice(5, 16) },
      { key: 'nodeName', label: 'Node' }, { key: 'region', label: 'Region' },
      { key: 'severity', label: 'Severity', pill: true }, { key: 'guess', label: 'Likely attack' },
      { key: 'durationMin', label: 'Duration', num: true, fmt: (v) => v + ' min' },
      { key: 'status', label: 'Status', pill: true }, { key: 'assignedName', label: 'Assigned to' }],
    fields: [
      { key: 'node', label: 'Farm node', required: true, from: 'nodes', createOnly: true },
      { key: 'severity', label: 'Severity', required: true, options: ['ALERT', 'CRITICAL'] },
      { key: 'guess', label: 'Likely attack', required: true, list: ATTACKS, maxlength: 80 },
      { key: 'status', label: 'Status', required: true, options: ['OPEN', 'INVESTIGATING', 'RESOLVED', 'FALSE_ALARM'] },
      { key: 'assignedTo', label: 'Assigned to', from: 'users', staffOnly: true, empty: '- nobody -' },
      { key: 'note', label: 'Analyst note', type: 'textarea', maxlength: 500, wide: true, help: 'required when the status is RESOLVED or FALSE_ALARM' }],
    deleteText: (r) => `Delete incident #${r.id} (${r.nodeName}, ${r.guess})? Its response actions are deleted too. This cannot be undone.`,
    defaults: { severity: 'ALERT', status: 'OPEN' },
  },
  actions: {
    title: 'Response actions', single: 'response action', owner: 'Module 4 · M. Mukesh Vasudhar (AP25110090048)',
    info: 'Steps taken for an incident. The first action on an OPEN incident moves it to INVESTIGATING.',
    columns: [
      { key: 'id', label: 'ID', num: true }, { key: 'incidentLabel', label: 'Incident' },
      { key: 'type', label: 'Action', fmt: (v, r) => r.typeText }, { key: 'description', label: 'Description', sort: false, wrap: true },
      { key: 'status', label: 'Status', pill: true }, { key: 'performedByName', label: 'By' },
      { key: 'dueDate', label: 'Due', fmt: (v) => v || '-' }],
    fields: [
      { key: 'incidentId', label: 'Incident', required: true, from: 'incidents', createOnly: true },
      { key: 'type', label: 'Action', required: true, options: ['BLOCK_IP', 'RECALIBRATE', 'MANUAL_VALVE', 'FIRMWARE_UPDATE', 'FIELD_VISIT', 'OTHER'] },
      { key: 'description', label: 'Description', required: true, type: 'textarea', maxlength: 300, wide: true },
      { key: 'performedBy', label: 'Performed by', from: 'users', staffOnly: true, empty: '- me -' },
      { key: 'status', label: 'Status', required: true, options: ['PLANNED', 'IN_PROGRESS', 'DONE'] },
      { key: 'dueDate', label: 'Due date', type: 'date' }],
    deleteText: (r) => `Delete action #${r.id} (${r.typeText})? This cannot be undone.`,
    defaults: { status: 'PLANNED', type: 'BLOCK_IP' },
  },
  users: {
    title: 'Users', single: 'user', owner: 'Module 5 · Sarwan (AP25110090060)',
    info: 'Accounts that can log in. ADMIN manages everything, ANALYST manages records, FARMER can only view.',
    adminOnly: true,
    columns: [
      { key: 'id', label: 'ID', num: true }, { key: 'username', label: 'Username' }, { key: 'fullName', label: 'Full name' },
      { key: 'role', label: 'Role', pill: true }, { key: 'createdAt', label: 'Created' }],
    fields: [
      { key: 'username', label: 'Username', required: true, placeholder: 'ravi_k', maxlength: 40 },
      { key: 'fullName', label: 'Full name', required: true, maxlength: 80 },
      { key: 'role', label: 'Role', required: true, options: ['ADMIN', 'ANALYST', 'FARMER'] },
      { key: 'password', label: 'Password', type: 'password', requiredOnCreate: true, help: 'at least 8 characters with a letter and a digit; leave empty to keep the old one' }],
    canDeleteRow: (r) => ME && r.id !== ME.user.id,
    deleteText: (r) => `Delete the account ${r.username} (${r.fullName})? This cannot be undone.`,
    defaults: { role: 'FARMER' },
  },
};

const $ = (id) => document.getElementById(id);
let current = null;           // key of the module shown
let rows = [];
let sort = { key: '', dir: 'asc' };
let editing = null;           // row being edited, or null when adding
const optionCache = {};

function canWrite(mod) {
  if (!ME) return false;
  return mod.adminOnly ? ME.canManageUsers : ME.canEdit;
}

// ------------------------------------------------------------------ list
async function load() {
  const mod = MODULES[current];
  const q = $('search').value.trim();
  const params = new URLSearchParams();
  if (q) params.set('q', q);
  if (sort.key) { params.set('sort', sort.key); params.set('dir', sort.dir); }
  try {
    rows = await api('/api/records/' + current + '?' + params);
  } catch (e) {
    rows = [];
    toast(cap(e.message), 'err');
  }
  renderTable(mod);
}

function renderTable(mod) {
  const t = $('recTable');
  t.textContent = '';
  const thead = el('thead');
  const hr = el('tr');
  mod.columns.forEach((c) => {
    const th = el('th', c.num ? 'num' : null);
    if (c.sort === false) {
      th.textContent = c.label;
    } else {
      const b = el('button', 'sort-btn', c.label);
      b.type = 'button';
      const on = sort.key === c.key;
      if (on) b.appendChild(el('span', 'arrow', sort.dir === 'asc' ? ' ▲' : ' ▼'));
      th.setAttribute('aria-sort', on ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none');
      b.addEventListener('click', () => {
        sort = { key: c.key, dir: on && sort.dir === 'asc' ? 'desc' : 'asc' };
        load();
      });
      th.appendChild(b);
    }
    hr.appendChild(th);
  });
  const write = canWrite(mod);
  if (write) hr.appendChild(el('th', 'actions-col', 'Actions'));
  thead.appendChild(hr);
  t.appendChild(thead);
  const tb = el('tbody');
  if (!rows.length) {
    const tr = el('tr');
    const td = el('td', 'empty', $('search').value ? 'No records match the search.' : 'No records yet.');
    td.colSpan = mod.columns.length + (write ? 1 : 0);
    tr.appendChild(td);
    tb.appendChild(tr);
  }
  rows.forEach((r) => {
    const tr = el('tr');
    mod.columns.forEach((c) => {
      const raw = r[c.key];
      const text = c.fmt ? c.fmt(raw, r) : (raw === null || raw === undefined || raw === '' ? '-' : String(raw));
      const td = el('td', (c.num ? 'num' : '') + (c.wrap ? ' wrap' : ''));
      if (c.pill && raw) td.appendChild(el('span', 'status ' + raw, String(raw).replace('_', ' ').toLowerCase()));
      else td.textContent = text;
      tr.appendChild(td);
    });
    if (write) {
      const td = el('td', 'actions-col');
      if (!mod.canEditRow || mod.canEditRow(r)) {
        const eb = el('button', 'link-btn', 'Edit');
        eb.type = 'button';
        eb.setAttribute('aria-label', 'Edit ' + mod.single + ' ' + r.id);
        eb.addEventListener('click', () => openForm(r));
        td.appendChild(eb);
      }
      if (!mod.canDeleteRow || mod.canDeleteRow(r)) {
        const db = el('button', 'link-btn danger', 'Delete');
        db.type = 'button';
        db.setAttribute('aria-label', 'Delete ' + mod.single + ' ' + r.id);
        db.addEventListener('click', () => confirmDelete(r));
        td.appendChild(db);
      }
      tr.appendChild(td);
    }
    tb.appendChild(tr);
  });
  t.appendChild(tb);
  $('recCount').textContent = rows.length + (rows.length === 1 ? ' record' : ' records');
}

// ------------------------------------------------------------------ form
async function options(from, staffOnly) {
  if (!optionCache[from]) optionCache[from] = await api('/api/options/' + from);
  return staffOnly ? optionCache[from].filter((o) => !o.label.endsWith('(farmer)')) : optionCache[from];
}

function formValues() {
  const v = {};
  $('formFields').querySelectorAll('[name]').forEach((i) => { v[i.name] = i.value; });
  return v;
}

function applyShowIf(mod) {
  const v = formValues();
  mod.fields.forEach((f) => {
    if (!f.showIf) return;
    const wrap = $('wrap_' + f.key);
    const show = f.showIf(v);
    wrap.hidden = !show;
    wrap.querySelector('[name]').disabled = !show;
  });
}

async function openForm(row) {
  const mod = MODULES[current];
  editing = row;
  $('formTitle').textContent = (row ? 'Edit ' : 'Add ') + mod.single + (row ? ' ' + (row.code || row.username || '#' + row.id) : '');
  $('formError').hidden = true;
  const box = $('formFields');
  box.textContent = '';
  const start = row || mod.defaults || {};
  for (const f of mod.fields) {
    const wrap = el('div', 'field' + (f.wide ? ' wide' : ''));
    wrap.id = 'wrap_' + f.key;
    const id = 'f_' + f.key;
    const label = el('label', null, f.label);
    label.htmlFor = id;
    const required = f.required || (f.requiredOnCreate && !row);
    if (required) label.appendChild(el('span', 'req', ' *'));
    wrap.appendChild(label);
    let input;
    if (f.options || f.from) {
      input = el('select');
      if (f.empty || !required) input.appendChild(new Option(f.empty || '- choose -', ''));
      else input.appendChild(new Option('- choose -', ''));
      const opts = f.options ? f.options.map((o) => ({ id: o, label: o })) : await options(f.from, f.staffOnly);
      opts.forEach((o) => input.appendChild(new Option(o.label, o.id)));
    } else if (f.type === 'textarea') {
      input = el('textarea');
      input.rows = 3;
    } else {
      input = el('input');
      input.type = f.type || 'text';
      if (f.list) {
        const dl = el('datalist');
        dl.id = 'dl_' + f.key;
        f.list.forEach((x) => dl.appendChild(new Option(x)));
        wrap.appendChild(dl);
        input.setAttribute('list', dl.id);
      }
    }
    input.id = id;
    input.name = f.key;
    if (required) input.required = true;
    ['placeholder', 'pattern', 'min', 'max', 'maxlength'].forEach((a) => { if (f[a] !== undefined) input.setAttribute(a, f[a]); });
    let value = start[f.key];
    if (row && f.key === 'region' && current === 'indicators') value = row.region || '';
    if (row && (f.key === 'validFromTime' || f.key === 'validToTime')) value = (row[f.key] || '').slice(0, 16);
    if (row && f.key === 'node') value = row.node;
    if (row && f.key === 'password') value = '';
    if (value !== undefined && value !== null) input.value = String(value);
    if (row && f.createOnly) input.disabled = true;
    const clear = () => {
      wrap.classList.remove('bad');
      wrap.querySelector('.field-error').textContent = '';
      $('formError').hidden = true;
      applyShowIf(mod);
    };
    input.addEventListener('input', clear);
    input.addEventListener('change', clear);
    wrap.appendChild(input);
    if (f.help) wrap.appendChild(el('small', 'help', f.help));
    wrap.appendChild(el('small', 'field-error'));
    box.appendChild(wrap);
  }
  applyShowIf(mod);
  $('formDialog').showModal();
  const first = box.querySelector('input:not([disabled]), select:not([disabled]), textarea:not([disabled])');
  if (first) first.focus();
}

function showFieldError(field, msg) {
  msg = cap(msg);
  $('formError').textContent = msg;
  $('formError').hidden = false;
  const wrap = field && $('wrap_' + field);
  if (wrap && !wrap.hidden) {
    wrap.classList.add('bad');
    wrap.querySelector('.field-error').textContent = msg;
    const i = wrap.querySelector('[name]');
    if (i && !i.disabled) i.focus();
  }
}

/** Browser-side checks first (required, pattern, range); the server checks everything again. */
function clientCheck() {
  for (const i of $('formFields').querySelectorAll('[name]')) {
    if (i.disabled) continue;
    if (!i.checkValidity()) {
      const f = MODULES[current].fields.find((x) => x.key === i.name);
      const why = i.validity.valueMissing ? 'is required' : i.validity.patternMismatch ? 'has the wrong format' + (f.help ? ' (' + f.help + ')' : '')
        : i.validity.rangeUnderflow || i.validity.rangeOverflow ? 'is out of range' : 'is not valid';
      showFieldError(i.name, f.label + ' ' + why);
      return false;
    }
  }
  return true;
}

$('recForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  $('formFields').querySelectorAll('.field').forEach((w) => { w.classList.remove('bad'); w.querySelector('.field-error').textContent = ''; });
  $('formError').hidden = true;
  if (!clientCheck()) return;
  const mod = MODULES[current];
  const body = {};
  $('formFields').querySelectorAll('[name]').forEach((i) => { if (!i.disabled) body[i.name] = i.value; });
  if (editing && current === 'incidents') body.node = editing.node;
  if (editing && current === 'actions') body.incidentId = editing.incidentId;
  $('btnSave').disabled = true;
  try {
    const saved = editing
      ? await api('/api/records/' + current + '/' + encodeURIComponent(editing.id), { method: 'PUT', body })
      : await api('/api/records/' + current, { method: 'POST', body });
    $('formDialog').close();
    Object.keys(optionCache).forEach((k) => delete optionCache[k]);
    toast((editing ? 'Saved changes to ' : 'Created ') + mod.single + ' ' + (saved.code || saved.username || saved.id));
    load();
  } catch (ex) {
    showFieldError(ex.field, ex.message);
  } finally {
    $('btnSave').disabled = false;
  }
});
$('btnCancel').addEventListener('click', () => $('formDialog').close());

// ------------------------------------------------------------------ delete
function confirmDelete(r) {
  const mod = MODULES[current];
  $('confirmTitle').textContent = 'Delete ' + mod.single + '?';
  $('confirmText').textContent = mod.deleteText(r);
  const d = $('confirmDialog');
  d.returnValue = '';
  d.showModal();
  d.addEventListener('close', async function onClose() {
    d.removeEventListener('close', onClose);
    if (d.returnValue !== 'ok') return;
    try {
      const res = await api('/api/records/' + current + '/' + encodeURIComponent(r.id), { method: 'DELETE' });
      Object.keys(optionCache).forEach((k) => delete optionCache[k]);
      toast(cap(res.message));
      load();
    } catch (ex) {
      toast(cap(ex.message), 'err');
    }
  });
}

// ------------------------------------------------------------------ page
function show() {
  let key = location.hash.replace('#', '') || 'nodes';
  if (!MODULES[key] || (MODULES[key].adminOnly && !ME.canManageUsers)) key = 'nodes';
  current = key;
  const mod = MODULES[key];
  document.title = 'Agro-CTI - ' + mod.title;
  $('recTitle').textContent = mod.title;
  $('recInfo').textContent = mod.info + '  ' + mod.owner;
  $('btnAdd').textContent = '+ Add ' + mod.single;
  $('btnAdd').hidden = !canWrite(mod);
  $('readonlyNote').hidden = canWrite(mod);
  $('search').value = '';
  sort = { key: '', dir: 'asc' };
  markNav();
  load();
}

let searchTimer = null;
$('search').addEventListener('input', () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(load, 250);
});
$('btnAdd').addEventListener('click', () => openForm(null));
window.addEventListener('hashchange', show);
requireLogin().then(show).catch(() => { /* api() already went to the login page */ });
