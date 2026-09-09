/* CuidAPP
   =================================================================
   Deteccion de caidas y aviso al cuidador.

   Tres papeles en la misma app:
     paciente  - lleva el telefono encima, detecta y avisa
     cuidador  - vigila a una o varias personas, guarda su historial
     admin     - ve todas las salas activas (para el desarrollo)

   No hay servidor propio. Los dispositivos se hablan por MQTT contra
   un broker publico, y cada uno guarda en su propio telefono lo que
   necesita conservar. El telefono del cuidador hace de archivo.     */

'use strict';

const $ = function (id) { return document.getElementById(id); };

const BROKERS = [
  'wss://broker.emqx.io:8084/mqtt',
  'wss://broker.hivemq.com:8884/mqtt',
  'wss://test.mosquitto.org:8081/mqtt'
];
let iBroker = 0, fallosBroker = 0;

const MI_ID = Math.random().toString(36).slice(2, 10);
const G = 9.80665;
const APK = 'https://github.com/CafecitoOwO/brazalete-caidas/releases/latest';

const UMB_DEF = {
  impactoG: 2.5, fuerteG: 3.5, libreG: 0.6, libreMs: 60, giroDps: 250,
  orientDeg: 50, quietoMs: 2500, quietoTolG: 0.18, quietoGiro: 35, puntosMin: 3
};

/* ================================================================
   Configuracion
   ================================================================ */

function leer(clave, pordefecto) {
  try {
    const v = localStorage.getItem(clave);
    return v === null ? pordefecto : JSON.parse(v);
  } catch (e) { return pordefecto; }
}
/**
 * Guarda en localStorage avisando si falla.
 *
 * Antes esto se tragaba el error en silencio, y cuando el almacenamiento
 * se llenaba la app dejaba de guardar sin decir nada: seguia detectando
 * pero no conservaba nada. Un fallo mudo es lo peor que puede pasarle a
 * una app que existe para guardar datos.
 */
let avisadoLleno = false;
function escribir(clave, valor) {
  try {
    localStorage.setItem(clave, JSON.stringify(valor));
    return true;
  } catch (e) {
    if (!avisadoLleno) {
      avisadoLleno = true;
      mostrarAviso('<b>El almacenamiento del telefono esta lleno</b> y la app dejo de ' +
        'guardar.<br><br>Descarga el dataset y el historial, y despues borralos desde ' +
        'sus pantallas para liberar espacio.');
      log('No se pudo guardar', 'Almacenamiento lleno (' + clave + ')', 'rojo');
    }
    return false;
  }
}

/* ---------------------------------------------------------------
   Grabaciones en IndexedDB.

   Las grabaciones del dataset son lo unico grande que guarda la app
   (unos 60 kB cada una). En localStorage, que tiene un tope de ~5 MB,
   llenaban el cupo y arrastraban con ellas a todo lo demas. IndexedDB
   no tiene ese problema.                                          */

let bd = null;
function abrirBD() {
  return new Promise(function (res) {
    if (bd) return res(bd);
    if (!window.indexedDB) return res(null);
    const p = indexedDB.open('cuidapp', 1);
    p.onupgradeneeded = function () {
      const d = p.result;
      if (!d.objectStoreNames.contains('grabaciones')) {
        d.createObjectStore('grabaciones', { keyPath: 'id' });
      }
    };
    p.onsuccess = function () { bd = p.result; res(bd); };
    p.onerror = function () { res(null); };
  });
}
async function bdGuardar(g) {
  const d = await abrirBD(); if (!d) return false;
  return new Promise(function (res) {
    const t = d.transaction('grabaciones', 'readwrite');
    t.objectStore('grabaciones').put(g);
    t.oncomplete = function () { res(true); };
    t.onerror = function () { res(false); };
  });
}
async function bdBorrar(id) {
  const d = await abrirBD(); if (!d) return;
  const t = d.transaction('grabaciones', 'readwrite');
  t.objectStore('grabaciones').delete(id);
}
async function bdVaciar() {
  const d = await abrirBD(); if (!d) return;
  const t = d.transaction('grabaciones', 'readwrite');
  t.objectStore('grabaciones').clear();
}
async function bdLeerTodo() {
  const d = await abrirBD(); if (!d) return [];
  return new Promise(function (res) {
    const p = d.transaction('grabaciones', 'readonly').objectStore('grabaciones').getAll();
    p.onsuccess = function () { res(p.result || []); };
    p.onerror = function () { res([]); };
  });
}

/** Cuanto espacio queda, para poder avisar antes de quedarse sin sitio. */
async function espacioLibre() {
  try {
    if (navigator.storage && navigator.storage.estimate) {
      const e = await navigator.storage.estimate();
      return { usado: e.usage || 0, tope: e.quota || 0 };
    }
  } catch (e) {}
  return null;
}

const cfg = {
  nombre:    leer('nombre', ''),
  rol:       leer('rol', 'paciente'),
  sala:      leer('sala', Math.random().toString(36).slice(2, 10)),
  pacientes: leer('pacientes', []),          // [{sala, nombre}] para el cuidador
  tel:       leer('tel', '112'),
  tgToken:   leer('tgToken', ''),
  tgChat:    leer('tgChat', ''),
  pin:       leer('pin', ''),
  avisos:    Object.assign(
               { auto: true, sonido: true, vibrar: true, ubicacion: true,
                 cuentaS: 25, escalarS: 60, autocal: true },
               leer('avisos', {}))
};
escribir('sala', cfg.sala);

let umb = Object.assign({}, UMB_DEF, leer('umb', {}));

// Emparejamiento por enlace: ?sala=xxx&modo=cuidador&nombre=Abuela
(function () {
  const p = new URLSearchParams(location.search);
  const sala = p.get('sala');
  const modo = p.get('modo');
  const nom  = p.get('nombre');
  if (sala && modo === 'cuidador') {
    cfg.rol = 'cuidador';
    if (!cfg.pacientes.some(function (x) { return x.sala === sala; })) {
      cfg.pacientes.push({ sala: sala, nombre: nom || 'Paciente' });
    }
    escribir('rol', cfg.rol);
    escribir('pacientes', cfg.pacientes);
  } else if (sala) {
    cfg.sala = sala;
    escribir('sala', sala);
    if (modo === 'paciente') { cfg.rol = 'paciente'; escribir('rol', 'paciente'); }
  }
})();

/**
 * Bloqueo del telefono del paciente.
 *
 * Sin esto, la persona a la que hay que cuidar puede cambiarse a "cuidador",
 * disparar caidas falsas o apagar la vigilancia sin darse cuenta. Con un PIN
 * puesto, su telefono queda reducido a lo unico que necesita: ver que esta
 * vigilado y poder pedir ayuda.
 *
 * Cancelar una alarma NUNCA pide PIN: en ese momento hacen falta segundos,
 * no contraseñas.
 */
let desbloqueado = false;
function bloqueado() { return !!cfg.pin && !desbloqueado; }
function pedirPin(motivo) {
  if (!bloqueado()) return true;
  const p = prompt(motivo + '\n\nEscribe el PIN de 4 cifras:');
  if (p === null) return false;
  if (p.trim() === cfg.pin) {
    desbloqueado = true;
    // Se vuelve a bloquear solo a los 3 minutos, para no dejarlo abierto.
    setTimeout(function () { desbloqueado = false; aplicarRol(); }, 180000);
    aplicarRol();
    return true;
  }
  alert('PIN incorrecto.');
  return false;
}

const esPaciente = function () { return cfg.rol === 'paciente'; };
const esCuidador = function () { return cfg.rol === 'cuidador'; };
const esAdmin    = function () { return cfg.rol === 'admin'; };

/* ================================================================
   Estado
   ================================================================ */

let vigilando = false, wakeLock = null, mqttCli = null;
let audio = null, alarma = null, cuentaAtras = null;
let nMuestras = 0, tVentanaHz = 0, hzActual = 0, totalMuestras = 0;

const bufM = [], serieAcc = [], serieBpm = [];
const sA = [0, 0, 1];
const st = { fase: 'REPOSO', t0: 0, picoSvm: 0, picoGiro: 0, vAntes: [0, 0, 1],
             tLibre: 0, marcaLibre: 0, datos: '' };

// Lo que sabemos de cada sala vigilada. Clave: codigo de sala.
const estados = {};
let salaActual = null;          // sala abierta en la pantalla de detalle
let alertaDe = null;            // sala que disparo la alerta en curso
let ultimaUbic = null;
let tEscalada = null;           // temporizador del aviso de ultimo minuto

function nombreDe(sala) {
  if (sala === cfg.sala) return cfg.nombre || 'Yo';
  const p = cfg.pacientes.filter(function (x) { return x.sala === sala; })[0];
  if (p && p.nombre) return p.nombre;
  const e = estados[sala];
  return (e && e.nombre) || sala;
}
function estadoDe(sala) {
  if (!estados[sala]) {
    estados[sala] = { nombre: '', estado: 'ok', ultimo: 0, vitales: {},
                      ubic: null, eventos: [], cuidadores: {} };
  }
  if (!estados[sala].cuidadores) estados[sala].cuidadores = {};
  return estados[sala];
}

/* ================================================================
   Presencia de los cuidadores
   ================================================================

   Un paciente puede tener varios cuidadores, y ahi aparece un problema
   que no existe con uno solo: si todos suponen que otro esta mirando,
   no mira nadie. Por eso cada cuidador publica cuando entro a revisar, y
   todos (incluido el paciente) ven la lista con la ultima vez de cada
   uno. Si nadie revisa en mucho tiempo, salta un aviso.                */

const HORAS_SIN_REVISAR = 12;

function publicarPresencia() {
  if (!esCuidador() || !mqttCli || !mqttCli.connected) return;
  const carga = JSON.stringify({
    nombre: cfg.nombre || 'Cuidador', id: MI_ID, ts: Date.now()
  });
  cfg.pacientes.forEach(function (p) {
    mqttCli.publish('brz/' + p.sala + '/presencia/' + MI_ID, carga, { retain: true });
  });
}
setInterval(publicarPresencia, 60000);

function listaCuidadores(sala) {
  const c = estadoDe(sala).cuidadores;
  return Object.keys(c).map(function (id) { return c[id]; })
    .sort(function (a, b) { return b.ts - a.ts; });
}

function haceCuanto(ts) {
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 90) return 'ahora';
  const m = Math.round(s / 60);
  if (m < 60) return 'hace ' + m + ' min';
  const h = Math.round(m / 60);
  if (h < 48) return 'hace ' + h + ' h';
  return 'hace ' + Math.round(h / 24) + ' dias';
}

