package com.proyecto.brazalete;

import android.content.Context;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;

/**
 * Voz entre el paciente y el cuidador, solo mientras hay una alerta.
 *
 * Para que sirve: cuando salta una alarma, lo primero que quiere hacer
 * un familiar es oir si la persona se queja, si contesta, o si fue una
 * falsa alarma. Y despues poder decirle "quedate quieta, ya voy".
 *
 * Dos reglas que no se negocian:
 *   1. Solo funciona con una alerta en curso. El servicio no llama a
 *      empezar() en ningun otro caso.
 *   2. El paciente ve en su pantalla que lo estan escuchando. Un
 *      microfono que se enciende a escondidas no es cuidado.
 *
 * Como va por dentro: el audio se manda en trozos de segundo y medio.
 * Cada trozo se graba como un archivo completo y se cierra, porque un
 * pedazo suelto de una grabacion continua no se puede reproducir. Se
 * usa WEBM/Opus, el mismo formato que manda la version web, para que
 * las dos se entiendan.
 */
public class AudioAlerta {

    public interface Salida {
        /** canal "p" = del paciente al cuidador; "c" = al reves. */
        void enviarTrozo(String canal, String mime, String base64);
    }

    private static final String TAG = "AudioAlerta";
    private static final int MS_TROZO = 1500;

    private final Context ctx;
    private final Salida salida;
    private final Handler hilo = new Handler(Looper.getMainLooper());

    private MediaRecorder rec;
    private File archivo;
    private volatile boolean emitiendo = false;
    private String canal = "p";

    private final ArrayDeque<byte[]> cola = new ArrayDeque<>();
    private MediaPlayer reproductor;
    private boolean reproduciendo = false;

    public AudioAlerta(Context ctx, Salida salida) {
        this.ctx = ctx;
        this.salida = salida;
    }

    public boolean estaEmitiendo() { return emitiendo; }

    // ------------------------------------------------------------------
    // Emitir

    public void empezar(String canal) {
        if (emitiendo) return;
        this.canal = canal;
        emitiendo = true;
        grabarTrozo();
    }

    public void parar() {
        emitiendo = false;
        cerrarGrabador();
    }

    private void grabarTrozo() {
        if (!emitiendo) return;
        try {
            archivo = new File(ctx.getCacheDir(), "voz_" + System.nanoTime() + ext());
            rec = new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (Build.VERSION.SDK_INT >= 29) {
                rec.setOutputFormat(MediaRecorder.OutputFormat.WEBM);
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            } else {
                // En Android viejo no hay Opus; AAC en mp4 lo entiende
                // igual el navegador del otro lado.
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            }
            rec.setAudioSamplingRate(16000);
            rec.setAudioEncodingBitRate(24000);
            rec.setAudioChannels(1);
            rec.setOutputFile(archivo.getAbsolutePath());
            rec.prepare();
            rec.start();
        } catch (Exception e) {
            Log.w(TAG, "no se pudo grabar: " + e.getMessage());
            emitiendo = false;
            return;
        }
        hilo.postDelayed(this::cerrarYEnviar, MS_TROZO);
    }

    private void cerrarYEnviar() {
        File f = archivo;
        cerrarGrabador();
        if (f != null && f.exists() && f.length() > 0) {
            try {
                byte[] datos = new byte[(int) f.length()];
                RandomAccessFile r = new RandomAccessFile(f, "r");
                r.readFully(datos);
                r.close();
                salida.enviarTrozo(canal, mime(), Base64.encodeToString(datos, Base64.NO_WRAP));
            } catch (Exception e) {
                Log.w(TAG, "no se pudo leer el trozo: " + e.getMessage());
            }
            f.delete();
        }
        if (emitiendo) grabarTrozo();
    }

    private void cerrarGrabador() {
        if (rec == null) return;
        try { rec.stop(); } catch (Exception ignored) { }
        try { rec.release(); } catch (Exception ignored) { }
        rec = null;
    }

    private String ext()  { return Build.VERSION.SDK_INT >= 29 ? ".webm" : ".m4a"; }
    private String mime() { return Build.VERSION.SDK_INT >= 29 ? "audio/webm" : "audio/mp4"; }

    // ------------------------------------------------------------------
    // Reproducir

    /** Encola un trozo recibido. Se reproducen en orden, sin pisarse. */
    public void reproducir(String base64) {
        try {
            byte[] datos = Base64.decode(base64, Base64.DEFAULT);
            synchronized (cola) {
                cola.add(datos);
                // Si se acumula retraso, se tira lo viejo: en una
                // emergencia interesa el ahora, no lo de hace 10 segundos.
                while (cola.size() > 6) cola.poll();
            }
            if (!reproduciendo) siguiente();
        } catch (Exception e) {
            Log.w(TAG, "trozo ilegible: " + e.getMessage());
        }
    }

    private void siguiente() {
        byte[] datos;
        synchronized (cola) { datos = cola.poll(); }
        if (datos == null) { reproduciendo = false; return; }
        reproduciendo = true;
        try {
            File f = new File(ctx.getCacheDir(), "rec_" + System.nanoTime() + ".bin");
            FileOutputStream fo = new FileOutputStream(f);
            fo.write(datos);
            fo.close();

            reproductor = new MediaPlayer();
            reproductor.setDataSource(f.getAbsolutePath());
            reproductor.setOnCompletionListener(mp -> { limpiar(mp, f); siguiente(); });
            reproductor.setOnErrorListener((mp, a, b) -> { limpiar(mp, f); siguiente(); return true; });
            reproductor.prepare();
            reproductor.start();
        } catch (Exception e) {
            Log.w(TAG, "no se pudo reproducir: " + e.getMessage());
            siguiente();
        }
    }

    private void limpiar(MediaPlayer mp, File f) {
        try { mp.release(); } catch (Exception ignored) { }
        if (f != null) f.delete();
    }

    public void pararTodo() {
        parar();
        synchronized (cola) { cola.clear(); }
        if (reproductor != null) {
            try { reproductor.release(); } catch (Exception ignored) { }
            reproductor = null;
        }
        reproduciendo = false;
    }
}
