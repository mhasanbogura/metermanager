#!/usr/bin/env node
/* Meter Manager — local static server + NESCO proxy.
   Run:  node server.js
   Open: http://localhost:3000

   The NESCO customer portal (customer.nesco.gov.bd) renders its data only
   during a POST that must reuse the session cookie issued by a preceding
   GET. Browsers cannot hold that session across a cross-origin CORS call,
   so a tiny server-side proxy is required. This file implements it.

   Endpoint:  GET /nesco?cust=XXXXXXXX
   Returns:   JSON { ok, info, rechargeHistory: [...] }
   */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3000;
const ROOT = __dirname;
const PANEL = 'https://customer.nesco.gov.bd/pre/panel';
const SUBMIT_HISTORY = '\u09B0\u09BF\u099A\u09BE\u09B0\u09CD\u099C \u09B9\u09BF\u09B8\u09CD\u099F\u09CD\u09B0\u09BF'; // রিচার্জ হিস্ট্রি
const SUBMIT_MONTHLY = '\u09AE\u09BE\u09B8\u09BF\u0995 \u09AC\u09CD\u09AF\u09AC\u09B9\u09BE\u09B0'; // মাসিক ব্যবহার

/* ---------- tiny https request helper ---------- */
function req(url, opts = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const headers = Object.assign(
      { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)', Accept: '*/*' },
      opts.headers || {}
    );
    const r = https.request(u, { method: opts.method || 'GET', headers }, res => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
    });
    r.on('error', reject);
    if (opts.body) r.write(opts.body);
    r.end();
  });
}

function sessionCookie(headers) {
  const set = headers['set-cookie'];
  if (!set) return '';
  const first = set[0].split(';')[0].trim();
  const keep = first.replace(/=.*$/, '');
  // keep same-named cookies across responses
  const out = set.map(c => c.split(';')[0].trim()).filter(c => c.startsWith(keep) || true);
  return out.join('; ');
}

function extractToken(html) {
  const m = html.match(/name="_token"\s+value="([^"]+)"/) || html.match(/_token" value="([^"]+)"/);
  return m ? m[1] : null;
}

function attr(html, re) {
  const m = html.match(re);
  return m ? m[1].trim() : '';
}
function parseHtml(html) {
  const start = html.indexOf('id="con_info_div"');
  if (start < 0) {
    return { info: {}, history: [] };
  }
  let end = html.indexOf('consumerRechargeData', start);
  if (end < 0) end = html.indexOf('arrear_notice_div', start);
  if (end < 0) end = html.length;
  const seg = html.slice(start, end);
  const inputs = [...seg.matchAll(/<input[^>]*(?:readonly|disabled)[^>]*value="([^"]*)"/g)].map(m => m[1]);
  const take = i => (inputs[i] || '').trim();
  let info = {
    name: take(0),
    fatherOrHusband: take(1),
    address: take(2),
    mobile: take(3),
    office: take(4),
    feeder: take(5),
    consumerNo: take(6),
    meterNo: take(7),
    sanctionedLoad: take(8),
    tariff: take(9),
    meterType: take(10),
    meterStatus: take(11),
    installDate: take(12),
    minimumRecharge: take(13),
    balance: take(14),
  };

  const hist = [];
  const reRows = /<[^>]*?consumerRechargeData[^>]*>/g;
  let m2;
  while ((m2 = reRows.exec(html))) {
    const tag = m2[0];
    const keys = ['order', 'token', 'seq', 'rent', 'demandcharge', 'tax', 'pfc', 'subsidyamount',
      'purchaseamount', 'totalamount', 'purchaseenergy', 'salename', 'purchasedate',
      'debtamount', 'paidamount', 'meterno', 'customerno', 'customername', 'tariff', 'organization'];
    const o = {};
    keys.forEach(k => { o[k] = attr(tag, new RegExp(`data-${k}=["']([^"']*)`, 'i')); });
    // Remote recharge status lives in the last <td> of the row (Success/Failed)
    let rechargeStatus = '';
    try {
      const cellStart = m2.index + tag.length;
      let rowEnd = html.indexOf('</tr>', cellStart);
      if (rowEnd < 0 || rowEnd - cellStart > 20000) rowEnd = Math.min(cellStart + 20000, html.length);
      const rowSeg = html.slice(cellStart, rowEnd);
      const reTds = /<td[^>]*>([\s\S]*?)<\/td>/gi;
      let m3;
      while ((m3 = reTds.exec(rowSeg))) {
        const cell = m3[1].replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').trim();
        if (cell) rechargeStatus = cell;
      }
      if (rechargeStatus.length > 30) rechargeStatus = '';
    } catch { rechargeStatus = ''; }
    o.rechargestatus = rechargeStatus;
    if (o.order || o.token) hist.push(o);
  }

  if (!info.consumerNo && hist.length) {
    const first = hist[0];
    info = {
      name: first.customername || '',
      fatherOrHusband: '',
      address: '',
      mobile: '',
      office: '',
      feeder: '',
      consumerNo: first.customerno || '',
      meterNo: first.meterno || '',
      sanctionedLoad: first.sanctionload || '',
      tariff: first.tariff || '',
      meterType: '',
      meterStatus: '',
      installDate: '',
      minimumRecharge: '',
      balance: '',
      organization: first.organization || '',
    };
  }

  return { info, history: hist };
}

/* ---------- type NESCO proxy ---------- */
async function nescoLookup(cust) {
  cust = String(cust).replace(/\D/g, '').slice(0, 11);
  if (!cust) throw new Error('missing customer number');

  async function fetchNesco(submit) {
    const get = await req(PANEL);
    const panelHtml = get.body.toString('utf8');
    const token = extractToken(panelHtml);
    if (!token) throw new Error('no csrf token');
    const cookie = sessionCookie(get.headers);
    if (!cookie) throw new Error('no session cookie');
    const form = '_token=' + encodeURIComponent(token) +
      '&cust_no=' + encodeURIComponent(cust) +
      '&submit=' + encodeURIComponent(submit);
    const resp = await req(PANEL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'Cookie': cookie,
        'Origin': 'https://customer.nesco.gov.bd',
        'Referer': PANEL,
      },
      body: form,
    });
    return { resp, panelHtml };
  }

  const { resp: histPost, panelHtml } = await fetchNesco(SUBMIT_HISTORY);
  if (histPost.status !== 200) throw new Error('nesco portal http ' + histPost.status);
  const histHtml = histPost.body.toString('utf8');
  const { info, history } = parseHtml(histHtml);
  if (!info.consumerNo && !info.name && !info.meterNo && !history.length) throw new Error('NESCO returned no customer data. The portal may have changed or blocked this request.');

  if (!info.balance) {
    const balPatterns = [
      /অবশিষ্ট ব্যালেন্স[\s\S]*?disabled[^>]*?value="?\s*(-?[\d,.]+)\s*"?/i,
      /অবশিষ্ট ব্যালেন্স[\s\S]*?value="?\s*(-?[\d,.]+)\s*"?/i,
      /(?:Remaining|অবশিষ্ট)\s*(?:balance|ব্যালেন্স)[\s\S]{0,300}?(-?[\d,]+\.?\d*)/,
      /data-balance=["'](-?[\d,.]+)/i,
    ];
    for (const pat of balPatterns) {
      const m = histHtml.match(pat) || (panelHtml && panelHtml.match(pat));
      if (m) { info.balance = m[1].replace(/[, ]/g, ''); break; }
    }
  }

  if (!info.readingDate) {
    const dateMatch = histHtml.match(/(\d{1,2}\s+(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{4})/i);
    if (dateMatch) info.readingDate = dateMatch[1].trim();
  }

  let monthlyUsage = [];
  try {
    const { resp: monthlyPost } = await fetchNesco(SUBMIT_MONTHLY);
    if (monthlyPost.status === 200) {
      monthlyUsage = parseMonthlyUsageHtml(monthlyPost.body.toString('utf8'));
    }
  } catch { /* monthly not available */ }

  return {
    info,
    monthlyUsage,
    history: history.map(h => {
      const num = v => { const n = parseFloat(String(v || 0).replace(/,/g, '')); return isNaN(n) ? 0 : n; };
      return {
        orderId: h.order || '', tokenNo: h.token || '', seqNo: h.seq || '',
        meterRent: num(h.rent), demandCharge: num(h.demandcharge),
        vat: num(h.tax), pfcCharge: num(h.pfc),
        subsidy: num(h.subsidyamount), electricityAmount: num(h.purchaseamount),
        rechargeAmount: num(h.totalamount), energyUnit: num(h.purchaseenergy),
        method: h.salename || '', rechargeDate: h.purchasedate || '',
        rechargeStatus: h.rechargestatus || '',
        debtAmount: num(h.debtamount), paidAmount: num(h.paidamount),
        meterNo: h.meterno || '', customerNo: h.customerno || '',
        customerName: h.customername || '', tariff: h.tariff || '', organization: h.organization || '',
      };
    }),
  };
}

const MONTH_MAP = {
  'january': '01', 'february': '02', 'march': '03', 'april': '04',
  'may': '05', 'june': '06', 'july': '07', 'august': '08',
  'september': '09', 'october': '10', 'november': '11', 'december': '12',
  'jan': '01', 'feb': '02', 'mar': '03', 'apr': '04',
  'jun': '06', 'jul': '07', 'aug': '08', 'sep': '09', 'oct': '10', 'nov': '11', 'dec': '12',
  'জানুয়ারি': '01', 'ফেব্রুয়ারি': '02', 'মার্চ': '03', 'এপ্রিল': '04',
  'মে': '05', 'জুন': '06', 'জুলাই': '07', 'আগস্ট': '08',
  'সেপ্টেম্বর': '09', 'অক্টোবর': '10', 'নভেম্বর': '11', 'ডিসেম্বর': '12',
};

function parseMonthlyUsageHtml(html) {
  const rows = [];

  let unitColIdx = -1;
  const theadMatch = html.match(/<thead[^>]*>([\s\S]*?)<\/thead>/i);
  if (theadMatch) {
    const ths = [...theadMatch[1].matchAll(/<th[^>]*>([\s\S]*?)<\/th>/gi)].map(m => m[1].replace(/<[^>]*>/g, '').trim());
    for (let i = 0; i < ths.length; i++) {
      if (ths[i].includes('\u0995\u09BF.\u0993.\u0986')) { unitColIdx = i; break; }
    }
  }

  const reTr = /<tr[^>]*>([\s\S]*?)<\/tr>/gi;
  let tr;
  while ((tr = reTr.exec(html))) {
    const tds = [...tr[1].matchAll(/<td[^>]*>([\s\S]*?)<\/td>/gi)].map(m => m[1].replace(/<[^>]*>/g, '').trim());
    if (tds.length < 5) continue;
    let year = null, month = null;
    for (const td of tds) {
      const lower = td.toLowerCase().trim();
      for (const [name, num] of Object.entries(MONTH_MAP)) {
        if (lower === name || lower.includes(name)) { month = num; break; }
      }
      if (month && year) break;
    }
    if (!month) continue;
    for (const td of tds) {
      if (month && /^\d{4}$/.test(td.trim())) { year = td.trim(); break; }
    }
    if (!year || !month) continue;
    const nums = tds.map(s => parseFloat(s.replace(/,/g, ''))).filter(n => !isNaN(n));
    if (nums.length < 3) continue;
    const unitVal = (unitColIdx >= 0 && unitColIdx < tds.length) ? parseFloat((tds[unitColIdx] || '0').replace(/,/g, '')) : nums[nums.length - 1];
    rows.push({ key: `${year}-${month}`, tds, nums, unit: isNaN(unitVal) ? 0 : unitVal });
  }
  return rows;
}

async function nescoMonthlyUsage(cust) {
  cust = String(cust).replace(/\D/g, '').slice(0, 11);
  if (!cust) throw new Error('missing customer number');

  const get = await req(PANEL);
  const token = extractToken(get.body.toString('utf8'));
  if (!token) throw new Error('no csrf token');
  const cookie = sessionCookie(get.headers);
  if (!cookie) throw new Error('no session cookie');

  const form = '_token=' + encodeURIComponent(token) +
    '&cust_no=' + encodeURIComponent(cust) +
    '&submit=' + encodeURIComponent(SUBMIT_MONTHLY);
  const post = await req(PANEL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Cookie': cookie,
      'Origin': 'https://customer.nesco.gov.bd',
      'Referer': PANEL,
    },
    body: form,
  });
  if (post.status !== 200) throw new Error('nesco portal http ' + post.status);
  const html = post.body.toString('utf8');
  return parseMonthlyUsageHtml(html);
}

