import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import type { User } from 'oidc-client-ts'
import './index.css'
import App from './App.tsx'
import { setTokenSource } from './api.ts'
import { AuthProvider, toSession, userManager } from './auth.tsx'
import { loadConfig } from './config.ts'
import { SiteProvider } from './ui.tsx'

const root = createRoot(document.getElementById('root')!)

function fatal(message: string) {
  root.render(<div className="fatal"><h1>AstraWMS</h1><p>{message}</p><a href="/">Try again</a></div>)
}

async function start() {
  const config = await loadConfig()
  const path = window.location.pathname

  if (path === '/auth/popup') {
    // Approver sign-in window: hand the result to the opener and close.
    await userManager(config, true).signinPopupCallback()
    return
  }

  const manager = userManager(config)
  let user: User | null
  if (path === '/auth/callback') {
    user = await manager.signinRedirectCallback()
    window.history.replaceState(null, '', typeof user.state === 'string' ? user.state : '/')
  } else {
    user = await manager.getUser()
    if (user && user.expired && navigator.onLine === false) {
      // ADR-0023: offline with an expired sign-in: work goes on (commands wait on the device); sign-in is renewed
      // when the network is back.
    } else if (!user || user.expired) {
      await manager.signinRedirect({ state: window.location.pathname + window.location.search })
      return
    }
  }

  let current = user
  manager.events.addUserLoaded((u) => {
    current = u
  })
  setTokenSource(() => current.access_token)

  const session = toSession(user)
  if (!session.tenant) {
    fatal(`User ${session.userName} is not assigned to a tenant. Ask an administrator to set tenant_id.`)
    return
  }
  const defaultSite = session.sites === '*' || session.sites.length === 0 ? 'DC1' : session.sites[0]

  root.render(
    <StrictMode>
      <AuthProvider config={config} manager={manager} initial={user}>
        <SiteProvider initial={defaultSite}>
          <BrowserRouter>
            <App environment={config.environment} />
          </BrowserRouter>
        </SiteProvider>
      </AuthProvider>
    </StrictMode>,
  )
}

// ADR-0023: the app shell stays on the device, so the RF screens open without network (production builds only).
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  navigator.serviceWorker.register('/sw.js').catch(() => undefined)
}

start().catch((e: unknown) => fatal(e instanceof Error ? e.message : String(e)))
