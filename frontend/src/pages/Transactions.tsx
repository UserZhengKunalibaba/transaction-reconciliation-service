import React, { useEffect, useState } from 'react';
import { api } from '../api';

interface UnifiedRow {
  id: number;
  timestamp: string;
  payee: string;
  amount: number | string;
  amountCurrency: string;
  source: string;
  reference?: string | null;
  type?: string | null;
  account?: string | null;
  category?: string | null;
  description?: string | null;
  originalAmount?: number | string | null;
  originalCurrency?: string | null;
}

interface PageResponse {
  content: UnifiedRow[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

interface Subtotal {
  count: number;
  subtotal: number | string;
  byCurrency: Record<string, number | string>;
}

const SOURCES = ['ALIPAY', 'WECHATPAY', 'MAYBANK_SA', 'MAYBANK_CC', 'MAYBANK', 'GRAB', 'TNG'];
const TYPES = ['EXPENSE', 'INCOME', 'TRANSFER'];
const ACCOUNTS = ['Maybank', 'Grab', 'Cash'];
const SIZES = [10, 20, 50, 100];

type SortDir = 'asc' | 'desc';

const COLUMNS: { key: keyof UnifiedRow; label: string }[] = [
  { key: 'id', label: 'ID' },
  { key: 'timestamp', label: 'Timestamp' },
  { key: 'payee', label: 'Payee' },
  { key: 'amount', label: 'Amount' },
  { key: 'amountCurrency', label: 'Ccy' },
  { key: 'source', label: 'Source' },
  { key: 'type', label: 'Type' },
  { key: 'account', label: 'Account' },
  { key: 'category', label: 'Category' },
  { key: 'reference', label: 'Reference' },
  { key: 'description', label: 'Description' },
];

/** Columns that default to descending on first click (numeric/recency fields). */
const DESC_DEFAULT = new Set(['id', 'timestamp', 'amount']);

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

/** One entry per currency, e.g. "RM 20,073.02 · -¥ 3,046.66 · -฿ 203.00". */
function formatSubtotal(sub: Subtotal): string {
  return Object.entries(sub.byCurrency || {})
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([ccy, v]) => formatMoney(v, ccy))
    .join(' · ');
}

export default function Transactions() {
  const [rows, setRows] = useState<UnifiedRow[]>([]);
  const [detail, setDetail] = useState<UnifiedRow | null>(null);
  const [f, setF] = useState({ source: '', type: '', account: '', category: '', payee: '', from: '', to: '' });
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [sortField, setSortField] = useState<string>('timestamp');
  const [sortDir, setSortDir] = useState<SortDir>('desc');
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState(false);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [sub, setSub] = useState<Subtotal | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const filterParams = () => {
    const q = new URLSearchParams();
    if (f.source) q.set('source', f.source);
    if (f.type) q.set('type', f.type);
    if (f.account) q.set('account', f.account);
    if (f.category) q.set('category', f.category);
    if (f.payee) q.set('payee', f.payee);
    if (f.from) q.set('from', f.from);
    if (f.to) q.set('to', f.to);
    return q;
  };

  const load = async (p = page, s = size, field = sortField, dir = sortDir) => {
    setError('');
    const q = filterParams();
    q.set('page', String(p));
    q.set('size', String(s));
    q.set('sort', `${field},${dir}`);
    try {
      const data = await api<PageResponse>(`/api/transactions?${q.toString()}`);
      const content = data.content || [];
      const pages = data.totalPages ?? 0;
      // Deleted past the last page: step back instead of showing empty.
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

  useEffect(() => {
    load(page, size, sortField, sortDir);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, size, sortField, sortDir]);

  useEffect(() => {
    loadSubtotal();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const loadSubtotal = async () => {
    try {
      setSub(await api<Subtotal>(`/api/transactions/subtotal?${filterParams().toString()}`));
    } catch (e: any) {
      setError(String(e?.message || e));
    }
  };

  const applyFilters = () => {
    setDetail(null);
    setNotice('');
    loadSubtotal();
    if (page === 0) {
      load(0, size, sortField, sortDir);
    } else {
      setPage(0);
    }
  };

  const changeSize = (s: number) => {
    setDetail(null);
    setSize(s);
    setPage(0);
  };

  const handleSort = (field: string) => {
    setDetail(null);
    if (field === sortField) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortField(field);
      setSortDir(DESC_DEFAULT.has(field) ? 'desc' : 'asc');
    }
    setPage(0);
  };

  const toggleOne = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const allOnPageSelected = rows.length > 0 && rows.every((r) => selected.has(r.id));

  const togglePage = () => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (allOnPageSelected) rows.forEach((r) => next.delete(r.id));
      else rows.forEach((r) => next.add(r.id));
      return next;
    });
  };

  const refreshAfterDelete = (deletedIds: number[]) => {
    setSelected((prev) => {
      const next = new Set(prev);
      deletedIds.forEach((id) => next.delete(id));
      return next;
    });
    setDetail((d) => (d && deletedIds.includes(d.id) ? null : d));
  };

  const deleteSelected = async () => {
    if (selected.size === 0 || busy) return;
    if (!window.confirm(`Delete ${selected.size} selected transaction(s)? This cannot be undone.`)) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const ids = [...selected];
      const r = await api<{ deleted: number }>(
        `/api/transactions?ids=${ids.join(',')}`, { method: 'DELETE' });
      setNotice(`Deleted ${r.deleted} transaction(s).`);
      refreshAfterDelete(ids);
      load(page, size, sortField, sortDir);
      loadSubtotal();
    } catch (e: any) {
      setError(String(e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  const deleteOne = async (id: number) => {
    if (busy) return;
    if (!window.confirm(`Delete transaction #${id}? This cannot be undone.`)) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await api(`/api/transactions/${id}`, { method: 'DELETE' });
      setNotice(`Deleted transaction #${id}.`);
      refreshAfterDelete([id]);
      load(page, size, sortField, sortDir);
      loadSubtotal();
    } catch (e: any) {
      setError(String(e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  const deleteAll = async () => {
    if (busy || totalElements === 0) return;
    if (!window.confirm(`Delete ALL ${totalElements} transactions (clean table)? This cannot be undone.`)) return;
    if (!window.confirm('Really delete everything? Confirm again to wipe the table.')) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const r = await api<{ deleted: number }>('/api/transactions/all', { method: 'DELETE' });
      setNotice(`Deleted ${r.deleted} transaction(s) — table is clean.`);
      setSelected(new Set());
      setDetail(null);
      setPage(0);
      load(0, size, sortField, sortDir);
      loadSubtotal();
    } catch (e: any) {
      setError(String(e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  const cell = (r: UnifiedRow, key: keyof UnifiedRow) => {
    if (key === 'description') return (r.description || '').slice(0, 120);
    const v = r[key];
    return v ?? '';
  };

  return (
    <div>
      <h2>Transactions</h2>
      <div>
        <select value={f.source} onChange={(e) => setF({ ...f, source: e.target.value })}>
          <option value="">source: all</option>
          {SOURCES.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
        <select value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })}>
          <option value="">type: all</option>
          {TYPES.map((t) => (
            <option key={t} value={t}>{t}</option>
          ))}
        </select>
        <select value={f.account} onChange={(e) => setF({ ...f, account: e.target.value })}>
          <option value="">account: all</option>
          {ACCOUNTS.map((a) => (
            <option key={a} value={a}>{a}</option>
          ))}
        </select>
        <input placeholder="category" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })} />
        <input placeholder="payee contains" value={f.payee} onChange={(e) => setF({ ...f, payee: e.target.value })} />
        <input type="date" value={f.from} onChange={(e) => setF({ ...f, from: e.target.value })} />
        <input type="date" value={f.to} onChange={(e) => setF({ ...f, to: e.target.value })} />
        <button onClick={applyFilters}>Filter</button>
      </div>
      <div>
        <button onClick={deleteSelected} disabled={selected.size === 0 || busy}>
          Delete selected ({selected.size})
        </button>
        <button onClick={deleteAll} disabled={totalElements === 0 || busy}>
          Delete all (clean table)
        </button>
        {selected.size > 0 && (
          <button onClick={() => setSelected(new Set())} disabled={busy}>Clear selection</button>
        )}
      </div>
      {error && <p className="error">{error}</p>}
      {notice && <p>{notice}</p>}
      <p>{totalElements} rows — page {totalPages === 0 ? 0 : page + 1} of {totalPages}</p>
      <p>
        {sub == null
          ? 'Subtotal: …'
          : <>Subtotal across all {sub.count} filtered rows: <b>{formatSubtotal(sub) || '—'}</b></>}
      </p>
      <table>
        <thead>
          <tr>
            <th>
              <input
                type="checkbox"
                checked={allOnPageSelected}
                disabled={rows.length === 0}
                onChange={togglePage}
                title={allOnPageSelected ? 'Deselect page' : 'Select page'}
              />
            </th>
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
            <tr key={r.id} onClick={() => setDetail(r)} style={{ cursor: 'pointer' }}>
              <td onClick={(e) => e.stopPropagation()}>
                <input
                  type="checkbox"
                  checked={selected.has(r.id)}
                  onChange={() => toggleOne(r.id)}
                />
              </td>
              {COLUMNS.map((c) => (
                <td key={c.key}>{cell(r, c.key)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      <div>
        <button onClick={() => setPage((p) => Math.max(0, p - 1))} disabled={page <= 0}>Prev</button>
        <button onClick={() => setPage((p) => p + 1)} disabled={totalPages === 0 || page + 1 >= totalPages}>Next</button>
        <select value={size} onChange={(e) => changeSize(Number(e.target.value))}>
          {SIZES.map((s) => (
            <option key={s} value={s}>{s} / page</option>
          ))}
        </select>
      </div>
      {detail && (
        <>
          <h3>Detail #{detail.id}</h3>
          <pre>{JSON.stringify(detail, null, 1)}</pre>
          <button onClick={() => deleteOne(detail.id)} disabled={busy}>
            Delete this transaction
          </button>
        </>
      )}
    </div>
  );
}