function pintarCuidadores(idDestino, sala) {
  const c = $(idDestino); if (!c) return;
  const lista = listaCuidadores(sala);
  if (!lista.length) {
    c.innerHTML = '<div class="prog">' +
      (idDestino === 'misCuidadores'
        ? 'Todavia no te vigila nadie. Manda tu enlace desde el engranaje.'
        : 'Solo vos, por ahora.') + '</div>';
    return;
  }
  c.innerHTML = '';
  lista.forEach(function (x) {
    const reciente = Date.now() - x.ts < 300000;
    const el = document.createElement('div');
    el.className = 'grab';
    el.innerHTML = '<div><b>' + (x.nombre || 'Cuidador') +
      (x.id === MI_ID ? ' (vos)' : '') + '</b><small>' +
      (reciente ? 'Conectado ahora' : 'Ultima vez que reviso: ' + haceCuanto(x.ts)) +
      '</small></div><i class="luz ' + (reciente ? 'ok' : 'aviso') + '"></i>';
    c.appendChild(el);
  });

  // Si hace demasiado que nadie entra a mirar, decirlo.
  const masReciente = lista[0].ts;
  if (Date.now() - masReciente > HORAS_SIN_REVISAR * 3600000) {
    const av = document.createElement('div');
    av.className = 'prog';
    av.style.color = '#fbbf24';
    av.innerHTML = '<b>Hace ' + haceCuanto(masReciente).replace('hace ', '') +
      ' que ningun cuidador entra a revisar.</b>';
    c.appendChild(av);
  }
}

/* ================================================================
   Utilidades de pantalla
   ================================================================ */

function log(txt, det, clase) {
  const d = new Date();
  const h = [d.getHours(), d.getMinutes(), d.getSeconds()]
    .map(function (n) { return String(n).padStart(2, '0'); }).join(':');
  const el = document.createElement('div');
  el.className = 'ev' + (clase ? ' ' + clase : '');
  el.innerHTML = '<time>' + h + '</time><div><b>' + txt + '</b>' +
                 (det ? '<small>' + det + '</small>' : '') + '</div>';
  const c = $('log');
  if (!c) return;
  if (c.firstChild && c.firstChild.textContent.indexOf('Sin eventos') >= 0) c.innerHTML = '';
  c.insertBefore(el, c.firstChild);
  while (c.children.length > 60) c.removeChild(c.lastChild);
}

function pintarBpm(v) {
  $('bpm').textContent = v > 0 ? v : '--';
  $('corazon').className = v > 0 ? 'corazon lat' : 'corazon';
  if (v > 0) {
    $('corazon').style.animationDuration = (60 / v) + 's';
    serieBpm.push(v);
    if (serieBpm.length > 60) serieBpm.shift();
    linea('gBpm', serieBpm, '#f87171');
  }
}
function pintarBat(p) {
  $('bat').textContent = p + '%';
  $('barBat').style.width = p + '%';
  $('barBat').style.background = p < 20 ? '#f87171' : p < 40 ? '#fbbf24' : '#4ade80';
}

function linea(idc, datos, color, ref) {
  const c = $(idc); if (!c) return;
  const ctx = c.getContext('2d'), r = window.devicePixelRatio || 1;
  const w = c.clientWidth, h = c.clientHeight;
  if (!w || !h) return;
  c.width = w * r; c.height = h * r; ctx.setTransform(r, 0, 0, r, 0, 0);
  ctx.clearRect(0, 0, w, h);
  if (datos.length < 2) return;
  let min = Infinity, max = -Infinity;
  for (let i = 0; i < datos.length; i++) {
    if (datos[i] < min) min = datos[i];
    if (datos[i] > max) max = datos[i];
  }
  if (ref != null) { if (ref > max) max = ref; if (ref < min) min = ref; }
  const m = (max - min) * 0.12 + 0.05; min -= m; max += m;
  const y = function (v) { return h - (v - min) / (max - min) * (h - 6) - 3; };
  if (ref != null) {
    ctx.beginPath(); ctx.moveTo(0, y(ref)); ctx.lineTo(w, y(ref));
    ctx.strokeStyle = '#7f1d1d'; ctx.lineWidth = 1;
    ctx.setLineDash([4, 4]); ctx.stroke(); ctx.setLineDash([]);
  }
  ctx.beginPath();
  for (let i = 0; i < datos.length; i++) {
    const x = i / (datos.length - 1) * w;
    i ? ctx.lineTo(x, y(datos[i])) : ctx.moveTo(x, y(datos[i]));
  }
  ctx.strokeStyle = color; ctx.lineWidth = 1.8; ctx.lineJoin = 'round'; ctx.stroke();
}

function barras(idc, datos, color) {
  const c = $(idc); if (!c) return;
  const ctx = c.getContext('2d'), r = window.devicePixelRatio || 1;
  const w = c.clientWidth, h = c.clientHeight;
  if (!w || !h) return;
  c.width = w * r; c.height = h * r; ctx.setTransform(r, 0, 0, r, 0, 0);
  ctx.clearRect(0, 0, w, h);
  if (!datos.length) return;
  const ancho = w / datos.length;
  ctx.fillStyle = color;
  for (let i = 0; i < datos.length; i++) {
    const alto = Math.max(2, datos[i] / 100 * (h - 4));
    ctx.fillRect(i * ancho + 1, h - alto, Math.max(1, ancho - 2), alto);
  }
}

function mostrarAviso(txt) { $('txtAviso').innerHTML = txt; $('cardAviso').style.display = ''; }
function ocultarAviso() { $('cardAviso').style.display = 'none'; }

/* ================================================================
   Sonido y vibracion
   ================================================================ */

function iniAudio() {
  if (!audio) audio = new (window.AudioContext || window.webkitAudioContext)();
  if (audio.state === 'suspended') audio.resume();
}
function sonar(grave) {
  pararSonido();
  if (cfg.avisos.sonido) {
    iniAudio();
    alarma = setInterval(function () {
      const o = audio.createOscillator(), g = audio.createGain();
      o.frequency.value = grave ? 660 : 880; o.type = 'square';
      g.gain.setValueAtTime(.18, audio.currentTime);
      g.gain.exponentialRampToValueAtTime(.001, audio.currentTime + .28);
      o.connect(g); g.connect(audio.destination);
      o.start(); o.stop(audio.currentTime + .3);
    }, grave ? 600 : 900);
  }
  if (cfg.avisos.vibrar && navigator.vibrate) {
    navigator.vibrate(grave ? [400, 150, 400, 150, 400] : [250, 400]);
  }
}
function pararSonido() {
  if (alarma) { clearInterval(alarma); alarma = null; }
  if (navigator.vibrate) { try { navigator.vibrate(0); } catch (e) {} }
}

/* ================================================================
   Alertas
   ================================================================ */

function mostrarAlerta(tipo, datos, sala) {
  const a = $('alerta');
  alertaDe = sala || cfg.sala;
  const quien = sala && sala !== cfg.sala ? nombreDe(sala) : null;
  a.className = 'ver ' + (tipo === 'pre' ? 'pre' : 'conf');
  $('alertaDatos').textContent = datos || '';
  clearInterval(cuentaAtras);

  if (tipo === 'pre') {
    $('alertaTit').textContent = quien
      ? 'Posible caida de ' + quien
      : 'Posible caida detectada';
    $('alertaTxt').textContent = quien
      ? 'Si sabes que esta bien, descarta el aviso.'
      : 'Si estas bien, cancela antes de que acabe la cuenta.';
    let n = cfg.avisos.cuentaS;
    $('cuenta').textContent = n; $('cuenta').style.display = 'block';
    cuentaAtras = setInterval(function () {
      n--; $('cuenta').textContent = Math.max(n, 0);
      if (n <= 0) clearInterval(cuentaAtras);
    }, 1000);
    sonar(false);
  } else {
    $('alertaTit').textContent = quien ? 'CAIDA DE ' + quien.toUpperCase() : 'CAIDA CONFIRMADA';
    $('alertaTxt').textContent = quien
      ? 'No hubo respuesta. Contacta con esta persona ahora mismo.'
      : 'No hubo respuesta. Se aviso a tus cuidadores.';
    $('cuenta').style.display = 'none';
    sonar(true);
  }
  $('btnEstoyBien').textContent = quien ? 'Descartar aviso' : 'Estoy bien, cancelar';
  pintarMapa();
  pintarMic();
}
/**
 * Aviso de ultimo minuto.
 *
 * Una alerta confirmada que nadie atiende es el peor escenario: el
 * sistema hizo su trabajo y aun asi la persona sigue en el suelo. Pasado
 * un margen sin que nadie cancele, se vuelve a insistir: se reanuda la
 * alarma y se manda un segundo aviso diciendo cuanto tiempo lleva.
 */
function programarEscalada(sala, datos, prueba, desde) {
  clearTimeout(tEscalada);
  if (prueba || !cfg.avisos.escalarS) return;
  // El inicio se arrastra entre insistencias para poder decir cuanto
  // tiempo lleva de verdad, no solo desde el ultimo aviso.
  const inicio = desde || Date.now();
  tEscalada = setTimeout(function () {
    if (estadoDe(sala).estado !== 'caida') return;   // ya lo atendieron
    const seg = Math.round((Date.now() - inicio) / 1000);
    const cuanto = seg < 90 ? seg + ' segundos' : Math.round(seg / 60) + ' minutos';
    const quien = sala === cfg.sala ? '' : ' de ' + nombreDe(sala);
    log('SIN RESPUESTA' + quien, 'La alerta lleva ' + cuanto + ' sin atender', 'rojo');
    avisarTelegram('SIN RESPUESTA' + quien + '. La alerta lleva ' + cuanto +
      ' sin que nadie la atienda.' + (datos ? ' ' + datos : ''));
    sonar(true);
    // Y se sigue insistiendo mientras no la cancelen.
    programarEscalada(sala, datos, false, inicio);
  }, cfg.avisos.escalarS * 1000);
}

function cerrarAlerta() {
  $('alerta').className = '';
  clearInterval(cuentaAtras);
  pararSonido();
  // El microfono se apaga con la alerta, sin excepciones.
  if (escuchando) { pedirEscuchar(false); escuchando = false; }
  pararDeEmitir();
  colaAudio.length = 0;
  alertaDe = null;
  pintarMic();
}

/**
 * Punto unico por el que pasan todos los avisos, vengan de donde vengan.
 * @param msg    "TIPO;clave=valor;..."
 * @param remoto llego por la nube, no lo genero este telefono
 * @param sala   de quien es el aviso
 * @param viejo  es un aviso retenido de hace rato: se anota pero no suena
 */
function procesarEstado(msg, remoto, sala, viejo, prueba) {
  const p = msg.split(';'), tipo = p[0], datos = p.slice(1).join('  ');
  sala = sala || cfg.sala;
  const e = estadoDe(sala);
  // Solo tiene sentido decir de quien es el aviso si no es de uno mismo.
  const suf = (remoto && sala !== cfg.sala) ? ' · ' + nombreDe(sala) : '';
  const marca = prueba ? ' [prueba]' : (viejo ? ' [anterior]' : '');
  const alarmar = cfg.avisos.auto && !viejo;

  if (tipo === 'PREALERTA') {
    e.estado = 'prealerta';
    log('Posible caida' + suf + marca, datos, 'ambar');
    if (alarmar) mostrarAlerta('pre', datos, sala);
  } else if (tipo === 'CAIDA_CONFIRMADA') {
    e.estado = 'caida';
    log('Caida confirmada' + suf + marca, datos, 'rojo');
    if (alarmar) mostrarAlerta('conf', datos, sala);
    if (!remoto && !prueba) avisarTelegram('CAIDA CONFIRMADA. ' + datos);
    programarEscalada(sala, datos, prueba);
  } else if (tipo === 'SOS_MANUAL') {
    e.estado = 'caida';
    log('SOS manual' + suf + marca, '', 'rojo');
    if (alarmar) mostrarAlerta('conf', '', sala);
    if (!remoto && !prueba) avisarTelegram('SOS manual.');
    programarEscalada(sala, '', prueba);
  } else if (tipo === 'CANCELADA') {
    e.estado = 'ok';
    clearTimeout(tEscalada); tEscalada = null;
    log('Alerta cancelada' + suf, 'Falso positivo descartado', 'verde');
    if (!alertaDe || alertaDe === sala) cerrarAlerta();
  } else {
    log(msg);
    return;
  }

  e.eventos.push({ t: Date.now(), tipo: tipo, datos: datos, prueba: !!prueba });
  while (e.eventos.length > 100) e.eventos.shift();

  anotarEvento(tipo, datos, viejo ? null : Date.now(), sala, prueba);

  if (!remoto) {
    if (tipo !== 'CANCELADA' && cfg.avisos.ubicacion && !prueba) pedirUbicacion();
    publicarEvento(tipo, datos, prueba);
  }
  pintarPacientes();
  pintarDetalle();
}

