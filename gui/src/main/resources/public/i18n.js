'use strict';

// ------------------------------------------------------------------ localization
//
// Every text of the interface has a key (e.g. "balances.title"). Each language is a JSON file in i18n/,
// named by its locale (en-US.json, pt-BR.json) and holding its own name in "_language". The server lists
// the files at /api/languages, so a new file adds a language to the header menu. A key missing from a
// language falls back to en-US. Logs, reports and error messages from the server stay in English.

const DEFAULT_LANGUAGE = 'pt-BR';
const FALLBACK_LANGUAGE = 'en-US';
const STORAGE_KEY = 'drexsim.lang';

let LANG = DEFAULT_LANGUAGE;
/** Locale for numbers, dates and times: the language's own tag. */
let LOCALE = DEFAULT_LANGUAGE;
let LANGUAGE_LIST = [];
let MESSAGES = {};
let FALLBACK = {};

const has = (key) => key in MESSAGES || key in FALLBACK;

/** Text of key in the current language, with {name} placeholders filled from vars. */
function tr(key, vars) {
  let s = MESSAGES[key] ?? FALLBACK[key] ?? key;
  if (vars) s = s.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
  return s;
}

/** Like tr, or fallback when no language has the key (e.g. text that comes from the server). */
const trOr = (key, fallback, vars) => (has(key) ? tr(key, vars) : fallback);

/** Plural text: key.one, key.other (and key.few, key.many where a language has them), with {n}. */
function trn(key, n, vars) {
  const form = `${key}.${new Intl.PluralRules(LOCALE).select(n)}`;
  return tr(has(form) ? form : `${key}.other`, { n, ...vars });
}

/** Number in the interface's locale. */
const num = (n, digits) => Number(n).toLocaleString(LOCALE, digits == null ? undefined
  : { minimumFractionDigits: digits, maximumFractionDigits: digits });

// Texts that come from the server in English, translated by their ids.
const opLabel = (op) => trOr(`operation.${op.id}.label`, op.label);
const opDescription = (op) => trOr(`operation.${op.id}.description`, op.description);
const opSigner = (op) => trOr(`operation.${op.id}.signer`, op.signer);
const fieldLabel = (op, f) => trOr(`operation.${op.id}.field.${f.name}`, trOr(`field.${f.name}`, f.label));
const statusLabel = (status) => trOr(`status.${status}`, status);
const jobTitle = (job) => trOr(`job.${job.kind}`, job.title);

async function fetchJson(path) {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${path}: ${res.status}`);
  return res.json();
}

/** Picks the saved language (or the default), and loads it and the fallback. */
async function loadLanguage() {
  try {
    LANGUAGE_LIST = await fetchJson('/api/languages');
  } catch (_) {
    LANGUAGE_LIST = [];
  }
  const codes = LANGUAGE_LIST.map((l) => l.code);
  let saved = null;
  try { saved = localStorage.getItem(STORAGE_KEY); } catch (_) { /* storage unavailable */ }
  LANG = [saved, DEFAULT_LANGUAGE, FALLBACK_LANGUAGE].find((c) => c && codes.includes(c)) || codes[0] || FALLBACK_LANGUAGE;
  LOCALE = LANG;
  const [messages, fallback] = await Promise.all([
    fetchJson(`/i18n/${LANG}.json`).catch(() => ({})),
    LANG === FALLBACK_LANGUAGE ? Promise.resolve(null) : fetchJson(`/i18n/${FALLBACK_LANGUAGE}.json`).catch(() => ({})),
  ]);
  MESSAGES = messages;
  FALLBACK = fallback || messages;
}

/** Translates the static page: [data-i18n] text and [data-i18n-placeholder|aria-label|title] attributes. */
function translatePage() {
  document.documentElement.lang = LANG;
  document.title = tr('app.title');
  document.querySelectorAll('[data-i18n]').forEach((el) => { el.textContent = tr(el.dataset.i18n); });
  for (const attr of ['placeholder', 'aria-label', 'title']) {
    document.querySelectorAll(`[data-i18n-${attr}]`).forEach((el) => el.setAttribute(attr, tr(el.getAttribute(`data-i18n-${attr}`))));
  }
  const picker = document.getElementById('lang');
  picker.innerHTML = LANGUAGE_LIST.map((l) =>
    `<option value="${l.code}" ${l.code === LANG ? 'selected' : ''}>${l.name}</option>`).join('');
  picker.closest('.lang').hidden = LANGUAGE_LIST.length < 2;
  picker.addEventListener('change', () => {
    try { localStorage.setItem(STORAGE_KEY, picker.value); } catch (_) { /* storage unavailable */ }
    location.reload();
  });
}

// The app renders text as it starts, so it runs once the language is loaded.
(async function boot() {
  await loadLanguage();
  translatePage();
  const app = document.createElement('script');
  app.src = 'app.js';
  document.body.appendChild(app);
})();
