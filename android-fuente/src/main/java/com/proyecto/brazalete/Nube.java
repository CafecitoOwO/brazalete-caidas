package com.proyecto.brazalete;

import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Conexion con los demas telefonos.
 *
 * Usa el mismo broker, los mismos temas y el mismo formato de mensaje que
 * la version web, asi que un telefono con la app nativa y otro con la web
 * se entienden sin tocar nada. La unica diferencia es el transporte: aqui
 * MQTT sobre TLS directo, en el navegador sobre WebSocket, porque el
 * navegador no puede abrir sockets normales.
 *
 * Un cuidador puede escuchar a varias personas a la vez: se suscribe a una
 * sala por cada una.
 */
public class Nube implements MqttCallback {

    public interface Escucha {
        void alConectar(boolean conectado, String servidor);
        void alEvento(String sala, String tipo, String datos, boolean viejo, boolean prueba);
        void alVitales(String sala, int bpm, int bateria, boolean vigilando, boolean pausa,
                       int hz, String actividad, int pasos);
        void alPerfil(String sala, String nombre);
        /** Un cuidador aviso de que entro a revisar a esta persona. */
        void alPresencia(String sala, String id, String nombre, long cuando);
        /** Ultimos valores de aceleracion del paciente, para las graficas. */
        void alOndas(String sala, float[] valores);
        void alHistorial(String sala, String json);
        /** canal: "pedido" | "p" (voz del paciente) | "c" (voz del cuidador) */
        void alAudio(String sala, String canal, String mime, String base64,
                     boolean activo, String de);
    }

    private static final String TAG = "Nube";

    // Si uno se cae el dia de la presentacion, se pasa al siguiente.
    private static final String[] SERVIDORES = {
            "ssl://broker.emqx.io:8883",
            "ssl://broker.hivemq.com:8883",
            "ssl://test.mosquitto.org:8886"
    };

    private final String miId = Long.toHexString(new java.util.Random().nextLong()).substring(0, 8);
    private final Escucha escucha;

    private List<String> salas = new ArrayList<>();
    private String salaPropia = "";
    private boolean esBrazalete = true;

    private MqttClient cli;
    private int iServidor = 0;
    private volatile boolean queriendoConectar = false;

    public Nube(Escucha escucha) { this.escucha = escucha; }

    public void configurar(List<String> salas, boolean esBrazalete, String salaPropia) {
        this.salas = salas;
        this.esBrazalete = esBrazalete;
        this.salaPropia = salaPropia;
    }

    public boolean conectado() { return cli != null && cli.isConnected(); }

    private String tema(String sala, String sub) { return "brz/" + sala + "/" + sub; }