/* ================================================================
   Sensores del telefono
   ================================================================ */

/* ----------------------------------------------------------------
   Calibracion automatica
   ----------------------------------------------------------------
   No todo el mundo se queda igual de quieto. Una persona con temblor,
   o que respira fuerte, nunca llega a la quietud que espera un umbral
   fijo, y su caida no se detecta jamas. Al reves, alguien muy quieto
   haria saltar la alarma con cualquier tropiezo.

   Asi que en vez de fijar "quieto = menos de 0,18 g", se mide cuanto se
   mueve ESTA persona cuando esta parada, y se define la quietud
   relativa a ella. Es donde el acelerometro y el giroscopio cooperan:
   hace falta que los dos esten tranquilos para dar el reposo por bueno.
   ---------------------------------------------------------------- */

const cal = { n: 0, sumDesv: 0, sumGiro: 0, listo: false, tolG: 0, giro: 0 };

function calibrar(m) {
  if (!cfg.avisos.autocal || st.fase !== 'REPOSO') return;
  const desv = Math.abs(m.svm - 1);
  // Si se esta moviendo, la muestra no sirve para aprender el reposo.
  if (desv > 0.35 || m.gyro > 120) return;

  cal.n++; cal.sumDesv += desv; cal.sumGiro += m.gyro;
  if (cal.n < 500 || cal.n % 250 !== 0) return;   // se refina cada pocos segundos

  const mediaDesv = cal.sumDesv / cal.n;
  const mediaGiro = cal.sumGiro / cal.n;
  // Tres veces el ruido propio, mas un margen. Acotado para que una
  // calibracion rara no deje el detector ciego ni histerico.
  cal.tolG = Math.min(0.35, Math.max(0.08, mediaDesv * 3 + 0.04));
  cal.giro = Math.min(80,   Math.max(12,   mediaGiro * 3 + 6));
  cal.listo = true;
  umb.quietoTolG = cal.tolG;
  umb.quietoGiro = cal.giro;
}

function vectorEn(tObj) {
  let mejor = null, dif = 1e9;
  for (let i = 0; i < bufM.length; i++) {
    const d = Math.abs(bufM[i].t - tObj);
    if (d < dif) { dif = d; mejor = bufM[i]; }
  }
  return mejor ? [mejor.sx, mejor.sy, mejor.sz] : [0, 0, 1];
}
function anguloEntre(u, v) {
  const du = Math.hypot(u[0], u[1], u[2]), dv = Math.hypot(v[0], v[1], v[2]);
  if (du < 1e-4 || dv < 1e-4) return 0;
  let c = (u[0]*v[0] + u[1]*v[1] + u[2]*v[2]) / (du * dv);
  c = Math.max(-1, Math.min(1, c));
  return Math.acos(c) * 180 / Math.PI;
}

function onMotion(e) {
  const a = e.accelerationIncludingGravity;
  if (!a || a.x === null || a.x === undefined) return;
  const r = e.rotationRate || {};
  const t = performance.now();
  const ax = a.x / G, ay = a.y / G, az = a.z / G;
  const gx = r.beta || 0, gy = r.gamma || 0, gz = r.alpha || 0;
  const svm = Math.hypot(ax, ay, az), gyro = Math.hypot(gx, gy, gz);

  const k = 0.15;
  sA[0] += k * (ax - sA[0]); sA[1] += k * (ay - sA[1]); sA[2] += k * (az - sA[2]);

  const m = { t: t, ax: ax, ay: ay, az: az, gx: gx, gy: gy, gz: gz,
              svm: svm, gyro: gyro, sx: sA[0], sy: sA[1], sz: sA[2] };
  bufM.push(m);
  while (bufM.length && t - bufM[0].t > 3000) bufM.shift();

  nMuestras++; totalMuestras++;
  if (t - tVentanaHz > 1000) {
    hzActual = Math.round(nMuestras * 1000 / (t - tVentanaHz));
    nMuestras = 0; tVentanaHz = t;
    $('hz').textContent = hzActual + ' Hz';
    $('barHz').style.width = Math.min(100, hzActual) + '%';
    const act = actividadActual();
    $('infoSensor').innerHTML = 'Aceleracion <b>' + svm.toFixed(2) + ' g</b> &nbsp; Giro <b>' +
      Math.round(gyro) + ' dps</b> &nbsp; Estado <b>' + st.fase + '</b>' +
      (act ? '<br>Ahora mismo: <b>' + act + '</b>' : '');
  }
  serieAcc.push(svm);
  if (serieAcc.length > 140) serieAcc.shift();

  if (grabando) filas.push(m);
  calibrar(m);
  detectar(m);
}

function detectar(m) {
  const t = m.t;
  if (m.svm < umb.libreG) {
    if (!st.tLibre) st.tLibre = t;
    if (t - st.tLibre >= umb.libreMs) st.marcaLibre = t;
  } else st.tLibre = 0;
  const huboLibre = st.marcaLibre > 0 && (t - st.marcaLibre) < 1200;

  switch (st.fase) {
    case 'REPOSO':
      if (m.svm > umb.impactoG) {
        st.picoSvm = m.svm; st.picoGiro = m.gyro;
        st.vAntes = vectorEn(t - 1500);
        st.fase = 'PICO'; st.t0 = t;
      }
      break;
    case 'PICO':
      if (m.svm > st.picoSvm) st.picoSvm = m.svm;
      if (m.gyro > st.picoGiro) st.picoGiro = m.gyro;
      if (t - st.t0 > 300) { st.fase = 'ASENTAR'; st.t0 = t; }
      break;
    case 'ASENTAR': {
      if (t - st.t0 < 500) break;
      const quieto = Math.abs(m.svm - 1) < umb.quietoTolG && m.gyro < umb.quietoGiro;
      if (!quieto) {
        st.fase = 'REPOSO';
        log('Golpe descartado', 'Sigue habiendo movimiento tras el impacto');
        break;
      }
      if (t - st.t0 < 500 + umb.quietoMs) break;
      const ang = anguloEntre(st.vAntes, [m.sx, m.sy, m.sz]);
      let p = 0;
      if (huboLibre) p += 2;
      if (st.picoGiro > umb.giroDps) p += 2;
      if (ang > umb.orientDeg) p += 2;
      if (st.picoSvm > umb.fuerteG) p += 1;
      const datos = 'pts=' + p + ';g=' + st.picoSvm.toFixed(1) +
                    ';dps=' + Math.round(st.picoGiro) + ';ang=' + Math.round(ang);
      if (p >= umb.puntosMin) {
        st.fase = 'PREALERTA'; st.t0 = t; st.datos = datos;
        procesarEstado('PREALERTA;' + datos, false);
      } else {
        st.fase = 'REPOSO';
        log('Golpe descartado', 'Solo ' + p + ' puntos de ' + umb.puntosMin + ' (' + datos + ')');
      }
      break;
    }
    case 'PREALERTA':
      if (t - st.t0 > cfg.avisos.cuentaS * 1000) {
        st.fase = 'CONFIRMADA'; st.t0 = t;
        procesarEstado('CAIDA_CONFIRMADA;' + st.datos, false);
      }
      break;
  }
}

async function iniciarSensores() {
  ocultarAviso();
  if (typeof DeviceMotionEvent === 'undefined') {
    mostrarAviso('Este navegador no da acceso al acelerometro. Abre CuidAPP con ' +
      '<b>Chrome en Android</b>.');
    return false;
  }
  if (!window.isSecureContext) {
    mostrarAviso('La pagina <b>no esta en un contexto seguro</b> y el navegador bloquea ' +
      'los sensores.<br>Estas en: <b>' + location.origin + '</b><br><br>' +
      'Tiene que ser <b>https://</b> o <b>http://localhost</b>.');
    return false;
  }
  if (typeof DeviceMotionEvent.requestPermission === 'function') {
    try {
      const p = await DeviceMotionEvent.requestPermission();
      if (p !== 'granted') {
        mostrarAviso('No diste permiso para los sensores. Recarga y acepta el aviso.');
        return false;
      }
    } catch (e) {
      mostrarAviso('No se pudo pedir permiso de sensores: ' + (e.message || e));
      return false;
    }
  }
  window.addEventListener('devicemotion', onMotion);
  tVentanaHz = performance.now();
  const antes = totalMuestras;
  setTimeout(function () {
    if (totalMuestras === antes) {
      mostrarAviso('No llegan datos de los sensores. Es normal en un <b>ordenador</b>, ' +
        'que no tiene acelerometro.<br>Para ver la app funcionando, usa el ' +
        '<b>menu de pruebas</b>.');
    } else ocultarAviso();
  }, 3000);
  return true;
}

async function pedirWakeLock() {
  try { if (navigator.wakeLock) wakeLock = await navigator.wakeLock.request('screen'); }
  catch (e) {}
}

document.addEventListener('visibilitychange', function () {
  if (!vigilando) return;
  if (document.visibilityState === 'hidden') {
    enviarVitales();
    log('Vigilancia pausada', 'Cambiaste de app o se apago la pantalla', 'ambar');
  } else {
    pedirWakeLock(); enviarVitales();
    log('Vigilancia reanudada', '', 'verde');
  }
});

async function alternarVigilancia() {
  if (vigilando) {
    if (!pedirPin('Vas a detener la vigilancia.')) return;
    vigilando = false;
    window.removeEventListener('devicemotion', onMotion);
    if (wakeLock) { try { wakeLock.release(); } catch (e) {} wakeLock = null; }
    $('btnVigilar').textContent = 'Iniciar vigilancia';
    $('btnVigilar').className = '';
    $('punto').classList.remove('on');
    $('txtEstado').textContent = 'Detenido';
    $('txtVig').textContent = 'Detenida';
    log('Vigilancia detenida');
    enviarVitales();
    return;
  }
  iniAudio();
  if (!await iniciarSensores()) return;
  vigilando = true; st.fase = 'REPOSO';
  pedirWakeLock();
  $('btnVigilar').textContent = 'Detener vigilancia';
  $('btnVigilar').className = 'rojo';
  $('punto').classList.add('on');
  $('txtEstado').textContent = 'Vigilando';
  $('txtVig').textContent = 'Activa';
  log('Vigilancia iniciada', 'Sensores del telefono', 'verde');
  enviarVitales();
}

setInterval(function () {
  if (vigilando || grabando) linea('gAcc', serieAcc, '#60a5fa', umb.impactoG);
}, 200);

