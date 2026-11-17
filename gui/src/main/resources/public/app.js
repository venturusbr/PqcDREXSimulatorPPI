'use strict';

// ------------------------------------------------------------------ state and helpers

const state = {
  network: null,
  accounts: [],
  deployment: null,
  operations: [],
  snapshot: null,
  txs: new Map(),
  jobs: new Map(),
  selectedJob: null,
  nodes: [],
  abi: null,
  balancesLoading: false,
  balancesQueued: false,
};

const ALL_DSAS = ['SECP256K1', 'SECP256R1', 'MLDSA44', 'MLDSA65', 'MLDSA87', 'FALCON512', 'SECP256K1-MLDSA44', 'SECP256K1-FALCON512'];
const PROFILES = ['SECP256K1:std', ...ALL_DSAS];
const DSA_NOTES = {
  'SECP256K1': 'ECDSA, as on Ethereum. Classical: broken by a quantum computer.',
  'SECP256R1': 'ECDSA on the NIST P-256 curve. Classical.',
  'MLDSA44': 'ML-DSA (FIPS 204), security category 2. Key 1,312 B, signature 2,420 B.',
  'MLDSA65': 'ML-DSA, category 3. Key 1,952 B, signature 3,309 B.',
  'MLDSA87': 'ML-DSA, category 5. Key 2,592 B, signature 4,627 B.',
  'FALCON512': 'Falcon-512, category 1. Key 896 B, signature about 660 B (variable). The cheapest in gas.',
  'SECP256K1-MLDSA44': 'Hybrid: an ECDSA and an ML-DSA-44 signature. Safe as long as either holds.',
  'SECP256K1-FALCON512': 'Hybrid: an ECDSA and a Falcon-512 signature.',
};

const $ = (sel) => document.querySelector(sel);
const brl = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function money(cents) {
  return brl.format(Number(cents) / 100);
}

function shortAddr(a) {
  return a && a.length > 14 ? `${a.slice(0, 8)}…${a.slice(-4)}` : (a || '');
}

function family(dsa) {
  const d = String(dsa || '').toUpperCase();
  if (d.includes('MLDSA') || d.includes('FALCON')) return d.startsWith('SECP256K1-') ? 'hybrid' : 'pq';
  return 'classical';
}

function badge(label) {
  const dsa = String(label || '').replace(/\s*\(std\)$/, '');
  return `<span class="badge ${family(dsa)}" title="${esc(DSA_NOTES[dsa] || '')}">${esc(label)}</span>`;
}

function profileLabel(p) {
  return p.endsWith(':std') ? `${p.slice(0, -4)} (std)` : p;
}

function time(iso) {
  return iso ? new Date(iso).toLocaleTimeString() : '';
}

let toastTimer;
function toast(message) {
  const t = $('#toast');
  t.textContent = message;
  t.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.add('hidden'), 2600);
}

