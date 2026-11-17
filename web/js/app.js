import {
  explainSms, buildReport, sampleData, weekStart, addDays, needsDescription,
  fmt, exact, CATEGORIES, guessCategory,
} from './core.js';

// Fill these in once the repository and a release exist; empty links are hidden.
const LINKS = {
  github: '',
  apk: '',
};

const EXAMPLES = [
  ['HDFC UPI', 'Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn {d/m/y}\nRef 431234567890\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808'],
  ['SBI UPI', 'Dear UPI user A/C X1234 debited by 235.0 on date {dMony} trf to RAMESH KUMAR Refno 431234567891. If not u? call 1800111109. -SBI'],
  ['ICICI', 'ICICI Bank Acct XX123 debited for Rs 349.00 on {d-Mon-y}; ZOMATO credited. UPI:432112345678. Call 18002662 for dispute.'],
  ['Axis', 'INR 500.00 debited\nA/c no. XX1234\n{d-m-y}, 12:00:00\nUPI/P2M/431234567892/BIG BASKET\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank'],
  ['Card spend', 'Rs 1,29,999.00 spent on HDFC Bank Card x4321 at AMAZON on {iso}:12:00:00. Not you? To block call 18002586161'],
  ['OTP (ignored)', '123456 is OTP for txn of INR 500.00 at AMAZON on card x4321. Do not share.'],
  ['Money in (ignored)', 'Rs.5000.00 credited to a/c XX1234 on {d-m-y} by a/c linked to VPA friend@okaxis (UPI Ref no 431234567893).'],
];

const STORE_KEY = 'spendwise-demo-v1';
const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
};

// ---------- State (kept in this browser only) ----------
let state = load();

function fresh() {
  return { seed: Math.floor(Math.random() * 1e9), created: Date.now(), budget: 20000, added: [], edits: {} };
}
function load() {
  try {
    const s = JSON.parse(localStorage.getItem(STORE_KEY));
    // Sample data is generated relative to "now"; regenerate when it is more than a day old.
    if (s && Date.now() - s.created < 86_400_000) return s;
  } catch { /* storage unavailable or corrupt */ }
  return fresh();
}
function save() {
  try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch { /* private mode: fine, state lives in memory */ }
}

let base = sampleData(state.created, state.seed);
function allTxns() {
  return [...base, ...state.added].map((t) => ({ ...t, ...(state.edits[t.id] || {}) }));
}

let shownWeek = weekStart(Date.now());
let pendingOnly = false;

// ---------- Parser playground ----------
function fillDates(template) {
  const d = new Date(Date.now() - 86_400_000); // yesterday, so the date is visibly picked up
  const p = (n) => String(n).padStart(2, '0');
  const mon = d.toLocaleString('en', { month: 'short' });
  const y2 = p(d.getFullYear() % 100);
  return template
    .replace('{d/m/y}', `${p(d.getDate())}/${p(d.getMonth() + 1)}/${y2}`)
    .replace('{d-m-y}', `${p(d.getDate())}-${p(d.getMonth() + 1)}-${y2}`)
    .replace('{dMony}', `${p(d.getDate())}${mon}${y2}`)
    .replace('{d-Mon-y}', `${p(d.getDate())}-${mon}-${y2}`)
    .replace('{iso}', `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`);
}

function renderExamples() {
  const box = $('examples');
  for (const [name, text] of EXAMPLES) {
    const b = el('button', 'chip', name);
    b.type = 'button';
    b.addEventListener('click', () => {
      $('sms').value = fillDates(text);
      renderResult();
    });
    box.append(b);
  }
}

