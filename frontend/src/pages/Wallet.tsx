import React, { useEffect, useState } from 'react';
import { api } from '../api';

interface UnifiedRow {
  id: number;
  timestamp: string;
  payee: string;
  amount: number | string;
  amountCurrency: string;
  source: string;
  account?: string | null;
  category?: string | null;
  description?: string | null;
}

interface PageResponse {
  content: UnifiedRow[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

const ACCOUNTS = ['Maybank', 'Grab', 'Cash'];

export default function Wallet() {
  const [rows, setRows] = useState<UnifiedRow[]>([]);
  const [account, setAccount] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [msg, setMsg] = useState('');
  const [busy, setBusy] = useState(false);
  const [selecting, setSelecting] = useState(false);

  const filterParams = () => {
    const q = new URLSearchParams();
    if (account) q.set('account', account);
    if (from) q.set('from', from);
    if (to) q.set('to', to);
    return q;
  };

  const load = async (p = page) => {
    setMsg('');
    const q = filterParams();
    q.set('page', String(p));
    q.set('size', '20');
    q.set('sort', 'timestamp,asc');
    try {
      const data = await api<PageResponse>(`/api/wallet/preview?${q.toString()}`);
      setRows(data.content || []);
      setTotalElements(data.totalElements ?? 0);
      setTotalPages(data.totalPages ?? 0);
      setPage(data.number ?? p);
    } catch (e: any) {
      setMsg(String(e?.message || e));
    }
  };

  useEffect(() => {
    load(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const applyFilters = () => {
    setSelected(new Set());
    setPage(0);
    load(0);
  };

  const toggleOne = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const pageIds = rows.map((r) => r.id);
  const allOnPageSelected = pageIds.length > 0 && pageIds.every((id) => selected.has(id));

  const togglePage = () => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (allOnPageSelected) pageIds.forEach((id) => next.delete(id));
      else pageIds.forEach((id) => next.add(id));
      return next;
    });
  };

  const selectAllMatching = async () => {
    if (selecting || totalElements === 0) return;
    setSelecting(true);
    setMsg('');
    try {
      const ids = await api<number[]>(`/api/wallet/ids?${filterParams().toString()}`);
      setSelected(new Set(ids));
      setMsg(`selected all ${ids.length} matching rows`);
    } catch (e: any) {
      setMsg(String(e?.message || e));
    } finally {
      setSelecting(false);
    }
  };

  const clearSelection = () => setSelected(new Set());

  const exportSelected = async () => {
    if (busy || selected.size === 0) return;
    setBusy(true);
    setMsg('exporting...');
    try {
      const res = await fetch('/api/wallet/export', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ids: [...selected] }),
      });
      if (!res.ok) {
        const text = await res.text();
        throw new Error(`${res.status} ${text}`);
      }
      const blob = await res.blob();
      const disposition = res.headers.get('content-disposition') || '';
      const match = disposition.match(/filename="?([^";]+)"?/);
      const filename = match ? match[1] : 'wallet-export.csv';
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      setMsg(`downloaded ${filename} (${selected.size} selected rows)`);
    } catch (e: any) {
      setMsg(String(e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <h2>Wallet Export</h2>
      <p>
        Exports the reconciled transactions as WalletApp CSV
        (Date,Account,Amount,Note,Payee,Category). Only MYR rows are exported
        (the CSV has no currency column). Filter by account and timestamp,
        tick exactly the rows to export, then download — the CSV contains only the selected rows.
      </p>
      <div style={{ marginBottom: 12 }}>
        <label>Account: </label>
        <select value={account} onChange={e => setAccount(e.target.value)}>
          <option value="">All</option>
          {ACCOUNTS.map((a) => (
            <option key={a} value={a}>{a}</option>
          ))}
        </select>
        <label style={{ marginLeft: 12 }}>From: </label>
        <input type="date" value={from} onChange={e => setFrom(e.target.value)} />
        <label style={{ marginLeft: 12 }}>To: </label>
        <input type="date" value={to} onChange={e => setTo(e.target.value)} />
        <button onClick={applyFilters} style={{ marginLeft: 12 }}>Filter</button>
      </div>
      <div style={{ marginBottom: 12 }}>
        <button onClick={selectAllMatching} disabled={selecting || totalElements === 0}>
          {selecting ? 'Selecting...' : `Select all ${totalElements} matching`}
        </button>
        <button onClick={clearSelection} disabled={selected.size === 0} style={{ marginLeft: 8 }}>
          Select none
        </button>
        <button onClick={exportSelected} disabled={busy || selected.size === 0} style={{ marginLeft: 8 }}>
          {busy ? 'Exporting...' : `Export selected (${selected.size})`}
        </button>
      </div>
      {msg && <pre>{msg}</pre>}
      <p>
        {totalElements} matching rows — {selected.size} selected —
        page {totalPages === 0 ? 0 : page + 1} of {totalPages}
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
            <th>ID</th><th>Timestamp</th><th>Account</th><th>Amount</th><th>Payee</th><th>Description</th><th>Category</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r: UnifiedRow) => (
            <tr key={r.id}>
              <td>
                <input
                  type="checkbox"
                  checked={selected.has(r.id)}
                  onChange={() => toggleOne(r.id)}
                />
              </td>
              <td>{r.id}</td>
              <td style={{ whiteSpace: 'nowrap' }}>{(r.timestamp || '').replace('T', ' ')}</td>
              <td>{r.account}</td>
              <td>{String(r.amount)}</td>
              <td>{r.payee}</td>
              <td>{(r.description || '').slice(0, 140)}</td>
              <td>{r.category}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div>
        <button onClick={() => { const p = Math.max(0, page - 1); setPage(p); load(p); }} disabled={page <= 0}>Prev</button>
        <button onClick={() => { const p = page + 1; setPage(p); load(p); }} disabled={totalPages === 0 || page + 1 >= totalPages}>Next</button>
      </div>
      <p>Import the downloaded CSV via Wallet Web App → Import.</p>
    </div>
  );
}
