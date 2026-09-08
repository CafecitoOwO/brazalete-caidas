package com.proyecto.brazalete;

/**
 * Detector de caidas por sistema de puntos.
 *
 * Es el mismo algoritmo que la app web, portado tal cual para que los
 * umbrales que salgan del analisis en Python valgan para los dos.
 *
 * La idea de fondo: una caida no se reconoce por un solo dato. Sentarse
 * de golpe tambien da un pico de aceleracion. Lo que separa una caida de
 * verdad es lo que pasa DESPUES: la persona se queda quieta 2-4 segundos.
 * Por eso impacto y quietud son obligatorios, y el resto (caida libre
 * previa, giro brusco, cambio de orientacion) solo suma confianza.
 */
public class Detector {

    public enum Fase { REPOSO, PICO, ASENTAR, PREALERTA }

    /** Umbrales. Los nombres coinciden con los de la app web y el script. */
    public static class Umbrales {
        public float impactoG   = 2.5f;   // pico minimo para mirar el evento
        public float fuerteG    = 3.5f;   // por encima, punto extra
        public float libreG     = 0.6f;   // caida libre
        public int   libreMs    = 60;     // ms seguidos en caida libre
        public float giroDps    = 250f;   // pico de giro -> punto extra
        public float orientDeg  = 50f;    // cambio de orientacion -> punto extra
        public int   quietoMs   = 2500;   // quietud exigida tras el impacto
        public float quietoTolG = 0.18f;  // |svm - 1| por debajo de esto
        public float quietoGiro = 35f;    // dps por debajo de esto
        public int   puntosMin  = 3;      // puntos para dar la prealerta
        public int   cuentaMs   = 25000;  // margen para cancelar
    }

    /** Lo que devuelve el detector cuando cree que hubo una caida. */
    public static class Evento {
        public int   puntos;
        public float picoG;
        public float picoDps;
        public float anguloDeg;
        public boolean huboCaidaLibre;

        public String resumen() {
            return "pts=" + puntos
                 + ";g=" + String.format(java.util.Locale.US, "%.1f", picoG)
                 + ";dps=" + Math.round(picoDps)
                 + ";ang=" + Math.round(anguloDeg);
        }
    }

    public final Umbrales u = new Umbrales();

    private Fase fase = Fase.REPOSO;
    private long t0, tLibre, marcaLibre;
    private float picoSvm, picoGiro;
    private final float[] vAntes = {0, 0, 1};
    private Evento pendiente;

    // Filtro paso bajo para estimar la direccion de la gravedad, que es lo
    // que da la orientacion del cuerpo.
    private final float[] suave = {0, 0, 1};
    private static final float K = 0.15f;

    // Historial corto para saber como estaba orientado ANTES del impacto.
    private static final int HIST = 400;          // ~4 s a 100 Hz
    private final long[] histT = new long[HIST];
    private final float[][] histV = new float[HIST][3];
    private int histN = 0, histI = 0;

    public Fase getFase() { return fase; }

    public void reiniciar() {
        fase = Fase.REPOSO;
        pendiente = null;
        picoSvm = picoGiro = 0;
        tLibre = marcaLibre = 0;
    }

    /**
     * Entra una muestra. Devuelve un Evento cuando pasa de PICO a PREALERTA,
     * y null el resto del tiempo.
     *
     * @param ax,ay,az aceleracion en g (gravedad incluida)
     * @param gx,gy,gz velocidad angular en grados por segundo
     * @param t        marca de tiempo en ms
     */
    public Evento muestra(float ax, float ay, float az,
                          float gx, float gy, float gz, long t) {

        float svm  = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        float giro = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);

        suave[0] += K * (ax - suave[0]);
        suave[1] += K * (ay - suave[1]);
        suave[2] += K * (az - suave[2]);

        histT[histI] = t;
        histV[histI][0] = suave[0];
        histV[histI][1] = suave[1];
        histV[histI][2] = suave[2];
        histI = (histI + 1) % HIST;
        if (histN < HIST) histN++;

        // Racha de caida libre: se anota, pero no es obligatoria. Si la
        // persona se agarra a algo al caer, no aparece.
        if (svm < u.libreG) {
            if (tLibre == 0) tLibre = t;
            if (t - tLibre >= u.libreMs) marcaLibre = t;
        } else {
            tLibre = 0;
        }
        boolean huboLibre = marcaLibre > 0 && (t - marcaLibre) < 1200;

        switch (fase) {
            case REPOSO:
                if (svm > u.impactoG) {
                    picoSvm = svm;
                    picoGiro = giro;
                    float[] v = orientacionEn(t - 1500);
                    vAntes[0] = v[0]; vAntes[1] = v[1]; vAntes[2] = v[2];
                    fase = Fase.PICO;
                    t0 = t;
                }
                break;

            case PICO:
                if (svm > picoSvm) picoSvm = svm;
                if (giro > picoGiro) picoGiro = giro;
                if (t - t0 > 300) { fase = Fase.ASENTAR; t0 = t; }
                break;

            case ASENTAR: {
                if (t - t0 < 500) break;   // el rebote del impacto no cuenta

                boolean quieto = Math.abs(svm - 1f) < u.quietoTolG && giro < u.quietoGiro;
                if (!quieto) {
                    // Sigue moviendose: se acomoda en el sofa, no se cayo.
                    fase = Fase.REPOSO;
                    break;
                }
                if (t - t0 < 500 + u.quietoMs) break;

                float ang = anguloEntre(vAntes, suave);
                int p = 0;
                if (huboLibre)             p += 2;
                if (picoGiro > u.giroDps)  p += 2;
                if (ang > u.orientDeg)     p += 2;
                if (picoSvm > u.fuerteG)   p += 1;

                if (p >= u.puntosMin) {
                    Evento e = new Evento();
                    e.puntos = p;
                    e.picoG = picoSvm;
                    e.picoDps = picoGiro;
                    e.anguloDeg = ang;
                    e.huboCaidaLibre = huboLibre;
                    pendiente = e;
                    fase = Fase.PREALERTA;
                    t0 = t;
                    return e;
                }
                fase = Fase.REPOSO;
                break;
            }

            case PREALERTA:
                // El servicio decide cuando confirmar; aqui solo se sostiene
                // el estado por si nadie cancela.
                break;
        }
        return null;
    }

    /** true cuando se acabo el margen para cancelar. */
    public boolean venciaCuenta(long t) {
        return fase == Fase.PREALERTA && (t - t0) > u.cuentaMs;
    }

    public Evento getPendiente() { return pendiente; }

    /** Orientacion estimada en un instante pasado. */
    private float[] orientacionEn(long tObj) {
        float[] mejor = {0, 0, 1};
        long dif = Long.MAX_VALUE;
        for (int k = 0; k < histN; k++) {
            long d = Math.abs(histT[k] - tObj);
            if (d < dif) { dif = d; mejor = histV[k]; }
        }
        return new float[]{mejor[0], mejor[1], mejor[2]};
    }

    private static float anguloEntre(float[] a, float[] b) {
        double da = Math.sqrt(a[0]*a[0] + a[1]*a[1] + a[2]*a[2]);
        double db = Math.sqrt(b[0]*b[0] + b[1]*b[1] + b[2]*b[2]);
        if (da < 1e-4 || db < 1e-4) return 0;
        double c = (a[0]*b[0] + a[1]*b[1] + a[2]*b[2]) / (da * db);
        c = Math.max(-1, Math.min(1, c));
        return (float) Math.toDegrees(Math.acos(c));
    }
}
