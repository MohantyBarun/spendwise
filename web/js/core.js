// JavaScript port of the app's core logic (app/src/main/java/com/spendwise/app/core).
// Keep the two in step: web/test/core.test.mjs mirrors the Java unit tests.

// ---------- Money ----------
const RUPEES = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });
const PAISE = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
export const fmt = (v) => '₹' + RUPEES.format(Math.round(v));
export const exact = (v) => (Math.abs(v - Math.round(v)) < 0.005 ? fmt(v) : '₹' + PAISE.format(v));

// ---------- Categories ----------
export const UNCATEGORISED = 'Uncategorised';
export const FOOD = 'Food & Dining';
export const CATEGORIES = [FOOD, 'Groceries', 'Transport', 'Fuel', 'Shopping', 'Bills & Recharge', 'Entertainment',
  'Health', 'Rent & Home', 'Education', 'Travel', 'Transfers', 'Other', UNCATEGORISED];

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const rule = (cat, ...words) => [cat, new RegExp('\\b(?:' + words.map(esc).join('|') + ')')];
// Order matters: first match wins ("swiggy instamart" is groceries, not dining).
const RULES = [
  rule('Groceries', 'bigbasket', 'big basket', 'blinkit', 'zepto', 'instamart', 'dmart', 'grocer', 'kirana',
    'supermarket', 'milk', 'vegetable', 'fruit', 'jiomart'),
  rule(FOOD, 'swiggy', 'zomato', 'domino', 'pizza', 'restaurant', 'cafe', 'kfc', 'mcdonald', 'burger', 'biryani',
    'bakery', 'dhaba', 'chai', 'coffee', 'starbucks', 'lunch', 'dinner', 'breakfast', 'snack', 'eatclub', 'food'),
  rule('Fuel', 'petrol', 'diesel', 'fuel', 'hpcl', 'bpcl', 'iocl', 'indian oil', 'indianoil', 'bharat petroleum', 'shell'),
  rule('Travel', 'irctc', 'redbus', 'makemytrip', 'goibibo', 'indigo', 'air india', 'flight', 'hotel', 'oyo', 'airbnb',
    'cleartrip', 'ixigo'),
  rule('Transport', 'uber', 'ola', 'rapido', 'metro', 'namma yatri', 'auto', 'cab', 'taxi', 'bus', 'parking', 'toll', 'fastag'),
  rule('Entertainment', 'netflix', 'hotstar', 'spotify', 'prime video', 'bookmyshow', 'pvr', 'inox', 'youtube', 'movie',
    'game', 'steam', 'playstation'),
  rule('Bills & Recharge', 'airtel', 'jio', 'vodafone', 'bsnl', 'electricity', 'bescom', 'recharge', 'broadband',
    'fibernet', 'dth', 'tata play', 'gas', 'water bill', 'bill', 'insurance', 'lic'),
  rule('Health', 'pharmacy', 'pharma', 'medical', 'medplus', 'apollo', '1mg', 'hospital', 'clinic', 'doctor',
    'diagnostic', 'lab', 'gym', 'cult'),
  rule('Rent & Home', 'rent', 'nobroker', 'maintenance', 'society', 'urban company', 'urbanclap', 'furniture', 'ikea'),
  rule('Education', 'udemy', 'coursera', 'school', 'college', 'tuition', 'course', 'book', 'exam'),
  rule('Shopping', 'amazon', 'flipkart', 'myntra', 'ajio', 'meesho', 'nykaa', 'decathlon', 'croma', 'reliance digital',
    'mall', 'store', 'shop'),
];

export function guessCategory(merchant = '', note = '') {
  const text = `${merchant || ''} ${note || ''}`.toLowerCase();
  for (const [cat, re] of RULES) if (re.test(text)) return cat;
  return UNCATEGORISED;
}