async function api(path, options = {}) {
  const init = { ...options, headers: { 'Content-Type': 'application/json', ...(options.headers || {}) } };
  if (init.body && typeof init.body !== 'string') init.body = JSON.stringify(init.body);
  const res = await fetch(path, init);
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok && !(data && 'ok' in data)) {
    const err = new Error((data && (data.error || data.message)) || `${res.status} ${res.statusText}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

document.addEventListener('click', (e) => {
  const copy = e.target.closest('[data-copy]');
  if (copy) {
    navigator.clipboard?.writeText(copy.dataset.copy);
    toast(`Copied ${shortAddr(copy.dataset.copy)}`);
  }
});

// ------------------------------------------------------------------ tabs

document.querySelectorAll('.tab').forEach((tab) => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t === tab));
    document.querySelectorAll('.panel').forEach((p) => p.classList.toggle('active', p.id === `tab-${tab.dataset.tab}`));
    try { localStorage.setItem('drexsim.tab', tab.dataset.tab); } catch (_) { /* storage unavailable */ }
  });
});
try {
  const saved = localStorage.getItem('drexsim.tab');
  if (saved) document.querySelector(`.tab[data-tab="${saved}"]`)?.click();
} catch (_) { /* storage unavailable */ }

// ------------------------------------------------------------------ network

async function loadNetwork() {
  try {
    state.network = await api('/api/network');
  } catch (e) {
    state.network = { reachable: false, error: e.message };
  }
  renderNetwork();
}

function renderNetwork() {
  const n = state.network || {};
  $('#netdot').className = `dot ${n.reachable ? 'up' : 'down'}`;
  $('#nettext').textContent = n.reachable
    ? `Chain ${n.chainId} · block #${n.block} · ${(n.validators || []).length} validators`
    : `Node unreachable${n.error ? `: ${n.error}` : ''}`;

  const kv = [
    ['Primary RPC endpoint', esc(n.rpc)],
    ['Chain id', esc(n.chainId)],
    ['Latest block', esc(n.block)],
    ['Validators', (n.validators || []).map((v) => `<span class="addr" data-copy="${esc(v)}">${esc(v)}</span>`).join('<br>') || '–'],
    ['Account DSAs accepted', (n.accountDsas || []).map(badge).join(' ') || '–'],
  ];
  $('#net-kv').innerHTML = kv.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('');

  const dsaSelect = $('#account-dsa');
  if (dsaSelect && !dsaSelect.options.length) {
    const dsas = n.accountDsas && n.accountDsas.length ? n.accountDsas : ALL_DSAS;
    dsaSelect.innerHTML = dsas.map((d) => `<option ${d === 'SECP256K1-MLDSA44' ? 'selected' : ''}>${esc(d)}</option>`).join('');
    updateStandardToggle();
  }
}

async function loadNodes() {
  try {
    const nodes = await api('/api/nodes');
    const changed = nodes.map((n) => n.name + n.url).join() !== state.nodes.map((n) => n.name + n.url).join();
    state.nodes = nodes;
    renderNodes();
    if (changed) {
      renderOperationForm();
      $('#bench-nodes').placeholder = nodes.map((n) => n.url).join(',') || 'http://localhost:8545,http://localhost:8546';
    }
  } catch (e) {
    $('#nodes').innerHTML = `<tbody><tr class="empty-row"><td>Could not list the nodes: ${esc(e.message)}</td></tr></tbody>`;
  }
}

function renderNodes() {
  const best = Math.max(0, ...state.nodes.filter((n) => n.reachable).map((n) => n.block));
  const rows = state.nodes.map((n) => {
    const lag = n.reachable && best - n.block > 1 ? ` <span class="small">(${best - n.block} behind)</span>` : '';
    return `<tr>
      <td><b>${esc(n.name)}</b>${n.primary ? ' <span class="small">primary</span>' : ''}</td>
      <td><span class="addr" data-copy="${esc(n.url)}">${esc(n.url)}</span></td>
      <td><span class="status ${n.reachable ? 'success' : 'failed'}" title="${esc(n.error || '')}">${n.reachable ? 'up' : 'down'}</span></td>
      <td class="num">${n.block != null ? `#${n.block}` : '–'}${lag}</td>
      <td class="num">${n.peers ?? '–'}</td>
      <td>${n.validator ? `<span class="addr" data-copy="${esc(n.validator)}">${esc(n.validator)}</span>` : '<span class="small">–</span>'}</td>
      <td><span class="small">${esc(n.p2p || '–')}</span></td>
      <td class="num">${n.nodeKeyBytes ? `${n.nodeKeyBytes.toLocaleString()} B` : '–'}</td>
    </tr>`;
  }).join('');
  $('#nodes').innerHTML = `<thead><tr><th>Node</th><th>RPC endpoint</th><th>Status</th><th class="num">Block</th><th class="num">Peers</th>
    <th>Validator address</th><th>P2P address</th><th class="num">Node public key</th></tr></thead>
    <tbody>${rows || '<tr class="empty-row"><td colspan="8">No nodes found.</td></tr>'}</tbody>`;
}

$('#btn-rescan').addEventListener('click', async (e) => {
  e.target.disabled = true;
  try {
    const found = await api('/api/nodes/rescan', { method: 'POST' });
    toast(`${found.length} node${found.length === 1 ? '' : 's'} found`);
    await loadNodes();
  } catch (err) {
    toast(err.message);
  } finally {
    e.target.disabled = false;
  }
});

function onBlock(number) {
  if (state.network) {
    state.network.block = number;
    state.network.reachable = true;
    renderNetwork();
  }
  refreshBalances();
}

// ------------------------------------------------------------------ deployment and balances

async function loadDeployment() {
  try {
    state.deployment = await api('/api/deployment');
  } catch (e) {
    state.deployment = null;
  }
  $('#no-deployment').classList.toggle('hidden', !!state.deployment);
  renderContracts();
  renderOperationForm();
}

function renderContracts() {
  const d = state.deployment;
  if (!d) {
    $('#contracts').innerHTML = '<tbody><tr class="empty-row"><td>No Drex network deployed on this chain yet.</td></tr></tbody>';
    return;
  }
  const rows = [
    ['RealDigital', d.realDigital], ['STR', d.str], ['RealDigitalEnableAccount', d.realDigitalEnableAccount],
    ['RealDigitalDefaultAccount', d.realDigitalDefaultAccount], ['KeyDictionary', d.keyDictionary],
    ['SwapOneStep', d.swapOneStep], ['SwapTwoSteps', d.swapTwoSteps], ['AddressDiscovery', d.addressDiscovery],
    ...(d.realTokenizados || []).map((t) => [`RealTokenizado ${t.symbol} (${t.participant})`, t.address]),
  ];
  $('#contracts').innerHTML = `<thead><tr><th>Contract</th><th>Address</th></tr></thead><tbody>${rows
    .map(([name, addr]) => `<tr><td>${esc(name)}</td><td><span class="addr" data-copy="${esc(addr)}">${esc(addr)}</span></td></tr>`)
    .join('')}</tbody>`;
}

async function refreshBalances() {
  if (!state.deployment) {
    renderBalances();
    return;
  }
  if (state.balancesLoading) {
    state.balancesQueued = true;
    return;
  }
  state.balancesLoading = true;
  try {
    const snap = await api('/api/balances');
    renderBalances(snap, state.snapshot);
    const rolesChanged = JSON.stringify(snap.wallets.map((w) => [w.wallet.address, w.roles]))
      !== JSON.stringify(state.snapshot?.wallets.map((w) => [w.wallet.address, w.roles]));
    state.snapshot = snap;
    if (rolesChanged) renderAccounts();
  } catch (e) {
    if (e.status === 404) { state.deployment = null; $('#no-deployment').classList.remove('hidden'); }
  } finally {
    state.balancesLoading = false;
    if (state.balancesQueued) {
      state.balancesQueued = false;
      setTimeout(refreshBalances, 300);
    }
  }
}

/* Role icons (24×24, stroked with currentColor). */
const ROLE_ICONS = {
  bcb: '<path d="M3 21h18M4 10h16M12 3 3.5 8h17Z"/><path d="M6 10v8M10 10v8M14 10v8M18 10v8M4.5 18h15"/>',
  participant: '<path d="M3 21h18M5 21V7l7-4 7 4v14"/><path d="M9 21v-4h6v4M9 9h.01M12 9h.01M15 9h.01M9 13h.01M12 13h.01M15 13h.01"/>',
  reserve: '<rect x="3" y="4" width="18" height="15" rx="2"/><circle cx="12" cy="11.5" r="3.5"/><path d="M12 8v1M12 14v1M8.5 11.5h1M14.5 11.5h1M7 19v2M17 19v2"/>',
  client: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
  none: '<circle cx="12" cy="12" r="8"/><path d="M6.5 17.5l11-11"/>',
};
const ROLE_LABELS = {
  bcb: 'BCB: administers Real Digital',
  participant: 'Participant: enabled in Real Digital',
  reserve: 'Reserve wallet of a Real Tokenizado',
  client: 'Client: enabled in a Real Tokenizado',
  none: 'Not enabled in any Drex token',
};
const ROLE_SHORT = { bcb: 'BCB', participant: 'participant', reserve: 'reserve', client: 'client' };

const roleIcon = (role, title) =>
  `<span class="role ${role}" title="${esc(title)}"><svg viewBox="0 0 24 24" aria-hidden="true">${ROLE_ICONS[role]}</svg></span>`;

/** Role icons of a balances row: what the wallet is on chain, as the snapshot read it. */
function roleIcons(w) {
  const r = w.roles || { roles: [], clientOf: [] };
  if (!r.roles.length) return roleIcon('none', ROLE_LABELS.none);
  return r.roles.map((role) => roleIcon(role,
    role === 'client' ? `Client of ${r.clientOf.join(', ')}: enabled in its Real Tokenizado` : ROLE_LABELS[role])).join('');
}

$('#role-legend').innerHTML = Object.entries(ROLE_SHORT)
  .map(([role, label]) => `<span>${roleIcon(role, ROLE_LABELS[role])}${label}</span>`).join('');

function amountHtml(h, tag = '') {
  const frozen = h.frozen ? `<span class="frozen" title="Frozen: cannot be spent">frozen ${money(h.frozen)}</span>` : '';
  return `<span class="${h.balance ? '' : 'zero'}">${money(h.balance)}</span>${tag}${frozen}`;
}

const NO_HOLDING = { balance: 0, frozen: 0 };
const tokenTag = (t) => ` <span class="token-tag" title="${esc(t.name)}">${esc(t.symbol)}</span>`;

function renderBalances(snap = state.snapshot, prev = null) {
  const table = $('#balances');
  if (!snap) {
    table.innerHTML = '<tbody><tr class="empty-row"><td>Balances appear once a Drex network is deployed.</td></tr></tbody>';
    $('#balances-meta').textContent = '';
    return;
  }
  const hideEmpty = $('#hide-empty').checked;
  const rd = snap.tokens.find((t) => t.symbol === 'BRL');
  const rts = snap.tokens.filter((t) => t.symbol !== 'BRL');
  const before = (addr, symbol) => prev?.wallets.find((w) => w.wallet.address === addr)?.holdings[symbol];
  const changed = (w, symbol) => {
    const old = before(w.wallet.address, symbol);
    const h = w.holdings[symbol] || NO_HOLDING;
    return old && (old.balance !== h.balance || old.frozen !== h.frozen);
  };
  const holds = (w, symbol) => { const h = w.holdings[symbol]; return h && (h.balance || h.frozen); };

  let rdSum = 0;
  const rtSums = Object.fromEntries(rts.map((t) => [t.symbol, 0]));
  const rows = snap.wallets
    .filter((w) => !hideEmpty || snap.tokens.some((t) => holds(w, t.symbol)))
    .map((w) => {
      const h = w.holdings.BRL || NO_HOLDING;
      rdSum += Number(h.balance);
      const held = rts.filter((t) => holds(w, t.symbol));
      held.forEach((t) => { rtSums[t.symbol] += Number(w.holdings[t.symbol].balance); });
      const rtCell = held.length
        ? held.map((t) => `<div class="rt-line">${amountHtml(w.holdings[t.symbol], tokenTag(t))}</div>`).join('')
        : amountHtml(NO_HOLDING, ' <span class="token-tag none">–</span>');
      return `<tr>
        <td><span class="wallet-name"><span class="roles">${roleIcons(w)}</span>${esc(w.wallet.label)}</span></td>
        <td>${badge(w.wallet.signing)}</td>
        <td><span class="addr" data-copy="${esc(w.wallet.address)}">${esc(shortAddr(w.wallet.address))}</span></td>
        <td class="num ${changed(w, 'BRL') ? 'flash' : ''}">${amountHtml(h)}</td>
        <td class="num rt ${rts.some((t) => changed(w, t.symbol)) ? 'flash' : ''}">${rtCell}</td>
      </tr>`;
    }).join('');
  const perToken = (values) => rts.map((t) => `<div class="rt-line">${money(values[t.symbol] || 0)}${tokenTag(t)}</div>`).join('')
    || money(0);
  table.innerHTML = `<thead><tr><th>Wallet</th><th>Signs with</th><th>Address</th>
      <th class="num" title="${esc(rd?.name || 'Real Digital')}">RD (BRL)</th><th class="num" title="Real Tokenizado, tagged with its token">RT</th></tr></thead>
    <tbody>${rows || '<tr class="empty-row"><td colspan="5">No wallets with a balance.</td></tr>'}</tbody>
    <tfoot><tr><td colspan="3">Sum of the wallets shown</td><td class="num">${money(rdSum)}</td><td class="num rt">${perToken(rtSums)}</td></tr>
    <tr class="supply"><td colspan="3">Total supply on chain</td><td class="num">${money(snap.totalSupply.BRL || 0)}</td><td class="num rt">${perToken(snap.totalSupply)}</td></tr></tfoot>`;

  $('#balances-meta').textContent = `Block #${snap.blockNumber}`;
}

$('#hide-empty').addEventListener('change', () => renderBalances());

// ------------------------------------------------------------------ accounts

async function loadAccounts() {
  state.accounts = await api('/api/accounts');
  renderAccounts();
  renderOperationForm();
}

function renderAccounts() {
  const byAddress = new Map((state.snapshot?.wallets || []).map((w) => [w.wallet.address.toLowerCase(), w]));
  const rows = state.accounts.map((a) => {
    const w = byAddress.get(a.address.toLowerCase());
    // Roles are read with the balances; an account created since the last snapshot has none yet.
    const roles = w ? `<span class="roles">${roleIcons(w)}</span>` : (state.deployment ? '<span class="roles"></span>' : '');
    return `<tr>
      <td><span class="wallet-name">${roles}${esc(a.name)}</span></td>
      <td>${badge(a.signing)}</td>
      <td>${a.format === 'STANDARD' ? 'standard 0x02' : 'flexible 0x42'}</td>
      <td><span class="addr" data-copy="${esc(a.address)}">${esc(a.address)}</span></td>
      <td class="num">${a.publicKeyBytes.toLocaleString()} B</td>
    </tr>`;
  }).join('');
  $('#accounts').innerHTML = `<thead><tr><th>Name</th><th>Signs with</th><th>Transactions</th><th>Address</th><th class="num">Public key</th></tr></thead>
    <tbody>${rows || '<tr class="empty-row"><td colspan="5">No accounts yet. Create one, or set up the demo network.</td></tr>'}</tbody>`;
}

function updateStandardToggle() {
  const dsa = $('#account-dsa').value;
  const box = $('#account-standard');
  const allowed = dsa === 'SECP256K1' || dsa === 'SECP256R1';
  box.disabled = !allowed;
  if (!allowed) box.checked = false;
}
$('#account-dsa').addEventListener('change', updateStandardToggle);

$('#dsa-notes').innerHTML = Object.entries(DSA_NOTES).map(([d, note]) => `<dt>${badge(d)}</dt><dd>${esc(note)}</dd>`).join('');

$('#account-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = e.target;
  const out = $('#account-result');
  try {
    const a = await api('/api/accounts', { method: 'POST', body: { name: form.name.value.trim(), dsa: form.dsa.value, standard: form.standard.checked } });
    out.className = 'op-result ok';
    out.innerHTML = `Created <b>${esc(a.name)}</b> (${esc(a.signing)}) at <span class="addr" data-copy="${esc(a.address)}">${esc(a.address)}</span>`;
    form.name.value = '';
    await loadAccounts();
    refreshBalances();
  } catch (err) {
    out.className = 'op-result err';
    out.textContent = err.message;
  }
});

// ------------------------------------------------------------------ operations

async function loadOperations() {
  state.operations = (await api('/api/operations')).sort((a, b) => a.flow - b.flow);
  $('#op-select').innerHTML = state.operations
    .map((o) => `<option value="${esc(o.id)}">${o.flow}. ${esc(o.label)}</option>`).join('');
  const preferred = state.operations.find((o) => o.id === 'swap');
  if (preferred) $('#op-select').value = preferred.id;
  renderOperationForm();
}

const OTHER_WALLET = '__other__';

// Selects an account by name, or switches the field to its free-text box for any other value.
function setWallet(form, name, value) {
  const select = form.querySelector(`select[data-wallet="${name}"]`);
  const other = form.querySelector(`input[data-for="${name}"]`);
  const known = value && value !== OTHER_WALLET && [...select.options].some((o) => o.value === value);
  const useOther = value === OTHER_WALLET || (value && !known);
  select.value = useOther ? OTHER_WALLET : (known ? value : select.options[0]?.value);
  select.name = useOther ? '' : name;
  other.name = useOther ? name : '';
  other.required = useOther;
  other.classList.toggle('hidden', !useOther);
  if (useOther && value !== OTHER_WALLET) other.value = value;
}

$('#op-form').addEventListener('change', (e) => {
  const name = e.target.dataset?.wallet;
  if (name) {
    setWallet(e.currentTarget, name, e.target.value);
    if (e.target.value === OTHER_WALLET) e.currentTarget.querySelector(`input[data-for="${name}"]`).focus();
  }
});

$('#op-select').addEventListener('change', () => {
  $('#op-result').classList.add('hidden');
  renderOperationForm();
});

function tokenSymbols(includeRd) {
  const rts = (state.deployment?.realTokenizados || []).map((t) => t.symbol);
  return includeRd ? ['BRL', ...rts] : rts;
}

function renderOperationForm() {
  const op = state.operations.find((o) => o.id === $('#op-select').value);
  const form = $('#op-form');
  if (!op) { form.innerHTML = ''; return; }
  $('#op-desc').innerHTML = `<span class="flow">Flow ${op.flow}</span>${esc(op.description)} Signed by ${esc(op.signer)}.`;

  const previous = Object.fromEntries(new FormData(form).entries());
  const accountOptions = state.accounts.map((a) => `<option value="${esc(a.name)}">${esc(a.name)} · ${esc(a.signing)}</option>`).join('');
  const walletOptions = state.accounts.map((a) => `<option value="${esc(a.name)}">${esc(a.name)}</option>`).join('');
  const fields = op.fields.map((f) => {
    let input;
    switch (f.type) {
      case 'account':
        input = `<select name="${f.name}" required>${accountOptions}</select>`;
        break;
      case 'wallet':
        // A real select of the simulated accounts; "Other address" swaps in a text box for any 0x address.
        input = `<select name="${f.name}" data-wallet="${f.name}" required>${walletOptions}
            <option value="${OTHER_WALLET}">Other address…</option></select>
          <input class="wallet-other hidden" data-for="${f.name}" placeholder="0x address" pattern="0x[0-9a-fA-F]{40}">`;
        break;
      case 'token':
      case 'rt':
        input = `<select name="${f.name}" required>${tokenSymbols(f.type === 'token')
          .map((s) => `<option>${esc(s)}</option>`).join('')}</select>`;
        break;
      case 'amount':
        input = `<input name="${f.name}" required inputmode="decimal" placeholder="1.000,00">`;
        break;
      case 'number':
        input = `<input name="${f.name}" required inputmode="numeric">`;
        break;
      default:
        input = `<input name="${f.name}" required>`;
    }
    return `<label class="field"><span>${esc(f.label)}</span>${input}</label>`;
  }).join('');

  const nodeOptions = (state.nodes.length ? state.nodes : [{ name: 'node-1', url: state.network?.rpc || '', primary: true, reachable: true }])
    .map((n) => `<option value="${esc(n.name)}" ${n.primary ? 'selected' : ''} ${n.reachable === false ? 'disabled' : ''}>${esc(n.name)} · ${esc(n.url.replace(/^https?:\/\//, ''))}${n.primary ? ' (primary)' : ''}${n.reachable === false ? ' – down' : ''}</option>`).join('');
  const nodeField = `<label class="field"><span>Send to node</span><select name="node">${nodeOptions}</select></label>`;

  form.innerHTML = `${fields}${nodeField}<button class="primary" type="submit">Sign and send</button>`;
  for (const [k, v] of Object.entries(previous)) {
    const wallet = form.querySelector(`select[data-wallet="${k}"]`);
    if (wallet) setWallet(form, k, v);
    else if (form.elements[k]) {
      const el = form.elements[k];
      // A select keeps its default when the old value is no longer offered (or is a node that went down).
      if (el.tagName !== 'SELECT' || [...el.options].some((o) => o.value === v && !o.disabled)) el.value = v;
    }
  }
  // Paying yourself is never what is meant: default the receiver to another account.
  if (form.elements.from && form.querySelector('select[data-wallet="to"]') && form.elements.to.value === form.elements.from.value) {
    const from = form.elements.from.value;
    const prefix = from.replace(/[^-]*$/, '');
    const other = state.accounts.find((a) => a.name !== from && prefix && a.name.startsWith(prefix))
      || state.accounts.find((a) => a.name !== from);
    if (other) form.elements.to.value = other.name;
  }
  // A payment to another bank goes between two different Real Tokenizados.
  if (op.id === 'swap' && form.elements.fromToken.value === form.elements.toToken.value) {
    const other = tokenSymbols(false).find((s) => s !== form.elements.fromToken.value);
    if (other) form.elements.toToken.value = other;
  }
}

$('#op-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = e.target;
  const op = $('#op-select').value;
  const params = Object.fromEntries(new FormData(form).entries());
  const out = $('#op-result');
  const button = form.querySelector('button');
  button.disabled = true;
  out.className = 'op-result busy';
  out.textContent = 'Signing and sending; waiting for the block that includes it…';
  try {
    const r = await api(`/api/operations/${op}`, { method: 'POST', body: params });
    out.className = `op-result ${r.ok ? 'ok' : 'err'}`;
    out.innerHTML = r.ok
      ? `${esc(r.message)}${r.signer ? `, signed by <b>${esc(r.signer)}</b>` : ''}${r.node ? ` via ${esc(r.node)}` : ''}.${(r.transactions || [])
        .map((h) => ` <button class="link" data-details="${esc(h)}" title="Show details">${esc(shortAddr(h))}</button>`).join('')}`
      : `Failed: ${esc(r.message)}`;
    refreshBalances();
  } catch (err) {
    out.className = 'op-result err';
    out.textContent = `Failed: ${err.message}`;
  } finally {
    button.disabled = false;
  }
});

// ------------------------------------------------------------------ transactions

async function loadTransactions() {
  for (const t of await api('/api/transactions?limit=200')) state.txs.set(t.id, t);
  renderTransactions();
}

function renderTransactions() {
  const rows = [...state.txs.values()].sort((a, b) => b.id - a.id).slice(0, 200).map((t) => {
    const gas = t.gasUsed != null ? `${t.gasUsed.toLocaleString()} <span class="small">/ ${t.gasLimit.toLocaleString()}</span>` : `<span class="small">limit ${t.gasLimit.toLocaleString()}</span>`;
    const keyBytes = t.publicKeyBytes ? `${t.publicKeyBytes.toLocaleString()} B` : 'recovered';
    return `<tr>
      <td class="small">${time(t.time)}</td>
      <td>${esc(t.operation || '–')}</td>
      <td>${esc(t.account)}</td>
      <td>${badge(t.dsa)} <span class="small">${esc(t.type)}</span></td>
      <td class="num">${(t.size / 1024).toFixed(1)} KB</td>
      <td class="num">${t.signatureBytes.toLocaleString()} B <span class="small">/ ${keyBytes}</span></td>
      <td class="num">${gas}</td>
      <td><span class="status ${esc(t.status)}" title="${esc(t.error || '')}">${esc(t.status)}</span>${t.block != null ? ` <span class="small">#${t.block}</span>` : ''}</td>
      <td><span class="hash" data-copy="${esc(t.hash)}" title="${esc(t.hash)}">${esc(shortAddr(t.hash))}</span></td>
      <td>${esc(t.node || '–')}</td>
      <td>${t.hash && t.status !== 'rejected' ? `<button class="small-btn" data-details="${esc(t.hash)}" data-node="${esc(t.node || '')}">Details</button>` : ''}</td>
    </tr>`;
  }).join('');
  $('#txs').innerHTML = `<thead><tr><th>Time</th><th>Operation</th><th>Account</th><th>Signs with</th><th class="num">Size</th>
    <th class="num">Signature / key</th><th class="num">Gas used / limit</th><th>Status</th><th>Hash</th><th>Node</th><th aria-label="Details"></th></tr></thead>
    <tbody>${rows || '<tr class="empty-row"><td colspan="11">No transactions yet. Run an operation.</td></tr>'}</tbody>`;
}

// ------------------------------------------------------------------ transaction details

/** Names for the addresses the simulator knows: accounts, Drex contracts and validators. */
function addressBook() {
  const book = new Map();
  const put = (addr, name, kind) => { if (addr) book.set(addr.toLowerCase(), { name, kind }); };
  put(`0x${'0'.repeat(40)}`, 'zero address (mint / burn)', 'zero');
  const d = state.deployment;
  if (d) {
    put(d.realDigital, 'RealDigital', 'contract');
    put(d.str, 'STR', 'contract');
    put(d.realDigitalEnableAccount, 'RealDigitalEnableAccount', 'contract');
    put(d.realDigitalDefaultAccount, 'RealDigitalDefaultAccount', 'contract');
    put(d.keyDictionary, 'KeyDictionary', 'contract');
    put(d.swapOneStep, 'SwapOneStep', 'contract');
    put(d.swapTwoSteps, 'SwapTwoSteps', 'contract');
    put(d.addressDiscovery, 'AddressDiscovery', 'contract');
    for (const t of d.realTokenizados || []) put(t.address, `RealTokenizado ${t.symbol}`, 'contract');
  }
  for (const n of state.nodes) if (n.validator) put(n.validator, `validator ${n.name}`, 'validator');
  for (const a of state.accounts) put(a.address, a.name, 'account');
  return book;
}

function addressHtml(addr, book) {
  if (!addr) return '<span class="small">–</span>';
  const known = book.get(String(addr).toLowerCase());
  const label = known ? `<b>${esc(known.name)}</b> ` : '';
  return `${label}<span class="addr" data-copy="${esc(addr)}" title="${esc(addr)}">${esc(known ? shortAddr(addr) : addr)}</span>`;
}

const hexBytes = (h) => (h && h.length > 2 ? (h.length - 2) / 2 : 0);
const qty = (h) => (h == null ? null : BigInt(h));
const fmtInt = (n) => (n == null ? '–' : BigInt(n).toLocaleString('en-US'));

/** Cents as R$ with BigInt precision. */
function brlBig(cents) {
  const neg = cents < 0n;
  const abs = neg ? -cents : cents;
  const units = (abs / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  return `${neg ? '-' : ''}R$ ${units},${(abs % 100n).toString().padStart(2, '0')}`;
}

// ---- minimal ABI decoding: static types, string, bytes and arrays of static types

const isDynamic = (t) => t === 'string' || t === 'bytes' || t.endsWith('[]') || t.startsWith('(');

function word(data, byteOffset) {
  const w = data.slice(byteOffset * 2, byteOffset * 2 + 64);
  if (w.length < 64) throw new Error('short data');
  return w;
}

function decodeStatic(type, w) {
  if (type === 'address') return { kind: 'address', value: `0x${w.slice(24)}` };
  if (type === 'bool') return { kind: 'bool', value: BigInt(`0x${w}`) !== 0n };
  if (/^uint\d*$/.test(type)) return { kind: 'uint', value: BigInt(`0x${w}`) };
  if (/^int\d*$/.test(type)) {
    let v = BigInt(`0x${w}`);
    if (v >= 1n << 255n) v -= 1n << 256n;
    return { kind: 'int', value: v };
  }
  if (/^bytes\d+$/.test(type)) return { kind: 'bytes', value: `0x${w.slice(0, Number(type.slice(5)) * 2)}` };
  throw new Error(`unsupported ${type}`);
}

function decodeAt(type, data, head) {
  if (!isDynamic(type)) return decodeStatic(type, word(data, head));
  if (type.startsWith('(')) throw new Error('tuple');
  const start = Number(BigInt(`0x${word(data, head)}`));
  const length = Number(BigInt(`0x${word(data, start)}`));
  if (type === 'string' || type === 'bytes') {
    const hex = data.slice((start + 32) * 2, (start + 32 + length) * 2);
    if (type === 'bytes') return { kind: 'bytes', value: `0x${hex}` };
    const bytes = new Uint8Array(hex.match(/../g)?.map((b) => parseInt(b, 16)) || []);
    return { kind: 'string', value: new TextDecoder().decode(bytes) };
  }
  const inner = type.slice(0, -2);
  if (isDynamic(inner)) throw new Error('nested dynamic array');
  const items = [];
  for (let i = 0; i < length; i++) items.push(decodeStatic(inner, word(data, start + 32 + i * 32)));
  return { kind: 'array', value: items };
}

/** Decodes a parameter list; returns null when the data does not fit the types. */
function decodeParams(params, hex) {
  const data = (hex || '').replace(/^0x/, '');
  try {
    return params.map((p, i) => ({ ...p, decoded: decodeAt(p.type, data, i * 32) }));
  } catch (_) {
    return null;
  }
}

function valueHtml(param, decoded, book, tokenish) {
  const v = decoded;
  switch (v.kind) {
    case 'address': return addressHtml(v.value, book);
    case 'bool': return v.value ? 'true' : 'false';
    case 'uint':
    case 'int': {
      const raw = v.value.toLocaleString('en-US');
      const money = tokenish && /amount|value|balance|wad/i.test(param.name) && v.value < 10n ** 30n;
      return money ? `<b>${esc(brlBig(v.value))}</b> <span class="small">${esc(raw)}</span>` : esc(raw);
    }
    case 'bytes': {
      const role = state.abi?.roles?.[v.value.toLowerCase()];
      return role ? `<b>${esc(role)}</b> <span class="addr" data-copy="${esc(v.value)}">${esc(shortAddr(v.value))}</span>`
        : `<span class="addr" data-copy="${esc(v.value)}">${esc(v.value.length > 70 ? `${v.value.slice(0, 66)}…` : v.value)}</span>`;
    }
    case 'string': return `“${esc(v.value)}”`;
    case 'array': return `[${v.value.map((x) => valueHtml(param, x, book, tokenish)).join(', ')}]`;
    default: return esc(String(v.value));
  }
}

function paramsTable(rows, book, tokenish) {
  if (!rows.length) return '<p class="small">No arguments.</p>';
  return `<table class="data compact"><thead><tr><th>Name</th><th>Type</th><th>Value</th></tr></thead><tbody>${rows
    .map((r) => `<tr><td>${esc(r.name || '–')}${r.indexed ? ' <span class="small">indexed</span>' : ''}</td><td><code>${esc(r.type)}</code></td>
      <td>${r.decoded ? valueHtml(r, r.decoded, book, tokenish) : `<span class="addr">${esc(r.raw)}</span>`}</td></tr>`).join('')}</tbody></table>`;
}

const kvHtml = (rows) => `<dl class="kv">${rows.filter(Boolean).map(([k, v]) => `<dt>${esc(k)}</dt><dd>${v}</dd>`).join('')}</dl>`;

function hexBlock(label, hex) {
  if (!hex || hex === '0x') return '';
  return `<details class="hexblock"><summary>${esc(label)} <span class="small">${hexBytes(hex).toLocaleString()} bytes</span></summary>
    <pre class="raw">${esc(hex)}</pre><button class="small-btn" data-copy="${esc(hex)}">Copy</button></details>`;
}

function isTokenContract(name) {
  return /^(RealDigital|RealTokenizado|STR|SwapOneStep|SwapTwoSteps)/.test(name || '');
}

function renderDetails(d) {
  const tx = d.transaction;
  const rc = d.receipt || null;
  const hasReceipt = rc && rc.blockHash;
  const book = addressBook();
  const toInfo = tx.to ? book.get(tx.to.toLowerCase()) : null;
  const status = !hasReceipt ? 'pending' : (rc.status === '0x1' ? 'success' : 'reverted');
  const flexible = tx.type === '0x42';

  // Call
  const input = tx.input || '0x';
  let callHtml;
  if (!tx.to) {
    callHtml = `<p>Contract creation. ${rc?.contractAddress ? `New contract at ${addressHtml(rc.contractAddress, book)}.` : ''}</p>${hexBlock('Init code', input)}`;
  } else if (input === '0x') {
    callHtml = '<p class="small">Plain value transfer: no call data.</p>';
  } else {
    const selector = input.slice(0, 10).toLowerCase();
    const fn = state.abi?.functions?.[selector];
    const args = fn ? decodeParams(fn.inputs, input.slice(10)) : null;
    callHtml = fn
      ? `<p class="fn"><code>${esc(fn.signature)}</code> <span class="small">selector ${esc(selector)}${toInfo ? '' : ` · ${esc(fn.contracts.join(', '))}`}</span></p>
         ${args ? paramsTable(args, book, isTokenContract(toInfo?.name || fn.contracts[0])) : '<p class="small">The arguments do not match the ABI.</p>'}`
      : `<p class="small">Unknown function selector <code>${esc(selector)}</code>.</p>`;
    callHtml += hexBlock('Call data', input);
  }

  // Gas
  const gasLimit = qty(tx.gas);
  const gasUsed = hasReceipt ? qty(rc.gasUsed) : null;
  const usedPct = gasUsed != null && gasLimit ? Number((gasUsed * 1000n) / gasLimit) / 10 : null;
  const blockPct = gasUsed != null && d.blockGasLimit ? (Number(gasUsed) * 100) / d.blockGasLimit : null;
  const gasHtml = kvHtml([
    ['Gas used', gasUsed != null ? `${fmtInt(gasUsed)} <span class="small">of ${fmtInt(gasLimit)} limit (${usedPct}%)</span>
      <span class="meter"><span style="width:${Math.min(100, usedPct)}%"></span></span>` : `<span class="small">limit ${fmtInt(gasLimit)}, not mined yet</span>`],
    blockPct != null && ['Share of the block', `${blockPct.toFixed(2)}% <span class="small">of ${d.blockGasLimit.toLocaleString('en-US')} gas</span>`],
    ['Effective gas price', `${fmtInt(qty(rc?.effectiveGasPrice ?? tx.gasPrice))} wei`],
    tx.maxFeePerGas && ['Max fee / priority fee', `${fmtInt(qty(tx.maxFeePerGas))} / ${fmtInt(qty(tx.maxPriorityFeePerGas))} wei`],
    hasReceipt && ['Cumulative gas in block', fmtInt(qty(rc.cumulativeGasUsed))],
  ]);

  // Signature
  const keyBytes = hexBytes(tx.publicKey);
  const sigBytes = flexible ? hexBytes(tx.signature) : 65;
  const sigHtml = kvHtml([
    ['Algorithm', `${badge(d.dsa || tx.dsaType || 'unknown')} ${d.quantumResistant ? '<span class="small">quantum resistant</span>' : '<span class="small">classical: forgeable by a quantum computer</span>'}`],
    ['Transaction type', flexible ? '<code>0x42</code> flexible (besu-pqc-ppi)' : `<code>${esc(tx.type)}</code> standard`],
    flexible && ['DSA id', `<code>${esc(tx.dsaType)}</code>`],
    ['Signature', `${sigBytes.toLocaleString()} bytes`],
    ['Public key carried', keyBytes ? `${keyBytes.toLocaleString()} bytes${d.hybrid ? ' <span class="small">(post-quantum half; the ECDSA half is recovered)</span>' : ''}`
      : '<span class="small">none: recovered from the ECDSA signature</span>'],
    d.sent && ['Encoded size', `${d.sent.size.toLocaleString()} bytes`],
    d.sent && ['Signing time', `${d.sent.signMs.toFixed(2)} ms`],
  ]) + hexBlock('Public key', tx.publicKey) + hexBlock('Signature', flexible ? tx.signature : (tx.r ? `${tx.r}${tx.s.slice(2)}${tx.v.slice(2).padStart(2, "0")}` : ''));

  // Events
  const logs = rc?.logs || [];
  const eventsHtml = logs.length ? logs.map((log) => {
    const emitter = book.get(log.address.toLowerCase());
    const ev = state.abi?.events?.[(log.topics[0] || '').toLowerCase()];
    let rows = null;
    if (ev) {
      const indexed = ev.inputs.filter((p) => p.indexed);
      const plain = ev.inputs.filter((p) => !p.indexed);
      const fromData = decodeParams(plain, log.data);
      rows = ev.inputs.map((p) => {
        if (p.indexed) {
          const topic = log.topics[1 + indexed.indexOf(p)] || '';
          return isDynamic(p.type) ? { ...p, raw: `${topic} (hash)` } : { ...p, decoded: decodeStatic(p.type, topic.slice(2)) };
        }
        const hit = fromData?.[plain.indexOf(p)];
        return hit || { ...p, raw: log.data };
      });
    }
    return `<div class="event">
      <div class="event-head"><span class="small">#${Number(log.logIndex)}</span> <b>${esc(ev ? ev.name : 'Unknown event')}</b>
        <span class="small">emitted by</span> ${addressHtml(log.address, book)}</div>
      ${rows ? paramsTable(rows, book, isTokenContract(emitter?.name)) : `<pre class="raw">${esc(JSON.stringify({ topics: log.topics, data: log.data }, null, 2))}</pre>`}
    </div>`;
  }).join('') : `<p class="small">${hasReceipt ? 'No events.' : 'Not mined yet.'}</p>`;

  // Overview
  const when = d.blockTimestamp ? new Date(d.blockTimestamp * 1000) : null;
  const overview = kvHtml([
    ['Status', `<span class="status ${status}">${status}</span>`],
    ['Block', hasReceipt ? `#${Number(rc.blockNumber).toLocaleString('en-US')} <span class="small">position ${Number(rc.transactionIndex)}${d.blockTransactions != null ? ` of ${d.blockTransactions}` : ''}</span>` : '–'],
    when && ['Time', `${when.toLocaleString()}`],
    ['From', addressHtml(tx.from, book)],
    ['To', tx.to ? addressHtml(tx.to, book) : '<span class="small">contract creation</span>'],
    ['Value', `${fmtInt(qty(tx.value))} wei`],
    ['Nonce', String(Number(tx.nonce))],
    ['Chain id', String(Number(tx.chainId))],
    d.sent && ['Sent from this GUI', `${esc(d.sent.operation || '–')}${d.sent.node ? ` <span class="small">via ${esc(d.sent.node)}</span>` : ''}`],
    ['Data from', `${esc(d.node)} <span class="small">${esc(d.nodeUrl)}</span>`],
    hasReceipt && ['Block hash', `<span class="addr" data-copy="${esc(rc.blockHash)}">${esc(rc.blockHash)}</span>`],
  ]);

  const raw = (title, method, obj) => `<details class="hexblock"><summary>${esc(title)} <code>${method}</code></summary>
    <pre class="raw">${esc(JSON.stringify(obj, null, 2))}</pre><button class="small-btn" data-copy="${esc(JSON.stringify(obj, null, 2))}">Copy JSON</button></details>`;

  $('#tx-dialog-title').innerHTML = `Transaction <span class="status ${status}">${status}</span>`;
  $('#tx-dialog-body').innerHTML = `
    <p class="txhash"><span class="hash" data-copy="${esc(tx.hash)}" title="Copy">${esc(tx.hash)}</span></p>
    <div class="detail-grid">
      <section class="detail"><h3>Overview</h3>${overview}</section>
      <section class="detail"><h3>Call${toInfo ? ` <span class="small">to ${esc(toInfo.name)}</span>` : ''}</h3>${callHtml}</section>
      <section class="detail"><h3>Signature</h3>${sigHtml}</section>
      <section class="detail"><h3>Gas</h3>${gasHtml}</section>
      <section class="detail wide"><h3>Events <span class="small">${logs.length}</span></h3>${eventsHtml}</section>
      <section class="detail wide"><h3>Raw JSON-RPC responses</h3>
        ${raw('Transaction', 'eth_getTransactionByHash', tx)}
        ${raw('Receipt', 'eth_getTransactionReceipt', d.receipt)}</section>
    </div>`;
}

async function showDetails(hash, node) {
  const dialog = $('#tx-dialog');
  $('#tx-dialog-title').textContent = 'Transaction';
  $('#tx-dialog-body').innerHTML = '<p class="muted">Loading…</p>';
  if (!dialog.open) dialog.showModal();
  try {
    if (!state.abi) state.abi = await api('/api/abi');
    const d = await api(`/api/transactions/${encodeURIComponent(hash)}${node ? `?node=${encodeURIComponent(node)}` : ''}`);
    renderDetails(d);
  } catch (err) {
    $('#tx-dialog-body').innerHTML = `<p class="op-result err">${esc(err.message)}</p>`;
  }
}

document.addEventListener('click', (e) => {
  const b = e.target.closest('[data-details]');
  if (b) showDetails(b.dataset.details, b.dataset.node);
});
$('#tx-dialog-close').addEventListener('click', () => $('#tx-dialog').close());
// A click on the backdrop (outside the panel) closes the dialog.
$('#tx-dialog').addEventListener('click', (e) => {
  const r = e.currentTarget.getBoundingClientRect();
  if (e.target === e.currentTarget && (e.clientX < r.left || e.clientX > r.right || e.clientY < r.top || e.clientY > r.bottom)) {
    e.currentTarget.close();
  }
});

// ------------------------------------------------------------------ jobs

function renderChips(container, selected) {
  $(container).innerHTML = PROFILES.map((p) => `<label class="chip"><input type="checkbox" value="${esc(p)}" ${selected.includes(p) ? 'checked' : ''}> ${badge(profileLabel(p))}</label>`).join('');
}
renderChips('#flows-dsas', PROFILES);
renderChips('#bench-dsas', ['SECP256K1:std', 'SECP256K1-MLDSA44']);

function checked(container) {
  return [...document.querySelectorAll(`${container} input:checked`)].map((i) => i.value);
}

async function startJob(kind) {
  let body = {};
  if (kind === 'flows') body = { dsas: checked('#flows-dsas') };
  if (kind === 'bench') {
    body = {
      dsas: checked('#bench-dsas'),
      rates: $('#bench-rates').value.split(',').map((s) => Number(s.trim())).filter((n) => n > 0),
      seconds: Number($('#bench-seconds').value),
      clients: Number($('#bench-clients').value),
      nodes: $('#bench-nodes').value.split(',').map((s) => s.trim()).filter(Boolean),
    };
  }
  try {
    const job = await api(`/api/jobs/${kind}`, { method: 'POST', body });
    state.jobs.set(job.id, { ...job, log: [] });
    if (kind === 'demo' || kind === 'deploy') {
      showSetupJob(job.id);
    } else {
      selectJob(job.id);
    }
    renderJobs();
  } catch (err) {
    toast(err.message);
  }
}

document.querySelectorAll('[data-job]').forEach((b) => b.addEventListener('click', () => startJob(b.dataset.job)));
$('#btn-demo').addEventListener('click', () => startJob('demo'));
$('#btn-deploy').addEventListener('click', () => startJob('deploy'));

async function loadJobs() {
  for (const j of await api('/api/jobs')) state.jobs.set(j.id, { ...j, log: state.jobs.get(j.id)?.log || [] });
  renderJobs();
}

function renderJobs() {
  const jobs = [...state.jobs.values()].sort((a, b) => b.id - a.id);
  $('#jobs').innerHTML = jobs.length ? jobs.map((j) => `<div class="job ${j.id === state.selectedJob ? 'selected' : ''}" data-id="${j.id}">
      <span><b>${esc(j.title)}</b> <span class="status ${esc(j.status)}">${esc(j.status)}</span></span>
      <span class="when">${time(j.startedAt)}${j.endedAt ? ` – ${time(j.endedAt)}` : ''}</span>
    </div>`).join('') : '<p class="muted">No runs yet.</p>';
  document.querySelectorAll('.job').forEach((el) => el.addEventListener('click', () => selectJob(Number(el.dataset.id))));
  const running = jobs.some((j) => j.status === 'running');
  document.querySelectorAll('[data-job], #btn-demo, #btn-deploy').forEach((b) => { b.disabled = running; });
}

async function selectJob(id) {
  state.selectedJob = id;
  const full = await api(`/api/jobs/${id}`);
  state.jobs.set(id, full);
  renderJobs();
  renderJobDetail();
}

function renderJobDetail() {
  const job = state.jobs.get(state.selectedJob);
  $('#job-detail').classList.toggle('hidden', !job);
  if (!job) return;
  $('#job-title').textContent = job.title;
  $('#job-status').className = `status ${job.status}`;
  $('#job-status').textContent = job.status + (job.error ? `: ${job.error}` : '');
  const log = $('#job-log');
  const atBottom = log.scrollTop + log.clientHeight >= log.scrollHeight - 20;
  log.textContent = (job.log || []).join('\n') || 'Starting…';
  if (atBottom) log.scrollTop = log.scrollHeight;
  $('#job-report').innerHTML = job.report ? markdown(job.report) : '';
}

let setupJob = null;
function showSetupJob(id) {
  setupJob = id;
  const box = $('#setup-job');
  box.classList.remove('hidden');
  box.innerHTML = '<pre class="console" id="setup-log">Starting…</pre>';
}

function onJobLog({ id, line }) {
  const job = state.jobs.get(id);
  if (job) (job.log = job.log || []).push(line);
  if (id === state.selectedJob) renderJobDetail();
  if (id === setupJob) {
    const pre = $('#setup-log');
    if (pre) {
      pre.textContent = (job?.log || [line]).join('\n');
      pre.scrollTop = pre.scrollHeight;
    }
  }
}

async function onJob(summary) {
  const known = state.jobs.get(summary.id);
  state.jobs.set(summary.id, { ...(known || {}), ...summary, log: known?.log || [] });
  renderJobs();
  if (summary.status !== 'running') {
    if (summary.id === state.selectedJob) selectJob(summary.id);
    if (summary.id === setupJob) {
      if (summary.status === 'done') {
        toast('Drex network ready');
        $('#setup-job').classList.add('hidden');
        setupJob = null;
      }
      await Promise.all([loadAccounts(), loadDeployment()]);
      refreshBalances();
    }
  } else if (summary.id === state.selectedJob) {
    renderJobDetail();
  }
}

// ------------------------------------------------------------------ minimal Markdown for the reports

function inline(s) {
  return s
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/\*([^*]+)\*/g, '<i>$1</i>')
    .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1');
}

