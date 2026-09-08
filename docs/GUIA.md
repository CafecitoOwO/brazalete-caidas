# Guía del proyecto

Todo lo que necesitás saber, sin dar por sentado ningún conocimiento previo.
Actualizada el 7 de septiembre de 2026.

---

## 1. Qué tenés ahora

### La app web — ya publicada y funcionando

## https://cafecitoowo.github.io/brazalete-caidas/

Se abre en cualquier celular, desde cualquier red, sin instalar nada. Está
verificada de punta a punta entre dos dispositivos.

### La app nativa de Android — para el paciente

Hace lo que la web no puede: **vigilar con la pantalla apagada**. Se instala
por cable en el celular.

### Cuál usa cada uno

| Persona | Qué usa | Por qué |
|---|---|---|
| **Paciente** | App nativa | Necesita vigilar aunque bloquee la pantalla o use otras apps |
| **Cuidador** | App web (el enlace de arriba) | Solo recibe avisos; no le hace falta instalar nada |

Las dos hablan el mismo idioma, así que se entienden entre sí sin
configuración especial. Si preferís, el cuidador también puede usar la nativa.

---

## 1-bis. Cómo pasarle la app a otra persona

Mandale **un solo enlace**:

## https://cafecitoowo.github.io/brazalete-caidas/

Ahí adentro hay un botón verde **"Descargar la app de Android"**.

**La instalan los dos, paciente y cuidador.** Al principio pensé que al cuidador
le bastaba la página web, y estaba equivocado: con la web solo recibe avisos
mientras la tenga abierta y mirando la pantalla, que es justo cuando no hacen
falta. Con la app instalada le suena aunque tenga el teléfono en el bolsillo y
bloqueado.

Además, **el teléfono del cuidador es el que guarda el historial del paciente**.
Como su app está siempre escuchando, va acumulando todo: batería, si el paciente
estaba vigilando de verdad, y cada alerta. Hasta 1500 registros, exportables a
CSV con el botón "Guardar historial en Descargas".

Por eso no hace falta ningún servidor: los datos son del cuidador y viven en su
teléfono.

La página web sigue sirviendo para probar y para la demo, pero no para el uso real.

Al instalar, Android va a avisar de que la app no viene de Play Store. Hay que
tocar **Ajustes → Permitir de esta fuente → Instalar**. Es normal en cualquier
app que no venga de la tienda.

Descarga directa del APK, por si la necesitás:
https://github.com/CafecitoOwO/brazalete-caidas/releases

---

## 1-ter. Probar desde la PC, sin segundo celular

Dos formas:

**La fácil.** Abrí en el navegador de la PC:
`https://cafecitoowo.github.io/brazalete-caidas/?sala=TU_SALA&modo=cuidador`
La PC pasa a ser el panel del cuidador. Todo lo que haga el celular lo ves ahí.

**Con los archivos de prueba** (doble clic en la carpeta del proyecto):

| Archivo | Qué hace |
|---|---|
| `PROBAR - ver que envia el celular.bat` | Muestra en vivo lo que publica el celular |
| `PROBAR - simular una caida.bat` | Manda una alerta a la sala; el celular del cuidador suena |

Los dos te piden el código de sala, que sale arriba en la app.

---

## 2. Cómo emparejar los dos teléfonos

Esto es lo único que hay que hacer para que un celular avise al otro.

1. En el celular del **paciente**, abrí la app y entrá en el engranaje
   (arriba a la derecha).
2. Tocá **"Enviar enlace al cuidador"**.
3. Mandá ese enlace por WhatsApp al celular del cuidador.
4. El cuidador abre el enlace. **Listo.** Queda configurado solo, sin teclear
   ningún código.

> El enlace lleva dentro el código de sala. Cualquiera que lo tenga puede ver
> los datos, así que no lo pongas en la diapositiva de la presentación.

---

## 3. Cómo usarla, paso a paso

### En el celular del paciente

1. Abrir la app.
2. Tocar **"Instalar la app en este teléfono"** (solo la primera vez).
3. Tocar **"Iniciar vigilancia"** y aceptar los permisos que pida.
4. Sujetar el celular al brazo, **siempre en la misma posición**. Esto
   importa: si un día lo llevás en el bolsillo y otro en el brazo, los
   umbrales dejan de valer.

### En el celular del cuidador

Abrir el enlace. Ya está. Va a mostrar:

