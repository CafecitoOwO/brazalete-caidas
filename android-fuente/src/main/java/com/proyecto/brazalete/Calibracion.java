package com.proyecto.brazalete;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Calibra el detector con los movimientos de quien lleva el telefono.
 *
 * Lo que se puede medir sin pedirle a nadie que se caiga es el lado
 * negativo: cuanta fuerza y cuanto giro genera esta persona, con este
 * telefono y llevandolo asi, haciendo cosas normales. Los umbrales se
 * colocan justo por encima de eso.
 *
 * Eso no ajusta el lado positivo (cuanto marca una caida de verdad): para
 * eso hacen falta caidas grabadas sobre un colchon y el script de
 * analisis. Pero quita de golpe la mayoria de los falsos positivos, que
 * es lo que hace que la gente apague la app.
 *
 * Nada se guarda hasta que el resultado se acepta.
 */
public class Calibracion {

    /** Cada cosa que se le pide hacer, en orden. */
    public enum Etapa {
        QUIETO   ("Quedate quieto",      "Sentado o de pie, sin moverte.",            10),
        ANDAR    ("Camina normal",       "Ve y ven, a tu paso de siempre.",           15),
        SENTARSE ("Sientate de golpe",   "Dejate caer en una silla o el sofa, 3 veces.", 20),
        GESTOS   ("Mueve el brazo",      "Saluda, rascate, agachate a atarte el zapato.", 15);

        public final String titulo, instruccion;
        public final int segundos;
        Etapa(String t, String i, int s) { titulo = t; instruccion = i; segundos = s; }
    }

    /** Lo que sale al terminar. */
    public static class Resultado {
        public float impactoG, giroDps, quietoTolG;
        public float picoNormalG, picoNormalDps, quietudMedida;
        public int   muestras;
        /** Umbrales que habia antes, para poder comparar. */
        public float antesImpactoG, antesGiroDps, antesQuietoTolG;
    }

    // Topes de cordura. Un telefono en un bolsillo flojo puede dar picos
    // enormes; dejar que el umbral suba sin limite seria dejar de detectar.
    private static final float IMPACTO_MIN = 1.8f,  IMPACTO_MAX = 4.5f;
    private static final float GIRO_MIN    = 140f,  GIRO_MAX    = 650f;
    private static final float QUIETO_MIN  = 0.07f, QUIETO_MAX  = 0.35f;

    // Margen sobre lo peor que hizo la persona haciendo vida normal.
    private static final float MARGEN_G    = 1.20f;
    private static final float MARGEN_GIRO = 1.15f;
    private static final float MARGEN_QUIETO = 1.6f;

    private Etapa etapa;
    private boolean midiendo = false;
    private long tFin = 0;

    private final List<Float> gNormal    = new ArrayList<>();
    private final List<Float> dpsNormal  = new ArrayList<>();
    private final List<Float> gQuieto    = new ArrayList<>();
    private int total = 0;

    public boolean estaMidiendo() { return midiendo; }
    public Etapa getEtapa()       { return etapa; }

    /** Arranca una etapa. El reloj lo lleva quien llama, con elapsedRealtime. */
    public void empezar(Etapa e, long ahora) {
        etapa = e;
        midiendo = true;
        tFin = ahora + e.segundos * 1000L;
    }

    /** Segundos que faltan para acabar la etapa en curso. */
    public int segundosRestantes(long ahora) {
        return midiendo ? Math.max(0, (int) ((tFin - ahora + 999) / 1000)) : 0;
    }

    /**
     * Una muestra del acelerometro y el giroscopio, ya en g y en grados/s.
     * Devuelve true cuando la etapa acaba de terminar.
     */
    public boolean muestra(float svm, float giro, long ahora) {
        if (!midiendo) return false;
        total++;
        if (etapa == Etapa.QUIETO) {
            gQuieto.add(Math.abs(svm - 1f));
        } else {
            gNormal.add(svm);
            dpsNormal.add(giro);
        }
        if (ahora >= tFin) { midiendo = false; return true; }
        return false;
    }

    public void cancelar() {
        midiendo = false;
        gNormal.clear(); dpsNormal.clear(); gQuieto.clear();
        total = 0;
    }

    public boolean hayDatosSuficientes() {
        return gQuieto.size() > 50 && gNormal.size() > 200;
    }

    /**
     * Calcula los umbrales.
     *
     * Se usa el percentil 99 y no el maximo: un solo pico raro (el telefono
     * golpea la mesa al sentarse) no puede subir el umbral para siempre y
     * dejar de detectar caidas reales.
     */
    public Resultado calcular(Detector.Umbrales actuales) {
        Resultado r = new Resultado();
        r.muestras = total;
        r.antesImpactoG   = actuales.impactoG;
        r.antesGiroDps    = actuales.giroDps;
        r.antesQuietoTolG = actuales.quietoTolG;

        r.picoNormalG   = percentil(gNormal,   0.99f);
        r.picoNormalDps = percentil(dpsNormal, 0.99f);
        r.quietudMedida = percentil(gQuieto,   0.95f);

        r.impactoG   = topar(r.picoNormalG   * MARGEN_G,      IMPACTO_MIN, IMPACTO_MAX);
        r.giroDps    = topar(r.picoNormalDps * MARGEN_GIRO,   GIRO_MIN,    GIRO_MAX);
        r.quietoTolG = topar(r.quietudMedida * MARGEN_QUIETO, QUIETO_MIN,  QUIETO_MAX);
        return r;
    }

    /** Deja los umbrales calculados guardados para siempre en el telefono. */
    public static void aplicar(Ajustes a, Detector.Umbrales u, Resultado r) {
        u.impactoG   = r.impactoG;
        u.giroDps    = r.giroDps;
        u.quietoTolG = r.quietoTolG;
        a.guardarUmbral("impactoG",   r.impactoG);
        a.guardarUmbral("giroDps",    r.giroDps);
        a.guardarUmbral("quietoTolG", r.quietoTolG);
    }

    private static float percentil(List<Float> v, float p) {
        if (v.isEmpty()) return 0f;
        List<Float> c = new ArrayList<>(v);
        Collections.sort(c);
        int i = Math.min(c.size() - 1, Math.max(0, Math.round((c.size() - 1) * p)));
        return c.get(i);
    }

    private static float topar(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