// ---------- SMS parser ----------
const IGNORE = /\b(otp|one time password|verification code|will be (?:debited|deducted|charged)|to be debited|is due|due on|due date|payment due|has requested|collect request|failed|declined|unsuccessful|not been debited|reversed|reversal)\b/;
const DEBIT = /\b(debited|spent|paid|sent|withdrawn|purchase|deducted|txn of)\b/;
const CREDIT = /\b(credited|received|deposited|refund(?:ed)?)\b/;
const AMOUNT = /(?:\brs\.?|\binr|₹)\s*(\d[\d,]*(?:\.\d{1,2})?)/;
const AMOUNT_BARE = /\bdebited (?:by|for|with|of)\s+(\d[\d,]*(?:\.\d{1,2})?)/;
const NAME = "([A-Za-z][A-Za-z0-9@._&'\\- ]*?)";
const END = '(?=\\s+(?:on|ref|refno|upi|via|at|from|using|thru|through|if|not)\\b|\\s*[,;(\\n]|\\.(?:\\s|$)|\\s*$)';
const MERCHANT = [
  /;\s*([^;\n]+?)\s+credited/gi,
  /UPI\/P2[AMP]\/\d+\/([^/\n]+)/gi,
  new RegExp('\\b(?:trf to|transfer(?:red)? to|paid to|to vpa|to)\\s+' + NAME + END, 'gi'),
  new RegExp('\\b(?:at|towards)\\s+' + NAME + END, 'gi'),
];
const ACCOUNT = /\b(?:a\/c|ac(?:ct)?|account|card)\s*(?:no\.?|number|ending(?: with| in)?)?\s*[:\-]?\s*[xX*]*\s*(\d{3,6})\b/i;
const REF = /(?:ref(?:erence)?\.?\s*(?:no\.?|number|id)?|rrn|utr|upi)\s*[:.\-]?\s*(\d{6,})/i;
const REF_UPI_PATH = /UPI\/P2[AMP]\/(\d{6,})/i;

const group = (re, s) => (s.match(re) || [])[1] || '';

function merchantOf(text) {
  for (const re of MERCHANT) {
    for (const m of text.matchAll(re)) {
      const name = m[1].replace(/\s+/g, ' ').replace(/[\s.\-]+$/, '').trim();
      const l = name.toLowerCase();
      if (name.length < 2 || l === 'you' || l === 'your' || l.startsWith('your ') || l.startsWith('know') || l.startsWith('block')) continue;
      return name.length > 40 ? name.slice(0, 40) : name;
    }
  }
  return 'Unknown';
}

/**
 * Parses a bank debit SMS. Returns { txn } on success or { reason } explaining why it was skipped.
 * @param {string|null} sender  SMS sender header, or null when the text was pasted / shared
 */
export function explainSms(body, now = Date.now(), sender = null) {
  if (!body || !body.trim()) return { reason: 'Nothing to read yet.' };
  if (sender !== null && !/[A-Za-z]/.test(sender)) return { reason: 'Sent from a personal phone number, not a bank.' };
  if (body.length > 2000) body = body.slice(0, 2000);
  const text = body.replace(/[ \t\r]+/g, ' ').trim();
  const lower = text.toLowerCase();
  if (IGNORE.test(lower)) return { reason: 'Looks like an OTP, a reminder, or a failed or reversed payment, so nothing was spent.' };
  const debit = lower.search(DEBIT);
  const credit = lower.search(CREDIT);
  if (debit < 0) return { reason: credit >= 0 ? 'Money came in, not out. Only spending is tracked.' : 'No sign of money leaving an account.' };
  if (credit >= 0 && credit < debit) return { reason: 'Money came in, not out. Only spending is tracked.' };
  const raw = group(AMOUNT, lower) || group(AMOUNT_BARE, lower);
  const amount = raw ? parseFloat(raw.replace(/,/g, '')) : 0;
  if (!(amount > 0)) return { reason: 'Could not find an amount.' };

  const merchant = merchantOf(text);
  let account = group(ACCOUNT, text);
  if (account.length > 4) account = account.slice(-4);
  const refNo = group(REF, text) || group(REF_UPI_PATH, text);
  const timestamp = sender === null ? findDate(text, now) : now;
  return {
    txn: {
      id: refNo ? `ref-${refNo}-${Math.round(amount * 100)}` : `sms-${timestamp}-${Math.round(amount * 100)}`,
      amount, merchant, note: '', category: guessCategory(merchant, ''), timestamp,
      source: sender === null ? 'SHARED' : 'SMS', accountLast4: account, refNo,
    },
  };
}

