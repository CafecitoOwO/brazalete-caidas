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
    private TextView estadoPaciente, infoPaciente;
    private TextView alertaTitulo, alertaCuenta, alertaTexto, alertaDatos;
    private LinearLayout tarjetaCuidador, pantallaAlerta;
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
        estadoPaciente   = findViewById(R.id.estadoPaciente);
        infoPaciente     = findViewById(R.id.infoPaciente);
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
            ajustes.setModo(modoBrazalete.isChecked() ? "brazalete" : "cuidador");
            // Reiniciar el servicio para que tome la sala y el modo nuevos.
            mandarAlServicio(ServicioVigilancia.ACCION_PARAR);
            btnVigilar.postDelayed(() ->
                    mandarAlServicio(ServicioVigilancia.ACCION_INICIAR), 600);
            Toast.makeText(this, "Guardado. Sala " + ajustes.getSala(),
                    Toast.LENGTH_SHORT).show();
            pintarModo();
        });

        findViewById(R.id.btnCompartir).setOnClickListener(v -> {
            String sala = campoSala.getText().toString().trim();
            if (sala.isEmpty()) sala = ajustes.getSala();
            String url = WEB + "?sala=" + Uri.encode(sala) + "&modo=cuidador";
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT,
                    "Abri este enlace para vigilar el brazalete:\n" + url);
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
        titulo.setText(brz ? "Brazalete" : "Panel del cuidador");
        tarjetaCuidador.setVisibility(brz ? View.GONE : View.VISIBLE);
        btnVigilar.setVisibility(brz ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnDemo).setVisibility(brz ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------

    @Override public void alCambiar(ServicioVigilancia.Estado e) {
        runOnUiThread(() -> {
            subestado.setText((e.vigilando ? "Vigilando" : "Detenido")
                    + "  ·  " + (e.conectado ? "Sala " + ajustes.getSala() : "Conectando..."));

            estadoVig.setText(e.vigilando ? "Activa" : "Detenida");
            btnVigilar.setText(e.vigilando ? "Detener vigilancia" : "Iniciar vigilancia");
            notaSegundoPlano.setVisibility(e.vigilando ? View.VISIBLE : View.GONE);

            if (e.hz > 0) {
                datosSensor.setText(String.format(java.util.Locale.US,
                        "Aceleracion %.2f g   Giro %d dps\nEstado %s   ·   %d Hz",
                        e.svm, Math.round(e.giro), e.fase, e.hz));
            }

            if (!ajustes.esBrazalete()) {
                if (e.estadoPaciente.equals("caida")) {
                    estadoPaciente.setText("CAIDA CONFIRMADA");
                    estadoPaciente.setTextColor(getColor(R.color.rojo));
                } else if (e.estadoPaciente.equals("prealerta")) {
                    estadoPaciente.setText("Posible caida");
                    estadoPaciente.setTextColor(getColor(R.color.ambar));
                } else if (e.ultimoRemoto == 0) {
                    estadoPaciente.setText("Sin datos");
                    estadoPaciente.setTextColor(getColor(R.color.apagado));
                } else {
                    estadoPaciente.setText("Todo normal");
                    estadoPaciente.setTextColor(getColor(R.color.verde));
                }
                infoPaciente.setText(e.ultimoRemoto == 0
                        ? "Esperando al brazalete. Comprueba que el otro telefono use la misma sala."
                        : "Ultimo aviso hace "
                          + (System.currentTimeMillis() - e.ultimoRemoto) / 1000 + " s.");
            }

            if (e.alerta.isEmpty()) {
                pantallaAlerta.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                pantallaAlerta.setVisibility(View.VISIBLE);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                boolean conf = e.alerta.equals("confirmada");
                alertaTitulo.setText(conf ? "CAIDA CONFIRMADA" : "Posible caida detectada");
                alertaTexto.setText(conf
                        ? "No hubo respuesta. Contacta con la persona ahora mismo."
                        : "Si estas bien, cancela antes de que acabe la cuenta.");
                alertaCuenta.setVisibility(conf ? View.GONE : View.VISIBLE);
                alertaCuenta.setText(String.valueOf(e.segundosRestantes));
                alertaDatos.setText(e.datosAlerta.replace(";", "   "));
            }
        });
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
