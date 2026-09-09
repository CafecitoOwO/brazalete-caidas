package com.proyecto.brazalete;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

/**
 * El corazon de la app nativa.
 *
 * Corre como servicio en primer plano, que es lo que Android exige para
 * poder leer sensores con la pantalla apagada o con otra app encima. Eso es
 * exactamente lo que el navegador no puede hacer, y el motivo de que exista
 * esta version.
 */
public class ServicioVigilancia extends Service implements SensorEventListener, Nube.Escucha {

    public static final String ACCION_INICIAR  = "iniciar";
    public static final String ACCION_PARAR    = "parar";
    public static final String ACCION_CANCELAR = "cancelar";
    public static final String ACCION_SOS      = "sos";
    public static final String ACCION_DEMO     = "demo";

    private static final String CANAL_VIG    = "vigilancia";
    private static final String CANAL_ALERTA = "alertas";
    private static final int    ID_NOTIF_VIG    = 1;
    private static final int    ID_NOTIF_ALERTA = 2;

    private static final float G = 9.80665f;

    /** Para que la pantalla pueda pintar el estado sin acoplarse al servicio. */
    public interface Observador {
        void alCambiar(Estado e);
    }

    public static class Estado {
        public boolean vigilando;
        public boolean conectado;
        public String  servidor = "";
        public String  fase = "REPOSO";
        public float   svm = 1f;
        public float   giro = 0f;
        public int     hz = 0;
        public String  alerta = "";      // "", "prealerta", "confirmada"
        public String  datosAlerta = "";
        public int     segundosRestantes = 0;
        public String  estadoPaciente = "ok";
        public String  salaAlerta = "";     // de quien es la alerta en curso
        public String  quienAlerta = "";    // su nombre, vacio si es propia
        public long    ultimoRemoto = 0;
        // Lo que llega del paciente (solo en modo cuidador)
        public int     batPaciente = -1;
        public int     hzPaciente = 0;
        public boolean pausaRemota = false;
        // Estadisticas del historial guardado en ESTE telefono
        public int     totalAlertas = 0;
        public int     totalConfirmadas = 0;
        public int     totalCanceladas = 0;
        public int     porcentajeVigilancia = -1;
        // Contexto: que esta haciendo la persona
        public String  actividad = "";
        public String  detalleContexto = "";
        public int     pasos = -1;
        public long    desde = 0;
        public int     registros = 0;
    }

    /** Lo que sabemos de cada persona vigilada. */
    public static class EstadoPac {
        public String sala, nombre = "";
        public int bat = -1, hz = 0, bpm = 0;
        public boolean vig = false, pausa = false;
        public long ultimo = 0;
        public String estado = "ok";      // ok | prealerta | caida
        // Que esta haciendo: "Caminando", "Quieto, de pie", etc.
        public String actividad = "";
        public int pasos = -1;
    }

    public final java.util.LinkedHashMap<String, EstadoPac> pacientes =
            new java.util.LinkedHashMap<>();

    private EstadoPac pac(String sala) {
        EstadoPac p = pacientes.get(sala);
        if (p == null) { p = new EstadoPac(); p.sala = sala; pacientes.put(sala, p); }
        return p;
    }

    private static ServicioVigilancia instancia;
    private static Observador observador;

    public static ServicioVigilancia get() { return instancia; }
    public static void observar(Observador o) { observador = o; }

    private final Estado estado = new Estado();
    public Estado getEstado() { return estado; }

    private SensorManager sensores;
    private Sensor acelerometro, giroscopio;
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrador;
    private Ringtone alarma;

    private Ajustes ajustes;
    private Nube nube;
    private Contexto contexto;
    private final Detector detector = new Detector();

    private final Handler hilo = new Handler(Looper.getMainLooper());
    private final float[] ultimoGiro = {0, 0, 0};

    /** Eventos guardados, en JSON, para publicarlos como historial. */
    private final java.util.List<String> historial = new java.util.ArrayList<>();
    // Salto de linea: JSONObject siempre lo escapa como \n dentro del texto,
    // asi que nunca aparece crudo y sirve de separador sin partir un evento.
    private static final String SEP = "\n";

