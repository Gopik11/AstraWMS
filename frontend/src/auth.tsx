import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts'
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import type { AppConfig } from './config'

/** Roles of the AstraWMS role catalogue (§G.5.2). */
export type Role =
  | 'RECEIVER' | 'PICKER' | 'INV_ANALYST' | 'INV_MANAGER' | 'SUPERVISOR' | 'QA_MANAGER' | 'SOLUTION_ADMIN'
  | 'ERP_INTEGRATION'

/** What the UI needs from the access token. The services validate the token themselves; this is for display. */
export interface Session {
  accessToken: string
  userName: string
  tenant: string
  roles: Role[]
  sites: string[] | '*'
  owners: string[] | '*'
  approvalLimit?: string
}

export function userManager(config: AppConfig, inMemory = false): UserManager {
  const origin = window.location.origin
  return new UserManager({
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: `${origin}/auth/callback`,
    popup_redirect_uri: `${origin}/auth/popup`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: 'openid',
    automaticSilentRenew: !inMemory,
    userStore: new WebStorageStateStore({ store: inMemory ? new InMemoryWebStorage() : window.sessionStorage }),
  })
}

function claims(token: string): Record<string, unknown> {
  const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
  const json = decodeURIComponent(
    atob(payload).split('').map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''),
  )
  return JSON.parse(json) as Record<string, unknown>
}

function scope(value: unknown): string[] | '*' {
  const list = Array.isArray(value) ? value.map(String) : value == null ? [] : String(value).split(',')
  const trimmed = list.map((v) => v.trim()).filter(Boolean)
  return trimmed.includes('*') ? '*' : trimmed
}

export function toSession(user: User): Session {
  const c = claims(user.access_token)
  const known: Role[] = ['RECEIVER', 'PICKER', 'INV_ANALYST', 'INV_MANAGER', 'SUPERVISOR', 'QA_MANAGER', 'SOLUTION_ADMIN',
    'ERP_INTEGRATION']
  // Only AstraWMS roles; Keycloak's own (default-roles-*, offline_access, …) are irrelevant here.
  const realmRoles = ((c.realm_access as { roles?: string[] } | undefined)?.roles ?? [])
    .filter((r): r is Role => known.includes(r as Role))
  return {
    accessToken: user.access_token,
    userName: String(c.preferred_username ?? c.sub ?? ''),
    tenant: String(c.tenant_id ?? ''),
    roles: realmRoles,
    sites: scope(c.wms_sites),
    owners: scope(c.wms_owners),
    approvalLimit: c.approval_limit == null ? undefined : String(c.approval_limit),
  }
}

interface AuthContextValue {
  session: Session
  hasRole: (...roles: Role[]) => boolean
  logout: () => void
  /**
   * Registers a passkey (ADR-0023): Keycloak's application-initiated action asks the device for its face, fingerprint
   * or PIN unlock and returns to the app. Afterwards the sign-in page offers "Sign in with Passkey".
   */
  setUpPasskey: () => void
  /** A fresh sign-in of another person in a popup (approvals); returns their access token. */
  approverToken: () => Promise<string>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ config, manager, initial, children }: {
  config: AppConfig
  manager: UserManager
  initial: User
  children: ReactNode
}) {
  const [user, setUser] = useState<User>(initial)

  useEffect(() => {
    const loaded = (u: User) => setUser(u)
    const expired = () => void manager.signinRedirect({ state: window.location.pathname })
    manager.events.addUserLoaded(loaded)
    manager.events.addAccessTokenExpired(expired)
    return () => {
      manager.events.removeUserLoaded(loaded)
      manager.events.removeAccessTokenExpired(expired)
    }
  }, [manager])

  const session = toSession(user)
  const value: AuthContextValue = {
    session,
    hasRole: (...roles) => roles.some((r) => session.roles.includes(r)),
    logout: () => void manager.signoutRedirect({ id_token_hint: user.id_token }),
    setUpPasskey: () => void manager.signinRedirect({
      state: window.location.pathname, extraQueryParams: { kc_action: 'webauthn-register-passwordless' },
    }),
    approverToken: async () => {
      const approver = await userManager(config, true).signinPopup({ prompt: 'login' })
      return approver.access_token
    },
  }
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth outside AuthProvider')
  }
  return value
}