/* ---------- NESCO debug ---------- */
async function nescoDebug(cust) {
  cust = String(cust).replace(/\D/g, '').slice(0, 11);
  if (!cust) throw new Error('missing customer number');

  async function fetchNesco(submit) {
    const get = await req(PANEL);
    const token = extractToken(get.body.toString('utf8'));
    if (!token) throw new Error('no csrf token');
    const cookie = sessionCookie(get.headers);
    if (!cookie) throw new Error('no session cookie');
    const form = '_token=' + encodeURIComponent(token) +
      '&cust_no=' + encodeURIComponent(cust) +
      '&submit=' + encodeURIComponent(submit);
    return req(PANEL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Cookie': cookie, 'Origin': 'https://customer.nesco.gov.bd', 'Referer': PANEL },
      body: form,
    });
  }

  const histPost = await fetchNesco(SUBMIT_HISTORY);
  const histHtml = histPost.body.toString('utf8');
  const { info, history } = parseHtml(histHtml);

  if (!info.readingDate) {
    const dateMatch = histHtml.match(/(\d{1,2}\s+(?:January|February|March|April|May|June|July|August|September|October|November|December)\s+\d{4})/i);
    if (dateMatch) info.readingDate = dateMatch[1].trim();
  }

  const monthlyPost = await fetchNesco(SUBMIT_MONTHLY);
  const monthlyHtml = monthlyPost.body.toString('utf8');
  const monthlyUsage = parseMonthlyUsageHtml(monthlyHtml);

  const balMatches = [];
  const balRe = /(?:balance|ব্যালেন্স|Remaining|বাকি|সুবিধা)[^<]{0,100}/gi;
  let bm;
  while ((bm = balRe.exec(histHtml))) balMatches.push(bm[0].slice(0, 150));
  const balRe2 = /(?:balance|ব্যালেন্স|Remaining|বাকি|সুবিধা)[^<]{0,100}/gi;
  while ((bm = balRe2.exec(monthlyHtml))) balMatches.push('monthly:' + bm[0].slice(0, 150));

  const balSnippet = histHtml.match(/অবশিষ্ট ব্যালেন্স[\s\S]{0,800}/i);
  const snippet = balSnippet ? balSnippet[0].replace(/\s+/g, ' ').slice(0, 800) : null;

  return { info, historyCount: history.length, monthlyUsageCount: monthlyUsage.length, readingDate: info.readingDate || null, snippet };
}

