import React, { useState } from 'react';
import { apiForm } from '../api';

const BUCKETS = ['MAYBANK_SA', 'MAYBANK_CC', 'GRAB', 'TNG', 'ALIPAY', 'WECHAT'] as const;
type Bucket = (typeof BUCKETS)[number];

interface UploadFileResult {
  originalFilename: string;
  storedFilename: string | null;
  bucket: string;
  status: string;
  message: string;
}

function BucketUploader({ bucket }: { bucket: Bucket }) {
  const [selected, setSelected] = useState<File[]>([]);
  const [results, setResults] = useState<UploadFileResult[]>([]);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');

  const pick = (e: React.ChangeEvent<HTMLInputElement>) => {
    setError('');
    const files = e.target.files ? Array.from(e.target.files) : [];
    setSelected((prev) => [...prev, ...files]);
    e.target.value = '';
  };

  const remove = (idx: number) => setSelected((prev) => prev.filter((_, i) => i !== idx));

  const upload = async () => {
    if (selected.length === 0) return;
    setUploading(true);
    setError('');
    try {
      const form = new FormData();
      form.append('bucket', bucket);
      selected.forEach((f) => form.append('files', f, f.name));
      const r = await apiForm<{ bucket: string; files: UploadFileResult[] }>(
        '/api/statements/upload',
        form
      );
      setResults(r.files || []);
      setSelected([]);
    } catch (e: any) {
      setError(String(e?.message || e));
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="card">
      <b>{bucket}</b>
      <div>
        <label>
          Select files{' '}
          <input
            type="file"
            multiple
            accept=".pdf,.csv,.xlsx,.xls,.zip"
            onChange={pick}
            disabled={uploading}
          />
        </label>
      </div>
      {selected.length > 0 && (
        <ul>
          {selected.map((f, i) => (
            <li key={`${f.name}-${f.size}-${i}`}>
              {f.name} ({f.size} bytes){' '}
              <button onClick={() => remove(i)} disabled={uploading}>
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}
      <button onClick={upload} disabled={selected.length === 0 || uploading}>
        {uploading ? 'Uploading…' : `Upload to ${bucket}`}
      </button>
      {error && <p className="error">{error}</p>}
      {results.length > 0 && (
        <ul>
          {results.map((r, i) => (
            <li key={i} className={r.status === 'SUCCESS' ? undefined : 'error'}>
              {r.originalFilename}: {r.status}
              {r.storedFilename ? ` → ${r.storedFilename}` : ''}
              {r.message && r.status !== 'SUCCESS' ? ` (${r.message})` : ''}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export default function Statements() {
  return (
    <div>
      <h2>Statements</h2>
      <h3>Upload</h3>
      <p>Select files for any bucket and upload. Each bucket is optional. Uploaded files are processed into unified transactions automatically in the background.</p>
      <div className="cards">
        {BUCKETS.map((b) => (
          <BucketUploader key={b} bucket={b} />
        ))}
      </div>
    </div>
  );
}
