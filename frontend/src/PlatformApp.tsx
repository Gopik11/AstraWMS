import { useState } from 'react'
import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import Platform from './pages/Platform'

/**
 * The operator's console (ADR-0031): tenants only. A platform administrator has no tenant, so none of a tenant's
 * screens, site selector or data are offered here.
 */
export default function PlatformApp({ environment }: { environment?: string }) {
  const { session, logout } = useAuth()
  const [menuOpen, setMenuOpen] = useState(false)
  return (
    <div className={`shell ${menuOpen ? 'menu-open' : ''}`}>
      <header className="topbar">
        <button className="menu-toggle" aria-label="Menu" onClick={() => setMenuOpen((o) => !o)}>☰</button>
        <span className="brand">AstraWMS</span>
        <span className="env">Platform</span>
        {environment && <span className="env">{environment}</span>}
        <span className="spacer" />
        <span className="who" title={session.roles.join(', ')}>{session.userName} · operator</span>
        <button className="link" onClick={logout}>Sign out</button>
      </header>
      <nav className="sidebar" onClick={() => setMenuOpen(false)}>
        <div className="nav-group">
          <div className="nav-title">Platform</div>
          <NavLink to="/tenants">Tenants</NavLink>
        </div>
      </nav>
      <main className="content">
        <Routes>
          <Route path="/tenants" element={<Platform />} />
          <Route path="/tenants/:tenantId" element={<Platform />} />
          <Route path="*" element={<Navigate to="/tenants" replace />} />
        </Routes>
      </main>
    </div>
  )
}