/* ================================================================
   Grabacion del dataset (solo paciente)
   ================================================================ */

const ETIQUETAS = ['caida_frente', 'caida_atras', 'caida_lado', 'caida_silla',
                   'sentarse', 'tumbarse', 'andar', 'escalera', 'gesto'];
let etiqueta = leer('etiqueta', 'caida_frente');
let grabando = false, filas = [], tGrab = 0, cronoInt = null;
let grabs = [];

// Las grabaciones viven en IndexedDB. Si quedaron algunas en el sitio
// viejo, se pasan solas la primera vez.
(async function cargarGrabaciones() {
  grabs = await bdLeerTodo();
  const viejas = leer('grabs', []);
  if (viejas.length) {
    for (let i = 0; i < viejas.length; i++) await bdGuardar(viejas[i]);
    grabs = await bdLeerTodo();
    try { localStorage.removeItem('grabs'); } catch (e) {}
    log('Grabaciones movidas', viejas.length + ' pasaron a un almacen mas grande', 'verde');
  }
  grabs.sort(function (a, b) { return a.id - b.id; });
  pintarLista();
})();

function pintarChips() {
  const c = $('chips'); if (!c) return;
  c.innerHTML = '';
  ETIQUETAS.forEach(function (e) {
    const d = document.createElement('div');
    d.className = 'chip' + (e === etiqueta ? ' sel' : '');
    d.textContent = e.replace(/_/g, ' ');
    d.onclick = function () { etiqueta = e; escribir('etiqueta', e); pintarChips(); };
    c.appendChild(d);
  });
}
function guardarGrabs() { /* cada grabacion se guarda sola en IndexedDB */ }
function pintarLista() {
  const c = $('listaGrab'); if (!c) return;
  if (!grabs.length) c.innerHTML = '<div class="prog">Ninguna todavia.</div>';
  else {
    c.innerHTML = '';
    grabs.slice().reverse().forEach(function (g) {
      const d = document.createElement('div');
      d.className = 'grab';
      d.innerHTML = '<div><b>' + g.et.replace(/_/g, ' ') + '</b><small>' + g.dur.toFixed(1) +
                    ' s &middot; ' + g.n + ' muestras</small></div><span class="x">&times;</span>';
      d.querySelector('.x').onclick = function () {
        bdBorrar(g.id);
        grabs = grabs.filter(function (x) { return x.id !== g.id; });
        pintarLista();
      };
      c.appendChild(d);
    });
  }
  const cai = grabs.filter(function (g) { return g.et.indexOf('caida') === 0; }).length;
  const kb = Math.round(JSON.stringify(grabs).length / 1024);
  $('progreso').innerHTML =
    'Caidas: <b>' + cai + '</b> de 11 recomendadas<br>' +
    'Movimientos normales: <b>' + (grabs.length - cai) + '</b> de 18 recomendados<br>' +
    'Estas grabaciones ocupan <b>' + kb + ' kB</b>';
  // Espacio real del telefono, no una estimacion inventada.
  espacioLibre().then(function (e) {
    if (!e || !e.tope) return;
    const usadoMb = (e.usado / 1048576).toFixed(1);
    const topeMb  = Math.round(e.tope / 1048576);
    const pct = Math.round(e.usado / e.tope * 100);
    $('progreso').innerHTML += '<br>Espacio del telefono: <b>' + usadoMb + ' MB</b> de ' +
      topeMb + ' MB (' + pct + '%)' +
      (pct > 80 ? ' &nbsp;<span style="color:#fbbf24">queda poco</span>' : '');
  });
}
function alternarGrabacion() {
  if (grabando) {
    grabando = false; clearInterval(cronoInt);
    const dur = (performance.now() - tGrab) / 1000;
    if (filas.length > 20) {
      const csv = filas.map(function (m) {
        return Math.round(m.t - tGrab) + ',' + m.ax.toFixed(3) + ',' + m.ay.toFixed(3) + ',' +
               m.az.toFixed(3) + ',' + m.gx.toFixed(1) + ',' + m.gy.toFixed(1) + ',' +
               m.gz.toFixed(1) + ',' + m.svm.toFixed(3) + ',' + m.gyro.toFixed(1);
      }).join('\n');
      const g = { id: Date.now(), et: etiqueta, dur: dur, n: filas.length, csv: csv };
      grabs.push(g);
      bdGuardar(g).then(function (ok) {
        if (ok) log('Grabacion guardada', etiqueta + ' - ' + dur.toFixed(1) + ' s', 'verde');
        else {
          log('No se pudo guardar', 'Sin espacio en el telefono', 'rojo');
          mostrarAviso('<b>No se pudo guardar la grabacion.</b> Al telefono no le queda ' +
            'espacio. Descarga el dataset y borra las grabaciones.');
        }
      });
    } else log('Grabacion descartada', 'Demasiado corta');
    filas = [];
    $('btnGrabar').textContent = 'Empezar a grabar';
    $('btnGrabar').className = '';
    $('crono').textContent = '0.0 s';
    pintarLista();
    return;
  }
  (async function () {
    if (!vigilando && !await iniciarSensores()) return;
    filas = []; grabando = true; tGrab = performance.now();
    pedirWakeLock();
    $('btnGrabar').textContent = 'Parar';
    $('btnGrabar').className = 'rojo';
    cronoInt = setInterval(function () {
      $('crono').textContent = ((performance.now() - tGrab) / 1000).toFixed(1) + ' s';
    }, 100);
  })();
}
function descargar(nombre, texto, tipo) {
  const b = new Blob([texto], { type: tipo || 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(b); a.download = nombre;
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(function () { URL.revokeObjectURL(a.href); }, 3000);
}
function exportarDataset() {
  if (!grabs.length) { alert('No hay grabaciones que descargar.'); return; }
  let txt = 'grabacion,etiqueta,t_ms,ax,ay,az,gx,gy,gz,svm,gyro\n';
  grabs.forEach(function (g, i) {
    const pre = (i + 1) + ',' + g.et + ',';
    txt += g.csv.split('\n').map(function (l) { return pre + l; }).join('\n') + '\n';
  });
  descargar('dataset.csv', txt);
  log('dataset.csv descargado', grabs.length + ' grabaciones', 'verde');
}

/* ================================================================
   Pulso por camara
   ================================================================ */

let ppgStream = null, ppgInt = null;
async function medirPulso() {
  if (ppgStream) { pararPulso(); return; }
  try {
    ppgStream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: 'environment' }, width: { ideal: 320 }, height: { ideal: 240 } } });
  } catch (e) { $('infoPulso').textContent = 'No se pudo abrir la camara: ' + (e.message || e); return; }
  const track = ppgStream.getVideoTracks()[0];
  try { await track.applyConstraints({ advanced: [{ torch: true }] }); } catch (e) {}
  const v = $('video'); v.srcObject = ppgStream; await v.play();

  const cv = document.createElement('canvas'); cv.width = 60; cv.height = 60;
  const cx = cv.getContext('2d', { willReadFrequently: true });
  const bruto = [], picos = [];
  const t0 = performance.now();
  $('btnPulso').textContent = 'Parar medicion';

  ppgInt = setInterval(function () {
    const t = performance.now(), seg = (t - t0) / 1000;
    cx.drawImage(v, 0, 0, 60, 60);
    const d = cx.getImageData(15, 15, 30, 30).data;
    let r = 0, g = 0;
    for (let i = 0; i < d.length; i += 4) { r += d[i]; g += d[i + 1]; }
    const n = d.length / 4; r /= n; g /= n;
    if (r < 90 || r - g < 25) {
      $('infoPulso').textContent = 'No detecto el dedo. Tapa del todo la camara trasera y el flash.';
      return;
    }
    bruto.push({ t: t, v: r });
    while (bruto.length && t - bruto[0].t > 6000) bruto.shift();
    if (bruto.length < 20) return;
    const media = bruto.reduce(function (s, x) { return s + x.v; }, 0) / bruto.length;
    const sig = bruto.map(function (x) { return x.v - media; });
    linea('gPulso', sig.slice(-120), '#4ade80');
    const i = sig.length - 3;
    if (i > 1 && sig[i] > sig[i - 1] && sig[i] >= sig[i + 1] && sig[i] > 0.35) {
      const tp = bruto[i].t;
      if (!picos.length || tp - picos[picos.length - 1] > 350) {
        picos.push(tp);
        if (picos.length > 12) picos.shift();
      }
    }
    if (picos.length >= 4) {
      const iv = [];
      for (let k = 1; k < picos.length; k++) iv.push(picos[k] - picos[k - 1]);
      iv.sort(function (a, b) { return a - b; });
      const b = Math.round(60000 / iv[Math.floor(iv.length / 2)]);
      if (b >= 40 && b <= 180) pintarBpm(b);
    }
    $('infoPulso').textContent = 'Midiendo... ' + Math.round(seg) + ' s de 30. No muevas el dedo.';
    if (seg > 30) {
      pararPulso();
      $('infoPulso').textContent = 'Medicion terminada: ' + $('bpm').textContent + ' lpm.';
      log('Pulso medido', $('bpm').textContent + ' lpm por camara', 'verde');
    }
  }, 40);
}
function pararPulso() {
  if (ppgInt) { clearInterval(ppgInt); ppgInt = null; }
  if (ppgStream) { ppgStream.getTracks().forEach(function (t) { t.stop(); }); ppgStream = null; }
  $('btnPulso').textContent = 'Medir pulso (30 s)';
}

/* ================================================================
   Ubicacion
   ================================================================ */

function pedirUbicacion() {
  if (!navigator.geolocation || !cfg.avisos.ubicacion) return;
  navigator.geolocation.getCurrentPosition(function (p) {
    ultimaUbic = { lat: p.coords.latitude, lon: p.coords.longitude,
                   acc: Math.round(p.coords.accuracy), ts: Date.now() };
    estadoDe(cfg.sala).ubic = ultimaUbic;
    pintarMapa();
    publicar(cfg.sala, 'ubicacion', JSON.stringify(ultimaUbic));
    log('Ubicacion obtenida', 'Precision ' + ultimaUbic.acc + ' m');
    const e = estadoDe(cfg.sala);
    if (e.estado !== 'ok') avisarTelegram('Ubicacion: ' + enlaceMapa(ultimaUbic));
  }, function (err) {
    log('Sin ubicacion', err.message || 'permiso denegado');
  }, { enableHighAccuracy: true, timeout: 10000, maximumAge: 30000 });
}
function enlaceMapa(u) {
  if (!u) return null;
  return 'https://www.google.com/maps?q=' + u.lat + ',' + u.lon;
}
function pintarMapa() {
  const u = alertaDe ? (estadoDe(alertaDe).ubic || ultimaUbic) : ultimaUbic;
  $('btnMapa').style.display = u ? '' : 'none';
  const d = salaActual ? estadoDe(salaActual).ubic : null;
  $('btnMapaPanel').style.display = d ? '' : 'none';
}

