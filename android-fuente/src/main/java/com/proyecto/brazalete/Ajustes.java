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

    /**
     * Identificador estable de este telefono.
     *
     * Antes se generaba uno nuevo en cada arranque, y como la presencia
     * se publica retenida, cada reinicio dejaba un cuidador fantasma en
     * la lista. Guardandolo, el mismo telefono es siempre el mismo.
     */
    public String getIdDispositivo() {
        String id = p.getString("idDispositivo", "");
        if (id.isEmpty()) {
            id = Long.toHexString(new java.util.Random().nextLong());
            id = id.length() > 8 ? id.substring(0, 8) : id;
            p.edit().putString("idDispositivo", id).apply();
        }
        return id;
    }

    public String getNombre()          { return p.getString("nombre", ""); }
    public void   setNombre(String v)  { p.edit().putString("nombre", v).apply(); }

    /**
     * Personas que vigila el cuidador. Se guardan como lineas "sala\tnombre".
     * Un cuidador puede tener a varias a la vez: la abuela y el abuelo, por
     * ejemplo, cada uno con su propio telefono.
     */
    public java.util.List<String[]> getPacientes() {
        java.util.List<String[]> lista = new java.util.ArrayList<>();
        String s = p.getString("pacientes", "");
        if (s.isEmpty()) return lista;
        for (String linea : s.split("\n")) {
            if (linea.isEmpty()) continue;
            String[] partes = linea.split("\t", 2);
            lista.add(new String[]{ partes[0], partes.length > 1 ? partes[1] : partes[0] });
        }
        return lista;
    }

    public void setPacientes(java.util.List<String[]> lista) {
        StringBuilder sb = new StringBuilder();
        for (String[] x : lista) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(x[0]).append('\t').append(x[1]);
        }
        p.edit().putString("pacientes", sb.toString()).apply();
    }

    public void agregarPaciente(String sala, String nombre) {
        java.util.List<String[]> l = getPacientes();
        for (String[] x : l) if (x[0].equals(sala)) return;
        l.add(new String[]{ sala, nombre });
        setPacientes(l);
    }

    /** Salas a las que hay que suscribirse segun el papel. */
    public java.util.List<String> salasQueEscucho() {
        java.util.List<String> s = new java.util.ArrayList<>();
        if (esBrazalete()) { s.add(getSala()); return s; }
        for (String[] x : getPacientes()) s.add(x[0]);
        return s;
    }

    public String nombreDe(String sala) {
        if (sala.equals(getSala())) {
            String n = getNombre();
            return n.isEmpty() ? "Yo" : n;
        }
        for (String[] x : getPacientes()) if (x[0].equals(sala)) return x[1];
        return sala;
    }

    /** Historial de eventos guardado, para que sobreviva a reinicios. */
    public String getHistorial()         { return p.getString("historial", ""); }
    public void   setHistorial(String v) { p.edit().putString("historial", v).apply(); }

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
