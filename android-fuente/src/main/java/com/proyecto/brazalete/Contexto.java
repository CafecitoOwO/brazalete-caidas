package com.proyecto.brazalete;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemClock;

/**
 * Lee todos los sensores que el telefono puede ofrecer, aparte del
 * acelerometro y el giroscopio que ya usa el detector de caidas, y con
 * ellos deduce QUE ESTA HACIENDO la persona.
 *
 * Por que hace falta:
 *
 *  - Para el cuidador, "Juan esta quieto y en horizontal" dice muchisimo
 *    mas que "Juan esta bien".
 *  - Para el detector, saber que la persona volvio a caminar despues del
 *    golpe es la mejor prueba de que NO se cayo. Descarta falsas alarmas
 *    que ninguna combinacion de umbrales podria descartar.
 *
 * Se separan dos cosas que suelen confundirse, porque se miden distinto:
 *
 *    POSTURA    como esta orientado el cuerpo   (sensor de gravedad)
 *    ACTIVIDAD  si se esta moviendo o no        (podometro + aceleracion lineal)
 */
public class Contexto implements SensorEventListener {

    public enum Postura   { VERTICAL, INCLINADO, HORIZONTAL, DESCONOCIDA }
    public enum Actividad { CAMINANDO, MOVIENDOSE, QUIETO, DESCONOCIDA }

    /** Donde lleva el telefono. Cambia como se interpreta la postura. */
    public enum Montaje { BOLSILLO, BRAZO, PECHO }

    private final SensorManager sm;
    private Montaje montaje = Montaje.BOLSILLO;

    // Lecturas crudas
    private final float[] gravedad = { 0, 0, 9.81f };
    private float aceleracionLineal = 0;   // m/s2 sin gravedad
    private float lux = -1;
    private boolean cerca = false;         // sensor de proximidad tapado
    private int pasosTotales = -1;
    private int pasosAlEmpezar = -1;
    private long tUltimoPaso = 0;
    private long tUltimoMovimiento = 0;

    // Resultado
    private Postura postura = Postura.DESCONOCIDA;
    private Actividad actividad = Actividad.DESCONOCIDA;
    private float inclinacionGrados = 0;

    public Contexto(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
    }

    public void setMontaje(Montaje m) { montaje = m; }

    /** Enciende todos los sensores de contexto que el telefono tenga. */
    public void empezar() {
        registrar(Sensor.TYPE_GRAVITY, SensorManager.SENSOR_DELAY_NORMAL);
        registrar(Sensor.TYPE_LINEAR_ACCELERATION, SensorManager.SENSOR_DELAY_GAME);
        registrar(Sensor.TYPE_STEP_DETECTOR, SensorManager.SENSOR_DELAY_NORMAL);
        registrar(Sensor.TYPE_STEP_COUNTER, SensorManager.SENSOR_DELAY_NORMAL);
        registrar(Sensor.TYPE_LIGHT, SensorManager.SENSOR_DELAY_NORMAL);
        registrar(Sensor.TYPE_PROXIMITY, SensorManager.SENSOR_DELAY_NORMAL);
        registrar(Sensor.TYPE_ROTATION_VECTOR, SensorManager.SENSOR_DELAY_NORMAL);
    }

    public void parar() { sm.unregisterListener(this); }

    private void registrar(int tipo, int velocidad) {
        Sensor s = sm.getDefaultSensor(tipo);
        // Si el telefono no tiene ese sensor simplemente no se usa; la app
        // sigue funcionando con lo que haya.
        if (s != null) sm.registerListener(this, s, velocidad);
    }