// ---------- Dates inside an SMS ----------
const MONTHS = 'jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec';
const ISO = /\b(20\d{2})-(\d{1,2})-(\d{1,2})/;
const NAMED = new RegExp(`\\b(\\d{1,2})[- ]?(${MONTHS})[a-z]*[- ,]*(\\d{4}|\\d{2})(?!\\d)`, 'i');
const NUMERIC = /\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?![\d.])/;
const year = (y) => (y.length === 2 ? 2000 + +y : +y);

function pickDate(day, month, yr, now) {
  if (month < 1 || month > 12 || day < 1 || day > 31) return now;
  const d = new Date(yr, month - 1, day, 12, 0, 0, 0);
  if (d.getDate() !== day || d.getMonth() !== month - 1) return now; // e.g. 31 Feb
  const n = new Date(now);
  if (n.getFullYear() === yr && n.getMonth() === month - 1 && n.getDate() === day) return now;
  const t = d.getTime();
  if (t > now || t < now - 400 * 86_400_000) return now;
  return t;
}

export function findDate(text, now = Date.now()) {
  let m = text.match(ISO);
  if (m) return pickDate(+m[3], +m[2], +m[1], now);
  m = text.match(NAMED);
  if (m) return pickDate(+m[1], Math.floor(MONTHS.indexOf(m[2].toLowerCase()) / 4) + 1, year(m[3]), now);
  m = text.match(NUMERIC);
  if (m) return pickDate(+m[1], +m[2], year(m[3]), now);
  return now;
}

// ---------- Weekly insights ----------
export const HISTORY_WEEKS = 4;
export const needsDescription = (t) => !t.note || !t.note.trim();

export function weekStart(t) {
  const d = new Date(t);
  d.setHours(0, 0, 0, 0);
  d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
  return d.getTime();
}

export function addDays(t, days) {
  const d = new Date(t);
  d.setDate(d.getDate() + days);
  return d.getTime();
}

const sortedDesc = (map, limit = Infinity) =>
  [...map.entries()].sort((a, b) => b[1] - a[1]).slice(0, limit);
const pct = (v) => Math.round(v);