/* ---------- static server ---------- */
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/markdown; charset=utf-8',
};

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`);
  const corsHeaders = { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type' };
  if (req.method === 'OPTIONS') { res.writeHead(204, corsHeaders); res.end(); return; }
  if (url.pathname === '/nesco') {
    const cust = url.searchParams.get('cust') || '';
    if (url.searchParams.get('probe')) {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
      res.end(JSON.stringify({ ok: true, probe: true }));
      return;
    }
    if (url.searchParams.get('debug')) {
      nescoDebug(cust)
        .then(data => {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
          res.end(JSON.stringify({ ok: true, ...data }));
        })
        .catch(err => {
          res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
          res.end(JSON.stringify({ ok: false, error: err.message }));
        });
      return;
    }
    nescoLookup(cust)
      .then(data => {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
        res.end(JSON.stringify({ ok: true, ...data }));
      })
      .catch(err => {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
        res.end(JSON.stringify({ ok: false, error: err.message }));
      });
    return;
  }

  let p = decodeURIComponent(url.pathname);
  if (p === '/') p = '/index.html';
  if (/^\/desco\.html\/\d+/i.test(p)) p = '/desco.html';
  if (/^\/nesco\.html\/\d+/i.test(p)) p = '/nesco.html';
  const file = path.normalize(path.join(ROOT, p));
  if (!file.startsWith(ROOT)) { res.writeHead(403); res.end('403'); return; }
  fs.readFile(file, (err, buf) => {
    if (err) { res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }); res.end('404 Not Found'); return; }
    const ext = path.extname(file).toLowerCase();
    res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(buf);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`Meter Manager running on http://0.0.0.0:${PORT}`);
  console.log(`  NESCO proxy:  /nesco?v=...`);
});