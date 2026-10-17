import React, { useEffect, useState } from 'react';
import { api } from '../api';

interface ReconRow {
  id: number;
  sourceUnifiedId: number;
  timestamp: string;
  account: string;
  amount: number | string;
  amountCurrency: string;
  source: string;
  payee: string;
  description?: string | null;
  type?: string | null;
  category?: string | null;
  reference?: string | null;
}

interface PageResponse {
  content: ReconRow[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

const ACCOUNTS = ['Maybank', 'Grab', 'Cash'];
const SIZES = [10, 20, 50, 100];

interface Subtotal {
  count: number;
  subtotal: number | string;
  byCurrency: Record<string, number | string>;
}

const CURRENCY_SYMBOLS: Record<string, string> = {
  MYR: 'RM',
  CNY: '¥',
  THB: '฿',
  SGD: 'S$',
  USD: '$',
  EUR: '€',
  GBP: '£',
  JPY: 'JP¥',
  HKD: 'HK$',
  IDR: 'Rp',
};

/** Format as "{symbol} {amount}", e.g. "RM 20,073.02" or "-¥ 3,046.66". */
function formatMoney(value: number | string, currency: string): string {
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n)) return `${currency} ${String(value)}`;
  const grouped = Math.abs(n).toLocaleString('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const symbol = CURRENCY_SYMBOLS[currency] ?? currency;
  return `${n < 0 ? '-' : ''}${symbol} ${grouped}`;
}

/** One entry per currency, e.g. "RM 20,073.02 · -฿ 203.00". */
function formatSubtotal(sub: Subtotal): string {
  return Object.entries(sub.byCurrency || {})
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([ccy, v]) => formatMoney(v, ccy))
    .join(' · ');
}

type SortDir = 'asc' | 'desc';

const COLUMNS: { key: string; label: string }[] = [
  { key: 'id', label: 'ID' },
  { key: 'timestamp', label: 'Timestamp' },
  { key: 'account', label: 'Account' },
  { key: 'amount', label: 'Amount' },
  { key: 'amountCurrency', label: 'Ccy' },
  { key: 'source', label: 'Source' },
  { key: 'type', label: 'Type' },
  { key: 'payee', label: 'Payee' },
  { key: 'category', label: 'Category' },
  { key: 'description', label: 'Description' },
];

const DESC_DEFAULT = new Set(['id', 'timestamp', 'amount']);