/** @param now current time; when it falls inside the week, comparisons and budget pace are pro-rated. */
export function buildReport(all, start, monthlyBudget = 0, now = Infinity) {
  const end = addDays(start, 7);
  let daysElapsed = 7;
  for (let d = 1; d <= 7; d++) if (addDays(start, d) > now) { daysElapsed = d; break; }
  const partial = daysElapsed < 7;
  const samePointEnd = addDays(start, -7 + daysElapsed);
  let prevSamePoint = 0;
  const priorStart = Array.from({ length: HISTORY_WEEKS }, (_, k) => addDays(start, -7 * (k + 1)));
  const priorTotals = new Array(HISTORY_WEEKS).fill(0);
  const priorCats = priorStart.map(() => new Map());
  const cats = new Map(), merchants = new Map(), merchantCount = new Map();
  const byDay = new Array(7).fill(0);
  const r = { weekStart: start, weekEnd: end, total: 0, prevTotal: 0, usualWeekly: 0, count: 0, pending: 0, tips: [] };
  let foodCount = 0, foodTotal = 0, smallCount = 0, smallTotal = 0, weekendTotal = 0, uncategorised = 0;
  const add = (map, k, v) => map.set(k, (map.get(k) || 0) + v);

  for (const t of all) {
    if (t.timestamp >= start && t.timestamp < end) {
      r.total += t.amount;
      r.count++;
      if (needsDescription(t)) r.pending++;
      add(cats, t.category, t.amount);
      const m = t.merchant || 'Unknown';
      add(merchants, m, t.amount);
      add(merchantCount, m, 1);
      if (t.category === FOOD) { foodCount++; foodTotal += t.amount; }
      if (t.category === UNCATEGORISED) uncategorised += t.amount;
      if (t.amount <= 100) { smallCount++; smallTotal += t.amount; }
      const dow = new Date(t.timestamp).getDay();
      byDay[(dow + 6) % 7] += t.amount;
      if (dow === 0 || dow === 6) weekendTotal += t.amount;
    } else {
      for (let k = 0; k < HISTORY_WEEKS; k++) {
        const e = k === 0 ? start : priorStart[k - 1];
        if (t.timestamp >= priorStart[k] && t.timestamp < e) {
          priorTotals[k] += t.amount;
          if (k === 0 && t.timestamp < samePointEnd) prevSamePoint += t.amount;
          add(priorCats[k], t.category, t.amount);
          break;
        }
      }
    }
  }

  r.prevTotal = priorTotals[0];
  r.daysElapsed = daysElapsed;
  r.prevSamePoint = prevSamePoint;
  const withData = priorTotals.filter((p) => p > 0);
  r.usualWeekly = withData.length ? withData.reduce((a, b) => a + b, 0) / withData.length : 0;
  r.byCategory = sortedDesc(cats);
  r.topMerchants = sortedDesc(merchants, 5);
  r.byDay = byDay;

  if (r.count === 0) {
    r.tips.push('No expenses recorded this week.');
    return r;
  }
  if (partial) {
    if (prevSamePoint > 0) {
      const change = (r.total - prevSamePoint) / prevSamePoint * 100;
      if (change >= 15) r.tips.push(`${daysElapsed} days in, you have spent ${fmt(r.total)}, ${pct(change)}% more than ${fmt(prevSamePoint)} by this point last week.`);
      else if (change <= -10) r.tips.push(`Nice: ${daysElapsed} days in, spending is ${pct(-change)}% below last week at this point (${fmt(r.total)} vs ${fmt(prevSamePoint)}).`);
    }
  } else if (r.prevTotal > 0) {
    const change = (r.total - r.prevTotal) / r.prevTotal * 100;
    if (change >= 15) r.tips.push(`You spent ${pct(change)}% more than last week (${fmt(r.total)} vs ${fmt(r.prevTotal)}).`);
    else if (change <= -10) r.tips.push(`Nice: spending is down ${pct(-change)}% from last week (${fmt(r.total)} vs ${fmt(r.prevTotal)}).`);
  }
  if (withData.length) {
    for (const [cat, value] of r.byCategory) {
      if (cat === UNCATEGORISED) continue;
      const usual = priorCats.reduce((s, pc) => s + (pc.get(cat) || 0), 0) / withData.length;
      const extra = value - usual;
      if (usual > 0 && value >= usual * 1.3 && extra >= 200) {
        r.tips.push(`${cat} is ${fmt(extra)} above your usual ${fmt(usual)} a week. Holding it at the usual level saves about ${fmt(extra * 4)} a month.`);
      }
    }
  }
  if (foodCount >= 4) {
    const avg = foodTotal / foodCount;
    r.tips.push(`You paid for food ${foodCount} times (${fmt(foodTotal)}, about ${fmt(avg)} each). Skipping two of those a week saves roughly ${fmt(avg * 8)} a month.`);
  }
  if (smallCount >= 5) r.tips.push(`${smallCount} small payments of ₹100 or less added up to ${fmt(smallTotal)}. These are the easiest to overlook.`);
  if (r.topMerchants.length) {
    const [name, value] = r.topMerchants[0];
    const share = value / r.total * 100;
    if (share >= 30 && (merchantCount.get(name) || 0) >= 2 && name !== 'Unknown') {
      r.tips.push(`${fmt(value)} (${pct(share)}% of the week) went to ${name} across ${merchantCount.get(name)} payments.`);
    }
  }
  if (r.count >= 4 && weekendTotal >= r.total * 0.5) {
    r.tips.push(`${fmt(weekendTotal)} of ${fmt(r.total)} was spent on Saturday and Sunday. Deciding a weekend amount in advance helps here.`);
  }
  if (monthlyBudget > 0) {
    const allowance = monthlyBudget * 7 / 30, soFar = monthlyBudget * daysElapsed / 30;
    const projected = r.total / daysElapsed * 30;
    if (r.total > soFar) {
      r.tips.push(`At this pace you will spend about ${fmt(projected)} this month, ${fmt(projected - monthlyBudget)} over your ${fmt(monthlyBudget)} budget. Aim for ${fmt(allowance)} a week.`);
    } else if (partial) {
      r.tips.push(`On track: ${fmt(r.total)} spent so far, within the ${fmt(soFar)} allowance for ${daysElapsed} days.`);
    } else {
      r.tips.push(`On track: ${fmt(r.total)} spent against a weekly allowance of ${fmt(allowance)}.`);
    }
  }
  if (r.pending > 0) r.tips.push(`${r.pending} payment${r.pending === 1 ? '' : 's'} still need a description. Adding them makes these tips more accurate.`);
  if (uncategorised >= r.total * 0.25) r.tips.push(`${fmt(uncategorised)} is uncategorised, so part of this week is a blind spot.`);
  return r;
}

