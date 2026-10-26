/* Meter Manager — offline shell service worker.
   Same-origin GETs are network-first with a SHORT TIMEOUT, then cache:
   - network answers within NETWORK_TIMEOUT -> fresh copy, as before;
   - network is slower (or down) -> the cached copy is served immediately, so
     cold start never waits on the wire, and the late network response still
     refreshes the cache in the background so the NEXT launch picks it up.
   Cross-origin requests (fonts, analytics, portal APIs) are left alone. */
const CACHE = 'metermanager-v19';
const NETWORK_TIMEOUT = 800;
const ASSETS = [
  './',
  './index.html',
  './css/styles.css',
  './js/app.js',
  './js/vendor/firebase-app-compat.js',
  './js/vendor/firebase-auth-compat.js',
  './js/vendor/firebase-database-compat.js',
  './firebase-config.js',
  './manifest.webmanifest',
  './icons/icon-electricity.svg',
  './icons/icon-512.png',
  './icons/icon-317.png'
];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  if (url.origin !== location.origin) return;

  const refresh = fetch(req)
    .then(res => {
      if (res && res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(req, copy)).catch(() => {});
      }
      return res;
    })
    .catch(() => null);

  const timeout = new Promise(resolve =>
    setTimeout(() => resolve(null), NETWORK_TIMEOUT));

  e.respondWith(
    Promise.race([refresh, timeout]).then(hit => {
      if (hit) return hit;
      /* ignoreSearch so versioned URLs (?v=487) still hit their precached copy;
         only navigations may fall back to the shell — never a script/style. */
      return caches.match(req, { ignoreSearch: true }).then(cached => {
        if (cached) return cached;
        return req.mode === 'navigate' ? caches.match('./index.html') : Response.error();
      });
    })
  );
  /* keep the slow/late response alive so the cache gets updated anyway */
  e.waitUntil(refresh);
});