| Lo que dice | Qué significa |
|---|---|
| **Todo normal** | El paciente está bien y la app está vigilando |
| **VIGILANCIA PAUSADA** | Cambió de app o se le apagó la pantalla. **No se están detectando caídas** |
| **SIN SEÑAL** | Hace más de 45 s que no llega nada: sin batería, sin internet, o app cerrada |
| **Posible caída** | Saltó la alerta, con 25 s para cancelar |
| **CAÍDA CONFIRMADA** | Nadie canceló. Hay que contactar ya |

### Para probar sin caerte

El botón **"Simular caída"** dispara toda la secuencia: cuenta atrás, alarma,
vibración y aviso al cuidador. Sirve para ensayar la demo.

---

## 4. El paso que no te podés saltar: grabar datos

Los umbrales que trae la app ahora son **estimaciones**. Para que el detector
funcione de verdad con tu cuerpo, tu celular y tu forma de llevarlo, hay que
grabar movimientos reales y calcular los umbrales con esos datos.

Es el paso que más riesgo elimina, y el único que no puedo hacer yo.

### Cómo se graba

1. En la app, pestaña **"Grabar"** (abajo a la derecha).
2. Elegí qué vas a grabar tocando una de las etiquetas.
3. Tocá **"Empezar a grabar"**, hacé el movimiento, tocá **"Parar"**.

### Qué grabar

| Etiqueta | Cuántas | Cómo |
|---|---|---|
| `caida_frente`, `caida_atras`, `caida_lado` | 3 de cada | **Sobre un colchón.** En serio. |
| `caida_silla` | 2 | Dejarse caer de una silla al colchón |
| `sentarse` | 5 | Sentarse de golpe en el sofá o la cama |
| `tumbarse` | 3 | Tumbarse rápido |
| `andar` | 4 | Caminar normal, 10-15 segundos |
| `escalera` | 3 | Subir y bajar |
| `gesto` | 3 | Saludar, rascarse, mover el brazo rápido |

Los movimientos normales importan **tanto como las caídas**: son los que
enseñan al detector qué NO es una caída.

### Descargar los datos

Tocá **"Descargar dataset.csv"**. El archivo va a tu carpeta de Descargas.

---

## 5. Calcular los umbrales con tus datos

Cuando tengas el `dataset.csv`:

1. Copialo a la carpeta `analisis\datos\` de este proyecto.
2. Pedime que ejecute el análisis, o corré vos este comando:

```bash
python "C:\Users\teamp\proyecto ideas\analisis\03_analisis_umbrales.py"
```

Te va a decir cuántas caídas acierta, cuántos falsos positivos da, y los
mejores umbrales encontrados. También genera `grafica_señales.png`, que va
directa al informe.

3. Esos números se escriben en la app, en el engranaje → "Umbrales de
   detección". No hay que recompilar nada.

---

## 6. Para la presentación

### El momento que vende el proyecto

**Sentarse de golpe delante del tribunal y que no pase nada.** Cualquiera
puede detectar un golpe fuerte; lo difícil es no confundirlo con sentarse.
Después, simular la caída y que salte la alarma en el otro celular.

### Limitaciones que conviene decir ustedes

Decirlas antes de que las pregunten suma. Ocultarlas resta.

- **La versión web no vigila en segundo plano.** Es una restricción del
  navegador. Por eso existe la app nativa, que sí lo hace.
- **El aviso remoto necesita internet.** Sin señal, la detección local sigue
  funcionando y el aviso sale en cuanto vuelva la conexión.
- **Cualquiera con el código de sala ve los datos.** El servidor es público y
  sin contraseña. Se resuelve con un código largo y no enseñándolo.
- **No es un dispositivo médico.** Es un complemento, nunca el único aviso.

### Cómo explicar el algoritmo en una frase

> "No alertamos porque haya un golpe fuerte, porque sentarse también lo
> produce. Alertamos cuando hay un golpe **y además** la persona se queda
> quieta 2 o 3 segundos después. El resto de señales suman confianza, pero
> esas dos son obligatorias."

---

## 7. Carpetas del proyecto

| Carpeta | Qué hay |
|---|---|
| `app\` | La app web. Es también el repositorio de GitHub |
| `android\` | La app nativa de Android |
| `analisis\` | El script de umbrales y la carpeta `datos\` |
| `archivos-originales\` | Copia intacta de lo que vino del chat anterior |
| `conversacion-previa.md` | La conversación original, por si hace falta |

---

## 8. Comandos útiles

Publicar cambios de la app web (yo lo hago, pero por si acaso):

```bash
cd "C:\Users\teamp\proyecto ideas\app"; git add -A; git commit -m "cambios"; git push
```

Ver qué está publicando el celular en tiempo real:

```bash
python "C:\Users\teamp\proyecto ideas\analisis\escuchar_sala.py" TU_SALA 30
```