function renderResult() {
  const box = $('result');
  box.replaceChildren();
  const text = $('sms').value;
  if (!text.trim()) {
    box.append(el('p', 'empty', 'The parsed payment appears here: amount, who was paid, category, account and reference number.'));
    return;
  }
  const { txn, reason } = explainSms(text);
  if (!txn) {
    box.append(el('span', 'status skip', 'Skipped'), el('p', null, reason),
      el('p', 'muted', 'The app drops messages like this on the phone, so they are never stored.'));
    return;
  }
  const dl = el('dl', 'fields');
  const row = (k, v) => dl.append(el('dt', null, k), el('dd', null, v || '—'));
  row('Paid to', txn.merchant);
  row('Category', txn.category);
  row('Date', new Date(txn.timestamp).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' }));
  row('Account', txn.accountLast4 ? `ending ${txn.accountLast4}` : '');
  row('Reference', txn.refNo);

  const addBtn = el('button', 'btn primary', 'Add to this week’s report');
  addBtn.type = 'button';
  const exists = allTxns().some((t) => t.id === txn.id);
  if (exists) { addBtn.disabled = true; addBtn.textContent = 'Already in the report'; }
  addBtn.addEventListener('click', () => {
    state.added.push(txn);
    save();
    shownWeek = weekStart(txn.timestamp);
    renderReport();
    renderResult();
    $('report').scrollIntoView();
  });
  box.append(el('span', 'status ok', 'Payment found'), el('p', 'amount', exact(txn.amount)), dl, addBtn,
    el('p', 'muted', 'Only these fields are saved. The message text stays on the phone.'));
}

// ---------- Report ----------
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const tip = $('tooltip');

function attachTooltip(node, html) {
  const show = (x, y) => {
    tip.replaceChildren(...html());
    tip.hidden = false;
    const w = tip.offsetWidth, h = tip.offsetHeight;
    tip.style.left = Math.min(window.innerWidth - w - 8, Math.max(8, x - w / 2)) + 'px';
    tip.style.top = Math.max(8, y - h - 12) + 'px';
  };
  node.tabIndex = 0;
  node.addEventListener('pointermove', (e) => show(e.clientX, e.clientY));
  node.addEventListener('pointerleave', () => { tip.hidden = true; });
  node.addEventListener('focus', () => { const r = node.getBoundingClientRect(); show(r.left + r.width / 2, r.top); });
  node.addEventListener('blur', () => { tip.hidden = true; });
}
const tipLines = (title, ...lines) => () => [el('strong', null, title), ...lines.map((l) => el('div', null, l))];

function renderReport() {
  const all = allTxns();
  const r = buildReport(all, shownWeek, Number(state.budget) || 0, Date.now());
  const thisWeek = weekStart(Date.now());
  const fmtDay = (t) => new Date(t).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
  $('weekTitle').textContent = `${fmtDay(shownWeek)} – ${fmtDay(addDays(shownWeek, 6))}${shownWeek === thisWeek ? ' · this week' : ''}`;
  $('next').disabled = shownWeek >= thisWeek;
  $('prev').disabled = shownWeek <= addDays(thisWeek, -28);

  $('total').textContent = fmt(r.total);
  const cmp = [`${r.count} payment${r.count === 1 ? '' : 's'}`];
  if (r.daysElapsed < 7) {
    cmp.push(`${r.daysElapsed} day${r.daysElapsed === 1 ? '' : 's'} in`);
    if (r.prevSamePoint > 0) cmp.push(`last week by now ${fmt(r.prevSamePoint)}`);
  } else if (r.prevTotal > 0) {
    cmp.push(`last week ${fmt(r.prevTotal)}`);
  }
  if (r.usualWeekly > 0) cmp.push(`usual ${fmt(r.usualWeekly)}`);
  $('compare').textContent = cmp.join(' · ');
  $('tips').replaceChildren(...r.tips.map((t) => el('li', null, t)));

  // Category bars: one series, so one hue; the value is printed beside every bar.
  const cats = $('categories');
  cats.replaceChildren();
  const maxCat = r.byCategory.length ? r.byCategory[0][1] : 0;
  for (const [name, value] of r.byCategory) {
    const row = el('div', 'hbar');
    const track = el('div', 'track');
    const fill = el('div', 'fill');
    fill.style.setProperty('--w', `${(value / maxCat) * 100}%`);
    track.append(fill);
    row.append(el('span', 'name', name), track, el('span', 'val', fmt(value)));
    const n = all.filter((t) => t.category === name && t.timestamp >= r.weekStart && t.timestamp < r.weekEnd).length;
    attachTooltip(row, tipLines(name, `${fmt(value)} · ${Math.round(value / r.total * 100)}% of the week`, `${n} payment${n === 1 ? '' : 's'}`));
    cats.append(row);
  }
  if (!r.byCategory.length) cats.append(el('p', 'muted', 'Nothing this week.'));

  // Day columns
  const days = $('days');
  days.replaceChildren();
  days.classList.add('cols-axis');
  const maxDay = Math.max(...r.byDay, 1);
  const todayIdx = shownWeek === thisWeek ? (new Date().getDay() + 6) % 7 : -1;
  r.byDay.forEach((v, i) => {
    const col = el('div', 'col' + (i === todayIdx ? ' today' : ''));
    const bar = el('div', 'bar');
    bar.style.setProperty('--h', `${(v / maxDay) * 100 * 0.82}%`);
    if (!v) bar.hidden = true;
    col.append(bar, el('span', 'day', DAYS[i]));
    attachTooltip(col, tipLines(new Date(addDays(shownWeek, i)).toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'short' }), v ? fmt(v) : 'No spending'));
    days.append(col);
  });

  $('merchants').replaceChildren(...r.topMerchants.map(([name, v]) => {
    const li = el('li', null, name);
    li.append(el('span', null, fmt(v)));
    return li;
  }));

  renderList(all, r);
}