    /** Conecta en segundo plano. Reintenta solo, rotando de servidor. */
    public void conectar() {
        if (queriendoConectar) return;
        queriendoConectar = true;
        new Thread(() -> {
            while (queriendoConectar) {
                String url = SERVIDORES[iServidor];
                try {
                    cerrarSilencioso();
                    cli = new MqttClient(url, "cuidapp_" + miId, new MemoryPersistence());
                    cli.setCallback(this);

                    MqttConnectOptions o = new MqttConnectOptions();
                    o.setCleanSession(true);
                    o.setAutomaticReconnect(true);
                    o.setConnectionTimeout(10);
                    o.setKeepAliveInterval(30);
                    cli.connect(o);

                    for (String s : salas) cli.subscribe("brz/" + s + "/#", 1);
                    if (escucha != null) escucha.alConectar(true, url);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "fallo con " + url + ": " + e.getMessage());
                    if (escucha != null) escucha.alConectar(false, url);
                    iServidor = (iServidor + 1) % SERVIDORES.length;
                    try { Thread.sleep(4000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "nube-conectar").start();
    }

    public void desconectar() {
        queriendoConectar = false;
        cerrarSilencioso();
    }

    private void cerrarSilencioso() {
        try { if (cli != null) { cli.disconnectForcibly(500, 500); cli.close(true); } }
        catch (Exception ignored) { }
        cli = null;
    }

    private void publicar(String sala, String sub, String carga, boolean retener) {
        if (!conectado() || sala == null || sala.isEmpty()) return;
        try {
            MqttMessage m = new MqttMessage(carga.getBytes("UTF-8"));
            m.setQos(1);
            m.setRetained(retener);
            cli.publish(tema(sala, sub), m);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo publicar en " + sub + ": " + e.getMessage());
        }
    }

    /** Quien soy, para que el otro lado muestre un nombre y no un codigo. */
    public void enviarPerfil(String nombre, String rol) {
        try {
            JSONObject j = new JSONObject();
            j.put("nombre", nombre == null || nombre.isEmpty() ? "Paciente" : nombre);
            j.put("rol", rol);
            j.put("id", miId);
            j.put("desde", System.currentTimeMillis());
            publicar(salaPropia, "perfil", j.toString(), true);
        } catch (Exception ignored) { }
    }

    public void enviarEvento(String sala, String tipo, String datos, boolean prueba) {
        try {
            JSONObject j = new JSONObject();
            j.put("tipo", tipo);
            j.put("datos", datos == null ? "" : datos);
            j.put("ts", System.currentTimeMillis());
            j.put("de", miId);
            j.put("prueba", prueba);
            publicar(sala, "evento", j.toString(), true);
        } catch (Exception ignored) { }
    }

    public void enviarVitales(int bpm, int bateria, boolean vigilando, int hz,
                              String actividad, int pasos, float[] ondas) {
        try {
            JSONObject j = new JSONObject();
            j.put("bpm", bpm);
            j.put("bat", bateria);
            j.put("vig", vigilando);
            // Que esta haciendo la persona, no solo si esta viva la conexion.
            if (actividad != null && !actividad.isEmpty()) j.put("act", actividad);
            if (pasos >= 0) j.put("pasos", pasos);
            // Unos pocos valores de aceleracion para que el cuidador pueda
            // dibujar la onda en vivo. Van redondeados a dos decimales para
            // que el mensaje siga siendo pequeño.
            if (ondas != null && ondas.length > 0) {
                org.json.JSONArray a = new org.json.JSONArray();
                for (float v : ondas) a.put(Math.round(v * 100) / 100.0);
                j.put("ondas", a);
            }
            // La app nativa nunca esta "pausada": ese era justamente el
            // problema del navegador que vino a resolver.
            j.put("pausa", false);
            j.put("hz", hz);
            j.put("t", System.currentTimeMillis());
            publicar(salaPropia, "vitales", j.toString(), true);
        } catch (Exception ignored) { }
    }

    /** Un trozo de voz. Nunca retenido: la voz vieja no le sirve a nadie. */
    public void enviarAudio(String sala, String canal, String mime, String base64) {
        try {
            JSONObject j = new JSONObject();
            j.put("t", mime);
            j.put("d", base64);
            j.put("de", miId);
            publicar(sala, "audio/" + canal, j.toString(), false);
        } catch (Exception ignored) { }
    }

    /**
     * El cuidador avisa de que entro a revisar.
     *
     * Con varios cuidadores, si todos suponen que otro esta mirando no
     * mira nadie. Publicando esto, todos ven cuando reviso cada uno.
     */
    public void enviarPresencia(String sala, String nombre) {
        try {
            JSONObject j = new JSONObject();
            j.put("id", miId);
            j.put("nombre", nombre == null || nombre.isEmpty() ? "Cuidador" : nombre);
            j.put("ts", System.currentTimeMillis());
            publicar(sala, "presencia/" + miId, j.toString(), true);
        } catch (Exception ignored) { }
    }

    /** El cuidador pide (o deja de pedir) escuchar. */
    public void pedirEscuchar(String sala, boolean activo, String nombre) {
        try {
            JSONObject j = new JSONObject();
            j.put("activo", activo);
            j.put("de", miId);
            j.put("nombre", nombre == null ? "" : nombre);
            publicar(sala, "audio/pedido", j.toString(), false);
        } catch (Exception ignored) { }
    }

    public void enviarUbicacion(double lat, double lon, int precision) {
        try {
            JSONObject j = new JSONObject();
            j.put("lat", lat);
            j.put("lon", lon);
            j.put("acc", precision);
            j.put("ts", System.currentTimeMillis());
            publicar(salaPropia, "ubicacion", j.toString(), true);
        } catch (Exception ignored) { }
    }

    /**
     * Publica la lista de eventos como mensaje retenido.
     *
     * El broker guarda el ultimo mensaje retenido de cada tema y se lo
     * entrega a quien se suscriba despues, asi que el cuidador ve el
     * historial aunque haya tenido la app cerrada durante horas.
     */
    public void enviarHistorial(List<String> eventosJson) {
        try {
            StringBuilder sb = new StringBuilder("{\"evs\":[");
            int desde = Math.max(0, eventosJson.size() - 60);
            for (int i = desde; i < eventosJson.size(); i++) {
                if (i > desde) sb.append(',');
                sb.append(eventosJson.get(i));
            }
            sb.append("]}");
            publicar(salaPropia, "historial", sb.toString(), true);
        } catch (Exception ignored) { }
    }

    // ---- callbacks de Paho ----

    @Override public void connectionLost(Throwable causa) {
        if (escucha != null) escucha.alConectar(false, SERVIDORES[iServidor]);
    }

    @Override public void messageArrived(String tema, MqttMessage msg) {
        String txt = new String(msg.getPayload());
        if (txt.isEmpty() || escucha == null) return;

        String[] partes = tema.split("/");
        if (partes.length < 3) return;
        String sala = partes[1], sub = partes[2];

        try {
            if (sub.equals("vitales")) {
                JSONObject j = new JSONObject(txt);
                escucha.alVitales(sala, j.optInt("bpm"), j.optInt("bat"),
                        j.optBoolean("vig"), j.optBoolean("pausa"), j.optInt("hz"),
                        j.optString("act", ""), j.optInt("pasos", -1));
                org.json.JSONArray a = j.optJSONArray("ondas");
                if (a != null && a.length() > 1) {
                    float[] v = new float[a.length()];
                    for (int i = 0; i < a.length(); i++) v[i] = (float) a.optDouble(i, 1);
                    escucha.alOndas(sala, v);
                }
            } else if (sub.equals("audio")) {
                String canal = partes.length > 3 ? partes[3] : "";
                JSONObject j = new JSONObject(txt);
                if (miId.equals(j.optString("de"))) return;   // eco propio
                escucha.alAudio(sala, canal, j.optString("t"), j.optString("d"),
                        j.optBoolean("activo"), j.optString("de"));
            } else if (sub.equals("presencia")) {
                JSONObject j = new JSONObject(txt);
                escucha.alPresencia(sala, j.optString("id"), j.optString("nombre"),
                        j.optLong("ts"));
            } else if (sub.equals("perfil")) {
                JSONObject j = new JSONObject(txt);
                escucha.alPerfil(sala, j.optString("nombre"));
            } else if (sub.equals("historial")) {
                escucha.alHistorial(sala, txt);
            } else if (sub.equals("evento")) {
                JSONObject j = new JSONObject(txt);
                if (miId.equals(j.optString("de"))) return;   // eco propio
                long ts = j.optLong("ts", 0);
                boolean viejo = ts > 0 && (System.currentTimeMillis() - ts) > 300000;
                escucha.alEvento(sala, j.optString("tipo"), j.optString("datos"),
                        viejo, j.optBoolean("prueba"));
            }
        } catch (Exception ignored) { }
    }

    @Override public void deliveryComplete(IMqttDeliveryToken t) { }
}