    private long tCuenta = 0;
    private int nMuestras = 0;
    private long tVentana = 0;

    // ------------------------------------------------------------------

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instancia = this;
        ajustes = new Ajustes(this);
        ajustes.cargarEn(detector.u);

        sensores = (SensorManager) getSystemService(SENSOR_SERVICE);
        acelerometro = sensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        giroscopio   = sensores.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        vibrador     = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        contexto     = new Contexto(this);

        crearCanales();

        String guardado = ajustes.getHistorial();
        if (!guardado.isEmpty()) {
            for (String e : guardado.split(SEP)) if (!e.isEmpty()) historial.add(e);
            recalcularEstadisticas();
        }

        nube = new Nube(this);
        nube.configurar(ajustes.salasQueEscucho(), ajustes.esBrazalete(), ajustes.getSala());
        nube.conectar();

        hilo.post(latido);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String accion = intent != null && intent.getAction() != null
                ? intent.getAction() : ACCION_INICIAR;

        // En Android hay que entrar en primer plano enseguida o el sistema
        // mata el servicio.
        startForeground(ID_NOTIF_VIG, notificacionVigilancia());

        switch (accion) {
            case ACCION_INICIAR:  iniciarVigilancia(); break;
            case ACCION_PARAR:    pararTodo();         return START_NOT_STICKY;
            case ACCION_CANCELAR: cancelarAlerta(true); break;
            case ACCION_SOS:
                lanzarAlerta(ajustes.getSala(), "SOS_MANUAL", "", true, false); break;
            case ACCION_DEMO:
                // Lo del menu de pruebas se marca como prueba para no
                // ensuciar las estadisticas reales.
                lanzarAlerta(ajustes.getSala(), "PREALERTA",
                        "pts=6;g=3.4;dps=312;ang=74", true, true); break;
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        pararTodo();
        instancia = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------

    private void iniciarVigilancia() {
        if (estado.vigilando) return;
        if (!ajustes.esBrazalete()) {
            // El cuidador no lee sensores, pero el servicio TIENE que seguir
            // vivo igual: es lo que mantiene la conexion abierta con el
            // telefono en el bolsillo. Sin esto solo habria avisos mientras
            // mirase la pantalla, que es justo cuando no hacen falta.
            estado.vigilando = true;
            avisar();
            return;
        }
        detector.reiniciar();
        // 10000 us = 100 Hz. Muy por encima de los ~56 Hz del navegador.
        sensores.registerListener(this, acelerometro, 10000);
        if (giroscopio != null) sensores.registerListener(this, giroscopio, 10000);
        contexto.empezar();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "brazalete:vigilancia");
        wakeLock.acquire();

        estado.vigilando = true;
        tVentana = SystemClock.elapsedRealtime();
        avisar();
    }

    private void pararTodo() {
        sensores.unregisterListener(this);
        if (contexto != null) contexto.parar();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        pararAlarma();
        hilo.removeCallbacks(latido);
        if (nube != null) nube.desconectar();
        estado.vigilando = false;
        avisar();
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------
    // Sensores

    @Override public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            // rad/s -> grados/s
            ultimoGiro[0] = (float) Math.toDegrees(e.values[0]);
            ultimoGiro[1] = (float) Math.toDegrees(e.values[1]);
            ultimoGiro[2] = (float) Math.toDegrees(e.values[2]);
            return;
        }
        if (e.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;

        // m/s2 -> g
        float ax = e.values[0] / G, ay = e.values[1] / G, az = e.values[2] / G;
        long t = SystemClock.elapsedRealtime();

        estado.svm  = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        estado.giro = (float) Math.sqrt(ultimoGiro[0] * ultimoGiro[0]
                                      + ultimoGiro[1] * ultimoGiro[1]
                                      + ultimoGiro[2] * ultimoGiro[2]);

        nMuestras++;
        if (t - tVentana > 1000) {
            estado.hz = (int) (nMuestras * 1000L / (t - tVentana));
            nMuestras = 0;
            tVentana = t;
            avisar();
        }

        if (!estado.alerta.isEmpty()) return;   // ya hay una alerta en curso

        Detector.Evento ev = detector.muestra(ax, ay, az,
                ultimoGiro[0], ultimoGiro[1], ultimoGiro[2], t);
        estado.fase = detector.getFase().name();
        if (ev != null) lanzarAlerta(ajustes.getSala(), "PREALERTA", ev.resumen(), true, false);
    }