/* ================================================================
   Audio durante una alerta
   ================================================================

   Sirve para lo unico que hace falta cuando salta una alarma: oir si
   la persona se queja, si esta hablando, o si fue una falsa alarma, y
   poder decirle "ya vamos" mientras llega alguien.

   Dos reglas que no se negocian, y conviene decirlas en la defensa:

     1. SOLO funciona con una alerta en curso. Fuera de eso el microfono
        ni se enciende. No es un sistema para escuchar a nadie.
     2. El paciente SIEMPRE ve en su pantalla que lo estan escuchando.
        Un microfono que se enciende a escondidas no es una funcion de
        cuidado, es espionaje.

   El audio va en trozos de segundo y medio. Cada trozo se graba entero
   por separado para que se pueda reproducir solo; si se cortara un
   flujo continuo, los pedazos sueltos no se podrian decodificar.      */

let micStream = null, emitiendo = false, escuchando = false;
const colaAudio = [];
let reproduciendo = false;

function tipoAudio() {
  const tipos = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4'];
  for (let i = 0; i < tipos.length; i++) {
    if (window.MediaRecorder && MediaRecorder.isTypeSupported(tipos[i])) return tipos[i];
  }
  return '';
}

function hayAlertaEnCurso() { return !!alertaDe; }

/** Enciende el microfono y empieza a mandar trozos. */
async function empezarAEmitir(canal, salaDestino) {
  if (emitiendo || !hayAlertaEnCurso()) return;
  if (!navigator.mediaDevices || !window.MediaRecorder) {
    log('Sin microfono', 'Este navegador no lo permite', 'rojo');
    return;
  }
  try {
    micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (e) {
    log('Microfono denegado', e.message || '', 'rojo');
    return;
  }
  emitiendo = true;
  pintarMic();
  (function trozo() {
    if (!emitiendo || !micStream) return;
    let mr;
    try { mr = new MediaRecorder(micStream, { mimeType: tipoAudio(), audioBitsPerSecond: 24000 }); }
    catch (e) { emitiendo = false; return; }
    const partes = [];
    mr.ondataavailable = function (e) { if (e.data && e.data.size) partes.push(e.data); };
    mr.onstop = function () {
      const b = new Blob(partes, { type: mr.mimeType });
      const fr = new FileReader();
      fr.onloadend = function () {
        const b64 = String(fr.result).split(',')[1];
        if (b64 && mqttCli && mqttCli.connected) {
          mqttCli.publish('brz/' + salaDestino + '/audio/' + canal,
            JSON.stringify({ t: mr.mimeType, d: b64, de: MI_ID }), { retain: false });
        }
        if (emitiendo) trozo();
      };
      fr.readAsDataURL(b);
    };
    mr.start();
    setTimeout(function () { try { mr.stop(); } catch (e) {} }, 1500);
  })();
}

function pararDeEmitir() {
  emitiendo = false;
  if (micStream) { micStream.getTracks().forEach(function (t) { t.stop(); }); micStream = null; }
  pintarMic();
}

/** Reproduce los trozos uno detras de otro, sin pisarse. */
function encolarAudio(tipo, b64) {
  colaAudio.push({ tipo: tipo, d: b64 });
  if (colaAudio.length > 8) colaAudio.shift();   // si se acumula, tirar lo viejo
  if (!reproduciendo) siguienteAudio();
}
function siguienteAudio() {
  const x = colaAudio.shift();
  if (!x) { reproduciendo = false; return; }
  reproduciendo = true;
  try {
    const bin = atob(x.d);
    const arr = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i);
    const url = URL.createObjectURL(new Blob([arr], { type: x.tipo }));
    const a = new Audio(url);
    a.onended = a.onerror = function () { URL.revokeObjectURL(url); siguienteAudio(); };
    a.play().catch(function () { URL.revokeObjectURL(url); siguienteAudio(); });
  } catch (e) { siguienteAudio(); }
}

function pedirEscuchar(activo) {
  if (!alertaDe || !mqttCli || !mqttCli.connected) return;
  mqttCli.publish('brz/' + alertaDe + '/audio/pedido',
    JSON.stringify({ activo: activo, de: MI_ID, nombre: cfg.nombre || 'Tu cuidador' }),
    { retain: false });
}

function pintarMic() {
  const soyPaciente = esPaciente();
  $('btnEscuchar').style.display = (!soyPaciente && hayAlertaEnCurso()) ? '' : 'none';
  $('btnHablar').style.display   = (!soyPaciente && hayAlertaEnCurso()) ? '' : 'none';
  $('btnEscuchar').textContent = escuchando ? 'Dejar de escuchar' : 'Escuchar que pasa';
  $('btnHablar').textContent   = emitiendo && !soyPaciente ? 'Dejar de hablar' : 'Hablarle';

  const av = $('micAviso');
  if (soyPaciente && emitiendo) {
    av.style.display = '';
    av.innerHTML = '<b>Tu cuidador te esta escuchando.</b><br>' +
      'Habla si necesitas algo. Se apaga solo al cancelar la alerta.';
  } else if (soyPaciente && escuchando) {
    av.style.display = '';
    av.innerHTML = '<b>Tu cuidador te esta hablando.</b>';
  } else {
    av.style.display = 'none';
  }
}

function avisarTelegram(txt) {
  if (!cfg.tgToken || !cfg.tgChat) return;
  fetch('https://api.telegram.org/bot' + cfg.tgToken + '/sendMessage?chat_id=' +
        encodeURIComponent(cfg.tgChat) + '&text=' + encodeURIComponent('[CuidAPP] ' + txt))
    .then(function () { log('Telegram enviado', '', 'verde'); })
    .catch(function () { log('Telegram enviado (sin confirmar)'); });
}

/* ================================================================
   Nube (MQTT) — varias salas a la vez
   ================================================================ */

function tema(sala, sub) { return 'brz/' + sala + '/' + sub; }

function salasQueEscucho() {
  if (esPaciente()) return [cfg.sala];
  if (esCuidador()) return cfg.pacientes.map(function (p) { return p.sala; });
  return [];   // el admin usa comodines
}

function conectarNube() {
  if (mqttCli) { try { mqttCli.end(true); } catch (e) {} mqttCli = null; }
  if (typeof mqtt === 'undefined') { $('txtNube').textContent = 'Sin libreria'; return; }
  const url = BROKERS[iBroker];
  $('txtNube').textContent = 'Conectando...';
  mqttCli = mqtt.connect(url, {
    clientId: 'cuidapp_' + MI_ID + '_' + Math.random().toString(16).slice(2, 6),
    reconnectPeriod: 4000, connectTimeout: 8000, clean: true
  });

  mqttCli.on('connect', function () {
    fallosBroker = 0;
    $('puntoNube').classList.add('on');
    $('txtNube').textContent = esAdmin() ? 'Escuchando todo' : 'Conectado';

    if (esAdmin()) {
      // Comodin: el admin ve todas las salas sin saber sus codigos de antemano.
      mqttCli.subscribe('brz/+/perfil');
      mqttCli.subscribe('brz/+/vitales');
      mqttCli.subscribe('brz/+/evento');
    } else {
      salasQueEscucho().forEach(function (s) { mqttCli.subscribe('brz/' + s + '/#'); });
      if (esPaciente()) publicarPerfil();
      // El cuidador avisa enseguida de que entro a mirar.
      if (esCuidador()) publicarPresencia();
    }
    log('Conectado', url.split('/')[2], 'verde');
  });

  mqttCli.on('error', function () { rotarBroker(); });
  mqttCli.on('close', function () {
    $('puntoNube').classList.remove('on');
    $('txtNube').textContent = 'Reconectando...';
    rotarBroker();
  });

  mqttCli.on('message', function (t, payload) {
    const txt = payload.toString();
    if (!txt) return;
    const partes = t.split('/');
    if (partes.length < 3) return;
    const sala = partes[1], sub = partes[2];
    const e = estadoDe(sala);

    if (sub === 'audio') {
      const canal = partes[3] || '';
      try {
        const d = JSON.parse(txt);
        if (d.de === MI_ID) return;                    // eco propio
        if (canal === 'pedido' && esPaciente() && sala === cfg.sala) {
          // Solo se enciende el microfono si hay una alerta en curso.
          if (d.activo && hayAlertaEnCurso()) empezarAEmitir('p', cfg.sala);
          else pararDeEmitir();
        } else if (canal === 'p' && !esPaciente() && escuchando) {
          encolarAudio(d.t, d.d);
        } else if (canal === 'c' && esPaciente() && hayAlertaEnCurso()) {
          escuchando = true; pintarMic();
          encolarAudio(d.t, d.d);
        }
      } catch (err) {}
      return;
    }
    if (sub === 'presencia') {
      // brz/<sala>/presencia/<id de cuidador>
      try {
        const d = JSON.parse(txt);
        if (d.id) e.cuidadores[d.id] = d;
      } catch (err) {}
    } else if (sub === 'perfil') {
      try { const d = JSON.parse(txt); e.nombre = d.nombre || ''; e.rol = d.rol; e.desde = d.desde; }
      catch (err) {}
    } else if (sub === 'vitales') {
      try {
        const d = JSON.parse(txt);
        e.vitales = d; e.ultimo = Date.now();
        if (sala === salaActual || (esPaciente() && sala === cfg.sala)) { /* se pinta abajo */ }
        anotarMuestra(d.bat, d.vig && !d.pausa, d.hz, sala);
      } catch (err) {}
    } else if (sub === 'evento') {
      recibirEvento(txt, sala);
    } else if (sub === 'ubicacion') {
      try { e.ubic = JSON.parse(txt); pintarMapa(); } catch (err) {}
    } else if (sub === 'historial') {
      fusionarHistorial(txt, sala);
    }
    pintarPacientes(); pintarDetalle(); pintarAdmin();
  });
}

function rotarBroker() {
  fallosBroker++;
  if (fallosBroker < 3) return;
  fallosBroker = 0;
  iBroker = (iBroker + 1) % BROKERS.length;
  log('Cambiando de servidor', BROKERS[iBroker].split('/')[2]);
  setTimeout(conectarNube, 500);
}

function publicar(sala, sub, contenido, retener) {
  if (!mqttCli || !mqttCli.connected) return;
  mqttCli.publish(tema(sala, sub), contenido, { retain: retener !== false });
}

function publicarPerfil() {
  publicar(cfg.sala, 'perfil', JSON.stringify({
    nombre: cfg.nombre || 'Paciente', rol: cfg.rol, id: MI_ID, desde: Date.now()
  }));
}

function publicarEvento(tipo, datos, prueba) {
  if (!mqttCli || !mqttCli.connected) return;
  const sala = esPaciente() ? cfg.sala : (alertaDe || salaActual || cfg.sala);
  mqttCli.publish(tema(sala, 'evento'), JSON.stringify({
    tipo: tipo, datos: datos || '', ts: Date.now(), de: MI_ID, prueba: !!prueba
  }), { retain: true });
}

function recibirEvento(txt, sala) {
  let tipo, datos, ts, de, prueba;
  try {
    const d = JSON.parse(txt);
    tipo = d.tipo; datos = d.datos || ''; ts = d.ts || 0; de = d.de; prueba = d.prueba;
  } catch (e) {
    const p = txt.split(';'); tipo = p[0]; datos = p.slice(1).join('  '); ts = Date.now();
  }
  if (!tipo || de === MI_ID) return;
  const viejo = ts && (Date.now() - ts > 300000);
  procesarEstado(tipo + (datos ? ';' + datos : ''), true, sala, viejo, prueba);
}

