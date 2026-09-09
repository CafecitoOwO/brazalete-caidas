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

    private TextView titulo, subestado, estadoVig, datosSensor, notaSegundoPlano;
    private TextView infoPaciente;
    private LinearLayout filasPacientes;
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

    private void pintarModo() {
        boolean brz = ajustes.esBrazalete();
        titulo.setText(brz ? "CuidAPP" : "CuidAPP - Cuidador");
        tarjetaCuidador.setVisibility(brz ? View.GONE : View.VISIBLE);
        tarjetaHistorial.setVisibility(brz ? View.GONE : View.VISIBLE);
        // El boton se queda en los dos modos: en cuidador enciende la escucha
        // en segundo plano, que es lo que hace que suene con el movil guardado.
        btnVigilar.setVisibility(View.VISIBLE);
        findViewById(R.id.btnDemo).setVisibility(brz ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------

    @Override public void alCambiar(ServicioVigilancia.Estado e) {
        runOnUiThread(() -> {
            subestado.setText((e.vigilando ? "Vigilando" : "Detenido")
                    + "  ·  " + (e.conectado ? "Sala " + ajustes.getSala() : "Conectando..."));

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
                pintarFilasPacientes();

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
            }
        });
    }

    /**
     * Una fila por persona vigilada, con su semaforo.
     *
     * El cuidador puede tener a varias a la vez, asi que no vale con una
     * sola tarjeta: hay que ver de un vistazo cual esta mal.
     */
    private void pintarFilasPacientes() {
        java.util.List<String[]> lista = ajustes.getPacientes();
        filasPacientes.removeAllViews();

        if (lista.isEmpty()) {
            infoPaciente.setText("Todavia no vigilas a nadie.\n\n"
                    + "Pedile a esa persona que abra CuidAPP, entre en el engranaje y toque "
                    + "\"Enviar enlace al cuidador\". El codigo que te pase va aca abajo.");
            infoPaciente.setVisibility(View.VISIBLE);
            return;
        }
        infoPaciente.setVisibility(View.GONE);

        ServicioVigilancia s = ServicioVigilancia.get();
        for (String[] p : lista) {
            String sala = p[0], nombre = p[1];
            ServicioVigilancia.EstadoPac ep = s != null ? s.pacientes.get(sala) : null;

            String txt; int color;
            if (ep == null || ep.ultimo == 0) {
                txt = "Sin datos · todavia no se conecto"; color = R.color.apagado;
            } else if ("caida".equals(ep.estado)) {
                txt = "CAIDA CONFIRMADA · contacta ahora"; color = R.color.rojo;
            } else if ("prealerta".equals(ep.estado)) {
                txt = "Posible caida · esperando"; color = R.color.ambar;
            } else {
                long seg = (System.currentTimeMillis() - ep.ultimo) / 1000;
                if (seg > 45) {
                    txt = "SIN SEÑAL desde hace "
                        + (seg < 120 ? seg + " s" : (seg / 60) + " min");
                    color = R.color.rojo;
                } else if (ep.pausa) {
                    txt = "Vigilancia pausada · cambio de app"; color = R.color.ambar;
                } else if (!ep.vig) {
                    txt = "Vigilancia detenida"; color = R.color.ambar;
                } else {
                    txt = "Todo normal · hace " + seg + " s"
                        + (ep.bat >= 0 ? "  ·  bateria " + ep.bat + "%" : "");
                    color = R.color.verde;
                }
            }

            LinearLayout fila = new LinearLayout(this);
            fila.setOrientation(LinearLayout.VERTICAL);
            fila.setPadding(0, 12, 0, 12);

            TextView tn = new TextView(this);
            tn.setText(nombre);
            tn.setTextColor(getColor(R.color.texto));
            tn.setTextSize(17);
            tn.setTypeface(null, android.graphics.Typeface.BOLD);

            TextView te = new TextView(this);
            te.setText(txt);
            te.setTextColor(getColor(color));
            te.setTextSize(13);

            fila.addView(tn);
            fila.addView(te);
            filasPacientes.addView(fila);
        }
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
        if (!faltan.isEmpty()) {
            ActivityCompat.requestPermissions(this, faltan.toArray(new String[0]), 10);
        }
    }

    @Override public void onRequestPermissionsResult(int codigo, @NonNull String[] permisos,
                                                     @NonNull int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
    }
}