    @Override public void onAccuracyChanged(Sensor s, int p) { }

    // ------------------------------------------------------------------
    // Alertas

    private void lanzarAlerta(String sala, String tipo, String datos,
                              boolean propia, boolean prueba) {
        boolean confirmada = tipo.equals("CAIDA_CONFIRMADA") || tipo.equals("SOS_MANUAL");
        estado.alerta = confirmada ? "confirmada" : "prealerta";
        estado.datosAlerta = datos;
        estado.estadoPaciente = confirmada ? "caida" : "prealerta";
        estado.salaAlerta = sala;
        estado.quienAlerta = sala.equals(ajustes.getSala()) ? "" : ajustes.nombreDe(sala);
        tCuenta = SystemClock.elapsedRealtime();

        sonarAlarma(confirmada);
        notificarAlerta(confirmada, datos, estado.quienAlerta);
        anotarHistorial(sala, tipo, datos, prueba);
        if (propia) {
            nube.enviarEvento(sala, tipo, datos, prueba);
            if (!prueba) pedirUbicacion();
        }
        avisar();
    }

    private void cancelarAlerta(boolean propia) {
        if (estado.alerta.isEmpty() && !propia) return;
        String sala = estado.salaAlerta.isEmpty() ? ajustes.getSala() : estado.salaAlerta;
        estado.alerta = "";
        estado.datosAlerta = "";
        estado.estadoPaciente = "ok";
        estado.quienAlerta = "";
        estado.salaAlerta = "";
        estado.segundosRestantes = 0;
        detector.reiniciar();
        pararAlarma();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(ID_NOTIF_ALERTA);
        if (propia) {
            nube.enviarEvento(sala, "CANCELADA", "", false);
            anotarHistorial(sala, "CANCELADA", "", false);
        }
        avisar();
    }

    private void sonarAlarma(boolean grave) {
        pararAlarma();
        try {
            android.net.Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (uri == null) uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            alarma = RingtoneManager.getRingtone(this, uri);
            if (alarma != null) {
                alarma.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                if (Build.VERSION.SDK_INT >= 28) alarma.setLooping(true);
                alarma.play();
            }
        } catch (Exception ignored) { }

        if (vibrador != null && vibrador.hasVibrator()) {
            long[] patron = grave ? new long[]{0, 500, 200, 500, 200, 500}
                                  : new long[]{0, 250, 400};
            vibrador.vibrate(VibrationEffect.createWaveform(patron, 0));
        }
    }

    private void pararAlarma() {
        try { if (alarma != null && alarma.isPlaying()) alarma.stop(); } catch (Exception ignored) { }
        alarma = null;
        if (vibrador != null) vibrador.cancel();
    }

    // ------------------------------------------------------------------
    // Latido: cuenta atras, vitales y refresco

    private final Runnable latido = new Runnable() {
        @Override public void run() {
            long ahora = SystemClock.elapsedRealtime();

            // Que esta haciendo la persona ahora mismo.
            if (estado.vigilando && ajustes.esBrazalete()) {
                estado.actividad = contexto.descripcion();
                estado.detalleContexto = contexto.detalle();
                estado.pasos = contexto.getPasos();
            }

            if (estado.alerta.equals("prealerta")) {
                // Si volvio a caminar, no se cayo. Es la prueba mas fuerte
                // que existe, y ningun umbral de aceleracion puede darla:
                // nadie camina tirado en el suelo.
                boolean propia = estado.salaAlerta.isEmpty()
                              || estado.salaAlerta.equals(ajustes.getSala());
                if (propia && ajustes.esBrazalete() && contexto.caminoHace(2500)) {
                    cancelarAlerta(true);
                    return;
                }
                int quedan = (int) Math.max(0,
                        (detector.u.cuentaMs - (ahora - tCuenta)) / 1000);
                estado.segundosRestantes = quedan;
                if (quedan <= 0) {
                    String s = estado.salaAlerta.isEmpty() ? ajustes.getSala() : estado.salaAlerta;
                    lanzarAlerta(s, "CAIDA_CONFIRMADA", estado.datosAlerta,
                            s.equals(ajustes.getSala()), false);
                }
            }

            if (ahora % 3000 < 1100) {
                nube.enviarVitales(0, nivelBateria(), estado.vigilando, estado.hz,
                        estado.actividad, estado.pasos);
            }

            actualizarNotificacionVigilancia();
            avisar();
            hilo.postDelayed(this, 1000);
        }
    };