    /** Lista de los sensores que este telefono si tiene, para el diagnostico. */
    public String sensoresDisponibles() {
        int[] tipos = {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GRAVITY,
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_STEP_DETECTOR, Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY, Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_SIGNIFICANT_MOTION
        };
        String[] nombres = {
            "Acelerometro", "Giroscopio", "Gravedad", "Aceleracion lineal",
            "Vector de rotacion", "Detector de pasos", "Contador de pasos",
            "Luz", "Proximidad", "Magnetometro", "Movimiento significativo"
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tipos.length; i++) {
            sb.append(sm.getDefaultSensor(tipos[i]) != null ? "✓ " : "✗ ")
              .append(nombres[i]).append('\n');
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------

    @Override public void onSensorChanged(SensorEvent e) {
        long ahora = SystemClock.elapsedRealtime();

        switch (e.sensor.getType()) {
            case Sensor.TYPE_GRAVITY:
                gravedad[0] = e.values[0];
                gravedad[1] = e.values[1];
                gravedad[2] = e.values[2];
                calcularPostura();
                break;

            case Sensor.TYPE_LINEAR_ACCELERATION:
                // Sin la gravedad, lo que queda es movimiento de verdad.
                aceleracionLineal = (float) Math.sqrt(
                        e.values[0] * e.values[0] +
                        e.values[1] * e.values[1] +
                        e.values[2] * e.values[2]);
                if (aceleracionLineal > 0.9f) tUltimoMovimiento = ahora;
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                tUltimoPaso = ahora;
                tUltimoMovimiento = ahora;
                break;

            case Sensor.TYPE_STEP_COUNTER:
                // Cuenta desde que arranco el telefono; se guarda el punto
                // de partida para poder dar los pasos de esta sesion.
                pasosTotales = (int) e.values[0];
                if (pasosAlEmpezar < 0) pasosAlEmpezar = pasosTotales;
                break;

            case Sensor.TYPE_LIGHT:
                lux = e.values[0];
                break;

            case Sensor.TYPE_PROXIMITY:
                cerca = e.values[0] < 5;
                break;
        }
        calcularActividad(ahora);
    }

    @Override public void onAccuracyChanged(Sensor s, int p) { }

    // ------------------------------------------------------------------

    private void calcularPostura() {
        double mag = Math.sqrt(gravedad[0]*gravedad[0] +
                               gravedad[1]*gravedad[1] +
                               gravedad[2]*gravedad[2]);
        if (mag < 1) { postura = Postura.DESCONOCIDA; return; }

        // Angulo entre el eje largo del telefono y la vertical. Con el
        // telefono de pie da 0 grados; tumbado, 90.
        double cos = Math.abs(gravedad[1]) / mag;
        cos = Math.max(-1, Math.min(1, cos));
        inclinacionGrados = (float) Math.toDegrees(Math.acos(cos));

        if (inclinacionGrados < 35)      postura = Postura.VERTICAL;
        else if (inclinacionGrados < 65) postura = Postura.INCLINADO;
        else                             postura = Postura.HORIZONTAL;
    }

    private void calcularActividad(long ahora) {
        if (ahora - tUltimoPaso < 3000)              actividad = Actividad.CAMINANDO;
        else if (ahora - tUltimoMovimiento < 2500)   actividad = Actividad.MOVIENDOSE;
        else                                         actividad = Actividad.QUIETO;
    }

    // ------------------------------------------------------------------
    // Lo que se muestra y se publica

    public Postura getPostura()     { return postura; }
    public Actividad getActividad() { return actividad; }
    public float getInclinacion()   { return inclinacionGrados; }
    public float getLux()           { return lux; }
    public boolean getCerca()       { return cerca; }
    public int getPasos() {
        if (pasosTotales < 0 || pasosAlEmpezar < 0) return -1;
        return pasosTotales - pasosAlEmpezar;
    }

    /** true si dio algun paso en los ultimos segundos. */
    public boolean caminoHace(long ms) {
        return tUltimoPaso > 0 && SystemClock.elapsedRealtime() - tUltimoPaso < ms;
    }

    /**
     * Frase corta para la notificacion y para el cuidador.
     *
     * Se dice solo lo que de verdad se puede medir. Con el telefono en el
     * bolsillo, "de pie" y "sentado" se distinguen bien porque el muslo
     * cambia de angulo; en el brazo no, y entonces no se afirma.
     */
    public String descripcion() {
        if (actividad == Actividad.CAMINANDO) return "Caminando";
        if (actividad == Actividad.MOVIENDOSE) return "Moviendose";

        switch (postura) {
            case VERTICAL:
                return montaje == Montaje.BOLSILLO ? "Quieto, de pie" : "Quieto, en vertical";
            case INCLINADO:
                return "Quieto, inclinado";
            case HORIZONTAL:
                if (montaje == Montaje.BOLSILLO) return "Quieto, sentado o acostado";
                return "Quieto, en horizontal";
            default:
                return "Quieto";
        }
    }

    /** Version larga, para la pantalla de detalle del cuidador. */
    public String detalle() {
        StringBuilder sb = new StringBuilder(descripcion());
        sb.append("  ·  inclinacion ").append(Math.round(inclinacionGrados)).append("°");
        int p = getPasos();
        if (p >= 0) sb.append("  ·  ").append(p).append(" pasos");
        if (lux >= 0) {
            sb.append("  ·  ");
            if (lux < 5) sb.append("a oscuras (bolsillo o de noche)");
            else if (lux < 200) sb.append("luz de interior");
            else sb.append("mucha luz");
        }
        return sb.toString();
    }
}
