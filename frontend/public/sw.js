/*
 * AstraWMS service worker (ADR-0023): keeps the app shell on the device so the RF screens open and work without
 * network. API calls are never cached here; RF commands made offline wait in the app's own queue (src/offline.ts).
 *   - /assets/* (content-hashed): cache first, then network.
 *   - pages and /config.json: network first, the cached copy when offline.
 */
const CACHE = 'astrawms-shell-v1'
const SHELL = ['/', '/config.json']
const NEVER = /^\/(api|realms|resources|health|mock-sap|actuator|auth)\//

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).catch(() => undefined))
  self.skipWaiting()
})

self.addEventListener('activate', (event) => {
  event.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()))
})

self.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET') return
  const url = new URL(request.url)
  if (url.origin !== self.location.origin || NEVER.test(url.pathname)) return

  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(caches.match(request).then((hit) => hit || fetch(request).then((response) => {
      if (response.ok) {
        const copy = response.clone()
        caches.open(CACHE).then((cache) => cache.put(request, copy))
      }
      return response
    })))
    return
  }

  const key = request.mode === 'navigate' ? '/' : request
  event.respondWith(fetch(request).then((response) => {
    if (response.ok) {
      const copy = response.clone()
      caches.open(CACHE).then((cache) => cache.put(key, copy))
    }
    return response
  }).catch(() => caches.match(key).then((hit) => hit || Response.error())))
})