/**
 * Que esta haciendo la persona, version del navegador.
 *
 * El navegador no da podometro ni sensor de gravedad, asi que se deduce:
 * la direccion de la gravedad sale de filtrar la aceleracion (sA), y el
 * movimiento, de cuanto varia la aceleracion en los ultimos segundos.
 * Menos fino que en la app nativa, pero dice lo mismo.
 */
function actividadActual() {
  if (!vigilando || !totalMuestras) return '';

  const n = Math.min(serieAcc.length, 60);
  if (n < 10) return '';
  let suma = 0, suma2 = 0;
  for (let i = serieAcc.length - n; i < serieAcc.length; i++) {
    suma += serieAcc[i]; suma2 += serieAcc[i] * serieAcc[i];
  }
  const media = suma / n;
  const desvio = Math.sqrt(Math.max(0, suma2 / n - media * media));

  if (desvio > 0.22) return 'Moviendose';

  const mag = Math.hypot(sA[0], sA[1], sA[2]);
  if (mag < 0.3) return 'Quieto';
  const inclinacion = Math.acos(Math.min(1, Math.abs(sA[1]) / mag)) * 180 / Math.PI;
  if (inclinacion < 35) return 'Quieto, en vertical';
  if (inclinacion < 65) return 'Quieto, inclinado';
  return 'Quieto, en horizontal';
}

function enviarVitales() {
  if (!esPaciente()) return;
  const b = parseInt($('bpm').textContent, 10);
  const p = parseInt($('bat').textContent, 10);
  const pausa = document.visibilityState === 'hidden';
  publicar(cfg.sala, 'vitales', JSON.stringify({
    bpm: isNaN(b) ? 0 : b, bat: isNaN(p) ? 0 : p,
    vig: vigilando, pausa: pausa, hz: hzActual, act: actividadActual(), t: Date.now()
  }));
  anotarMuestra(isNaN(p) ? 0 : p, vigilando && !pausa, hzActual, cfg.sala);
}
setInterval(enviarVitales, 3000);

/* ================================================================
   Historial y estadisticas
   ================================================================ */

let hist = leer('hist', []);
const tUltimaMuestra = {};

function guardarHist() {
  if (hist.length > 2000) hist = hist.slice(-2000);
  escribir('hist', hist);
}
function anotarEvento(tipo, datos, ts, sala, prueba) {
  const t = ts || Date.now();
  sala = sala || cfg.sala;
  if (hist.some(function (x) {
    return x.k === 'ev' && x.tipo === tipo && x.sala === sala && Math.abs(x.t - t) < 1500;
  })) return;
  hist.push({ k: 'ev', t: t, sala: sala, tipo: tipo, datos: datos || '', prueba: !!prueba });
  guardarHist(); pintarHistorial();
  if (esPaciente()) publicarHistorial();
}
function anotarMuestra(bat, vig, hz, sala) {
  sala = sala || cfg.sala;
  const t = Date.now();
  if (t - (tUltimaMuestra[sala] || 0) < 60000) return;
  tUltimaMuestra[sala] = t;
  hist.push({ k: 'm', t: t, sala: sala, bat: bat, vig: vig ? 1 : 0, hz: hz || 0 });
  guardarHist(); pintarHistorial();
}
function publicarHistorial() {
  if (!mqttCli || !mqttCli.connected || !esPaciente()) return;
  const evs = hist.filter(function (x) { return x.k === 'ev' && x.sala === cfg.sala; }).slice(-60);
  publicar(cfg.sala, 'historial', JSON.stringify({ evs: evs }));
}
function fusionarHistorial(txt, sala) {
  try {
    const d = JSON.parse(txt);
    if (!d.evs) return;
    let nuevos = 0;
    d.evs.forEach(function (e) {
      if (!hist.some(function (x) {
        return x.k === 'ev' && x.tipo === e.tipo && x.sala === sala && Math.abs(x.t - e.t) < 1500;
      })) {
        hist.push({ k: 'ev', t: e.t, sala: sala, tipo: e.tipo,
                    datos: e.datos || '', prueba: !!e.prueba });
        nuevos++;
      }
    });
    if (nuevos) {
      hist.sort(function (a, b) { return a.t - b.t; });
      guardarHist(); pintarHistorial();
      log('Historial recuperado', nuevos + ' eventos anteriores de ' + nombreDe(sala));
    }
  } catch (e) {}
}

const NOMBRE_EV = {
  PREALERTA: 'Posible caida', CAIDA_CONFIRMADA: 'Caida confirmada',
  SOS_MANUAL: 'SOS manual', CANCELADA: 'Alerta cancelada'
};

function pintarHistorial() {
  if (!$('listaHist')) return;
  const reales = hist.filter(function (x) { return !x.prueba; });
  const evs = reales.filter(function (x) { return x.k === 'ev'; });
  const ms  = reales.filter(function (x) { return x.k === 'm'; });

  const pre  = evs.filter(function (e) { return e.tipo === 'PREALERTA'; }).length;
  const conf = evs.filter(function (e) { return e.tipo === 'CAIDA_CONFIRMADA' ||
                                                 e.tipo === 'SOS_MANUAL'; }).length;
  const canc = evs.filter(function (e) { return e.tipo === 'CANCELADA'; }).length;

  $('statAlertas').textContent = pre + conf;
  $('statAlertasDet').textContent = (pre + conf) === 0 ? 'ninguna'
    : conf + ' confirmadas, ' + canc + ' canceladas';
  $('statFalsas').textContent = (pre + conf) ? Math.round(canc / (pre + conf) * 100) + '%' : '--';

  if (ms.length) {
    const act = ms.filter(function (m) { return m.vig; }).length;
    $('statUptime').textContent = Math.round(act / ms.length * 100) + '%';
  }

  // Tiempo en el suelo: de cada PREALERTA a la CANCELADA que la sigue.
  const duraciones = [];
  for (let i = 0; i < evs.length; i++) {
    if (evs[i].tipo !== 'PREALERTA') continue;
    for (let j = i + 1; j < evs.length; j++) {
      if (evs[j].tipo === 'CANCELADA') { duraciones.push((evs[j].t - evs[i].t) / 1000); break; }
      if (evs[j].tipo === 'PREALERTA') break;
    }
  }
  $('statSuelo').textContent = duraciones.length
    ? Math.round(duraciones.reduce(function (a, b) { return a + b; }, 0) / duraciones.length) + ' s'
    : '--';

  if (hist.length) {
    const ini = new Date(hist[0].t), fin = new Date(hist[hist.length - 1].t);
    const horas = (fin - ini) / 3600000;
    $('resumenPeriodo').innerHTML =
      'Desde <b>' + ini.toLocaleString() + '</b><br>hasta <b>' + fin.toLocaleString() + '</b><br>' +
      'Periodo: <b>' + (horas < 1 ? Math.round(horas * 60) + ' min' : horas.toFixed(1) + ' h') +
      '</b> &nbsp;·&nbsp; <b>' + hist.length + '</b> registros';
  }

  const bats = ms.map(function (m) { return m.bat; }).filter(function (b) { return b > 0; });
  if (bats.length > 1) {
    linea('gHistBat', bats.slice(-200), '#4ade80');
    $('infoBat').textContent = 'De ' + Math.min.apply(null, bats) + '% a ' +
      Math.max.apply(null, bats) + '%, en ' + bats.length + ' medidas.';
  }

  // Vigilancia activa por hora
  const cubos = {};
  ms.forEach(function (m) {
    const h = Math.floor(m.t / 3600000);
    if (!cubos[h]) cubos[h] = { n: 0, a: 0 };
    cubos[h].n++; if (m.vig) cubos[h].a++;
  });
  const claves = Object.keys(cubos).sort();
  barras('gHistAct', claves.slice(-48).map(function (k) {
    return cubos[k].a / cubos[k].n * 100;
  }), '#60a5fa');

  const c = $('listaHist');
  if (!evs.length) { c.innerHTML = '<div class="prog">Sin eventos registrados.</div>'; return; }
  c.innerHTML = '';
  evs.slice().reverse().slice(0, 80).forEach(function (e) {
    const d = new Date(e.t);
    const clase = (e.tipo === 'CAIDA_CONFIRMADA' || e.tipo === 'SOS_MANUAL') ? 'rojo'
                : e.tipo === 'PREALERTA' ? 'ambar' : 'verde';
    const el = document.createElement('div');
    el.className = 'ev ' + clase;
    const quien = esCuidador() ? '<small>' + nombreDe(e.sala) + '</small>' : '';
    el.innerHTML = '<time>' + d.toLocaleDateString().slice(0, 5) + '<br>' +
      d.toLocaleTimeString().slice(0, 5) + '</time><div><b>' +
      (NOMBRE_EV[e.tipo] || e.tipo) + '</b>' + quien +
      (e.datos ? '<small>' + e.datos + '</small>' : '') + '</div>';
    c.appendChild(el);
  });
}

function exportarHistorial() {
  if (!hist.length) { alert('No hay historial que descargar.'); return; }
  let txt = 'fecha_hora,sala,persona,tipo,evento,bateria,vigilando,hz,prueba,datos\n';
  hist.forEach(function (x) {
    const f = new Date(x.t).toISOString();
    const q = nombreDe(x.sala || cfg.sala);
    if (x.k === 'ev') {
      txt += f + ',' + (x.sala || '') + ',"' + q + '",evento,' + x.tipo + ',,,,' +
             (x.prueba ? 1 : 0) + ',"' + (x.datos || '') + '"\n';
    } else {
      txt += f + ',' + (x.sala || '') + ',"' + q + '",muestra,,' + x.bat + ',' +
             x.vig + ',' + x.hz + ',0,\n';
    }
  });
  descargar('historial.csv', txt);
}

/* ================================================================
   Lista de pacientes (cuidador)
   ================================================================ */

function clasificar(sala) {
  const e = estadoDe(sala);
  const seg = e.ultimo ? (Date.now() - e.ultimo) / 1000 : null;
  if (e.estado === 'caida') return { luz: 'mal', txt: 'CAIDA CONFIRMADA', det: 'Contacta ahora mismo.' };
  if (e.estado === 'prealerta') return { luz: 'aviso', txt: 'Posible caida', det: 'Esperando confirmacion.' };
  if (seg === null) return { luz: '', txt: 'Sin datos', det: 'Todavia no se conecto. Comprueba el codigo.' };
  if (seg > 45) return { luz: 'mal', txt: 'SIN SEÑAL',
    det: 'Sin noticias hace ' + (seg < 120 ? Math.round(seg) + ' s' : Math.round(seg / 60) + ' min') +
         '. No se detectan caidas.' };
  if (e.vitales.pausa) return { luz: 'aviso', txt: 'Vigilancia pausada',
    det: 'Cambio de app o se apago la pantalla.' };
  if (!e.vitales.vig) return { luz: 'aviso', txt: 'Vigilancia detenida', det: 'No inicio la vigilancia.' };
  const bat = e.vitales.bat;
  // Si el telefono del paciente sabe que esta haciendo, se dice eso en vez
  // de un "todo normal" generico: para el cuidador vale mucho mas.
  const act = e.vitales.act;
  return { luz: 'ok', txt: act || 'Todo normal',
    det: 'Hace ' + Math.round(seg) + ' s' +
         (bat ? '  ·  bateria ' + bat + '%' : '') +
         (e.vitales.pasos > 0 ? '  ·  ' + e.vitales.pasos + ' pasos' : '') };
}