export default function Recon() {
  const [rows, setRows] = useState<ReconRow[]>([]);
  const [account, setAccount] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [sortField, setSortField] = useState('timestamp');
  const [sortDir, setSortDir] = useState<SortDir>('desc');
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [status, setStatus] = useState<any>(null);
  const [result, setResult] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [sub, setSub] = useState<Subtotal | null>(null);

  const filterParams = () => {
    const q = new URLSearchParams();
    if (account) q.set('account', account);
    if (from) q.set('from', from);
    if (to) q.set('to', to);
    return q;
  };

  const load = async (p = page, s = size, field = sortField, dir = sortDir) => {
    setError('');
    const q = filterParams();
    q.set('page', String(p));
    q.set('size', String(s));
    q.set('sort', `${field},${dir}`);
    try {
      const data = await api<PageResponse>(`/api/reconciliation/transactions?${q.toString()}`);
      const content = data.content || [];
      const pages = data.totalPages ?? 0;
      if (p > 0 && content.length === 0 && pages > 0 && p >= pages) {
        setPage(pages - 1);
        return;
      }
      setRows(content);
      setTotalElements(data.totalElements ?? 0);
      setTotalPages(pages);
      setPage(data.number ?? p);
    } catch (e: any) {
      setError(String(e?.message || e));
    }
  };

  const loadStatus = async () => {
    try {
      setStatus(await api<any>('/api/reconciliation/status'));
    } catch {
      /* ignore */
    }
  };

  useEffect(() => {
    load(page, size, sortField, sortDir);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, size, sortField, sortDir]);

  useEffect(() => {
    loadStatus();
    loadSubtotal();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadSubtotal = async () => {
    try {
      setSub(await api<Subtotal>(`/api/reconciliation/transactions/subtotal?${filterParams().toString()}`));
    } catch (e: any) {
      setError(String(e?.message || e));
    }
  };

  const applyFilters = () => {
    loadStatus();
    loadSubtotal();
    if (page === 0) load(0, size, sortField, sortDir);
    else setPage(0);
  };

  const handleSort = (field: string) => {
    if (field === sortField) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortField(field);
      setSortDir(DESC_DEFAULT.has(field) ? 'desc' : 'asc');
    }
    setPage(0);
  };

  const run = async () => {
    if (busy) return;
    setBusy(true);
    setError('');
    setResult('running...');
    try {
      const r = await api<any>('/api/reconciliation/run', { method: 'POST' });
      setResult(JSON.stringify(r));
      load(page, size, sortField, sortDir);
      loadStatus();
      loadSubtotal();
    } catch (e: any) {
      setResult('');
      setError(String(e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  const clear = async () => {
    if (busy) return;
    if (!window.confirm('Delete ALL recon output rows? This cannot be undone.')) return;
    setBusy(true);
    setError('');
    try {
      const r = await api<{ deleted: number }>('/api/reconciliation/transactions/all', { method: 'DELETE' });
      setResult(`Cleared ${r.deleted} row(s).`);
      setPage(0);
      load(0, size, sortField, sortDir);
      loadStatus();
      loadSubtotal();
    } catch (e: any) {
      setError(String(e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h2>Reconciliation Output</h2>
      <p>
        Maybank (SA + CC union) · Grab (trips + Maybank GrabPay top-up incomes) · Cash (TNG faithful;
        Alipay / WeChat expenses drop when their exact CNY amount settled via Maybank, else kept as
        -0.01 MYR). One table, retries de-duplicate on (source, account).
        {status != null && (
          <>
            {' '}— total <b>{status.total}</b>
            {ACCOUNTS.map((a) => (
              <span key={a}> · {a} <b>{status[a] ?? 0}</b></span>
            ))}
          </>
        )}
      </p>
      <div>
        <select value={account} onChange={(e) => setAccount(e.target.value)}>
          <option value="">account: all</option>
          {ACCOUNTS.map((a) => (
            <option key={a} value={a}>{a}</option>
          ))}
        </select>
        <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} title="from timestamp" />
        <input type="date" value={to} onChange={(e) => setTo(e.target.value)} title="to timestamp" />
        <button onClick={applyFilters}>Filter</button>
        <button onClick={run} disabled={busy}>Run reconciliation</button>
        <button onClick={clear} disabled={busy || totalElements === 0}>Clear table</button>
      </div>
      {error && <p className="error">{error}</p>}
      {result && <pre>{result}</pre>}
      <p>{totalElements} rows — page {totalPages === 0 ? 0 : page + 1} of {totalPages}</p>
      <p>
        {sub == null
          ? 'Reconciled total: …'
          : <>Reconciled total across all {sub.count} filtered rows: <b>{formatSubtotal(sub) || '—'}</b></>}
      </p>
      <table>
        <thead>
          <tr>
            {COLUMNS.map((c) => (
              <th
                key={c.key}
                onClick={() => handleSort(c.key)}
                style={{ cursor: 'pointer', userSelect: 'none', whiteSpace: 'nowrap' }}
                title={`Sort by ${c.label}`}
                aria-sort={sortField === c.key ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}
              >
                {c.label}{sortField === c.key ? (sortDir === 'asc' ? ' ▲' : ' ▼') : ''}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.id}>
              <td>{r.id}</td>
              <td style={{ whiteSpace: 'nowrap' }}>{(r.timestamp || '').replace('T', ' ')}</td>
              <td>{r.account}</td>
              <td>{String(r.amount)}</td>
              <td>{r.amountCurrency}</td>
              <td>{r.source}</td>
              <td>{r.type}</td>
              <td>{r.payee}</td>
              <td>{r.category}</td>
              <td>{(r.description || '').slice(0, 120)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div>
        <button onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page <= 0}>Prev</button>
        <button onClick={() => setPage((p) => p + 1)} disabled={totalPages === 0 || page + 1 >= totalPages}>Next</button>
        <select value={size} onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}>
          {SIZES.map((s) => (
            <option key={s} value={s}>{s} / page</option>
          ))}
        </select>
      </div>
    </div>
  );
}
