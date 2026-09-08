# Brazalete — detección de caídas y monitoreo cardíaco

Aplicación web que detecta caídas usando el acelerómetro y el giroscopio del
celular, y avisa a otro teléfono a cualquier distancia.

Proyecto escolar. **No es un dispositivo médico.**

## Qué hace

- **Detecta caídas** con el mismo algoritmo que llevará el brazalete ESP32:
  no una simple regla de "pasó de 3 g", sino un sistema de puntos que exige
  impacto **y** quietud posterior, y suma confianza si además hubo caída
  libre, un pico de giro alto o un cambio de orientación grande. Eso es lo
  que separa una caída real de sentarse de golpe en el sofá.
- **Da 25 segundos para cancelar** antes de dar la alarma por buena. Un falso
  positivo cancelable cuesta poco; un falso negativo, mucho.
- **Avisa a otro celular** por internet, sin límite de distancia, con el
  estado del paciente, sus constantes y su ubicación GPS.
- **Mide el pulso** tapando la cámara trasera con el dedo, el mismo principio
  óptico que usa el sensor MAX30102.
- **Graba datasets etiquetados** y los exporta como CSV, para ajustar los
  umbrales con datos reales en vez de con números inventados.

## Los dos modos

Se eligen en el engranaje de arriba a la derecha.

| Modo | Para quién | Qué hace |
|---|---|---|
| **Brazalete** | el celular de quien lo lleva | Detecta y envía |
| **Cuidador** | cualquier otro celular | Solo recibe y avisa |

Los dos tienen que usar el mismo **código de sala**. La forma fácil de
emparejarlos es el botón "Enviar enlace al cuidador": manda un enlace que
configura el otro teléfono solo.

## Cómo se usa

1. Abrir la página en Chrome de Android.
2. Pulsar **"Instalar la app en este teléfono"**.
3. Pulsar **"Iniciar vigilancia"** y aceptar los permisos.
4. Sujetar el celular al brazo, siempre en la misma posición.

Para probar sin caerse: el botón **"Simular caída"** dispara la secuencia
completa.

## Limitaciones conocidas

Vale la pena decirlas en la presentación antes de que las pregunten:

- **Si se bloquea la pantalla, los sensores se detienen.** Es una restricción
  del navegador, no un fallo. La app mantiene la pantalla encendida mientras
  vigila, pero si se cambia de aplicación se corta. Es exactamente el
  argumento a favor de construir el brazalete dedicado.
- **El aviso remoto necesita internet** en el teléfono del paciente. Sin
  señal, la detección local sigue funcionando y el aviso sale en cuanto
  vuelva la conexión.
- **Cualquiera que conozca el código de sala puede ver los datos.** El
  servidor es público y sin contraseña. Conviene usar un código largo y no
  ponerlo en la diapositiva.
- **En iPhone funciona peor**: el flash no se enciende desde el navegador, así
  que la medición de pulso es poco fiable. Los sensores de movimiento sí van.

## Archivos

| Archivo | Qué es |
|---|---|
| `index.html` | La aplicación entera |
| `sw.js` | Service worker: permite instalarla y que funcione sin internet |
| `manifest.json` | Metadatos de la app instalada |
| `mqtt.min.js` | Librería de conexión, local para no depender de un CDN |
