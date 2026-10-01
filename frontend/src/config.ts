/**
 * Runtime configuration, served as /config.json by the gateway, so one build runs in every environment.
 * authority = the OIDC issuer of the astrawms realm; clientId = the public PKCE client (astra-web).
 */
export interface AppConfig {
  authority: string
  clientId: string
  environment?: string
}

export async function loadConfig(): Promise<AppConfig> {
  const response = await fetch('/config.json', { cache: 'no-store' })
  if (!response.ok) {
    throw new Error(`Cannot load /config.json (HTTP ${response.status})`)
  }
  return (await response.json()) as AppConfig
}
