package com.proyecto.brazalete;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements ServicioVigilancia.Observador {

    /** La app web publicada, para que el cuidador no tenga que instalar nada. */
    private static final String WEB = "https://cafecitoowo.github.io/brazalete-caidas/";

    private Ajustes ajustes;

    /**
     * Selector de foto de perfil.
     *
     * Tiene que declararse aqui, como campo, porque Android exige que
     * estos lanzadores se registren antes de que la pantalla termine de
     * crearse.
     */
    private final androidx.activity.result.ActivityResultLauncher<String> elegirFoto =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
                    uri -> { if (uri != null) guardarFotoDesde(uri); });

    /**
     * Camara para sacarse una foto en el momento.
     *
     * Devuelve una miniatura, no la foto entera. Para un avatar de 128 px
     * sobra, y evita tener que declarar permisos de camara ni configurar
     * un proveedor de archivos.
     */
    private final androidx.activity.result.ActivityResultLauncher<Void> sacarFoto =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.TakePicturePreview(),
                    bmp -> { if (bmp != null) guardarFotoDesdeBitmap(bmp); });

    private TextView titulo, subestado, estadoVig, datosSensor, notaSegundoPlano;
    private TextView infoPaciente, infoMisCuidadores;
    private LinearLayout filasPacientes, filasCuidadores, filasMisCuidadores, tarjetaMisCuidadores;
    private long tUltimasListas = 0;
    private EditText campoNombre;
    private TextView alertaTitulo, alertaCuenta, alertaTexto, alertaDatos;
    private LinearLayout tarjetaCuidador, tarjetaHistorial, pantallaAlerta;
    private TextView statsHistorial, resumenRango;
    private GraficaHistorial graficaHistorial;
    private Button btnVigilar;
    private EditText campoSala;
    private RadioButton modoBrazalete, modoCuidador;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        ajustes = new Ajustes(this);

        titulo           = findViewById(R.id.titulo);
        subestado        = findViewById(R.id.subestado);
        estadoVig        = findViewById(R.id.estadoVig);
        datosSensor      = findViewById(R.id.datosSensor);
        notaSegundoPlano = findViewById(R.id.notaSegundoPlano);
        tarjetaCuidador  = findViewById(R.id.tarjetaCuidador);
        tarjetaHistorial = findViewById(R.id.tarjetaHistorial);
        statsHistorial   = findViewById(R.id.statsHistorial);
        resumenRango     = findViewById(R.id.resumenRango);
        graficaHistorial = findViewById(R.id.graficaHistorial);
        infoPaciente     = findViewById(R.id.infoPaciente);
        filasPacientes   = findViewById(R.id.filasPacientes);
        filasCuidadores  = findViewById(R.id.filasCuidadores);
        filasMisCuidadores = findViewById(R.id.filasMisCuidadores);
        tarjetaMisCuidadores = findViewById(R.id.tarjetaMisCuidadores);
        infoMisCuidadores = findViewById(R.id.infoMisCuidadores);
        campoNombre      = findViewById(R.id.campoNombre);
        pantallaAlerta   = findViewById(R.id.pantallaAlerta);
        alertaTitulo     = findViewById(R.id.alertaTitulo);
        alertaCuenta     = findViewById(R.id.alertaCuenta);
        alertaTexto      = findViewById(R.id.alertaTexto);
        alertaDatos      = findViewById(R.id.alertaDatos);
        btnVigilar       = findViewById(R.id.btnVigilar);
        campoSala        = findViewById(R.id.campoSala);
        modoBrazalete    = findViewById(R.id.modoBrazalete);
        modoCuidador     = findViewById(R.id.modoCuidador);

        campoSala.setText(ajustes.getSala());
        campoNombre.setText(ajustes.getNombre());

        findViewById(R.id.btnAgregarPaciente).setOnClickListener(v -> {
            EditText cn = findViewById(R.id.campoNuevoNombre);
            EditText cc = findViewById(R.id.campoNuevoCodigo);
            String cod = cc.getText().toString().trim();
            String nom = cn.getText().toString().trim();
            if (cod.isEmpty()) {
                Toast.makeText(this, "Falta el codigo de la persona", Toast.LENGTH_SHORT).show();
                return;
            }
            ajustes.agregarPaciente(cod, nom.isEmpty() ? cod : nom);
            cn.setText(""); cc.setText("");
            Toast.makeText(this, "Agregado. Reconectando...", Toast.LENGTH_SHORT).show();
            mandarAlServicio(ServicioVigilancia.ACCION_PARAR);
            btnVigilar.postDelayed(() ->
                    mandarAlServicio(ServicioVigilancia.ACCION_INICIAR), 700);
        });
        modoBrazalete.setChecked(ajustes.esBrazalete());
        modoCuidador.setChecked(!ajustes.esBrazalete());

        // Tocar el selector cambia el modo en el acto. Antes habia que
        // acordarse de pulsar "Guardar y reconectar", asi que la pantalla
        // se quedaba igual y parecia que la app no hacia nada.
        modoBrazalete.setOnCheckedChangeListener((v, marcado) -> {
            if (marcado) cambiarModo("brazalete");
        });
        modoCuidador.setOnCheckedChangeListener((v, marcado) -> {
            if (marcado) cambiarModo("cuidador");
        });

        btnVigilar.setOnClickListener(v -> {
            ServicioVigilancia s = ServicioVigilancia.get();
            if (s != null && s.getEstado().vigilando) {
                mandarAlServicio(ServicioVigilancia.ACCION_PARAR);
            } else {
                pedirPermisos();
                mandarAlServicio(ServicioVigilancia.ACCION_INICIAR);
            }
        });

        findViewById(R.id.btnDemo).setOnClickListener(v -> {
            pedirPermisos();
            mandarAlServicio(ServicioVigilancia.ACCION_DEMO);
        });

        findViewById(R.id.btnSos).setOnClickListener(v -> {
            // Dispara la alarma a todo volumen en todos los telefonos que
            // te siguen. Un toque accidental no puede hacer eso.
            int cuantos = 0;
            ServicioVigilancia s = ServicioVigilancia.get();
            if (s != null) {
                for (ServicioVigilancia.EstadoPac q : s.pacientes.values()) {
                    cuantos += q.cuidadores.size();
                }
            }
            String aviso = cuantos > 0
                    ? "Vas a avisar ahora mismo a " + cuantos
                      + (cuantos == 1 ? " cuidador." : " cuidadores.")
                    : "Todavia no hay ningun cuidador conectado, asi que "
                      + "este aviso no le va a llegar a nadie.";
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Pedir ayuda?")
                    .setMessage(aviso)
                    .setPositiveButton("Pedir ayuda", (d, w) -> {
                        pedirPermisos();
                        mandarAlServicio(ServicioVigilancia.ACCION_SOS);
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        });

        findViewById(R.id.btnEscuchar).setOnClickListener(v -> {
            pedirPermisos();
            mandarAlServicio(ServicioVigilancia.ACCION_ESCUCHAR);
        });
        findViewById(R.id.btnHablar).setOnClickListener(v -> {
            pedirPermisos();
            mandarAlServicio(ServicioVigilancia.ACCION_HABLAR);
        });

        findViewById(R.id.btnEstoyBien).setOnClickListener(v ->
                mandarAlServicio(ServicioVigilancia.ACCION_CANCELAR));

        findViewById(R.id.btnLlamar).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_DIAL,
                    Uri.parse("tel:" + ajustes.getTelefono()));
            startActivity(i);
        });

        findViewById(R.id.btnGuardar).setOnClickListener(v -> {
            String sala = campoSala.getText().toString().trim();
            if (!sala.isEmpty()) ajustes.setSala(sala);
            ajustes.setNombre(campoNombre.getText().toString().trim());
            ajustes.setModo(modoBrazalete.isChecked() ? "brazalete" : "cuidador");
            // Reiniciar el servicio para que tome la sala y el modo nuevos.
            mandarAlServicio(ServicioVigilancia.ACCION_PARAR);
            btnVigilar.postDelayed(() ->
                    mandarAlServicio(ServicioVigilancia.ACCION_INICIAR), 600);
            Toast.makeText(this, "Guardado. Sala " + ajustes.getSala(),
                    Toast.LENGTH_SHORT).show();
            pintarModo();
        });

        findViewById(R.id.btnExportar).setOnClickListener(v -> guardarHistorial());

        findViewById(R.id.btnRangoDia).setOnClickListener(v ->
                cambiarRango(GraficaHistorial.DIA));
        findViewById(R.id.btnRangoSemana).setOnClickListener(v ->
                cambiarRango(GraficaHistorial.SEMANA));
        findViewById(R.id.btnRangoMes).setOnClickListener(v ->
                cambiarRango(GraficaHistorial.MES));
        marcarRangoElegido(GraficaHistorial.DIA);

        // Tocar la foto de arriba abre el menu de perfil.
        findViewById(R.id.miFoto).setOnClickListener(v -> menuPerfil());
        findViewById(R.id.menuApp).setOnClickListener(v -> {
            android.widget.PopupMenu m = new android.widget.PopupMenu(this, v);
            m.getMenu().add("Mi perfil");
            m.getMenu().add("Ver todos mis datos");
            if (ajustes.esBrazalete()) m.getMenu().add("Calibrar con mis movimientos");
            m.getMenu().add("Compartir enlace al cuidador");
            m.getMenu().add("Guardar historial en Descargas");
            m.setOnMenuItemClickListener(it -> {
                String t = String.valueOf(it.getTitle());
                if (t.equals("Mi perfil")) menuPerfil();
                else if (t.equals("Ver todos mis datos")) verMisDatos();
                else if (t.equals("Calibrar con mis movimientos")) calibrar();
                else if (t.equals("Compartir enlace al cuidador")) compartirEnlace();
                else if (t.equals("Guardar historial en Descargas")) guardarHistorial();
                return true;
            });
            m.show();
        });
        ponerFoto(findViewById(R.id.miFoto), ajustes.getFoto());

        findViewById(R.id.btnInvitarCuidador).setOnClickListener(v -> invitarCuidador());

        findViewById(R.id.btnCompartir).setOnClickListener(v -> compartirEnlace());

        pintarModo();
        pedirPermisos();

        // El cuidador no tiene que acordarse de activar nada: si abre la
        // app es porque quiere estar pendiente. El paciente si decide
        // cuando empieza a vigilar, porque implica sus sensores.
        if (!ajustes.esBrazalete() && ServicioVigilancia.get() == null) {
            mandarAlServicio(ServicioVigilancia.ACCION_INICIAR);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ServicioVigilancia.observar(this);
        ServicioVigilancia s = ServicioVigilancia.get();
        if (s != null) alCambiar(s.getEstado());
    }

    @Override protected void onPause() {
        super.onPause();
        ServicioVigilancia.observar(null);
    }

    private void mandarAlServicio(String accion) {
        Intent i = new Intent(this, ServicioVigilancia.class);
        i.setAction(accion);
        ContextCompat.startForegroundService(this, i);
    }

    /* ==============================================================
       Tarjetas de pacientes
       ==============================================================

       Una tarjeta por persona vigilada, como en el dibujo: foto,
       nombre, que esta haciendo, sus señales en vivo y los botones.
       Las vistas se crean una vez y despues solo se actualizan, para
       que no parpadeen ni pierdan el scroll cada segundo.            */

    private final java.util.HashMap<String, View> tarjetasPac = new java.util.HashMap<>();

    private void pintarTarjetasPacientes() {
        ServicioVigilancia s = ServicioVigilancia.get();
        java.util.List<String[]> lista = ajustes.getPacientes();

        if (ajustes.esBrazalete() || lista.isEmpty()) {
            if (filasPacientes.getChildCount() > 0) {
                filasPacientes.removeAllViews();
                tarjetasPac.clear();
            }
            infoPaciente.setVisibility(ajustes.esBrazalete() ? View.GONE : View.VISIBLE);
            return;
        }
        infoPaciente.setVisibility(View.GONE);

        // Quitar las que sobren
        java.util.HashSet<String> vigentes = new java.util.HashSet<>();
        for (String[] p : lista) vigentes.add(p[0]);
        java.util.Iterator<String> it = tarjetasPac.keySet().iterator();
        while (it.hasNext()) {
            String sala = it.next();
            if (!vigentes.contains(sala)) {
                filasPacientes.removeView(tarjetasPac.get(sala));
                it.remove();
            }
        }

        for (String[] p : lista) {
            final String sala = p[0];
            View v = tarjetasPac.get(sala);
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_paciente, filasPacientes, false);
                tarjetasPac.put(sala, v);
                filasPacientes.addView(v);

                // El menu de la esquina recoge lo que en el dibujo cuelga
                // del icono de rayas: mapa y quitar.
                v.findViewById(R.id.menuPac).setOnClickListener(x -> menuPaciente(x, sala));

                v.findViewById(R.id.btnReconectarPac).setOnClickListener(x -> reconectar());

                v.findViewById(R.id.btnMicPac).setOnClickListener(x -> pedirMicrofono(sala));
                v.findViewById(R.id.btnVigilarPac).setOnClickListener(x -> {
                    ServicioVigilancia sv = ServicioVigilancia.get();
                    if (sv == null) {
                        Toast.makeText(this, "El servicio no esta activo",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    sv.pedirVigilancia(sala);
                    Toast.makeText(this, "Aviso enviado: le pedimos que inicie la vigilancia",
                            Toast.LENGTH_SHORT).show();
                });
            }
            actualizarTarjeta(v, sala, p[1], s);
        }
    }

    private void actualizarTarjeta(View v, String sala, String nombre, ServicioVigilancia s) {
        ServicioVigilancia.EstadoPac p = (s == null) ? null : s.pacientes.get(sala);

        ((TextView) v.findViewById(R.id.nombre)).setText(nombre);
        // La cara de la persona, si la mando junto con su perfil.
        ponerFoto(v.findViewById(R.id.foto), p == null ? "" : p.foto);

        long seg = (p == null || p.ultimo == 0) ? -1 : (System.currentTimeMillis() - p.ultimo) / 1000;
        int color; String estadoTxt;
        if (p != null && p.estado.equals("caida"))      { color = 0xFFF87171; estadoTxt = "CAIDA CONFIRMADA"; }
        else if (p != null && p.estado.equals("prealerta")) { color = 0xFFFBBF24; estadoTxt = "Posible caida"; }
        else if (seg < 0)                                { color = 0xFF5A5751; estadoTxt = "Sin datos todavia"; }
        else if (seg > 45)                               { color = 0xFFF87171; estadoTxt = "Sin señal"; }
        else if (p.pausa)                                { color = 0xFFFBBF24; estadoTxt = "Vigilancia pausada"; }
        else if (!p.vig)                                 { color = 0xFFFBBF24; estadoTxt = "Vigilancia detenida"; }
        else { color = 0xFF4ADE80; estadoTxt = p.actividad.isEmpty() ? "Todo normal" : p.actividad; }

        TextView act = v.findViewById(R.id.actividad);
        act.setText(estadoTxt);
        act.setTextColor(color);
        // Un punto, un significado: el punto y el texto que tiene al lado
        // hablan los dos de la CONEXION. El estado ya se lee en grande, con
        // su propio color, en @id/actividad. Antes el punto iba tintado por
        // el estado y la etiqueta por la conexion, pegados y contradiciendose.
        TextView con = v.findViewById(R.id.txtConectado);
        boolean enLinea = seg >= 0 && seg <= 45;
        int colorCon = enLinea ? 0xFF4ADE80 : (seg < 0 ? 0xFF5A5751 : 0xFFF87171);
        con.setText(enLinea ? "Conectado  -  " + haceCuanto(p.ultimo).replace("hace ", "")
                            : (seg < 0 ? "Sin datos" : "Desconectado"));
        con.setTextColor(colorCon);
        v.findViewById(R.id.luz).getBackground().setTint(colorCon);

        ((TextView) v.findViewById(R.id.txtAlertas)).setText(
                s == null ? "Alertas: --"
                          : "Alertas en 1 h: " + s.alertasUltimaHora(sala));

        // "Movimiento", no "Giroscopio": lo que se dibuja es la fuerza que
        // mide el acelerometro, y ademas nadie de fuera sabe que es un
        // giroscopio.
        MiniGrafica onda = v.findViewById(R.id.ondaAcc);
        onda.setTitulo("Movimiento");

        TextView cPul = v.findViewById(R.id.cifraPulso);
        TextView cPas = v.findViewById(R.id.cifraPasos);
        TextView cBat = v.findViewById(R.id.cifraBateria);

        if (p == null || seg < 0 || seg > 45) {
            onda.marcarDesconectado();
            cPul.setText("--"); cPas.setText("--"); cBat.setText("--");
        } else {
            cPul.setText(p.bpm   > 0 ? String.valueOf(p.bpm)   : "--");
            cPas.setText(p.pasos >= 0 ? String.valueOf(p.pasos) : "--");
            cBat.setText(p.bat   >= 0 ? p.bat + "%"             : "--");
            onda.setDatos(p.ondas, 2.5f);
        }

        StringBuilder n = new StringBuilder();
        if (p == null || seg < 0) n.append("Esperando su primera conexion");
        else {
            // El "hace cuanto", la bateria y los pasos ya salen arriba;
            // aqui solo queda lo que no cabe en ningun otro sitio.
            if (p.hz > 0) n.append("Midiendo ").append(p.hz)
                          .append(" veces por segundo");
        }
        ((TextView) v.findViewById(R.id.numeros)).setText(n.toString());

        ((TextView) v.findViewById(R.id.ubicacion)).setText(
                p == null || p.ubicacion.isEmpty()
                        ? "Ubicacion: solo se envia al saltar una alerta"
                        : "Ubicacion: " + p.ubicacion);
        v.findViewById(R.id.btnReconectarPac).setEnabled(s != null);
    }

    /**
     * Invita a otro cuidador a seguir a una persona.
     *
     * Antes mandaba siempre al primero de la lista: con dos o mas personas
     * a cargo era imposible invitar a nadie para la segunda. Ahora, si hay
     * mas de una, pregunta para cual.
     */
    private void invitarCuidador() {
        java.util.List<String[]> l = ajustes.getPacientes();
        if (l.isEmpty()) {
            Toast.makeText(this, "Primero agrega a una persona", Toast.LENGTH_SHORT).show();
            return;
        }
        if (l.size() == 1) { mandarInvitacion(l.get(0)); return; }

        String[] nombres = new String[l.size()];
        for (int i = 0; i < l.size(); i++) {
            nombres[i] = l.get(i)[1].isEmpty() ? l.get(i)[0] : l.get(i)[1];
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Invitar un cuidador para quien?")
                .setItems(nombres, (d, i) -> mandarInvitacion(l.get(i)))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void mandarInvitacion(String[] p) {
        String nom = p[1].isEmpty() ? p[0] : p[1];
        String url = WEB + "?sala=" + Uri.encode(p[0]) + "&modo=cuidador"
                   + "&nombre=" + Uri.encode(nom);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT,
                "Ayudame a cuidar a " + nom + ". Abri este enlace:\n" + url);
        startActivity(Intent.createChooser(i, "Invitar a otro cuidador"));
    }

    /** Manda al cuidador el enlace que lo deja configurado solo. */
    private void compartirEnlace() {
        String sala = campoSala.getText().toString().trim();
        if (sala.isEmpty()) sala = ajustes.getSala();
        String nom = ajustes.getNombre().isEmpty() ? "Paciente" : ajustes.getNombre();
        String url = WEB + "?sala=" + Uri.encode(sala) + "&modo=cuidador"
                   + "&nombre=" + Uri.encode(nom);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT,
                "Vigilame con CuidAPP. Abri este enlace:\n" + url
              + "\n\nPara que te avise con el telefono guardado, instala la app "
              + "desde el boton verde que sale ahi.");
        startActivity(Intent.createChooser(i, "Enviar al cuidador"));
    }

    /** Lo que en el dibujo cuelga del icono de rayas de cada paciente. */
    private void menuPaciente(View ancla, String sala) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, ancla);
        m.getMenu().add("Ver todos sus datos");
        m.getMenu().add("Ver en el mapa");
        m.getMenu().add("Descargar datos");
        m.getMenu().add("Cambiar el nombre");
        m.getMenu().add("Quitar de la lista");
        m.setOnMenuItemClickListener(it -> {
            String t = String.valueOf(it.getTitle());
            if (t.equals("Ver todos sus datos")) verPerfil(sala);
            else if (t.equals("Ver en el mapa")) abrirMapa(sala);
            else if (t.equals("Descargar datos")) guardarHistorial();
            else if (t.equals("Cambiar el nombre")) renombrarPaciente(sala);
            else if (t.equals("Quitar de la lista")) quitarPaciente(sala);
            return true;
        });
        m.show();
    }

    /** Cambiar como se llama esa persona en tu lista. */
    private void renombrarPaciente(String sala) {
        final android.widget.EditText campo = new android.widget.EditText(this);
        campo.setText(ajustes.nombreDe(sala));
        campo.setHint("Nombre");
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        campo.setPadding(pad, pad / 2, pad, pad / 2);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Nombre de esta persona")
                .setView(campo)
                .setPositiveButton("Guardar", (d, w) -> {
                    String nuevo = campo.getText().toString().trim();
                    if (nuevo.isEmpty()) return;
                    java.util.List<String[]> lista = new java.util.ArrayList<>();
                    for (String[] q : ajustes.getPacientes()) {
                        lista.add(q[0].equals(sala) ? new String[]{q[0], nuevo} : q);
                    }
                    ajustes.setPacientes(lista);
                    pintarTarjetasPacientes();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void quitarPaciente(String sala) {
        // Se pierde el seguimiento de esa persona: mejor preguntar antes.
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Quitar a " + ajustes.nombreDe(sala) + "?")
                .setMessage("Dejaras de recibir sus avisos. El historial que ya "
                          + "tienes guardado no se borra.")
                .setPositiveButton("Quitar", (d, w) -> {
                    java.util.List<String[]> nueva = new java.util.ArrayList<>();
                    for (String[] q : ajustes.getPacientes()) {
                        if (!q[0].equals(sala)) nueva.add(q);
                    }
                    ajustes.setPacientes(nueva);
                    reconectar();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /**
     * Toda la informacion de esa persona, no un resumen.
     *
     * El telefono del paciente lee siete sensores; hasta ahora al cuidador
     * le llegaban cuatro cifras. Aqui esta lo que la app sabe de verdad,
     * incluida la lista de sensores que ese telefono tiene.
     */
    private void verPerfil(String sala) {
        ServicioVigilancia s = ServicioVigilancia.get();
        ServicioVigilancia.EstadoPac p = (s == null) ? null : s.pacientes.get(sala);
        String nom = ajustes.nombreDe(sala);
        StringBuilder b = new StringBuilder();

        if (p == null || p.ultimo == 0) {
            b.append("Todavia no se conecto ninguna vez.\n\n");
            b.append("Codigo de sala: ").append(sala);
        } else {
            long seg = (System.currentTimeMillis() - p.ultimo) / 1000;
            b.append("CONEXION\n");
            b.append("  Ultimo dato: ").append(haceCuanto(p.ultimo)).append("\n");
            b.append("  Estado: ").append(seg > 45 ? "sin señal" : "en linea").append("\n");
            b.append("  Vigilando: ")
             .append(p.vig ? (p.pausa ? "en pausa" : "si") : "no").append("\n");
            b.append("  Codigo de sala: ").append(sala).append("\n\n");

            b.append("MOVIMIENTO\n");
            if (!p.actividad.isEmpty()) b.append("  Actividad: ").append(p.actividad).append("\n");
            if (!p.postura.isEmpty())   b.append("  Postura: ").append(bonito(p.postura)).append("\n");
            b.append("  Inclinacion: ").append(p.inclinacion).append(" grados\n");
            if (p.pasos >= 0) b.append("  Pasos hoy: ").append(p.pasos).append("\n");
            if (!p.montaje.isEmpty()) b.append("  Lo lleva en: ").append(bonito(p.montaje)).append("\n");
            b.append("\n");

            b.append("ENTORNO\n");
            b.append("  Luz: ").append(p.lux < 0 ? "sin dato"
                    : p.lux + " lux (" + (p.lux < 10 ? "oscuro"
                    : p.lux < 200 ? "interior" : "luz fuerte") + ")").append("\n");
            b.append("  Sensor de proximidad: ")
             .append(p.cerca ? "tapado (bolsillo o boca abajo)" : "despejado").append("\n");
            if (!p.ubicacion.isEmpty()) b.append("  Ubicacion: ").append(p.ubicacion).append("\n");
            else b.append("  Ubicacion: solo se envia al saltar una alerta\n");
            b.append("\n");

            b.append("TELEFONO\n");
            b.append("  Bateria: ").append(p.bat < 0 ? "sin dato" : p.bat + "%").append("\n");
            b.append("  Midiendo: ").append(p.hz).append(" veces por segundo\n");
            if (p.bpm > 0) b.append("  Pulso: ").append(p.bpm).append(" bpm\n");
            b.append("  Cuidadores que la siguen: ").append(p.cuidadores.size()).append("\n");
            if (s != null) {
                b.append("  Alertas en la ultima hora: ")
                 .append(s.alertasUltimaHora(sala)).append("\n");
            }

            if (!p.sensores.isEmpty()) {
                b.append("\nSENSORES DE ESE TELEFONO\n");
                for (String linea : p.sensores.split("\n")) {
                    if (!linea.trim().isEmpty()) b.append("  ").append(linea.trim()).append("\n");
                }
            }
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(nom.isEmpty() ? "Informacion" : "Informacion de " + nom)
                .setMessage(b.toString())
                .setPositiveButton("Cerrar", null)
                .show();
    }

    /**
     * Calibracion guiada: mide como se mueve esta persona y sube los
     * umbrales justo por encima de eso.
     *
     * Solo calibra el lado negativo (que NO es una caida). Para el lado
     * positivo hacen falta caidas grabadas sobre un colchon, y eso se hace
     * con la pestaña de grabar y el script de analisis.
     */
    private void calibrar() {
        ServicioVigilancia s = ServicioVigilancia.get();
        if (s == null || !s.getEstado().vigilando) {
            Toast.makeText(this, "Inicia la vigilancia antes de calibrar",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Calibrar con tus movimientos")
                .setMessage("Vamos a medir como te mueves normalmente, en cuatro "
                          + "pasos de unos 15 segundos. Con eso la app aprende que "
                          + "NO es una caida en tu caso.\n\n"
                          + "Lleva el telefono donde lo lleves siempre. Si un dia "
                          + "lo cambias de sitio, hay que calibrar otra vez.\n\n"
                          + "No hace falta que te caigas.".replace("  ", " "))
                .setPositiveButton("Empezar", (d, w) ->
                        etapaCalibracion(s.nuevaCalibracion(), 0))
                .setNegativeButton("Ahora no", null)
                .show();
    }

    /** Va pidiendo una etapa detras de otra y al final enseña el resultado. */
    private void etapaCalibracion(Calibracion cal, int i) {
        ServicioVigilancia s = ServicioVigilancia.get();
        if (s == null) return;
        Calibracion.Etapa[] etapas = Calibracion.Etapa.values();
        if (i >= etapas.length) { finCalibracion(cal); return; }
        Calibracion.Etapa e = etapas[i];

        androidx.appcompat.app.AlertDialog dlg =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle((i + 1) + " de " + etapas.length + ": " + e.titulo)
                .setMessage(e.instruccion + "\n\nEmpieza cuando toques Ya.")
                .setCancelable(false)
                .setPositiveButton("Ya", null)
                .setNegativeButton("Dejarlo", (d, w) -> s.terminarCalibracion())
                .create();

        dlg.setOnShowListener(x -> dlg.getButton(
                androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(y -> {
            dlg.dismiss();
            cal.empezar(e, android.os.SystemClock.elapsedRealtime());
            cuentaAtrasCalibracion(cal, i, e);
        }));
        dlg.show();
    }

    /** El cartel con la cuenta atras mientras dura la etapa. */
    private void cuentaAtrasCalibracion(Calibracion cal, int i, Calibracion.Etapa e) {
        androidx.appcompat.app.AlertDialog dlg =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(e.titulo)
                .setMessage(e.instruccion)
                .setCancelable(false)
                .create();
        dlg.show();

        final Runnable[] tic = new Runnable[1];
        tic[0] = () -> {
            if (!dlg.isShowing()) return;
            ServicioVigilancia sv = ServicioVigilancia.get();
            if (sv == null) { dlg.dismiss(); return; }
            int quedan = cal.segundosRestantes(android.os.SystemClock.elapsedRealtime());
            if (cal.estaMidiendo()) {
                dlg.setMessage(e.instruccion + "\n\n" + quedan + " s");
                btnVigilar.postDelayed(tic[0], 250);
            } else {
                dlg.dismiss();
                etapaCalibracion(cal, i + 1);
            }
        };
        btnVigilar.postDelayed(tic[0], 250);
    }

    private void finCalibracion(Calibracion cal) {
        ServicioVigilancia s = ServicioVigilancia.get();
        if (s == null) return;
        if (!cal.hayDatosSuficientes()) {
            s.terminarCalibracion();
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("No alcanzo")
                    .setMessage("Llegaron muy pocas muestras. Comprueba que la "
                              + "vigilancia estaba activa y vuelve a intentarlo.")
                    .setPositiveButton("Cerrar", null)
                    .show();
            return;
        }
        Calibracion.Resultado r = cal.calcular(s.umbrales());
        String msg = "Con " + r.muestras + " muestras tuyas:\n\n"
                + "Lo mas fuerte que generaste haciendo vida normal fue "
                + String.format(java.util.Locale.US, "%.2f", r.picoNormalG) + " g"
                + " y " + Math.round(r.picoNormalDps) + " grados/s.\n\n"
                + "Umbral de impacto: "
                + String.format(java.util.Locale.US, "%.2f", r.antesImpactoG) + " g  ->  "
                + String.format(java.util.Locale.US, "%.2f", r.impactoG) + " g\n"
                + "Umbral de giro: " + Math.round(r.antesGiroDps) + "  ->  "
                + Math.round(r.giroDps) + " grados/s\n"
                + "Quietud exigida: "
                + String.format(java.util.Locale.US, "%.2f", r.antesQuietoTolG) + "  ->  "
                + String.format(java.util.Locale.US, "%.2f", r.quietoTolG) + " g\n\n"
                + "Esto quita falsas alarmas. Para afinar la deteccion de caidas "
                + "de verdad hacen falta caidas grabadas sobre un colchon.";

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Listo")
                .setMessage(msg)
                .setPositiveButton("Guardar", (d, w) -> {
                    Calibracion.aplicar(ajustes, s.umbrales(), r);
                    s.terminarCalibracion();
                    Toast.makeText(this, "Umbrales guardados", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Dejar los de antes", (d, w) -> s.terminarCalibracion())
                .setCancelable(false)
                .show();
    }

    /**
     * Lo mismo que ve el cuidador de ti, pero mirandote a ti mismo.
     *
     * Incluye la lista de sensores que este telefono tiene de verdad, que
     * es lo que se pedia: saber que datos se pueden sacar de aqui.
     */
    private void verMisDatos() {
        ServicioVigilancia s = ServicioVigilancia.get();
        if (s == null) {
            Toast.makeText(this, "El servicio no esta activo", Toast.LENGTH_SHORT).show();
            return;
        }
        ServicioVigilancia.Estado e = s.getEstado();
        StringBuilder b = new StringBuilder();

        b.append("AHORA MISMO\n");
        b.append("  Fuerza medida: ")
         .append(String.format(java.util.Locale.US, "%.2f", e.svm)).append(" g\n");
        b.append("  Giro: ").append(Math.round(e.giro)).append(" grados/s\n");
        b.append("  Midiendo: ").append(e.hz).append(" veces por segundo\n");
        b.append("  Fase del detector: ").append(e.fase).append("\n");
        if (!e.actividad.isEmpty()) b.append("  Actividad: ").append(e.actividad).append("\n");
        b.append("  Bateria: ").append(nivelBateriaTexto()).append("\n\n");

        Detector.Umbrales u = s.umbrales();
        b.append("UMBRALES QUE USA\n");
        b.append("  Impacto minimo: ")
         .append(String.format(java.util.Locale.US, "%.2f", u.impactoG)).append(" g\n");
        b.append("  Giro: ").append(Math.round(u.giroDps)).append(" grados/s\n");
        b.append("  Quietud exigida: ")
         .append(String.format(java.util.Locale.US, "%.2f", u.quietoTolG)).append(" g\n");
        b.append("  Quieto durante: ").append(u.quietoMs).append(" ms\n");
        b.append("  Puntos para avisar: ").append(u.puntosMin).append("\n");
        b.append("  Margen para cancelar: ").append(u.cuentaMs / 1000).append(" s\n\n");

        String sensores = s.sensoresDelTelefono();
        b.append("SENSORES DE ESTE TELEFONO\n");
        b.append(sensores.isEmpty()
                ? "  (se leen al iniciar la vigilancia)"
                : sensores);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Todos mis datos")
                .setMessage(b.toString())
                .setPositiveButton("Cerrar", null)
                .setNeutralButton(ajustes.esBrazalete() ? "Calibrar" : null,
                        ajustes.esBrazalete() ? (d, w) -> calibrar() : null)
                .show();
    }

    private String nivelBateriaTexto() {
        android.content.IntentFilter f =
                new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent i = registerReceiver(null, f);
        if (i == null) return "sin dato";
        int n = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
        int max = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
        return (n < 0 || max <= 0) ? "sin dato" : Math.round(n * 100f / max) + "%";
    }

    /** VERTICAL -> Vertical, BOLSILLO -> Bolsillo. */
    private String bonito(String enumName) {
        if (enumName == null || enumName.isEmpty()) return "";
        String x = enumName.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(x.charAt(0)) + x.substring(1);
    }

    /**
     * Pide el audio de un paciente. Por diseño el microfono solo se abre
     * si hay una alerta en curso, asi que fuera de eso se explica en vez
     * de dejar un boton que no hace nada.
     */
    private void pedirMicrofono(String sala) {
        ServicioVigilancia s = ServicioVigilancia.get();
        if (s == null) {
            Toast.makeText(this, "El servicio no esta activo", Toast.LENGTH_SHORT).show();
            return;
        }
        ServicioVigilancia.EstadoPac p = s.pacientes.get(sala);
        boolean enAlerta = p != null && (p.estado.equals("caida") || p.estado.equals("prealerta"));
        if (!enAlerta) {
            Toast.makeText(this,
                    "El microfono solo se abre durante una alerta. Ahora no hay ninguna.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        s.pedirAudioDe(sala, true);
        Toast.makeText(this, "Pidiendo audio a " + ajustes.nombreDe(sala),
                Toast.LENGTH_SHORT).show();
    }

    private void abrirMapa(String sala) {
        ServicioVigilancia s = ServicioVigilancia.get();
        if (s == null) return;
        ServicioVigilancia.EstadoPac p = s.pacientes.get(sala);
        if (p == null || p.ubicacion.isEmpty()) {
            Toast.makeText(this, "Todavia no hay ubicacion de esta persona",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.google.com/maps?q=" + p.lat + "," + p.lon)));
    }

    /* ==============================================================
       Listas de personas (cuidadores)
       ============================================================== */

    private void pintarListaCuidadores(LinearLayout destino, String sala, TextView vacio) {
        ServicioVigilancia s = ServicioVigilancia.get();
        destino.removeAllViews();
        if (s == null) return;
        ServicioVigilancia.EstadoPac p = s.pacientes.get(sala);
        if (p == null || p.cuidadores.isEmpty()) {
            if (vacio != null) vacio.setVisibility(View.VISIBLE);
            return;
        }
        if (vacio != null) vacio.setVisibility(View.GONE);

        java.util.ArrayList<ServicioVigilancia.Persona> orden =
                new java.util.ArrayList<>(p.cuidadores.values());
        java.util.Collections.sort(orden, (a, b) -> Long.compare(b.ultimaVez, a.ultimaVez));

        final String miId = ajustes.getIdDispositivo();
        long masReciente = 0;
        for (ServicioVigilancia.Persona q : orden) {
            if (q.ultimaVez > masReciente) masReciente = q.ultimaVez;
            View f = getLayoutInflater().inflate(R.layout.item_persona, destino, false);
            boolean activo = System.currentTimeMillis() - q.ultimaVez < 300000;
            boolean soyYo = miId.equals(q.id);

            ((TextView) f.findViewById(R.id.nombreP)).setText(
                    (q.nombre == null || q.nombre.isEmpty() ? "Cuidador" : q.nombre)
                    + (soyYo ? "  (vos)" : ""));
            ((TextView) f.findViewById(R.id.detalleP)).setText(
                    activo ? "Revisando ahora" : "Ultima vez que reviso: " + haceCuanto(q.ultimaVez));
            f.findViewById(R.id.luzP).getBackground().setTint(activo ? 0xFF4ADE80 : 0xFFFBBF24);

            Button avisar = f.findViewById(R.id.btnAvisarP);
            if (soyYo) {
                // No tiene sentido darse un toque a uno mismo.
                avisar.setVisibility(View.GONE);
            } else {
                avisar.setVisibility(View.VISIBLE);
                final String destinoId = q.id;
                final String destinoNombre = q.nombre;
                avisar.setOnClickListener(x -> {
                    ServicioVigilancia sv = ServicioVigilancia.get();
                    if (sv == null) return;
                    sv.avisarACuidador(sala, destinoId,
                            ajustes.getNombre().isEmpty() ? "Un cuidador" : ajustes.getNombre(),
                            nombreDePaciente(sala));
                    Toast.makeText(this, "Aviso enviado a " +
                            (destinoNombre == null || destinoNombre.isEmpty() ? "ese cuidador" : destinoNombre),
                            Toast.LENGTH_SHORT).show();
                });
            }
            destino.addView(f);
        }

        // Si hace mucho que nadie mira, decirlo donde se vea.
        if (masReciente > 0 && System.currentTimeMillis() - masReciente > 12 * 3600000L) {
            TextView av = new TextView(this);
            av.setText("Hace " + haceCuanto(masReciente).replace("hace ", "") +
                    " que ningun cuidador entra a revisar.");
            av.setTextColor(0xFFFBBF24);
            av.setTextSize(13);
            av.setPadding(0, 10, 0, 0);
            destino.addView(av);
        }
    }

    private String nombreDePaciente(String sala) {
        for (String[] p : ajustes.getPacientes()) if (p[0].equals(sala)) return p[1];
        return sala;
    }

    private String haceCuanto(long ts) {
        long s = (System.currentTimeMillis() - ts) / 1000;
        if (s < 90) return "ahora";
        long m = s / 60;
        if (m < 60) return "hace " + m + " min";
        long h = m / 60;
        if (h < 48) return "hace " + h + " h";
        return "hace " + (h / 24) + " dias";
    }

    /* ==============================================================
       Foto de perfil
       ==============================================================

       Se reduce a 128 px y se guarda en base64. Asi pesa unos pocos
       kilobytes y viaja junto al nombre hasta el telefono del cuidador,
       que ve una cara en vez de un codigo. Una foto de camara entera no
       cabria en el mensaje.                                           */

    private static final int LADO_FOTO = 128;

    /**
     * Tu perfil: quien eres dentro de la app.
     *
     * Antes esto era solo una lista de cuatro acciones. No habia ninguna
     * pantalla donde ver tu nombre, tu foto, tu rol ni tu codigo, que era
     * justo lo que faltaba.
     */
    private void menuPerfil() {
        View v = getLayoutInflater().inflate(R.layout.dialog_perfil, null);

        String nombre = ajustes.getNombre();
        boolean brz = ajustes.esBrazalete();

        ((TextView) v.findViewById(R.id.perfilNombre))
                .setText(nombre.isEmpty() ? "Sin nombre todavia" : nombre);
        ((TextView) v.findViewById(R.id.perfilRol))
                .setText(brz ? "Persona vigilada (brazalete)" : "Cuidador");
        ponerFoto(v.findViewById(R.id.perfilFoto), ajustes.getFoto());

        ServicioVigilancia s = ServicioVigilancia.get();
        StringBuilder d = new StringBuilder();
        d.append("Tu codigo: ").append(ajustes.getSala().isEmpty()
                ? "sin configurar" : ajustes.getSala());
        if (brz) {
            int n = (s == null) ? 0 : cuantosCuidadores(s);
            d.append("\nCuidadores que te siguen: ").append(n);
            d.append("\nVigilancia: ").append(
                    s != null && s.getEstado().vigilando ? "activa" : "detenida");
        } else {
            d.append("\nPersonas a tu cargo: ").append(ajustes.getPacientes().size());
            if (s != null) {
                d.append("\nRegistros guardados: ").append(s.getEstado().registros);
            }
        }
        d.append("\nConexion: ").append(
                s != null && s.getEstado().conectado ? "conectado" : "sin conexion");
        ((TextView) v.findViewById(R.id.perfilDatos)).setText(d.toString());

        // El nombre no es decorativo: es lo que ve la otra persona.
        if (nombre.isEmpty()) {
            TextView aviso = v.findViewById(R.id.perfilAviso);
            aviso.setText("Ponte un nombre: es como te ven los demas en sus "
                        + "telefonos. Sin el sales como \"Cuidador\" a secas.");
            aviso.setVisibility(View.VISIBLE);
        }

        androidx.appcompat.app.AlertDialog dlg =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setView(v)
                        .setPositiveButton("Cerrar", null)
                        .create();

        v.findViewById(R.id.perfilCambiarNombre).setOnClickListener(x -> {
            dlg.dismiss(); pedirNombre();
        });
        v.findViewById(R.id.perfilGaleria).setOnClickListener(x -> {
            dlg.dismiss(); elegirFoto.launch("image/*");
        });
        v.findViewById(R.id.perfilCamara).setOnClickListener(x -> {
            dlg.dismiss();
            try { sacarFoto.launch(null); }
            catch (Exception e) {
                Toast.makeText(this, "No pude abrir la camara", Toast.LENGTH_SHORT).show();
            }
        });
        View quitar = v.findViewById(R.id.perfilQuitarFoto);
        if (ajustes.getFoto().isEmpty()) {
            quitar.setVisibility(View.GONE);
        } else {
            quitar.setOnClickListener(x -> {
                dlg.dismiss();
                ajustes.setFoto("");
                android.widget.ImageView iv = findViewById(R.id.miFoto);
                iv.setTag(null);
                ponerFoto(iv, "");
                reconectar();
            });
        }
        dlg.show();
    }

    /** Cuantos cuidadores te siguen, sumando los de todas tus salas. */
    private int cuantosCuidadores(ServicioVigilancia s) {
        int n = 0;
        for (ServicioVigilancia.EstadoPac p : s.pacientes.values()) n += p.cuidadores.size();
        return n;
    }

    /** Cambiar el nombre con el que te ven los demas. */
    private void pedirNombre() {
        final EditText campo = new EditText(this);
        campo.setText(ajustes.getNombre());
        campo.setHint("Como te ven los demas");
        campo.setSingleLine(true);
        int p = (int) (18 * getResources().getDisplayMetrics().density);
        campo.setPadding(p, p, p, p);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Tu nombre")
                .setMessage("Es lo que aparece en el telefono de la otra persona, "
                          + "en vez del codigo de sala.")
                .setView(campo)
                .setPositiveButton("Guardar", (d, w) -> {
                    ajustes.setNombre(campo.getText().toString().trim());
                    campoNombre.setText(ajustes.getNombre());
                    pintarModo();
                    reconectar();
                    Toast.makeText(this, "Nombre guardado", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Guarda una foto ya en memoria (la que devuelve la camara). */
    private void guardarFotoDesdeBitmap(android.graphics.Bitmap bmp) {
        try {
            int lado = Math.min(bmp.getWidth(), bmp.getHeight());
            android.graphics.Bitmap cuadrada = android.graphics.Bitmap.createBitmap(bmp,
                    (bmp.getWidth() - lado) / 2, (bmp.getHeight() - lado) / 2, lado, lado);
            android.graphics.Bitmap chica = android.graphics.Bitmap.createScaledBitmap(
                    cuadrada, LADO_FOTO, LADO_FOTO, true);
            java.io.ByteArrayOutputStream salida = new java.io.ByteArrayOutputStream();
            chica.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, salida);
            String b64 = android.util.Base64.encodeToString(salida.toByteArray(),
                    android.util.Base64.NO_WRAP);
            ajustes.setFoto(b64);
            android.widget.ImageView iv = findViewById(R.id.miFoto);
            iv.setTag(null);
            ponerFoto(iv, b64);
            reconectar();
            Toast.makeText(this, "Foto guardada", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "No pude usar esa foto", Toast.LENGTH_SHORT).show();
        }
    }

    private void guardarFotoDesde(Uri uri) {
        try {
            java.io.InputStream in = getContentResolver().openInputStream(uri);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();

            // Bajar de golpe el tamaño al leer, para no cargar en memoria
            // una foto de 12 megapixeles solo para hacerla diminuta.
            int escala = 1;
            while (o.outWidth / (escala * 2) >= LADO_FOTO
                    && o.outHeight / (escala * 2) >= LADO_FOTO) escala *= 2;

            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = escala;
            in = getContentResolver().openInputStream(uri);
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeStream(in, null, o2);
            if (in != null) in.close();
            if (bmp == null) { Toast.makeText(this, "No pude leer esa imagen", Toast.LENGTH_SHORT).show(); return; }

            // Recorte cuadrado centrado y escalado final
            int lado = Math.min(bmp.getWidth(), bmp.getHeight());
            android.graphics.Bitmap cuadrada = android.graphics.Bitmap.createBitmap(bmp,
                    (bmp.getWidth() - lado) / 2, (bmp.getHeight() - lado) / 2, lado, lado);
            android.graphics.Bitmap chica = android.graphics.Bitmap.createScaledBitmap(
                    cuadrada, LADO_FOTO, LADO_FOTO, true);

            java.io.ByteArrayOutputStream salida = new java.io.ByteArrayOutputStream();
            chica.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, salida);
            String b64 = android.util.Base64.encodeToString(salida.toByteArray(),
                    android.util.Base64.NO_WRAP);

            ajustes.setFoto(b64);
            ponerFoto(findViewById(R.id.miFoto), b64);
            // Reconectar para que el perfil nuevo salga publicado.
            reconectar();
            Toast.makeText(this, "Foto guardada (" + (b64.length() / 1024) + " kB)",
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "No pude usar esa imagen: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Pone una foto en base64 dentro de un ImageView, recortada en circulo. */
    private void ponerFoto(android.widget.ImageView iv, String b64) {
        if (iv == null) return;
        // Solo se redibuja si cambio, para no decodificar cada segundo.
        Object anterior = iv.getTag();
        if (b64 != null && b64.equals(anterior)) return;
        iv.setTag(b64);

        if (b64 == null || b64.isEmpty()) {
            iv.setImageResource(R.mipmap.ic_launcher);
            int p = (int) (7 * getResources().getDisplayMetrics().density);
            iv.setPadding(p, p, p, p);
            return;
        }
        try {
            byte[] d = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
            android.graphics.Bitmap bmp =
                    android.graphics.BitmapFactory.decodeByteArray(d, 0, d.length);
            if (bmp == null) return;
            androidx.core.graphics.drawable.RoundedBitmapDrawable redonda =
                    androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
                            .create(getResources(), bmp);
            redonda.setCircular(true);
            iv.setImageDrawable(redonda);
            iv.setPadding(0, 0, 0, 0);
        } catch (Exception ignored) { }
    }

    private void reconectar() {
        mandarAlServicio(ServicioVigilancia.ACCION_PARAR);
        btnVigilar.postDelayed(() ->
                mandarAlServicio(ServicioVigilancia.ACCION_INICIAR), 600);
    }

    private void cambiarModo(String modo) {
        if (ajustes.getModo().equals(modo)) return;
        ajustes.setModo(modo);
        pintarModo();
        // Reconectar para suscribirse a las salas que correspondan al papel.
        mandarAlServicio(ServicioVigilancia.ACCION_PARAR);
        btnVigilar.postDelayed(() ->
                mandarAlServicio(ServicioVigilancia.ACCION_INICIAR), 600);
        Toast.makeText(this,
                modo.equals("brazalete") ? "Ahora sos el paciente" : "Ahora sos el cuidador",
                Toast.LENGTH_SHORT).show();
    }

    private void pintarModo() {
        boolean brz = ajustes.esBrazalete();
        // En el dibujo el renglon grande es el nombre de quien usa la
        // app ("Usuario 9"), con la marca en el logo de al lado.
        String yo = ajustes.getNombre();
        titulo.setText(yo.isEmpty() ? (brz ? "CuidAPP" : "CuidAPP - Cuidador") : yo);
        tarjetaCuidador.setVisibility(brz ? View.GONE : View.VISIBLE);
        tarjetaHistorial.setVisibility(brz ? View.GONE : View.VISIBLE);
        // El paciente ve quien lo vigila; el cuidador ve a sus pacientes.
        tarjetaMisCuidadores.setVisibility(brz ? View.VISIBLE : View.GONE);
        // El cuidador no detecta nada con sus propios sensores; esa tarjeta
        // solo confunde en su telefono.
        findViewById(R.id.tarjetaDeteccion).setVisibility(brz ? View.VISIBLE : View.GONE);
        pintarTarjetasPacientes();
        // El boton se queda en los dos modos: en cuidador enciende la escucha
        // en segundo plano, que es lo que hace que suene con el movil guardado.
        btnVigilar.setVisibility(View.VISIBLE);
        findViewById(R.id.btnDemo).setVisibility(brz ? View.VISIBLE : View.GONE);
        // El SOS avisa a TUS cuidadores: en modo cuidador no llega a nadie.
        findViewById(R.id.btnSos).setVisibility(brz ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------

    @Override public void alCambiar(ServicioVigilancia.Estado e) {
        runOnUiThread(() -> {
            // Las tarjetas se refrescan siempre; las listas de personas
            // cada 3 s, que cambian poco y recrearlas hace parpadeo.
            pintarTarjetasPacientes();
            // Rehacer la foto de la cabecera. No cuesta nada porque
            // ponerFoto no hace nada si no cambio, y asi no se queda
            // cuadrada si algo le reasigno la imagen por el camino.
            ponerFoto(findViewById(R.id.miFoto), ajustes.getFoto());
            if (System.currentTimeMillis() - tUltimasListas > 3000) {
                tUltimasListas = System.currentTimeMillis();
                if (ajustes.esBrazalete()) {
                    pintarListaCuidadores(filasMisCuidadores, ajustes.getSala(), infoMisCuidadores);
                } else {
                    filasCuidadores.removeAllViews();
                    for (String[] p : ajustes.getPacientes()) {
                        TextView cab = new TextView(this);
                        cab.setText(p[1]);
                        cab.setTextColor(0xFF8D8A83);
                        cab.setTextSize(12);
                        cab.setPadding(0, 8, 0, 0);
                        filasCuidadores.addView(cab);
                        pintarListaCuidadores(filasCuidadores, p[0], null);
                    }
                }
            }

            // La cabecera habla de VOS, no del paciente. Antes mostraba la
            // sala propia, que a un cuidador no le dice nada y encima se
            // confundia con la tarjeta de la persona vigilada.
            String yo = ajustes.getNombre().isEmpty()
                    ? (ajustes.esBrazalete() ? "Sin nombre" : "Sin nombre")
                    : ajustes.getNombre();
            String detalle;
            if (ajustes.esBrazalete()) {
                detalle = e.conectado ? (e.vigilando ? "vigilando" : "detenido") : "conectando...";
                detalle = "sos el paciente · " + detalle;
            } else {
                int n = ajustes.getPacientes().size();
                detalle = "sos el cuidador · " + (n == 0 ? "sin nadie a cargo"
                        : n == 1 ? "1 persona a cargo" : n + " personas a cargo");
            }
            // El nombre ya es el titulo, justo encima: aqui solo el detalle.
            subestado.setText(detalle);

            boolean brz = ajustes.esBrazalete();
            estadoVig.setText(e.vigilando ? (brz ? "Activa" : "Escuchando") : "Detenida");
            btnVigilar.setText(e.vigilando
                    ? (brz ? "Detener vigilancia" : "Desactivar avisos")
                    : (brz ? "Iniciar vigilancia"  : "Activar avisos"));
            notaSegundoPlano.setText(brz
                    ? "Funciona con la pantalla apagada y con otras apps abiertas."
                    : "Recibiras el aviso aunque tengas el telefono guardado y bloqueado.");
            notaSegundoPlano.setVisibility(e.vigilando ? View.VISIBLE : View.GONE);

            if (e.hz > 0) {
                String linea = String.format(java.util.Locale.US,
                        "Aceleracion %.2f g   Giro %d dps\nEstado %s   ·   %d Hz",
                        e.svm, Math.round(e.giro), e.fase, e.hz);
                // Lo que de verdad le interesa a una persona: que esta haciendo.
                if (!e.detalleContexto.isEmpty()) linea += "\n\n" + e.detalleContexto;
                datosSensor.setText(linea);
            }

            if (!ajustes.esBrazalete()) {
                // El historial vive en ESTE telefono: el del cuidador esta
                // siempre escuchando, asi que hace de archivo sin servidor.
                if (e.registros == 0) {
                    statsHistorial.setText("Sin registros todavia.");
                } else {
                    String desde = new java.text.SimpleDateFormat("d MMM HH:mm",
                            new java.util.Locale("es")).format(new java.util.Date(e.desde));
                    statsHistorial.setText(
                            "Registrando desde el " + desde + "\n"
                          + e.registros + " registros guardados\n"
                          + "Alertas: " + e.totalAlertas
                          + "  (" + e.totalConfirmadas + " confirmadas, "
                          + e.totalCanceladas + " canceladas)\n"
                          + (e.porcentajeVigilancia >= 0
                             ? "Vigilancia activa el " + e.porcentajeVigilancia + "% del tiempo"
                             : ""));
                }
                refrescarGrafica();
            }

            if (e.alerta.isEmpty()) {
                pantallaAlerta.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                pantallaAlerta.setVisibility(View.VISIBLE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                boolean conf = e.alerta.equals("confirmada");
                boolean ajeno = !e.quienAlerta.isEmpty();
                alertaTitulo.setText(conf
                        ? (ajeno ? "CAIDA DE " + e.quienAlerta.toUpperCase() : "CAIDA CONFIRMADA")
                        : (ajeno ? "Posible caida de " + e.quienAlerta : "Posible caida detectada"));
                alertaTexto.setText(conf
                        ? "No hubo respuesta. Contacta con la persona ahora mismo."
                        : (ajeno ? "Si sabes que esta bien, descarta el aviso."
                                 : "Si estas bien, cancela antes de que acabe la cuenta."));
                findViewById(R.id.btnEstoyBien).post(() ->
                        ((Button) findViewById(R.id.btnEstoyBien)).setText(
                                ajeno ? "Descartar aviso" : "Estoy bien, cancelar"));
                alertaCuenta.setVisibility(conf ? View.GONE : View.VISIBLE);
                alertaCuenta.setText(String.valueOf(e.segundosRestantes));
                alertaDatos.setText(e.datosAlerta.replace(";", "   "));

                // Voz: el cuidador puede oir y hablar; el paciente solo
                // responder, y siempre viendo que su microfono esta abierto.
                boolean brz2 = ajustes.esBrazalete();
                findViewById(R.id.btnEscuchar).setVisibility(brz2 ? View.GONE : View.VISIBLE);
                findViewById(R.id.btnHablar).setVisibility(View.VISIBLE);
                ((Button) findViewById(R.id.btnEscuchar)).setText(
                        e.escuchando ? "Dejar de escuchar" : "Escuchar que pasa");
                ((Button) findViewById(R.id.btnHablar)).setText(
                        e.micAbierto ? "Dejar de hablar" : (brz2 ? "Responder" : "Hablarle"));

                TextView mic = findViewById(R.id.micAviso);
                if (brz2 && e.micAbierto) {
                    mic.setVisibility(View.VISIBLE);
                    mic.setText("Tu cuidador te esta escuchando.\nHabla si necesitas algo.");
                } else if (brz2 && e.escuchando) {
                    mic.setVisibility(View.VISIBLE);
                    mic.setText("Tu cuidador te esta hablando.");
                } else {
                    mic.setVisibility(View.GONE);
                }
            }
        });
    }

    private void cambiarRango(long ms) {
        graficaHistorial.setRango(ms);
        marcarRangoElegido(ms);
        refrescarGrafica();
    }

    /** El rango activo se ve mas claro; los otros quedan apagados. */
    private void marcarRangoElegido(long ms) {
        int[] ids = { R.id.btnRangoDia, R.id.btnRangoSemana, R.id.btnRangoMes };
        long[] rangos = { GraficaHistorial.DIA, GraficaHistorial.SEMANA, GraficaHistorial.MES };
        for (int i = 0; i < ids.length; i++) {
            Button b = findViewById(ids[i]);
            boolean elegido = rangos[i] == ms;
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    elegido ? 0xFF3F7D4F : 0xFF3A3733));
            b.setAlpha(elegido ? 1f : 0.75f);
        }
    }

    /** Vuelve a leer el historial del servicio y redibuja. */
    private void refrescarGrafica() {
        ServicioVigilancia s = ServicioVigilancia.get();
        if (s == null || graficaHistorial == null) return;
        graficaHistorial.setHistorial(s.historialCrudo());
        resumenRango.setText(graficaHistorial.resumenDelRango());
    }

    /** Escribe el historial como CSV en la carpeta Descargas del telefono. */
    private void guardarHistorial() {
        ServicioVigilancia s = ServicioVigilancia.get();
        if (s == null) {
            Toast.makeText(this, "El servicio no esta activo", Toast.LENGTH_SHORT).show();
            return;
        }
        String csv = s.historialCsv();
        String nombre = "historial_" + ajustes.getSala() + "_"
                + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
                        .format(new java.util.Date()) + ".csv";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, nombre);
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv");
                Uri destino = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                java.io.OutputStream os = getContentResolver().openOutputStream(destino);
                os.write(csv.getBytes("UTF-8"));
                os.close();
            } else {
                java.io.File f = new java.io.File(getExternalFilesDir(null), nombre);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                fo.write(csv.getBytes("UTF-8"));
                fo.close();
            }
            Toast.makeText(this, "Guardado en Descargas: " + nombre, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo guardar: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------

    private void pedirPermisos() {
        List<String> faltan = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                   != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        // Para poder oir a la persona cuando salte una alarma.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.RECORD_AUDIO);
        }
        if (!faltan.isEmpty()) {
            ActivityCompat.requestPermissions(this, faltan.toArray(new String[0]), 10);
        }
    }

    @Override public void onRequestPermissionsResult(int codigo, @NonNull String[] permisos,
                                                     @NonNull int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
    }
}
