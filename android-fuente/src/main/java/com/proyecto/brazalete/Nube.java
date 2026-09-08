package com.proyecto.brazalete;

import android.util.Log;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;

/**
 * Conexion con el cuidador.
 *
 * Usa el mismo broker, los mismos temas y el mismo formato de mensaje que
 * la app web, asi que un telefono con la app nativa y otro con la web se
 * entienden sin tocar nada. La unica diferencia es el transporte: aqui MQTT
 * sobre TLS directo, en el navegador sobre WebSocket, porque el navegador no
 * puede abrir sockets normales.
 */
public class Nube implements MqttCallback {

    public interface Escucha {
        void alConectar(boolean conectado, String servidor);
        void alEvento(String tipo, String datos, boolean viejo);
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
    private String sala;
    private boolean esBrazalete;

    private MqttClient cli;
    private int iServidor = 0;
    private volatile boolean queriendoConectar = false;

    public Nube(Escucha escucha) { this.escucha = escucha; }

    public void configurar(String sala, boolean esBrazalete) {
        this.sala = sala;
        this.esBrazalete = esBrazalete;
    }

    public boolean conectado() { return cli != null && cli.isConnected(); }

    private String tema(String sub) { return "brz/" + sala + "/" + sub; }

    /** Conecta en segundo plano. Reintenta solo, rotando de servidor. */
    public void conectar() {
        if (queriendoConectar) return;
        queriendoConectar = true;
        new Thread(() -> {
            while (queriendoConectar) {
                String url = SERVIDORES[iServidor];
                try {
                    cerrarSilencioso();
                    cli = new MqttClient(url, "brz_" + miId, new MemoryPersistence());
                    cli.setCallback(this);

                    MqttConnectOptions o = new MqttConnectOptions();
                    o.setCleanSession(true);
                    o.setAutomaticReconnect(true);
                    o.setConnectionTimeout(10);
                    o.setKeepAliveInterval(30);
                    cli.connect(o);

                    cli.subscribe(tema("evento"), 1);
                    if (!esBrazalete) {
                        cli.subscribe(tema("vitales"), 0);
                        cli.subscribe(tema("ubicacion"), 1);
                    }
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

    private void publicar(String sub, String carga, boolean retener) {
        if (!conectado()) return;
        try {
            MqttMessage m = new MqttMessage(carga.getBytes("UTF-8"));
            m.setQos(1);
            m.setRetained(retener);
            cli.publish(tema(sub), m);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo publicar en " + sub + ": " + e.getMessage());
        }
    }

    public void enviarEvento(String tipo, String datos) {
        try {
            JSONObject j = new JSONObject();
            j.put("tipo", tipo);
            j.put("datos", datos == null ? "" : datos);
            j.put("ts", System.currentTimeMillis());
            j.put("de", miId);
            publicar("evento", j.toString(), true);
        } catch (Exception ignored) { }
    }

    public void enviarVitales(int bpm, int bateria, boolean vigilando, int hz) {
        try {
            JSONObject j = new JSONObject();
            j.put("bpm", bpm);
            j.put("bat", bateria);
            j.put("vig", vigilando);
            // La app nativa nunca esta "pausada": ese era justamente el
            // problema del navegador que vino a resolver.
            j.put("pausa", false);
            j.put("hz", hz);
            j.put("t", System.currentTimeMillis());
            publicar("vitales", j.toString(), true);
        } catch (Exception ignored) { }
    }

    public void enviarUbicacion(double lat, double lon, int precision) {
        try {
            JSONObject j = new JSONObject();
            j.put("lat", lat);
            j.put("lon", lon);
            j.put("acc", precision);
            j.put("ts", System.currentTimeMillis());
            publicar("ubicacion", j.toString(), true);
        } catch (Exception ignored) { }
    }

    // ---- callbacks de Paho ----

    @Override public void connectionLost(Throwable causa) {
        if (escucha != null) escucha.alConectar(false, SERVIDORES[iServidor]);
    }

    @Override public void messageArrived(String tema, MqttMessage msg) {
        String txt = new String(msg.getPayload());
        if (txt.isEmpty()) return;
        if (!tema.endsWith("/evento")) return;
        try {
            JSONObject j = new JSONObject(txt);
            if (miId.equals(j.optString("de"))) return;   // eco propio
            long ts = j.optLong("ts", 0);
            boolean viejo = ts > 0 && (System.currentTimeMillis() - ts) > 300000;
            if (escucha != null) {
                escucha.alEvento(j.optString("tipo"), j.optString("datos"), viejo);
            }
        } catch (Exception ignored) { }
    }

    @Override public void deliveryComplete(IMqttDeliveryToken t) { }
}