function pintarPacientes() {
  const c = $('listaPacientes'); if (!c) return;
  if (!esCuidador()) return;
  if (!cfg.pacientes.length) {
    c.innerHTML = '<div class="card"><div class="prog">Todavia no vigilas a nadie.<br><br>' +
      'Pedile a la persona que abra CuidAPP en su telefono, entre en el engranaje y toque ' +
      '<b>"Enviar enlace a mi cuidador"</b>. Cuando abras ese enlace, aparece aca sola.</div></div>';
    return;
  }
  c.innerHTML = '';
  cfg.pacientes.forEach(function (p) {
    const k = clasificar(p.sala);
    const d = document.createElement('div');
    d.className = 'paciente';
    d.innerHTML = '<i class="luz ' + k.luz + '"></i><div class="txt"><b>' +
      (p.nombre || nombreDe(p.sala)) + '</b><small>' + k.txt + ' &middot; ' + k.det +
      '</small></div><span class="flecha">&rsaquo;</span>';
    d.onclick = function () { salaActual = p.sala; ir('d'); };
    c.appendChild(d);
  });
}

function pintarDetalle() {
  if (!salaActual || !$('detEstado')) return;
  const e = estadoDe(salaActual), k = clasificar(salaActual);
  $('subtitulo').textContent = nombreDe(salaActual);
  $('detEstado').textContent = k.txt;
  $('detEstado').style.color = k.luz === 'mal' ? '#f87171'
                             : k.luz === 'aviso' ? '#fbbf24'
                             : k.luz === 'ok' ? '#4ade80' : '#8d8a83';
  $('detInfo').textContent = k.det;
  const bat = e.vitales.bat || 0;
  $('detBat').textContent = bat ? bat + '%' : '--%';
  $('detBarBat').style.width = bat + '%';
  $('detBarBat').style.background = bat < 20 ? '#f87171' : bat < 40 ? '#fbbf24' : '#4ade80';
  $('detBpm').textContent = e.vitales.bpm || '--';

  const c = $('detLog');
  if (!e.eventos.length) c.innerHTML = '<div class="prog">Sin eventos registrados.</div>';
  else {
    c.innerHTML = '';
    e.eventos.slice().reverse().slice(0, 30).forEach(function (ev) {
      const d = new Date(ev.t);
      const clase = (ev.tipo === 'CAIDA_CONFIRMADA' || ev.tipo === 'SOS_MANUAL') ? 'rojo'
                  : ev.tipo === 'PREALERTA' ? 'ambar' : 'verde';
      const el = document.createElement('div');
      el.className = 'ev ' + clase;
      el.innerHTML = '<time>' + d.toLocaleTimeString().slice(0, 5) + '</time><div><b>' +
        (NOMBRE_EV[ev.tipo] || ev.tipo) + (ev.prueba ? ' [prueba]' : '') + '</b>' +
        (ev.datos ? '<small>' + ev.datos + '</small>' : '') + '</div>';
      c.appendChild(el);
    });
  }
  pintarCuidadores('detCuidadores', salaActual);
  pintarMapa();
}

/* ================================================================
   Panel del desarrollador
   ================================================================ */

function pintarAdmin() {
  if (!esAdmin() || !$('listaAdmin')) return;
  const salas = Object.keys(estados);
  $('admSalas').textContent = salas.length;
  $('admDisp').textContent = salas.filter(function (s) {
    return estados[s].ultimo && Date.now() - estados[s].ultimo < 120000;
  }).length;
  const c = $('listaAdmin');
  if (!salas.length) { c.innerHTML = '<div class="prog">Escuchando el servidor...</div>'; return; }
  c.innerHTML = '';
  salas.sort(function (a, b) { return (estados[b].ultimo || 0) - (estados[a].ultimo || 0); })
    .forEach(function (s) {
      const e = estados[s], k = clasificar(s);
      const d = document.createElement('div');
      d.className = 'paciente';
      const act = e.ultimo ? Math.round((Date.now() - e.ultimo) / 1000) + ' s' : 'nunca';
      d.innerHTML = '<i class="luz ' + k.luz + '"></i><div class="txt"><b>' +
        (e.nombre || '(sin nombre)') + '  <small style="display:inline">' + s + '</small></b>' +
        '<small>' + k.txt + ' &middot; ultimo dato hace ' + act +
        (e.vitales.hz ? ' &middot; ' + e.vitales.hz + ' Hz' : '') + '</small></div>';
      c.appendChild(d);
    });
}

/* ================================================================
   Menu de pruebas
   ================================================================ */

function diagnostico() {
  const l = [];
  l.push((window.isSecureContext ? '&#10003;' : '&#10007;') + ' Contexto seguro (' + location.protocol + ')');
  l.push((typeof DeviceMotionEvent !== 'undefined' ? '&#10003;' : '&#10007;') + ' Acelerometro disponible');
  l.push((totalMuestras > 0 ? '&#10003;' : '&#10007;') + ' Sensores enviando datos (' + totalMuestras + ' muestras)');
  l.push((mqttCli && mqttCli.connected ? '&#10003;' : '&#10007;') + ' Conexion con el servidor');
  l.push((navigator.geolocation ? '&#10003;' : '&#10007;') + ' Ubicacion disponible');
  l.push((navigator.vibrate ? '&#10003;' : '&#10007;') + ' Vibracion disponible');
  l.push(('serviceWorker' in navigator && navigator.serviceWorker.controller ? '&#10003;' : '&#10007;') +
         ' Instalable / funciona sin internet');
  l.push((navigator.wakeLock ? '&#10003;' : '&#10007;') + ' Puede mantener la pantalla encendida');
  l.push('');
  if (cal.listo) {
    l.push('&#10003; <b>Deteccion calibrada para esta persona</b>');
    l.push('&nbsp;&nbsp;&nbsp;Quietud: ' + cal.tolG.toFixed(3) + ' g y ' +
           Math.round(cal.giro) + ' dps, con ' + cal.n + ' muestras de reposo');
  } else if (cfg.avisos.autocal) {
    l.push('&#8230; Calibrando (' + cal.n + ' de 500 muestras de reposo). ' +
           'Deja el telefono quieto un rato con la vigilancia activa.');
  } else {
    l.push('&#10007; Ajuste automatico desactivado: se usan los umbrales fijos');
  }
  $('diagnostico').innerHTML = l.join('<br>');
}

function medirPing() {
  if (!mqttCli || !mqttCli.connected) { $('pingInfo').textContent = 'Sin conexion.'; return; }
  const marca = 'ping' + Date.now();
  const t0 = performance.now();
  const sala = esPaciente() ? cfg.sala : (cfg.pacientes[0] && cfg.pacientes[0].sala) || cfg.sala;
  const tm = tema(sala, 'ping');
  mqttCli.subscribe(tm);
  const alRecibir = function (t, p) {
    if (t !== tm || p.toString() !== marca) return;
    const ms = Math.round(performance.now() - t0);
    $('pingInfo').innerHTML = 'Ida y vuelta al servidor: <b>' + ms + ' ms</b>' +
      (ms < 300 ? ' — excelente' : ms < 900 ? ' — bien' : ' — lento, puede tardar en avisar');
    mqttCli.removeListener('message', alRecibir);
    mqttCli.unsubscribe(tm);
  };
  mqttCli.on('message', alRecibir);
  $('pingInfo').textContent = 'Midiendo...';
  mqttCli.publish(tm, marca, { retain: false });
  setTimeout(function () {
    if ($('pingInfo').textContent === 'Midiendo...') {
      $('pingInfo').textContent = 'No volvio el mensaje. Revisa la conexion.';
      try { mqttCli.removeListener('message', alRecibir); mqttCli.unsubscribe(tm); } catch (e) {}
    }
  }, 6000);
}

/* ================================================================
   Ajustes
   ================================================================ */

const CAMPOS = [['uImpacto','impactoG'], ['uFuerte','fuerteG'], ['uLibre','libreG'],
  ['uGiro','giroDps'], ['uOrient','orientDeg'], ['uQuieto','quietoMs'],
  ['uTol','quietoTolG'], ['uPuntos','puntosMin']];

function pintarSalasCuidador() {
  const c = $('listaSalasCuidador'); if (!c) return;
  if (!cfg.pacientes.length) { c.innerHTML = '<p class="nota">Todavia no agregaste a nadie.</p>'; return; }
  c.innerHTML = '';
  cfg.pacientes.forEach(function (p, i) {
    const d = document.createElement('div');
    d.className = 'grab';
    d.innerHTML = '<div><b>' + p.nombre + '</b><small>' + p.sala + '</small></div>' +
                  '<span class="x">&times;</span>';
    d.querySelector('.x').onclick = function () {
      cfg.pacientes.splice(i, 1);
      escribir('pacientes', cfg.pacientes);
      pintarSalasCuidador(); pintarPacientes(); conectarNube();
    };
    c.appendChild(d);
  });
}

function abrirAjustes() {
  $('inNombre').value = cfg.nombre;
  $('inSala').value = cfg.sala;
  $('inTel').value = cfg.tel;
  $('inTgToken').value = cfg.tgToken;
  $('inTgChat').value = cfg.tgChat;
  $('inCuenta').value = cfg.avisos.cuentaS;
  $('inPin').value = cfg.pin;
  $('inEscalar').value = cfg.avisos.escalarS;
  $('chkAutocal').checked = cfg.avisos.autocal;
  $('chkAuto').checked = cfg.avisos.auto;
  $('chkSonido').checked = cfg.avisos.sonido;
  $('chkVibrar').checked = cfg.avisos.vibrar;
  $('chkUbicacion').checked = cfg.avisos.ubicacion;
  $('modoPaciente').classList.toggle('sel', esPaciente());
  $('modoCuidador').classList.toggle('sel', esCuidador());
  $('modoAdmin').classList.toggle('sel', esAdmin());
  $('bloquePaciente').style.display = esPaciente() ? '' : 'none';
  $('bloqueCuidador').style.display = esCuidador() ? '' : 'none';
  $('notaModo').textContent =
    esPaciente() ? 'Tu telefono detecta caidas y avisa a quien tenga tu codigo.'
    : esCuidador() ? 'Recibis avisos de las personas que vigiles y guardas su historial en este telefono.'
    : 'Ves todas las salas activas del servidor. Solo para el desarrollo.';
  CAMPOS.forEach(function (c) { $(c[0]).value = umb[c[1]]; });
  pintarSalasCuidador();
  $('ajustes').classList.add('ver');
}

