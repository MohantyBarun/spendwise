// Mirrors the Java unit tests so the web demo parses SMS exactly like the app.
// Run: node --test web/test
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { explainSms, findDate, buildReport, sampleData, weekStart, addDays, FOOD, UNCATEGORISED } from '../js/core.js';

const TS = 1_794_900_000_000;
const parse = (sender, body, ts = TS) => explainSms(body, ts, sender).txn ?? null;

test('HDFC UPI', () => {
  const t = parse('VM-HDFCBK', 'Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 17/11/26\nRef 431234567890\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808');
  assert.equal(t.amount, 250);
  assert.equal(t.merchant, 'SWIGGY');
  assert.equal(t.accountLast4, '1234');
  assert.equal(t.refNo, '431234567890');
  assert.equal(t.category, FOOD);
});

test('SBI without currency prefix', () => {
  const t = parse('AD-SBIUPI', 'Dear UPI user A/C X1234 debited by 235.0 on date 17Nov26 trf to RAMESH KUMAR Refno 431234567891. If not u? call 1800111109. -SBI');
  assert.equal(t.amount, 235);
  assert.equal(t.merchant, 'RAMESH KUMAR');
  assert.equal(t.refNo, '431234567891');
  assert.equal(t.category, UNCATEGORISED);
});

test('ICICI debit that mentions credited', () => {
  const t = parse('JD-ICICIB', 'ICICI Bank Acct XX123 debited for Rs 349.00 on 17-Nov-26; ZOMATO credited. UPI:432112345678. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.');
  assert.equal(t.amount, 349);
  assert.equal(t.merchant, 'ZOMATO');
  assert.equal(t.accountLast4, '123');
  assert.equal(t.refNo, '432112345678');
});

test('Axis multi-line', () => {
  const t = parse('AX-AXISBK', 'INR 500.00 debited\nA/c no. XX1234\n17-11-26, 12:00:00\nUPI/P2M/431234567892/BIG BASKET\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank');
  assert.equal(t.amount, 500);
  assert.equal(t.merchant, 'BIG BASKET');
  assert.equal(t.refNo, '431234567892');
  assert.equal(t.category, 'Groceries');
});

test('Kotak VPA and card spend with lakh grouping', () => {
  const k = parse('VK-KOTAKB', 'Sent Rs.120.00 from Kotak Bank AC X1234 to chaiwala@okaxis on 17-11-26.UPI Ref 431234567894. Not you, https://kotak.com/KBANKT/Fraud');
  assert.equal(k.merchant, 'chaiwala@okaxis');
  assert.equal(k.refNo, '431234567894');
  const c = parse('VM-HDFCBK', 'Rs 1,29,999.00 spent on HDFC Bank Card x4321 at AMAZON on 2026-11-17:12:00:00. Not you? To block call 18002586161');
  assert.equal(c.amount, 129999);
  assert.equal(c.merchant, 'AMAZON');
  assert.equal(c.accountLast4, '4321');
  assert.equal(c.category, 'Shopping');
});

test('ignores credits, OTPs, reminders, failures, people and promos', () => {
  assert.equal(parse('VM-HDFCBK', 'Rs.5000.00 credited to a/c XX1234 on 17-11-26 by a/c linked to VPA friend@okaxis (UPI Ref no 431234567893).'), null);
  assert.equal(parse('VM-HDFCBK', '123456 is OTP for txn of INR 500.00 at AMAZON on card x4321. Do not share.'), null);
  assert.equal(parse('VM-HDFCBK', 'Rs 199.00 will be debited from A/c XX1234 on 20-11-26 for Netflix mandate.'), null);
  assert.equal(parse('VM-HDFCBK', 'Your UPI txn of Rs 300 to SWIGGY has failed. Amount if debited will be refunded.'), null);
  assert.equal(parse('+919876543210', 'Rs 500 debited from your a/c XX1234 to SCAMMER.'), null);
  assert.equal(parse('VM-HDFCBK', 'Get 10% off on your next order!'), null);
  assert.match(explainSms('Rs.5000.00 credited to a/c XX1234', TS).reason, /came in/);
});

