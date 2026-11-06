import { Suspense, lazy } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
const Platform = lazy(() => import('./pages/Platform'))

/**
 * The app for a platform administrator who belongs to no tenant (ADR-0030 A: PLATFORM_ADMIN is not tied to a tenant).
 * Only the platform view: every other screen works inside one tenant and would be refused.
 */
export default function PlatformApp({ environment }: { environment?: string }) {
  const { session, logout } = useAuth()
  return (
    <div className="shell">
      <header className="topbar">
        <span className="brand">AstraWMS</span>
        {environment && <span className="env">{environment}</span>}
        <span className="spacer" />
        <span className="who" title={session.roles.join(', ')}>{session.userName} · platform</span>
        <button className="link" onClick={logout}>Sign out</button>
      </header>
      <nav className="sidebar">
        <div className="nav-group">
          <div className="nav-title">Platform</div>
          <a className="active" href="/platform">Tenants</a>
        </div>
      </nav>
      <main className="content">
        <Suspense fallback={<p className="muted">Loading…</p>}>
          <Routes>
            <Route path="/platform" element={<Platform />} />
            <Route path="*" element={<Navigate to="/platform" replace />} />
          </Routes>
        </Suspense>
      </main>
    </div>
  )
}