/** Renders the reports' Markdown: headings, tables, lists, paragraphs and <details> blocks. Input is escaped first. */
function markdown(text) {
  const lines = esc(text).split('\n');
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (/^&lt;details&gt;&lt;summary&gt;(.*)&lt;\/summary&gt;$/.test(line.trim())) {
      out.push(`<details><summary>${line.trim().replace(/^&lt;details&gt;&lt;summary&gt;(.*)&lt;\/summary&gt;$/, '$1')}</summary>`);
      i++;
    } else if (line.trim() === '&lt;/details&gt;') {
      out.push('</details>');
      i++;
    } else if (/^#{1,4} /.test(line)) {
      const level = Math.min(line.match(/^#+/)[0].length, 3);
      out.push(`<h${level}>${inline(line.replace(/^#+ /, ''))}</h${level}>`);
      i++;
    } else if (line.startsWith('|')) {
      const rows = [];
      while (i < lines.length && lines[i].startsWith('|')) rows.push(lines[i++]);
      const cells = (r) => r.replace(/^\|/, '').replace(/\|\s*$/, '').split(/(?<!\\)\|/).map((c) => inline(c.trim().replace(/\\\|/g, '|')));
      const body = rows.filter((r, n) => !(n === 1 && /^\|[\s|:-]+$/.test(r)));
      out.push(`<table><thead><tr>${cells(body[0]).map((c) => `<th>${c}</th>`).join('')}</tr></thead><tbody>${body.slice(1)
        .map((r) => `<tr>${cells(r).map((c) => `<td>${c}</td>`).join('')}</tr>`).join('')}</tbody></table>`);
    } else if (/^\s*[-*] /.test(line)) {
      const items = [];
      while (i < lines.length && /^\s*[-*] /.test(lines[i])) items.push(lines[i++].replace(/^\s*[-*] /, ''));
      out.push(`<ul>${items.map((it) => `<li>${inline(it)}</li>`).join('')}</ul>`);
    } else if (line.trim() === '') {
      i++;
    } else {
      const para = [];
      while (i < lines.length && lines[i].trim() !== '' && !/^(#|\||\s*[-*] |&lt;)/.test(lines[i])) para.push(lines[i++]);
      if (para.length) out.push(`<p>${inline(para.join(' '))}</p>`);
      else i++;
    }
  }
  return out.join('\n');
}

// ------------------------------------------------------------------ live events

function connectEvents() {
  const es = new EventSource('/api/events');
  es.addEventListener('block', (e) => onBlock(JSON.parse(e.data).number));
  es.addEventListener('tx', (e) => {
    const t = JSON.parse(e.data);
    state.txs.set(t.id, t);
    renderTransactions();
    if (t.status !== 'pending') refreshBalances();
  });
  es.addEventListener('job', (e) => onJob(JSON.parse(e.data)));
  es.addEventListener('job-log', (e) => onJobLog(JSON.parse(e.data)));
  es.addEventListener('accounts', () => loadAccounts().then(refreshBalances));
  es.addEventListener('nodes', () => loadNodes());
  es.addEventListener('balances-changed', () => { loadDeployment().then(refreshBalances); });
  es.onerror = () => { $('#netdot').className = 'dot down'; };
  es.onopen = () => loadNetwork();
}

// ------------------------------------------------------------------ start

(async function start() {
  renderBalances();
  renderTransactions();
  await loadNetwork();
  await Promise.all([loadNodes(), loadAccounts(), loadDeployment(), loadOperations(), loadTransactions(), loadJobs()]);
  refreshBalances();
  connectEvents();
  setInterval(loadNetwork, 15000);
  setInterval(loadNodes, 10000);
})();
