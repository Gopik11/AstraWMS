import { useState, type ReactNode } from 'react'
import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { useAuth, type Role } from './auth'
import { useSiteContext } from './ui'
import Home from './pages/Home'
import RfWork from './pages/RfWork'
import Receipts from './pages/Receipts'
import ReceiptDetail from './pages/ReceiptDetail'
import Returns from './pages/Returns'
import Orders from './pages/Orders'
import OrderDetail from './pages/OrderDetail'
import Waves from './pages/Waves'
import PackStation from './pages/PackStation'
import Loads from './pages/Loads'
import Inventory from './pages/Inventory'
import Adjust from './pages/Adjust'
import Counts from './pages/Counts'
import Replenishment from './pages/Replenishment'
import Tasks from './pages/Tasks'
import MasterData from './pages/MasterData'
import ErpSimulator from './pages/ErpSimulator'
import Operations from './pages/Operations'

interface NavItem {
  to: string
  label: string
  roles?: Role[]          // undefined = every signed-in user (reads)
}

const NAV: { group: string; items: NavItem[] }[] = [
  { group: 'Floor', items: [{ to: '/rf', label: 'RF work', roles: ['RECEIVER', 'PICKER', 'INV_ANALYST', 'SUPERVISOR'] }] },
  {
    group: 'Operations',
    items: [
      { to: '/', label: 'Overview' },
      { to: '/receipts', label: 'Receipts' },
      { to: '/returns', label: 'Customer returns' },
      { to: '/orders', label: 'Outbound orders' },
      { to: '/waves', label: 'Waves', roles: ['SUPERVISOR'] },
      { to: '/pack', label: 'Pack station', roles: ['PICKER', 'SUPERVISOR'] },
      { to: '/loads', label: 'Loads', roles: ['PICKER', 'SUPERVISOR'] },
      { to: '/tasks', label: 'Tasks' },
    ],
  },
  {
    group: 'Inventory',
    items: [
      { to: '/inventory', label: 'Stock inquiry' },
      { to: '/counts', label: 'Cycle counts', roles: ['INV_ANALYST', 'INV_MANAGER', 'SUPERVISOR'] },
      { to: '/replenishment', label: 'Replenishment', roles: ['SUPERVISOR', 'INV_MANAGER', 'SOLUTION_ADMIN'] },
      { to: '/adjust', label: 'Adjust / status', roles: ['INV_ANALYST', 'INV_MANAGER', 'SUPERVISOR', 'QA_MANAGER'] },
    ],
  },
  {
    group: 'Setup',
    items: [
      { to: '/master-data', label: 'Master data', roles: ['SOLUTION_ADMIN'] },
      { to: '/erp', label: 'ERP simulator', roles: ['ERP_INTEGRATION', 'SOLUTION_ADMIN'] },
      { to: '/operations', label: 'Operations', roles: ['SOLUTION_ADMIN'] },
    ],
  },
]

function Guard({ roles, children }: { roles?: Role[]; children: ReactNode }) {
  const { hasRole } = useAuth()
  if (roles && !hasRole(...roles)) {
    return <div className="alert error">Your roles do not include this function ({roles.join(', ')}).</div>
  }
  return <>{children}</>
}

export default function App({ environment }: { environment?: string }) {
  const { session, hasRole, logout } = useAuth()
  const { site, setSite } = useSiteContext()
  const [menuOpen, setMenuOpen] = useState(false)
  const sites = session.sites === '*' ? null : session.sites

  return (
    <div className={`shell ${menuOpen ? 'menu-open' : ''}`}>
      <header className="topbar">
        <button className="menu-toggle" aria-label="Menu" onClick={() => setMenuOpen((o) => !o)}>☰</button>
        <span className="brand">AstraWMS</span>
        {environment && <span className="env">{environment}</span>}
        <span className="spacer" />
        <label className="site-select">
          Site
          {sites ? (
            <select value={site} onChange={(e) => setSite(e.target.value)}>
              {sites.map((s) => <option key={s}>{s}</option>)}
            </select>
          ) : (
            <input value={site} onChange={(e) => setSite(e.target.value.toUpperCase())} size={6} />
          )}
        </label>
        <span className="who" title={session.roles.join(', ')}>
          {session.userName} · {session.tenant}
        </span>
        <button className="link" onClick={logout}>Sign out</button>
      </header>
      <nav className="sidebar" onClick={() => setMenuOpen(false)}>
        {NAV.map((g) => {
          const items = g.items.filter((i) => !i.roles || hasRole(...i.roles))
          return items.length === 0 ? null : (
            <div key={g.group} className="nav-group">
              <div className="nav-title">{g.group}</div>
              {items.map((i) => <NavLink key={i.to} to={i.to} end={i.to === '/'}>{i.label}</NavLink>)}
            </div>
          )
        })}
      </nav>
      <main className="content">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/rf" element={<Guard roles={['RECEIVER', 'PICKER', 'INV_ANALYST', 'SUPERVISOR']}><RfWork /></Guard>} />
          <Route path="/replenishment" element={<Guard roles={['SUPERVISOR', 'INV_MANAGER', 'SOLUTION_ADMIN']}><Replenishment /></Guard>} />
          <Route path="/counts" element={<Guard roles={['INV_ANALYST', 'INV_MANAGER', 'SUPERVISOR']}><Counts /></Guard>} />
          <Route path="/receipts" element={<Receipts />} />
          <Route path="/receipts/:doc" element={<ReceiptDetail />} />
          <Route path="/returns" element={<Returns />} />
          <Route path="/orders" element={<Orders />} />
          <Route path="/orders/:doc" element={<OrderDetail />} />
          <Route path="/waves" element={<Guard roles={['SUPERVISOR']}><Waves /></Guard>} />
          <Route path="/tasks" element={<Tasks />} />
          <Route path="/pack" element={<Guard roles={['PICKER', 'SUPERVISOR']}><PackStation /></Guard>} />
          <Route path="/loads" element={<Guard roles={['PICKER', 'SUPERVISOR']}><Loads /></Guard>} />
          <Route path="/inventory" element={<Inventory />} />
          <Route path="/adjust" element={<Guard roles={['INV_ANALYST', 'INV_MANAGER', 'SUPERVISOR', 'QA_MANAGER']}><Adjust /></Guard>} />
          <Route path="/master-data" element={<Guard roles={['SOLUTION_ADMIN']}><MasterData /></Guard>} />
          <Route path="/operations" element={<Guard roles={['SOLUTION_ADMIN']}><Operations /></Guard>} />
          <Route path="/erp" element={<Guard roles={['ERP_INTEGRATION', 'SOLUTION_ADMIN']}><ErpSimulator /></Guard>} />
          <Route path="/auth/callback" element={<Navigate to="/" replace />} />
          <Route path="*" element={<div className="alert error">Page not found</div>} />
        </Routes>
      </main>
    </div>
  )
}
