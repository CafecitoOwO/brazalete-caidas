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
function escribir(clave, valor) {
  try { localStorage.setItem(clave, JSON.stringify(valor)); } catch (e) {}
}

const cfg = {
  nombre:    leer('nombre', ''),
  rol:       leer('rol', 'paciente'),
  sala:      leer('sala', Math.random().toString(36).slice(2, 10)),
  pacientes: leer('pacientes', []),          // [{sala, nombre}] para el cuidador
  tel:       leer('tel', '112'),
  tgToken:   leer('tgToken', ''),
  tgChat:    leer('tgChat', ''),
  avisos:    Object.assign(
               { auto: true, sonido: true, vibrar: true, ubicacion: true, cuentaS: 25 },
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
                      ubic: null, eventos: [] };
  }
  return estados[sala];
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
}
function cerrarAlerta() {
  $('alerta').className = '';
  clearInterval(cuentaAtras);
  pararSonido();
  alertaDe = null;
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
  } else if (tipo === 'SOS_MANUAL') {
    e.estado = 'caida';
    log('SOS manual' + suf + marca, '', 'rojo');
    if (alarmar) mostrarAlerta('conf', '', sala);
    if (!remoto && !prueba) avisarTelegram('SOS manual.');
  } else if (tipo === 'CANCELADA') {
    e.estado = 'ok';
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
    $('infoSensor').innerHTML = 'Aceleracion <b>' + svm.toFixed(2) + ' g</b> &nbsp; Giro <b>' +
      Math.round(gyro) + ' dps</b> &nbsp; Estado <b>' + st.fase + '</b>';
  }
  serieAcc.push(svm);
  if (serieAcc.length > 140) serieAcc.shift();

  if (grabando) filas.push(m);
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
let grabs = leer('grabs', []);

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
function guardarGrabs() {
  try { localStorage.setItem('grabs', JSON.stringify(grabs)); }
  catch (e) { alert('Se lleno el almacenamiento. Descarga el dataset y borra las grabaciones.'); }
}
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
        grabs = grabs.filter(function (x) { return x.id !== g.id; });
        guardarGrabs(); pintarLista();
      };
      c.appendChild(d);
    });
  }
  const cai = grabs.filter(function (g) { return g.et.indexOf('caida') === 0; }).length;
  const kb = Math.round(JSON.stringify(grabs).length / 1024);
  $('progreso').innerHTML =
    'Caidas: <b>' + cai + '</b> de 11 recomendadas<br>' +
    'Movimientos normales: <b>' + (grabs.length - cai) + '</b> de 18 recomendados<br>' +
    'Espacio usado: <b>' + kb + ' kB</b> de unos 4000';
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
      grabs.push({ id: Date.now(), et: etiqueta, dur: dur, n: filas.length, csv: csv });
      guardarGrabs();
      log('Grabacion guardada', etiqueta + ' - ' + dur.toFixed(1) + ' s', 'verde');
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

    if (sub === 'perfil') {
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

function enviarVitales() {
  if (!esPaciente()) return;
  const b = parseInt($('bpm').textContent, 10);
  const p = parseInt($('bat').textContent, 10);
  const pausa = document.visibilityState === 'hidden';
  publicar(cfg.sala, 'vitales', JSON.stringify({
    bpm: isNaN(b) ? 0 : b, bat: isNaN(p) ? 0 : p,
    vig: vigilando, pausa: pausa, hz: hzActual, t: Date.now()
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
  return { luz: 'ok', txt: 'Todo normal',
    det: 'Hace ' + Math.round(seg) + ' s' + (bat ? '  ·  bateria ' + bat + '%' : '') };
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
  cfg.avisos.cuentaS   = Math.max(5, Math.min(120, parseInt($('inCuenta').value, 10) || 25));
  cfg.avisos.auto      = $('chkAuto').checked;
  cfg.avisos.sonido    = $('chkSonido').checked;
  cfg.avisos.vibrar    = $('chkVibrar').checked;
  cfg.avisos.ubicacion = $('chkUbicacion').checked;
  ['nombre','rol','sala','tel','tgToken','tgChat','avisos','pacientes'].forEach(function (k) {
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
  $('navMonitor').style.display   = esPaciente() ? '' : 'none';
  $('navGrabar').style.display    = esPaciente() ? '' : 'none';
  $('navPacientes').style.display = esCuidador() ? '' : 'none';
  $('navHistorial').style.display = esAdmin() ? 'none' : '';
  $('navAdmin').style.display     = esAdmin() ? '' : 'none';
  $('cardApk').style.display      = esAdmin() ? 'none' : '';
  ir(esPaciente() ? 'm' : esCuidador() ? 'p' : 'a');
}

/* ================================================================
   Enganches de la interfaz
   ================================================================ */

$('btnAjustes').onclick = abrirAjustes;
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

$('btnVigilar').onclick = alternarVigilancia;
$('btnSos').onclick = function () { iniAudio(); procesarEstado('SOS_MANUAL', false); };
$('btnGrabar').onclick = alternarGrabacion;
$('btnExportar').onclick = exportarDataset;
$('btnVaciar').onclick = function () {
  if (confirm('Se borran las ' + grabs.length + ' grabaciones. Descargaste el dataset?')) {
    grabs = []; guardarGrabs(); pintarLista();
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

setInterval(function () { pintarPacientes(); pintarDetalle(); pintarAdmin(); }, 2000);

pintarChips(); pintarLista(); pintarHistorial();
aplicarRol(); conectarNube();

if (!window.isSecureContext) {
  mostrarAviso('Esta pagina no esta en un <b>contexto seguro</b> (' + location.origin + '), ' +
    'asi que el navegador va a bloquear los sensores y la camara.<br><br>' +
    'Tiene que abrirse por <b>https://</b> o <b>http://localhost</b>.');
}
