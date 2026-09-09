package com.proyecto.brazalete;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

/**
 * Una onda pequeña, del tamaño de una tarjeta.
 *
 * Es lo que se ve en el dibujo del cuaderno: cada paciente muestra sus
 * señales en vivo, con una linea de puntos marcando el umbral. Sirve
 * para que el cuidador vea de un vistazo si hay actividad o si la señal
 * esta plana, sin tener que leer numeros.
 */
public class MiniGrafica extends View {

    private final Paint linea = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint umbral = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texto = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path camino = new Path();

    private float[] datos = new float[0];
    private float ref = Float.NaN;      // linea de umbral, si aplica
    private String titulo = "";
    private boolean sinDatos = true;

    public MiniGrafica(Context c) { this(c, null); }

    public MiniGrafica(Context c, AttributeSet a) {
        super(c, a);
        linea.setStyle(Paint.Style.STROKE);
        linea.setStrokeWidth(dp(1.8f));
        linea.setStrokeJoin(Paint.Join.ROUND);
        linea.setColor(Color.parseColor("#4ADE80"));

        umbral.setStyle(Paint.Style.STROKE);
        umbral.setStrokeWidth(dp(1f));
        umbral.setColor(Color.parseColor("#7F1D1D"));
        umbral.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(4)}, 0));

        texto.setColor(Color.parseColor("#8D8A83"));
        texto.setTextSize(dp(10));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public void setTitulo(String t) { titulo = t; invalidate(); }
    public void setColor(String hex) { linea.setColor(Color.parseColor(hex)); invalidate(); }

    public void setDatos(float[] d, float referencia) {
        datos = d == null ? new float[0] : d;
        ref = referencia;
        sinDatos = datos.length < 2;
        invalidate();
    }

    public void marcarDesconectado() { sinDatos = true; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float arriba = dp(13);            // sitio para el titulo

        if (!titulo.isEmpty()) c.drawText(titulo, 0, dp(10), texto);

        if (sinDatos) {
            texto.setTextSize(dp(11));
            c.drawText("desconectado", dp(2), h / 2 + dp(4), texto);
            texto.setTextSize(dp(10));
            return;
        }

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : datos) { if (v < min) min = v; if (v > max) max = v; }
        if (!Float.isNaN(ref)) { if (ref > max) max = ref; if (ref < min) min = ref; }
        float margen = (max - min) * 0.15f + 0.05f;
        min -= margen; max += margen;
        final float rango = Math.max(max - min, 1e-3f);

        if (!Float.isNaN(ref)) {
            float y = h - (ref - min) / rango * (h - arriba) - dp(2);
            c.drawLine(0, y, w, y, umbral);
        }

        camino.reset();
        for (int i = 0; i < datos.length; i++) {
            float x = i / (float) (datos.length - 1) * w;
            float y = h - (datos[i] - min) / rango * (h - arriba) - dp(2);
            if (i == 0) camino.moveTo(x, y); else camino.lineTo(x, y);
        }
        c.drawPath(camino, linea);
    }
}
