package com.proyecto.brazalete;

import android.content.Context;
import android.content.SharedPreferences;

/** Configuracion guardada. Los nombres coinciden con los de la app web. */
public class Ajustes {

    private static final String ARCHIVO = "brazalete";
    private final SharedPreferences p;

    public Ajustes(Context c) {
        p = c.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE);
        if (getSala().isEmpty()) {
            // Sala aleatoria la primera vez, como en la app web.
            setSala(Long.toString(Math.abs(new java.util.Random().nextLong()), 36)
                    .substring(0, 8));
        }
    }

    public String getSala()            { return p.getString("sala", ""); }
    public void   setSala(String v)    { p.edit().putString("sala", v).apply(); }

    public boolean esBrazalete()       { return p.getString("modo", "brazalete").equals("brazalete"); }
    public void   setModo(String v)    { p.edit().putString("modo", v).apply(); }
    public String getModo()            { return p.getString("modo", "brazalete"); }

    public String getTelefono()        { return p.getString("tel", "112"); }
    public void   setTelefono(String v){ p.edit().putString("tel", v).apply(); }

    /** Umbrales, para poder pegar los que salgan del analisis en Python. */
    public void cargarEn(Detector.Umbrales u) {
        u.impactoG   = p.getFloat("impactoG",   u.impactoG);
        u.fuerteG    = p.getFloat("fuerteG",    u.fuerteG);
        u.libreG     = p.getFloat("libreG",     u.libreG);
        u.giroDps    = p.getFloat("giroDps",    u.giroDps);
        u.orientDeg  = p.getFloat("orientDeg",  u.orientDeg);
        u.quietoTolG = p.getFloat("quietoTolG", u.quietoTolG);
        u.quietoMs   = p.getInt("quietoMs",     u.quietoMs);
        u.puntosMin  = p.getInt("puntosMin",    u.puntosMin);
        u.cuentaMs   = p.getInt("cuentaMs",     u.cuentaMs);
    }

    public void guardarUmbral(String clave, float valor) {
        p.edit().putFloat(clave, valor).apply();
    }

    public void guardarUmbralInt(String clave, int valor) {
        p.edit().putInt(clave, valor).apply();
    }
}