    private int nivelBateria() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) { return 0; }
    }

    // ------------------------------------------------------------------
    // Ubicacion: solo al saltar una alerta, no todo el rato

    private void pedirUbicacion() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location ultima = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (ultima == null) ultima = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (ultima != null) {
                nube.enviarUbicacion(ultima.getLatitude(), ultima.getLongitude(),
                        (int) ultima.getAccuracy());
            }
            LocationListener uno = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    nube.enviarUbicacion(l.getLatitude(), l.getLongitude(), (int) l.getAccuracy());
                    try { lm.removeUpdates(this); } catch (Exception ignored) { }
                }
                @Override public void onProviderEnabled(String p) { }
                @Override public void onProviderDisabled(String p) { }
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, uno, Looper.getMainLooper());
            hilo.postDelayed(() -> { try { lm.removeUpdates(uno); } catch (Exception ignored) { } }, 20000);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------
    // Nube

    @Override public void alConectar(boolean conectado, String servidor) {
        estado.conectado = conectado;
        estado.servidor = servidor;
        if (conectado) {
            nube.enviarPerfil(ajustes.getNombre(), ajustes.getModo());
            // Al reconectar se vuelve a dejar el historial publicado, por si
            // el broker perdio el mensaje retenido.
            if (!historial.isEmpty() && ajustes.esBrazalete()) nube.enviarHistorial(soloEventos());
        }
        avisar();
    }

    @Override public void alPerfil(String sala, String nombre) {
        if (nombre != null && !nombre.isEmpty()) pac(sala).nombre = nombre;
        avisar();
    }

    @Override public void alHistorial(String sala, String json) {
        // El cuidador ya guarda todo lo que ve en vivo; esto solo sirve para
        // recuperar lo que se perdio mientras tenia la app cerrada.
        try {
            org.json.JSONObject j = new org.json.JSONObject(json);
            org.json.JSONArray evs = j.optJSONArray("evs");
            if (evs == null) return;
            int nuevos = 0;
            for (int i = 0; i < evs.length(); i++) {
                org.json.JSONObject e = evs.getJSONObject(i);
                if (!yaEstaEnHistorial(sala, e.optString("tipo"), e.optLong("t"))) {
                    org.json.JSONObject copia = new org.json.JSONObject();
                    copia.put("k", "ev");
                    copia.put("t", e.optLong("t"));
                    copia.put("sala", sala);
                    copia.put("tipo", e.optString("tipo"));
                    copia.put("datos", e.optString("datos"));
                    historial.add(copia.toString());
                    nuevos++;
                }
            }
            if (nuevos > 0) {
                ajustes.setHistorial(android.text.TextUtils.join(SEP, historial));
                recalcularEstadisticas();
                avisar();
            }
        } catch (Exception ignored) { }
    }

    private boolean yaEstaEnHistorial(String sala, String tipo, long t) {
        for (String s : historial) {
            try {
                org.json.JSONObject j = new org.json.JSONObject(s);
                if (!"ev".equals(j.optString("k"))) continue;
                if (!tipo.equals(j.optString("tipo"))) continue;
                if (!sala.equals(j.optString("sala", sala))) continue;
                if (Math.abs(j.optLong("t") - t) < 1500) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    @Override public void alVitales(String sala, int bpm, int bateria, boolean vigilando,
                                    boolean pausa, int hz, String actividad, int pasos) {
        EstadoPac p = pac(sala);
        p.ultimo = System.currentTimeMillis();
        p.bat = bateria; p.hz = hz; p.bpm = bpm; p.vig = vigilando; p.pausa = pausa;
        p.actividad = actividad; p.pasos = pasos;

        // Compatibilidad con la pantalla de un solo paciente
        estado.ultimoRemoto = p.ultimo;
        estado.batPaciente = bateria;
        estado.hzPaciente = hz;
        estado.pausaRemota = pausa;

        anotarMuestra(sala, bateria, vigilando && !pausa, hz);
        avisar();
    }

    /** Anota un evento y lo deja publicado para el cuidador. */
    private void anotarHistorial(String sala, String tipo, String datos, boolean prueba) {
        try {
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("k", "ev");
            j.put("t", System.currentTimeMillis());
            j.put("sala", sala);
            j.put("tipo", tipo);
            j.put("datos", datos == null ? "" : datos);
            if (prueba) j.put("prueba", true);
            agregarAlHistorial(j.toString());
            if (ajustes.esBrazalete()) nube.enviarHistorial(soloEventos());
        } catch (Exception ignored) { }
    }

    /**
     * Guarda una medida periodica del paciente.
     *
     * Este es el archivo de verdad: el telefono del cuidador esta siempre
     * escuchando, asi que puede quedarse con todo el historial sin que haga
     * falta ningun servidor.
     */
    private final java.util.HashMap<String, Long> tUltimaMuestra = new java.util.HashMap<>();
    private void anotarMuestra(String sala, int bateria, boolean vigilando, int hz) {
        long ahora = System.currentTimeMillis();
        Long ult = tUltimaMuestra.get(sala);
        if (ult != null && ahora - ult < 60000) return;   // una por minuto basta
        tUltimaMuestra.put(sala, ahora);
        try {
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("k", "m");
            j.put("t", ahora);
            j.put("sala", sala);
            j.put("bat", bateria);
            j.put("vig", vigilando ? 1 : 0);
            j.put("hz", hz);
            agregarAlHistorial(j.toString());
        } catch (Exception ignored) { }
    }

    private void agregarAlHistorial(String json) {
        historial.add(json);
        while (historial.size() > 1500) historial.remove(0);
        ajustes.setHistorial(android.text.TextUtils.join(SEP, historial));
        recalcularEstadisticas();
    }

    /** Solo los eventos, que es lo unico que se publica al broker. */
    private java.util.List<String> soloEventos() {
        java.util.List<String> evs = new java.util.ArrayList<>();
        for (String s : historial) if (s.contains("\"k\":\"ev\"")) evs.add(s);
        return evs;
    }

    private void recalcularEstadisticas() {
        int pre = 0, conf = 0, canc = 0, muestras = 0, activas = 0;
        long primera = 0;
        for (String s : historial) {
            try {
                org.json.JSONObject j = new org.json.JSONObject(s);
                if (primera == 0) primera = j.optLong("t");
                // Lo del menu de pruebas no cuenta para las estadisticas.
                if (j.optBoolean("prueba")) continue;
                if ("ev".equals(j.optString("k"))) {
                    String t = j.optString("tipo");
                    if (t.equals("PREALERTA")) pre++;
                    else if (t.equals("CAIDA_CONFIRMADA") || t.equals("SOS_MANUAL")) conf++;
                    else if (t.equals("CANCELADA")) canc++;
                } else {
                    muestras++;
                    if (j.optInt("vig") == 1) activas++;
                }
            } catch (Exception ignored) { }
        }
        estado.totalAlertas = pre + conf;
        estado.totalConfirmadas = conf;
        estado.totalCanceladas = canc;
        estado.porcentajeVigilancia = muestras > 0 ? Math.round(activas * 100f / muestras) : -1;
        estado.desde = primera;
        estado.registros = historial.size();
    }

    /** Historial completo en CSV, para compartirlo o guardarlo. */
    public String historialCsv() {
        StringBuilder sb = new StringBuilder("fecha_hora,tipo,evento,bateria,vigilando,hz,datos\n");
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
        for (String s : historial) {
            try {
                org.json.JSONObject j = new org.json.JSONObject(s);
                String fecha = f.format(new java.util.Date(j.optLong("t")));
                if ("ev".equals(j.optString("k"))) {
                    sb.append(fecha).append(",evento,").append(j.optString("tipo"))
                      .append(",,,,\"").append(j.optString("datos")).append("\"\n");
                } else {
                    sb.append(fecha).append(",muestra,,").append(j.optInt("bat")).append(',')
                      .append(j.optInt("vig")).append(',').append(j.optInt("hz")).append(",\n");
                }
            } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    @Override public void alEvento(String sala, String tipo, String datos,
                                   boolean viejo, boolean prueba) {
        EstadoPac p = pac(sala);
        p.ultimo = System.currentTimeMillis();
        estado.ultimoRemoto = p.ultimo;

        if (tipo.equals("CANCELADA")) {
            p.estado = "ok";
            cancelarAlerta(false);
            avisar();
            return;
        }
        if (tipo.equals("PREALERTA")) p.estado = "prealerta";
        else p.estado = "caida";

        // Un aviso retenido de hace rato se anota, pero no vuelve a sonar.
        if (!viejo && !ajustes.esBrazalete()) lanzarAlerta(sala, tipo, datos, false, prueba);
        avisar();
    }

    // ------------------------------------------------------------------
    // Notificaciones

    private void crearCanales() {
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel vig = new NotificationChannel(CANAL_VIG,
                "Vigilancia activa", NotificationManager.IMPORTANCE_LOW);
        vig.setDescription("Aviso permanente mientras la app vigila.");
        vig.setShowBadge(false);
        nm.createNotificationChannel(vig);

        NotificationChannel al = new NotificationChannel(CANAL_ALERTA,
                "Alertas de caida", NotificationManager.IMPORTANCE_HIGH);
        al.setDescription("Suena aunque el telefono este en silencio o bloqueado.");
        al.enableVibration(true);
        al.setBypassDnd(true);
        nm.createNotificationChannel(al);
    }

    private PendingIntent abrirApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notificacionVigilancia() {
        // La notificacion permanente dice que esta haciendo la persona, que
        // es mas util que repetir "vigilando" todo el dia.
        String texto = ajustes.esBrazalete()
                ? (estado.vigilando
                    ? (estado.actividad.isEmpty() ? "Vigilando caidas" : estado.actividad)
                    : "En pausa")
                : "Panel del cuidador";
        String sub = estado.conectado ? "Sala " + ajustes.getSala() : "Sin conexion";
        return new NotificationCompat.Builder(this, CANAL_VIG)
                .setContentTitle(texto)
                .setContentText(sub + (estado.hz > 0 ? "  ·  " + estado.hz + " Hz" : ""))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(abrirApp())
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void actualizarNotificacionVigilancia() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(ID_NOTIF_VIG, notificacionVigilancia());
    }

    private void notificarAlerta(boolean confirmada, String datos, String quien) {
        String titulo = confirmada
                ? (quien.isEmpty() ? "CAIDA CONFIRMADA" : "CAIDA DE " + quien.toUpperCase())
                : (quien.isEmpty() ? "Posible caida detectada" : "Posible caida de " + quien);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CANAL_ALERTA)
                .setContentTitle(titulo)
                .setContentText(confirmada
                        ? "No hubo respuesta. Contacta con la persona ahora mismo."
                        : "Toca para cancelar si estas bien.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(abrirApp())
                // Abre la app aunque la pantalla este bloqueada. Esto es lo
                // que una pagina web no puede hacer.
                .setFullScreenIntent(abrirApp(), true);
        if (datos != null && !datos.isEmpty()) {
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(datos));
        }
        getSystemService(NotificationManager.class).notify(ID_NOTIF_ALERTA, b.build());
    }

    private void avisar() {
        if (observador != null) hilo.post(() -> observador.alCambiar(estado));
    }
}