test('dates in shared SMS', () => {
  const now = new Date(2026, 10, 17, 18, 30).getTime();
  const noon = (y, m, d) => new Date(y, m, d, 12).getTime();
  assert.equal(findDate('On 14/11/26 Ref 4312', now), noon(2026, 10, 14));
  assert.equal(findDate('on date 03Nov26 trf', now), noon(2026, 10, 3));
  assert.equal(findDate('on 30-Oct-26; ZOMATO', now), noon(2026, 9, 30));
  assert.equal(findDate('on 2026-11-02:12:00:00', now), noon(2026, 10, 2));
  assert.equal(findDate('16-11-2026, 12:00', now), noon(2026, 10, 16));
  assert.equal(findDate('On 17/11/26', now), now);
  assert.equal(findDate('on 25/12/26', now), now);
  assert.equal(findDate('on 31/02/26', now), now);
  assert.equal(findDate('Sent Rs.250.00 to SWIGGY', now), now);
  assert.equal(explainSms('Sent Rs.250.00 To SWIGGY On 14/11/26', now).txn.timestamp, noon(2026, 10, 14));
});

test('weekly report matches the Java engine', () => {
  const mon = new Date(2026, 10, 9).getTime();
  assert.equal(weekStart(mon + 3_600_000), mon);
  assert.equal(weekStart(addDays(mon, 6) + 23 * 3_600_000), mon);
  const tx = (d, h, amount, merchant, category, note) => ({ timestamp: addDays(mon, d) + h * 3_600_000, amount, merchant, category, note });
  const all = [];
  for (let d = 0; d < 5; d++) all.push(tx(d, 20, 300, 'SWIGGY', FOOD, 'dinner'));
  all.push(tx(2, 15, 2000, 'AMAZON', 'Shopping', 'headphones'));
  for (let d = 0; d < 5; d++) all.push(tx(d, 10, 20, 'chaiwala@okaxis', UNCATEGORISED, ''));
  all.push(tx(-3, 12, 600, 'ZOMATO', FOOD, 'lunch'), tx(-4, 12, 400, 'FLIPKART', 'Shopping', 'cable'),
    tx(-17, 12, 600, 'ZOMATO', FOOD, 'lunch'), tx(-40, 12, 99999, 'OLD', 'Other', 'old'), tx(7, 1, 5000, 'NEXT', 'Other', 'x'));
  const r = buildReport(all, mon, 12000);
  assert.equal(r.total, 3600);
  assert.equal(r.count, 11);
  assert.equal(r.pending, 5);
  assert.equal(r.prevTotal, 1000);
  assert.equal(r.usualWeekly, 800);
  const has = (s) => r.tips.some((t) => t.includes(s));
  assert.ok(has('260% more than last week'));
  assert.ok(has('Food & Dining is ₹900 above your usual ₹600'));
  assert.ok(has('You paid for food 5 times'));
  assert.ok(has('5 small payments'));
  assert.ok(has('over your ₹12,000 budget'));
  assert.ok(!has('Saturday and Sunday'));
});

test('week in progress compares like for like', () => {
  const mon = new Date(2026, 10, 9).getTime();
  const tx = (d, amount) => ({ timestamp: addDays(mon, d) + 12 * 3_600_000, amount, merchant: 'X', category: FOOD, note: 'n' });
  const all = [tx(-7, 300), tx(-3, 3000), tx(0, 500)];
  const r = buildReport(all, mon, 3000, addDays(mon, 1) + 19 * 3_600_000);
  assert.equal(r.daysElapsed, 2);
  assert.equal(r.prevSamePoint, 300);
  assert.ok(r.tips.some((t) => t.includes('2 days in, you have spent ₹500, 67% more than ₹300')));
  assert.ok(r.tips.some((t) => t.includes('At this pace you will spend about ₹7,500 this month')));
  const done = buildReport(all, mon, 0, addDays(mon, 9));
  assert.ok(done.tips.some((t) => t.includes('spending is down 85% from last week')));
});

test('sample data is realistic and reportable', () => {
  const now = new Date(2026, 10, 17, 18, 30).getTime();
  const s = sampleData(now, 42);
  assert.ok(s.length > 60);
  assert.equal(new Set(s.map((t) => t.id)).size, s.length);
  assert.ok(s.every((t) => t.timestamp <= now && t.amount > 0));
  const r = buildReport(s, weekStart(now), 20000);
  assert.ok(r.pending > 0 && r.usualWeekly > 0 && r.tips.length >= 3);
});
