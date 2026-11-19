// Shared by every page: calls to the Java API, the login check and the top navigation.
'use strict';

const REGIONS = ['Guntur', 'Krishna', 'Prakasam', 'Nellore'];

/** Calls the API; sends JSON; on 401 goes to the login page. Throws Error(message) with .field on failure. */
async function api(url, opts = {}) {
  const o = { method: opts.method || 'GET', headers: {}, credentials: 'same-origin' };
  if (opts.body !== undefined) {
    o.headers['Content-Type'] = 'application/json';
    o.body = JSON.stringify(opts.body);
  }
  const r = await fetch(url, o);
  let data = {};
  try { data = await r.json(); } catch (e) { /* empty body */ }
  if (r.status === 401 && !url.endsWith('/api/login')) {
    location.href = 'login.html?next=' + encodeURIComponent(location.pathname + location.hash);
    throw new Error('please log in');
  }
  if (!r.ok) {
    const err = new Error(data.error || r.statusText);
    err.field = data.field;
    err.status = r.status;
    throw err;
  }
  return data;
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined && text !== null) e.textContent = text;   // textContent: data is never run as HTML
  return e;
}

let ME = null;

/** Loads the logged-in user, fills the user chip and hides links the role cannot use. */
async function requireLogin() {
  ME = await api('/api/me');
  const chip = document.getElementById('userChip');
  if (chip) {
    chip.textContent = '';
    chip.appendChild(el('b', null, ME.user.fullName));
    chip.appendChild(el('span', 'role ' + ME.user.role, ME.user.role.toLowerCase()));
  }
  document.querySelectorAll('[data-admin-only]').forEach((n) => { n.hidden = !ME.canManageUsers; });
  const out = document.getElementById('btnLogout');
  if (out) {
    out.addEventListener('click', async () => {
      try { await api('/api/logout', { method: 'POST' }); } finally { location.href = 'login.html'; }
    });
  }
  return ME;
}

/** Marks the current page in the navigation bar. */
function markNav() {
  const here = location.pathname.replace(/^\//, '') || 'index.html';
  const hash = location.hash.replace('#', '');
  document.querySelectorAll('.nav a').forEach((a) => {
    const [page, h] = a.getAttribute('href').split('#');
    const on = page === here && (!h || h === hash || (!hash && h === 'nodes'));
    a.classList.toggle('active', on);
    if (on) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
}

/** Server messages start in lower case; show them as sentences. */
const cap = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);

let toastTimer = null;
function toast(msg, kind = 'ok') {
  const t = document.getElementById('toast');
  if (!t) return;
  t.textContent = msg;
  t.className = 'toast show ' + kind;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.className = 'toast'; }, 3500);
}
