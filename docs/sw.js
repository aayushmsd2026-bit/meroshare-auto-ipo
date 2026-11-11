// IPO Helper service worker: lets the app open instantly and work as an installed app.
// Pages are fetched fresh when online (so updates show up right away) and from cache when offline.
// GitHub API calls are never cached.
const CACHE = "ipo-helper-v3";
const SHELL = ["./", "index.html", "manifest.webmanifest", "vendor/libsodium.js", "vendor/libsodium-wrappers.js",
  "icons/icon-192.png", "icons/icon-512.png", "icons/icon-32.png", "dps.json"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET") return;
  if (url.origin === location.origin) {
    // network first, cache fallback
    e.respondWith(fetch(e.request).then((r) => {
      if (r.ok) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); }
      return r;
    }).catch(() => caches.match(e.request, { ignoreSearch: true }).then((r) => r || caches.match("index.html"))));
  } else if (url.hostname.endsWith("fonts.googleapis.com") || url.hostname.endsWith("fonts.gstatic.com")) {
    // fonts: cache first
    e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request).then((r) => {
      const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); return r;
    })));
  }
});

// Phone notifications sent by the bot after each run.
self.addEventListener("push", (e) => {
  let msg = { title: "IPO Helper", body: "There's an update.", url: "./#updates" };
  try { msg = { ...msg, ...e.data.json() }; } catch {}
  e.waitUntil(self.registration.showNotification(msg.title, {
    body: msg.body, icon: "icons/icon-192.png", badge: "icons/icon-192.png",
    tag: "ipo-helper", renotify: true, data: { url: msg.url },
  }));
});
self.addEventListener("notificationclick", (e) => {
  e.notification.close();
  const target = new URL((e.notification.data && e.notification.data.url) || "./#updates", self.registration.scope).href;
  e.waitUntil(clients.matchAll({ type: "window", includeUncontrolled: true }).then((list) => {
    for (const c of list) { if (c.url.startsWith(self.registration.scope)) { c.navigate(target); return c.focus(); } }
    return clients.openWindow(target);
  }));
});
