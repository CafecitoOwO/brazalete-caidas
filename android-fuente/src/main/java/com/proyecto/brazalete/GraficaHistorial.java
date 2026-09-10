package com.proyecto.brazalete;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * El historial guardado, dibujado a lo largo del tiempo.
 *
 * Hasta ahora los 1500 registros solo se podian exportar a CSV: estaban
 * guardados pero no se veian por ningun lado. Esto es lo que pedia la
 * lista, "como las pestañas de inversion": la bateria a lo largo de los
 * dias, las franjas en las que NO se estuvo vigilando, y una marca por
 * cada alerta.
 *
 * Se le pasa el historial en crudo (la lista de JSON del servicio) y el
 * rango en milisegundos; el recorte y la escala los hace la vista.
 */
public class GraficaHistorial extends View {

    /** Rangos que ofrece el selector, en milisegundos. */
    public static final long DIA    = 24L * 3600 * 1000;
    public static final long SEMANA = 7 * DIA;
    public static final long MES    = 30 * DIA;

    private static final int VERDE = 0xFF4ADE80;   // bateria
    private static final int AMBAR = 0xFFFBBF24;   // vigilancia pausada / prealerta
    private static final int ROJO  = 0xFFEF4444;   // caida confirmada
    private static final int GRIS  = 0xFF8D8A83;   // textos

    private final Paint linea   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint relleno = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint banda   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eje     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marca   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texto   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path camino   = new Path();
    private final Path bajo     = new Path();

    private static class Muestra { long t; int bat; boolean vig; }
    private static class Evento  { long t; String tipo; }

    private final List<Muestra> muestras = new ArrayList<>();
    private final List<Evento>  eventos  = new ArrayList<>();

    private long rango = DIA;
    private long hasta = 0;          // borde derecho; 0 = no hay datos
    private String aviso = "Sin registros todavia.";

    public GraficaHistorial(Context c) { this(c, null); }

