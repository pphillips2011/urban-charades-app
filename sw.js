const CACHE_NAME = "urban-charades-v11";
const OFFLINE_URL = "./index.html";

const ASSETS = [
  "./",
  "./index.html",
  "./manifest.json",
  "./urban-logo.png",

  "./img/urban-movies.png",
  "./img/hip-hop-rnb.png",
  "./img/famous-black-people.png",
  "./img/hood-creatures.png",
  "./img/act-it-out.png",
  "./img/hood-slang-quotes.png",

  "./audio/correct.mp3",
  "./audio/skip.mp3",
  "./audio/timeup.mp3",
  "./audio/start-round.mp3",

  "./icons/icon-192.png",
  "./icons/icon-512.png",
  "./icons/icon-512-maskable.png"
];

self.addEventListener("install", (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE_NAME);

    await Promise.all(
      ASSETS.map(async (url) => {
        try {
          await cache.add(url);
        } catch (e) {
          // ignore missing optional files
        }
      })
    );

    self.skipWaiting();
  })());
});

self.addEventListener("activate", (event) => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(
      keys.map((key) => key !== CACHE_NAME ? caches.delete(key) : null)
    );
    self.clients.claim();
  })());
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  const accept = req.headers.get("accept") || "";
  const isHTML = req.mode === "navigate" || accept.includes("text/html");

  if (isHTML) {
    event.respondWith((async () => {
      try {
        const fresh = await fetch(req);
        const cache = await caches.open(CACHE_NAME);
        cache.put(req, fresh.clone()).catch(() => {});
        return fresh;
      } catch (e) {
        return (await caches.match(req)) || (await caches.match(OFFLINE_URL));
      }
    })());
    return;
  }

  event.respondWith((async () => {
    const cached = await caches.match(req);
    if (cached) return cached;

    try {
      const res = await fetch(req);
      const cache = await caches.open(CACHE_NAME);
      cache.put(req, res.clone()).catch(() => {});
      return res;
    } catch (e) {
      return cached || Response.error();
    }
  })());
});