function renderList(all, r) {
  const week = all.filter((t) => t.timestamp >= r.weekStart && t.timestamp < r.weekEnd)
    .sort((a, b) => b.timestamp - a.timestamp);
  const pending = week.filter(needsDescription);
  $('reminder').hidden = pending.length === 0;
  $('reminderText').textContent = `${pending.length} payment${pending.length === 1 ? ' needs' : 's need'} a description. Add a quick note while you still remember.`;
  if (!pending.length) pendingOnly = false;
  $('pendingOnly').checked = pendingOnly;

  const box = $('txns');
  box.replaceChildren();
  const rows = pendingOnly ? pending : week;
  for (const t of rows) {
    const row = el('div', 'txn' + (needsDescription(t) ? ' pending' : ''));
    const who = el('div', 'who');
    who.append(el('strong', null, t.merchant),
      el('small', null, new Date(t.timestamp).toLocaleString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })));

    const note = el('input');
    note.value = t.note || '';
    note.placeholder = 'What was this for?';
    note.maxLength = 500;
    note.setAttribute('aria-label', `Description for ${t.merchant}`);
    note.addEventListener('change', () => {
      const edit = { ...(state.edits[t.id] || {}), note: note.value.trim() };
      // As in the app: if still uncategorised, let the description pick a category.
      if (t.category === 'Uncategorised') edit.category = guessCategory(t.merchant, note.value);
      state.edits[t.id] = edit;
      save();
      renderReport();
    });

    const cat = el('select');
    cat.setAttribute('aria-label', `Category for ${t.merchant}`);
    for (const c of CATEGORIES) {
      const o = el('option', null, c);
      o.value = c;
      o.selected = c === t.category;
      cat.append(o);
    }
    cat.addEventListener('change', () => {
      state.edits[t.id] = { ...(state.edits[t.id] || {}), category: cat.value };
      save();
      renderReport();
    });

    row.append(who, note, cat, el('span', 'amt', exact(t.amount)));
    box.append(row);
  }
  if (!rows.length) box.append(el('p', 'none', 'No payments this week.'));
}

// ---------- Wiring ----------
function init() {
  for (const a of document.querySelectorAll('.needs-link')) {
    const url = LINKS[a.dataset.link];
    if (url) { a.href = url; a.rel = 'noopener'; } else a.classList.add('missing');
  }
  renderExamples();
  $('sms').addEventListener('input', renderResult);
  $('sms').value = fillDates(EXAMPLES[0][1]);
  renderResult();

  $('budget').value = state.budget;
  $('budget').addEventListener('change', () => {
    state.budget = Math.max(0, Number($('budget').value) || 0);
    save();
    renderReport();
  });
  $('prev').addEventListener('click', () => { shownWeek = addDays(shownWeek, -7); renderReport(); });
  $('next').addEventListener('click', () => { shownWeek = addDays(shownWeek, 7); renderReport(); });
  $('pendingOnly').addEventListener('change', (e) => { pendingOnly = e.target.checked; renderReport(); });
  $('reset').addEventListener('click', () => {
    state = fresh();
    save();
    base = sampleData(state.created, state.seed);
    shownWeek = weekStart(Date.now());
    $('budget').value = state.budget;
    renderReport();
    renderResult();
  });
  renderReport();

  if ('serviceWorker' in navigator && location.protocol === 'https:') {
    navigator.serviceWorker.register('sw.js').catch(() => { /* offline support is optional */ });
  }
}

init();