    public GraficaHistorial(Context c, AttributeSet a) {
        super(c, a);
        linea.setStyle(Paint.Style.STROKE);
        linea.setStrokeWidth(dp(2f));
        linea.setStrokeJoin(Paint.Join.ROUND);
        linea.setStrokeCap(Paint.Cap.ROUND);
        linea.setColor(VERDE);

        relleno.setStyle(Paint.Style.FILL);
        relleno.setColor((VERDE & 0x00FFFFFF) | 0x22000000);

        banda.setStyle(Paint.Style.FILL);
        banda.setColor((AMBAR & 0x00FFFFFF) | 0x1E000000);

        eje.setStyle(Paint.Style.STROKE);
        eje.setStrokeWidth(dp(0.8f));
        eje.setColor(0xFF3A3733);
        eje.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0));

        marca.setStyle(Paint.Style.STROKE);
        marca.setStrokeWidth(dp(1.6f));

        texto.setColor(GRIS);
        texto.setTextSize(sp(9.5f));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    /** Como dp, pero siguiendo el tamano de letra que eligio la persona. */
    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }

    /** Cambia la ventana de tiempo sin volver a parsear el historial. */
    public void setRango(long ms) { rango = ms; invalidate(); }

    public long getRango() { return rango; }

    /**
     * Carga el historial en crudo. Acepta los dos tipos de registro que
     * guarda el servicio: k="ev" (alertas) y el resto (muestras por minuto).
     */
    public void setHistorial(List<String> crudo) {
        muestras.clear();
        eventos.clear();
        hasta = 0;
        if (crudo != null) {
            for (String s : crudo) {
                try {
                    JSONObject j = new JSONObject(s);
                    long t = j.optLong("t");
                    if (t <= 0) continue;
                    if (t > hasta) hasta = t;
                    if ("ev".equals(j.optString("k"))) {
                        // Lo del menu de pruebas no ensucia la grafica.
                        if (j.optBoolean("prueba")) continue;
                        Evento e = new Evento();
                        e.t = t;
                        e.tipo = j.optString("tipo");
                        eventos.add(e);
                    } else {
                        Muestra m = new Muestra();
                        m.t = t;
                        m.bat = j.optInt("bat", -1);
                        m.vig = j.optInt("vig") == 1;
                        muestras.add(m);
                    }
                } catch (Exception ignored) { }
            }
        }
        aviso = (muestras.isEmpty() && eventos.isEmpty())
                ? "Sin registros todavia."
                : "Nada en este periodo.";
        invalidate();
    }

    /** Resumen de lo que se ve ahora mismo, para el texto de debajo. */
    public String resumenDelRango() {
        if (hasta == 0) return "Sin registros todavia.";
        long desde = hasta - rango;
        int alertas = 0, confirmadas = 0;
        for (Evento e : eventos) {
            if (e.t < desde) continue;
            if (e.tipo.equals("CAIDA_CONFIRMADA") || e.tipo.equals("SOS_MANUAL")) confirmadas++;
            else if (e.tipo.equals("PREALERTA")) alertas++;
        }
        int total = 0, activas = 0, batMin = 101;
        for (Muestra m : muestras) {
            if (m.t < desde) continue;
            total++;
            if (m.vig) activas++;
            if (m.bat >= 0 && m.bat < batMin) batMin = m.bat;
        }
        if (total == 0 && alertas == 0 && confirmadas == 0) return "Nada en este periodo.";
        StringBuilder sb = new StringBuilder();
        if (total > 0) {
            sb.append("Vigilando el ").append(Math.round(activas * 100f / total))
              .append("% del tiempo");
            if (batMin <= 100) sb.append("  -  bateria minima ").append(batMin).append('%');
        }
        if (confirmadas > 0 || alertas > 0) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(confirmadas)
              .append(confirmadas == 1 ? " caida confirmada" : " caidas confirmadas")
              .append("  -  ").append(alertas)
              .append(alertas == 1 ? " aviso cancelado" : " avisos cancelados");
        }
        return sb.toString();
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float abajo = dp(14);                 // sitio para las fechas
        float alto  = h - abajo;

        if (hasta == 0 || rango <= 0) { dibujarAviso(c, w, h, "Sin registros todavia."); return; }

        long desde = hasta - rango;
        boolean algoEnRango = false;

        // Franjas de las ventanas sin vigilancia. Van primero para que la
        // linea de bateria quede por encima.
        Muestra prev = null;
        for (Muestra m : muestras) {
            if (m.t < desde) { prev = m; continue; }
            algoEnRango = true;
            if (prev != null && !prev.vig) {
                float x0 = x(Math.max(prev.t, desde), desde, w);
                float x1 = x(m.t, desde, w);
                if (x1 > x0) c.drawRect(x0, 0, x1, alto, banda);
            }
            prev = m;
        }

        // Rejilla: 0 / 50 / 100 % de bateria.
        for (int p = 0; p <= 100; p += 50) {
            float y = alto - p / 100f * (alto - dp(4)) - dp(2);
            c.drawLine(dp(20), y, w, y, eje);
            c.drawText(p + "%", 0, y + dp(3), texto);
        }

        // Linea de bateria, con relleno suave por debajo.
        camino.reset();
        bajo.reset();
        boolean primero = true;
        float ultX = 0;
        for (Muestra m : muestras) {
            if (m.t < desde || m.bat < 0) continue;
            float px = x(m.t, desde, w);
            float py = alto - m.bat / 100f * (alto - dp(4)) - dp(2);
            if (primero) {
                camino.moveTo(px, py);
                bajo.moveTo(px, alto);
                bajo.lineTo(px, py);
                primero = false;
            } else {
                camino.lineTo(px, py);
                bajo.lineTo(px, py);
            }
            ultX = px;
        }
        if (!primero) {
            bajo.lineTo(ultX, alto);
            bajo.close();
            c.drawPath(bajo, relleno);
            c.drawPath(camino, linea);
        }

        // Una marca vertical por alerta. Rojo si se confirmo, ambar si no.
        for (Evento e : eventos) {
            if (e.t < desde) continue;
            boolean grave = e.tipo.equals("CAIDA_CONFIRMADA") || e.tipo.equals("SOS_MANUAL");
            if (!grave && !e.tipo.equals("PREALERTA")) continue;
            algoEnRango = true;
            marca.setColor(grave ? ROJO : AMBAR);
            float px = x(e.t, desde, w);
            c.drawLine(px, grave ? 0 : alto * 0.45f, px, alto, marca);
        }

        if (!algoEnRango) { dibujarAviso(c, w, h, aviso); return; }

        dibujarFechas(c, desde, w, h);
    }

    private void dibujarAviso(Canvas c, float w, float h, String txt) {
        texto.setTextSize(sp(11));
        c.drawText(txt, (w - texto.measureText(txt)) / 2, h / 2, texto);
        texto.setTextSize(sp(9.5f));
    }

    /** Etiquetas del eje de tiempo, con el formato que toque segun el rango. */
    private void dibujarFechas(Canvas c, long desde, float w, float h) {
        SimpleDateFormat f =
                new SimpleDateFormat(rango <= DIA ? "HH:mm" : "d MMM", new Locale("es"));
        final int cuantas = 4;
        for (int i = 0; i <= cuantas; i++) {
            String s = f.format(new Date(desde + rango * i / cuantas));
            float ancho = texto.measureText(s);
            float px = w * i / (float) cuantas;
            // Las de los bordes se meten hacia dentro para que no se corten.
            if (i == 0) px = 0;
            else if (i == cuantas) px = w - ancho;
            else px -= ancho / 2;
            c.drawText(s, px, h - dp(2), texto);
        }
    }

    private float x(long t, long desde, float w) {
        return (t - desde) / (float) rango * w;
    }
}
