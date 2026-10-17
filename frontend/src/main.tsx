import ReactDOM from 'react-dom/client';
import { BrowserRouter, Link, Route, Routes } from 'react-router-dom';
import Statements from './pages/Statements';
import Transactions from './pages/Transactions';
import Recon from './pages/Recon';
import Wallet from './pages/Wallet';
import './index.css';

function App() {
  return (
    <BrowserRouter>
      <nav>
        <b>💳 Local Reconciliation</b>
        <Link to="/statements">Statements</Link>
        <Link to="/transactions">Transactions</Link>
        <Link to="/recon">Recon Output</Link>
        <Link to="/wallet">Wallet Export</Link>
      </nav>
      <main>
        <Routes>
          <Route path="/" element={<Statements />} />
          <Route path="/statements" element={<Statements />} />
          <Route path="/transactions" element={<Transactions />} />
          <Route path="/recon" element={<Recon />} />
          <Route path="/wallet" element={<Wallet />} />
        </Routes>
      </main>
    </BrowserRouter>
  );
}

ReactDOM.createRoot(document.getElementById('root')!).render(<App />);