// ---------- Sample data ----------
function rng(seed) { // mulberry32: small, seedable, good enough for demo data
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function sampleData(now = Date.now(), seed = 42) {
  const rand = rng(seed);
  const int = (n) => Math.floor(rand() * n);
  const out = [];
  const today = new Date(now); today.setHours(0, 0, 0, 0);
  const add = (day, hour, merchant, amount, note, category) => out.push({
    timestamp: day + hour * 3_600_000 + int(50) * 60_000, merchant, amount, note, source: 'SAMPLE',
    category: category || guessCategory(merchant, note), refNo: '', accountLast4: '',
  });
  for (let back = 34; back >= 0; back--) {
    const day = addDays(today.getTime(), -back);
    const dow = new Date(day).getDay();
    const weekend = dow === 0 || dow === 6;
    if (!weekend) add(day, 10, 'chaiwala@okaxis', 15 + 5 * int(4), 'morning chai');
    if (!weekend && int(10) < 6) add(day, 9, rand() < 0.5 ? 'RAPIDO' : 'UBER INDIA', 80 + int(180), 'ride to office');
    if (int(10) < (back < 7 ? 8 : 4)) add(day, 21, rand() < 0.5 ? 'SWIGGY' : 'ZOMATO', 180 + int(300), 'dinner order');
    if (dow === 0) add(day, 11, rand() < 0.5 ? 'BLINKIT' : 'BIG BASKET', 600 + int(900), 'weekly groceries');
    if (weekend && int(10) < 4) add(day, 19, 'PVR INOX', 350 + int(400), 'movie');
    if (weekend && int(10) < 5) add(day, 14, 'RAHUL SHARMA', 200 + 50 * int(10), 'split for lunch', 'Transfers');
    if (int(14) === 0) add(day, 16, 'AMAZON', 499 + int(2000), 'household items');
    if (back === 20) add(day, 12, 'AIRTEL', 299, 'phone recharge');
    if (back === 12) add(day, 8, 'NETFLIX', 199, 'subscription');
    if (back === 15) add(day, 18, 'APOLLO PHARMACY', 340 + int(200), 'medicines');
  }
  out.forEach((t, i) => {
    t.id = `sample-${i}`;
    if (t.timestamp > now) t.timestamp = now - (out.length - i) * 60_000;
    if (now - t.timestamp < 3 * 86_400_000) {
      t.note = '';
      if (guessCategory(t.merchant, '') === UNCATEGORISED) t.category = UNCATEGORISED;
    }
  });
  return out;
}
