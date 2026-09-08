/* Service worker del Brazalete.

   Hace dos cosas:
   1. Que Chrome ofrezca "Instalar aplicacion" (sin esto solo sale un
      acceso directo cualquiera).
   2. Que la app abra aunque no haya internet. La deteccion de caidas es
      100% local, asi que sigue funcionando; lo unico que se pierde sin
      red es el aviso al cuidador.

   Estrategia: red primero, cache como respaldo. Asi cada vez que
   publicamos una version nueva el telefono la coge sola, sin trucos
   para borrar la cache.                                              */

const CACHE = 'brazalete-v1';
const ARCHIVOS = [
  './',
  './index.html',
  './manifest.json',
  './mqtt.min.js',
  './icono-192.png',
  './icono-512.png'
];

self.addEventListener('install', function (e) {
  self.skipWaiting();
  e.waitUntil(
    caches.open(CACHE)
      .then(function (c) { return c.addAll(ARCHIVOS); })
      .catch(function () { /* si algo no esta, no bloquear la instalacion */ })
  );
});

self.addEventListener('activate', function (e) {
  e.waitUntil(
    caches.keys().then(function (claves) {
      return Promise.all(claves
        .filter(function (k) { return k !== CACHE; })
        .map(function (k) { return caches.delete(k); }));
    }).then(function () { return self.clients.claim(); })
  );
});

self.addEventListener('fetch', function (e) {
  if (e.request.method !== 'GET') return;

  // Nunca interceptar el broker MQTT ni Telegram: son de otro dominio y
  // tienen que salir siempre a la red de verdad.
  var u = new URL(e.request.url);
  if (u.origin !== location.origin) return;

  e.respondWith(
    fetch(e.request).then(function (r) {
      var copia = r.clone();
      caches.open(CACHE).then(function (c) { c.put(e.request, copia); });
      return r;
    }).catch(function () {
      return caches.match(e.request).then(function (m) {
        return m || caches.match('./index.html');
      });
    })
  );
});
