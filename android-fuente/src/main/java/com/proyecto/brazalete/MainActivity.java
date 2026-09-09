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
    private TextView statsHistorial;
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
            pedirPermisos();
            mandarAlServicio(ServicioVigilancia.ACCION_SOS);
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

        // Tocar la foto de arriba abre el menu de perfil.
        findViewById(R.id.miFoto).setOnClickListener(v -> menuPerfil());
        ponerFoto(findViewById(R.id.miFoto), ajustes.getFoto());

        findViewById(R.id.btnInvitarCuidador).setOnClickListener(v -> {
            java.util.List<String[]> l = ajustes.getPacientes();
            if (l.isEmpty()) {
                Toast.makeText(this, "Primero agrega a una persona", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] p = l.get(0);
            String url = WEB + "?sala=" + Uri.encode(p[0]) + "&modo=cuidador"
                       + "&nombre=" + Uri.encode(p[1]);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT,
                    "Ayudame a cuidar a " + p[1] + ". Abri este enlace:\n" + url);
            startActivity(Intent.createChooser(i, "Invitar a otro cuidador"));
        });

        findViewById(R.id.btnCompartir).setOnClickListener(v -> {
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
        });

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

                v.findViewById(R.id.btnQuitarPac).setOnClickListener(x -> {
                    java.util.List<String[]> l = ajustes.getPacientes();
                    java.util.List<String[]> nueva = new java.util.ArrayList<>();
                    for (String[] q : l) if (!q[0].equals(sala)) nueva.add(q);
                    ajustes.setPacientes(nueva);
                    reconectar();
                });
                v.findViewById(R.id.btnMapaPac).setOnClickListener(x -> abrirMapa(sala));
                v.findViewById(R.id.btnDatosPac).setOnClickListener(x -> guardarHistorial());
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
        v.findViewById(R.id.luz).getBackground().setTint(color);

        MiniGrafica onda = v.findViewById(R.id.ondaAcc);
        onda.setTitulo("Aceleracion");
        MiniGrafica bat = v.findViewById(R.id.ondaBat);
        bat.setTitulo("Bateria");
        bat.setColor("#60A5FA");

        if (p == null || seg < 0 || seg > 45) {
            onda.marcarDesconectado();
            bat.marcarDesconectado();
        } else {
            onda.setDatos(p.ondas, 2.5f);
            float[] sb = new float[p.serieBat.size()];
            for (int i = 0; i < sb.length; i++) sb[i] = p.serieBat.get(i);
            bat.setDatos(sb, Float.NaN);
        }

        StringBuilder n = new StringBuilder();
        if (p == null || seg < 0) n.append("Esperando su primera conexion");
        else {
            n.append("Ultimo dato ").append(seg < 90 ? seg + " s" : (seg / 60) + " min").append(" atras");
            if (p.bat >= 0) n.append("   ·   Bateria ").append(p.bat).append('%');
            if (p.hz > 0)   n.append("   ·   ").append(p.hz).append(" Hz");
            if (p.pasos > 0) n.append("\n").append(p.pasos).append(" pasos");
        }
        ((TextView) v.findViewById(R.id.numeros)).setText(n.toString());

        ((TextView) v.findViewById(R.id.ubicacion)).setText(
                p == null || p.ubicacion.isEmpty()
                        ? "Ubicacion: solo se envia al saltar una alerta"
                        : "Ubicacion: " + p.ubicacion);
        v.findViewById(R.id.btnMapaPac).setEnabled(p != null && !p.ubicacion.isEmpty());
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

        long masReciente = 0;
        for (ServicioVigilancia.Persona q : orden) {
            if (q.ultimaVez > masReciente) masReciente = q.ultimaVez;
            View f = getLayoutInflater().inflate(R.layout.item_persona, destino, false);
            boolean activo = System.currentTimeMillis() - q.ultimaVez < 300000;
            ((TextView) f.findViewById(R.id.nombreP)).setText(
                    q.nombre == null || q.nombre.isEmpty() ? "Cuidador" : q.nombre);
            ((TextView) f.findViewById(R.id.detalleP)).setText(
                    activo ? "Revisando ahora" : "Ultima vez que reviso: " + haceCuanto(q.ultimaVez));
            f.findViewById(R.id.luzP).getBackground().setTint(activo ? 0xFF4ADE80 : 0xFFFBBF24);
            f.findViewById(R.id.btnAvisarP).setOnClickListener(x ->
                    Toast.makeText(this, "Avisar a " + q.nombre + ": pendiente",
                            Toast.LENGTH_SHORT).show());
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

    /** Menu que sale al tocar tu foto: cambiarla, sacarte una, o el nombre. */
    private void menuPerfil() {
        String nombre = ajustes.getNombre();
        String titulo = nombre.isEmpty() ? "Tu perfil" : nombre;
        String[] opciones = {
                "Elegir una foto de la galeria",
                "Sacarme una foto ahora",
                "Cambiar mi nombre",
                ajustes.getFoto().isEmpty() ? null : "Quitar la foto"
        };
        java.util.List<String> lista = new java.util.ArrayList<>();
        for (String o : opciones) if (o != null) lista.add(o);
        String[] finales = lista.toArray(new String[0]);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(titulo)
                .setItems(finales, (d, i) -> {
                    switch (finales[i]) {
                        case "Elegir una foto de la galeria": elegirFoto.launch("image/*"); break;
                        case "Sacarme una foto ahora":
                            try { sacarFoto.launch(null); }
                            catch (Exception e) {
                                Toast.makeText(this, "No pude abrir la camara",
                                        Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case "Cambiar mi nombre": pedirNombre(); break;
                        case "Quitar la foto":
                            ajustes.setFoto("");
                            android.widget.ImageView iv = findViewById(R.id.miFoto);
                            iv.setTag(null);
                            ponerFoto(iv, "");
                            reconectar();
                            break;
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
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
        titulo.setText(brz ? "CuidAPP" : "CuidAPP - Cuidador");
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
            subestado.setText(yo + "  ·  " + detalle);

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