function guardarAjustes() {
  cfg.nombre  = $('inNombre').value.trim();
  cfg.sala    = $('inSala').value.trim() || cfg.sala;
  cfg.tel     = $('inTel').value.trim() || '112';
  cfg.tgToken = $('inTgToken').value.trim();
  cfg.tgChat  = $('inTgChat').value.trim();
  cfg.pin     = ($('inPin').value || '').replace(/\D/g, '').slice(0, 4);
  cfg.avisos.cuentaS   = Math.max(5, Math.min(120, parseInt($('inCuenta').value, 10) || 25));
  cfg.avisos.escalarS  = Math.max(0, Math.min(600, parseInt($('inEscalar').value, 10) || 0));
  cfg.avisos.autocal   = $('chkAutocal').checked;
  cfg.avisos.auto      = $('chkAuto').checked;
  cfg.avisos.sonido    = $('chkSonido').checked;
  cfg.avisos.vibrar    = $('chkVibrar').checked;
  cfg.avisos.ubicacion = $('chkUbicacion').checked;
  ['nombre','rol','sala','tel','tgToken','tgChat','avisos','pacientes','pin'].forEach(function (k) {
    escribir(k, cfg[k]);
  });
  CAMPOS.forEach(function (c) {
    const v = parseFloat($(c[0]).value);
    if (!isNaN(v)) umb[c[1]] = v;
  });
  escribir('umb', umb);
  $('ajustes').classList.remove('ver');
  aplicarRol();
  conectarNube();
  log('Ajustes guardados', 'Papel: ' + cfg.rol);
}

/* ================================================================
   Navegacion y roles
   ================================================================ */

const PAGINAS = { m: 'pagMonitor', p: 'pagPacientes', d: 'pagDetalle',
                  h: 'pagHistorial', g: 'pagGrabar', t: 'pagPruebas', a: 'pagAdmin' };
const NAVS = { m: 'navMonitor', p: 'navPacientes', h: 'navHistorial',
               g: 'navGrabar', t: 'navPruebas', a: 'navAdmin' };

function ir(p) {
  Object.keys(PAGINAS).forEach(function (k) {
    $(PAGINAS[k]).classList.toggle('ver', k === p);
  });
  Object.keys(NAVS).forEach(function (k) {
    $(NAVS[k]).classList.toggle('sel', k === p || (p === 'd' && k === 'p'));
  });
  if (p === 'h') pintarHistorial();
  if (p === 'p') { pintarPacientes(); $('subtitulo').textContent = 'Cuidador'; }
  if (p === 'd') pintarDetalle();
  if (p === 't') diagnostico();
  if (p === 'a') pintarAdmin();
}

function aplicarRol() {
  const rol = cfg.rol;
  $('subtitulo').textContent = rol === 'paciente' ? (cfg.nombre || 'Paciente')
                             : rol === 'cuidador' ? 'Cuidador' : 'Desarrollador';
  // Con PIN puesto, el telefono del paciente se queda solo con lo suyo:
  // ni grabar datasets, ni menu de pruebas, ni cambiar de papel.
  const simple = esPaciente() && bloqueado();
  $('navMonitor').style.display   = esPaciente() ? '' : 'none';
  $('navGrabar').style.display    = (esPaciente() && !simple) ? '' : 'none';
  $('navPruebas').style.display   = simple ? 'none' : '';
  $('navPacientes').style.display = esCuidador() ? '' : 'none';
  $('navHistorial').style.display = esAdmin() ? 'none' : '';
  $('navAdmin').style.display     = esAdmin() ? '' : 'none';
  $('cardApk').style.display      = (esAdmin() || simple) ? 'none' : '';
  ir(esPaciente() ? 'm' : esCuidador() ? 'p' : 'a');
}

/* ================================================================
   Enganches de la interfaz
   ================================================================ */

$('btnAjustes').onclick = function () {
  if (pedirPin('Los ajustes estan protegidos.')) abrirAjustes();
};
$('btnCerrarAjustes').onclick = function () { $('ajustes').classList.remove('ver'); };
$('btnGuardar').onclick = guardarAjustes;
$('btnRestaurar').onclick = function () { umb = Object.assign({}, UMB_DEF); abrirAjustes(); };

$('modoPaciente').onclick = function () { cfg.rol = 'paciente'; abrirAjustes(); };
$('modoCuidador').onclick = function () { cfg.rol = 'cuidador'; abrirAjustes(); };
$('modoAdmin').onclick    = function () { cfg.rol = 'admin';    abrirAjustes(); };

$('btnCompartir').onclick = function () {
  const sala = $('inSala').value.trim() || cfg.sala;
  const nom = encodeURIComponent($('inNombre').value.trim() || 'Paciente');
  const url = location.origin + location.pathname + '?sala=' + encodeURIComponent(sala) +
              '&modo=cuidador&nombre=' + nom;
  if (navigator.share) navigator.share({ title: 'CuidAPP', text: 'Vigilame con CuidAPP', url: url }).catch(function () {});
  else if (navigator.clipboard) navigator.clipboard.writeText(url)
    .then(function () { alert('Enlace copiado:\n\n' + url); })
    .catch(function () { prompt('Copia este enlace:', url); });
  else prompt('Copia este enlace:', url);
};

$('btnAgregarSala').onclick = function () {
  const nom = $('inNuevoNombre').value.trim() || 'Paciente';
  const cod = $('inNuevoCodigo').value.trim();
  if (!cod) { alert('Falta el codigo de la persona.'); return; }
  if (cfg.pacientes.some(function (x) { return x.sala === cod; })) { alert('Ya lo tenes agregado.'); return; }
  cfg.pacientes.push({ sala: cod, nombre: nom });
  escribir('pacientes', cfg.pacientes);
  $('inNuevoNombre').value = ''; $('inNuevoCodigo').value = '';
  pintarSalasCuidador(); pintarPacientes(); conectarNube();
};
$('btnAgregarPaciente').onclick = function () { abrirAjustes(); };

$('btnVolverLista').onclick = function () { salaActual = null; ir('p'); };
$('btnQuitarPaciente').onclick = function () {
  if (!salaActual) return;
  if (!confirm('Dejar de vigilar a ' + nombreDe(salaActual) + '?')) return;
  cfg.pacientes = cfg.pacientes.filter(function (p) { return p.sala !== salaActual; });
  escribir('pacientes', cfg.pacientes);
  salaActual = null; ir('p'); conectarNube();
};
$('btnLlamarDet').onclick = function () { location.href = 'tel:' + cfg.tel; };
$('btnInvitarCuidador').onclick = function () {
  if (!salaActual) return;
  const url = location.origin + location.pathname + '?sala=' + encodeURIComponent(salaActual) +
              '&modo=cuidador&nombre=' + encodeURIComponent(nombreDe(salaActual));
  const txt = 'Ayudame a cuidar a ' + nombreDe(salaActual) + '. Abri este enlace:\n' + url;
  if (navigator.share) navigator.share({ title: 'CuidAPP', text: txt, url: url }).catch(function () {});
  else if (navigator.clipboard) navigator.clipboard.writeText(url)
    .then(function () { alert('Enlace copiado:\n\n' + url); })
    .catch(function () { prompt('Copia este enlace:', url); });
  else prompt('Copia este enlace:', url);
};

$('btnVigilar').onclick = alternarVigilancia;
$('btnSos').onclick = function () { iniAudio(); procesarEstado('SOS_MANUAL', false); };
$('btnGrabar').onclick = alternarGrabacion;
$('btnExportar').onclick = exportarDataset;
$('btnVaciar').onclick = function () {
  if (confirm('Se borran las ' + grabs.length + ' grabaciones. Descargaste el dataset?')) {
    bdVaciar(); grabs = []; pintarLista();
  }
};
$('btnPulso').onclick = medirPulso;
$('btnExportHist').onclick = exportarHistorial;
$('btnBorrarHist').onclick = function () {
  if (confirm('Se borra el historial de este telefono. Lo descargaste?')) {
    hist = []; guardarHist(); pintarHistorial();
  }
};
$('btnApk').onclick = function () { location.href = APK; };

$('btnEstoyBien').onclick = function () {
  const sala = alertaDe || cfg.sala;
  cerrarAlerta(); st.fase = 'REPOSO';
  procesarEstado('CANCELADA', false, sala);
};
$('btnLlamar').onclick = function () { location.href = 'tel:' + cfg.tel; };
$('btnEscuchar').onclick = function () {
  escuchando = !escuchando;
  pedirEscuchar(escuchando);
  if (!escuchando) colaAudio.length = 0;
  pintarMic();
  log(escuchando ? 'Escuchando al paciente' : 'Dejaste de escuchar', '', 'ambar');
};
$('btnHablar').onclick = function () {
  if (emitiendo) { pararDeEmitir(); return; }
  empezarAEmitir('c', alertaDe || cfg.sala);
};
$('btnMapa').onclick = function () {
  const u = alertaDe ? (estadoDe(alertaDe).ubic || ultimaUbic) : ultimaUbic;
  if (u) window.open(enlaceMapa(u), '_blank');
};
$('btnMapaPanel').onclick = function () {
  const u = salaActual ? estadoDe(salaActual).ubic : null;
  if (u) window.open(enlaceMapa(u), '_blank');
};

$('btnPruebaPre').onclick    = function () { iniAudio(); procesarEstado('PREALERTA;pts=6;g=3.4;dps=312;ang=74', false, null, false, true); };
$('btnPruebaConf').onclick   = function () { iniAudio(); procesarEstado('CAIDA_CONFIRMADA;pts=6;g=3.4;dps=312;ang=74', false, null, false, true); };
$('btnPruebaSos').onclick    = function () { iniAudio(); procesarEstado('SOS_MANUAL', false, null, false, true); };
$('btnPruebaCancel').onclick = function () { procesarEstado('CANCELADA', false, alertaDe || cfg.sala, false, true); };
$('btnDiag').onclick = diagnostico;
$('btnPing').onclick = medirPing;

Object.keys(NAVS).forEach(function (k) { $(NAVS[k]).onclick = function () { ir(k); }; });

/* ================================================================
   Instalacion y arranque
   ================================================================ */

let promptInstalar = null;
if ('serviceWorker' in navigator && location.protocol !== 'file:') {
  window.addEventListener('load', function () {
    navigator.serviceWorker.register('sw.js').catch(function () {});
  });
}
window.addEventListener('beforeinstallprompt', function (e) {
  e.preventDefault(); promptInstalar = e; $('btnInstalar').style.display = '';
});
$('btnInstalar').onclick = function () {
  if (!promptInstalar) return;
  promptInstalar.prompt();
  promptInstalar.userChoice.then(function () {
    promptInstalar = null; $('btnInstalar').style.display = 'none';
  });
};
window.addEventListener('appinstalled', function () {
  $('btnInstalar').style.display = 'none';
  log('CuidAPP instalada', 'Ya se abre desde el icono', 'verde');
});

if (navigator.getBattery) {
  navigator.getBattery().then(function (b) {
    const u = function () { pintarBat(Math.round(b.level * 100)); };
    u(); b.addEventListener('levelchange', u);
  });
}
window.addEventListener('resize', function () {
  linea('gAcc', serieAcc, '#60a5fa', umb.impactoG);
  linea('gBpm', serieBpm, '#f87171');
  pintarHistorial();
});

setInterval(function () {
  pintarPacientes(); pintarDetalle(); pintarAdmin();
  // El paciente ve quien lo esta vigilando y hace cuanto que no lo miran.
  if (esPaciente()) pintarCuidadores('misCuidadores', cfg.sala);
}, 2000);

pintarChips(); pintarLista(); pintarHistorial();
aplicarRol(); conectarNube();

if (!window.isSecureContext) {
  mostrarAviso('Esta pagina no esta en un <b>contexto seguro</b> (' + location.origin + '), ' +
    'asi que el navegador va a bloquear los sensores y la camara.<br><br>' +
    'Tiene que abrirse por <b>https://</b> o <b>http://localhost</b>.');
}
