# CuidAPP
## Detección automática de caídas y aviso remoto mediante sensores inerciales de teléfono móvil

**Informe técnico del proyecto**
Septiembre de 2026

---

## 1. Introducción

### 1.1. El problema

Las caídas son la principal causa de lesión no intencional en personas
mayores de 65 años. Lo determinante en el pronóstico no suele ser el golpe
en sí, sino el **tiempo que la persona permanece en el suelo sin recibir
auxilio**: una caída sin consecuencias graves puede convertirse en un
cuadro serio si nadie acude durante horas, por deshidratación,
hipotermia o complicaciones respiratorias.

El escenario típico es una persona que vive sola, o que pasa buena parte
del día sin compañía, y un familiar que no tiene forma de saber que algo
pasó hasta que llama y no obtiene respuesta.

### 1.2. Las soluciones existentes y su límite

Existen dispositivos comerciales de teleasistencia que resuelven esto: los
botones de pánico colgados al cuello, y algunos relojes inteligentes de
gama alta con detección automática. Ambos tienen un límite práctico:

- **El botón de pánico exige que la persona esté consciente** y pueda
  pulsarlo, que es justamente lo que falla en las caídas más graves.
- **Los dispositivos con detección automática son caros** y requieren
  comprar hardware específico, a menudo con una cuota mensual asociada.

### 1.3. La hipótesis de este trabajo

Cualquier teléfono moderno ya lleva un acelerómetro y un giroscopio de
calidad suficiente, y ya está conectado a internet. La hipótesis de este
proyecto es que **con ese hardware que la persona ya tiene se puede
construir un detector de caídas útil**, sin comprar nada.

---

## 2. Objetivos

### 2.1. Objetivo general

Desarrollar un sistema capaz de detectar automáticamente la caída de una
persona usando los sensores de su teléfono, y notificarlo a un cuidador
situado a cualquier distancia.

### 2.2. Objetivos específicos

1. Implementar un algoritmo de detección que distinga una caída real de
   movimientos cotidianos que producen un impacto similar.
2. Establecer comunicación entre el dispositivo del paciente y el del
   cuidador sin depender de que estén en la misma red ni cerca.
3. Garantizar que la vigilancia continúe con el teléfono bloqueado y
   guardado, que es la situación de uso real.
4. Registrar un historial de eventos y constantes que permita un
   seguimiento posterior.
5. Permitir que un mismo cuidador vigile a varias personas.

---

## 3. El problema técnico central

Detectar un impacto es trivial: basta comprobar si la aceleración supera
un umbral. El problema real es que **muchas acciones cotidianas producen
un impacto indistinguible del de una caída**.

Sentarse bruscamente en un sofá genera un pico de entre 1,8 y 2,5 g.
Dejarse caer en la cama, tropezar sin llegar a caer, o incluso dejar el
teléfono sobre una mesa producen firmas parecidas. Un detector basado solo
en el umbral de impacto genera tantos falsos positivos que el usuario
acaba desactivándolo, con lo cual el sistema deja de servir.

### 3.1. La observación que resuelve el problema

Lo que distingue una caída no es el impacto, sino **lo que ocurre
después**.

| | Sentarse de golpe | Caída real |
|---|---|---|
| Pico de aceleración | 1,8 – 2,5 g | 3 g o más |
| Velocidad angular | baja | pico alto |
| Segundos siguientes | la persona **sigue moviéndose**: se acomoda, coge el móvil, cambia de postura | **quietud casi total** durante 2 a 4 segundos |
| Orientación final | sentado, tronco vertical | tumbado, cambio de orientación grande |

La ventana de inactividad posterior al impacto es el discriminador más
fiable, y es el eje del algoritmo implementado.

---

## 4. El algoritmo de detección

### 4.1. Por qué no una cadena de condiciones

La aproximación intuitiva sería exigir que se cumplan todas las
condiciones a la vez: caída libre **y** impacto **y** giro **y** cambio de
orientación **y** inactividad. Esta aproximación falla en la práctica:

- Si la persona **se agarra a algo al caer**, no hay fase de caída libre.
- Si cae **hacia adelante desde una silla**, el cambio de orientación
  puede ser pequeño.
- Si el teléfono queda **atrapado bajo el cuerpo**, el giro se amortigua.

Exigir todas las señales convierte cada excepción en un falso negativo, y
un falso negativo en este sistema significa que nadie acude.

### 4.2. Sistema de acumulación de evidencia

El algoritmo implementado separa las señales en **necesarias** y **de
confianza**:

**Condiciones necesarias** (sin ambas, no hay alerta):

1. Un impacto que supere el umbral configurado.
2. Un periodo de quietud posterior sostenido.

**Señales que suman puntos:**

| Señal | Puntos | Fundamento |
|---|---|---|
| Caída libre previa | +2 | Indica desplome, no apoyo controlado |
| Pico de giro > 250 °/s | +2 | El cuerpo rota al perder el equilibrio |
| Cambio de orientación > 50° | +2 | De vertical a horizontal |
| Impacto > 3,5 g | +1 | Golpe especialmente fuerte |

Se emite alerta al alcanzar un umbral de puntos configurable (3 por
defecto). Así, una caída sin fase de caída libre puede seguir detectándose
si el giro y el cambio de orientación son claros.

### 4.3. Máquina de estados

El detector recorre cuatro estados:

```
REPOSO ──impacto──▶ PICO ──300 ms──▶ ASENTAR ──quietud sostenida──▶ PREALERTA
   ▲                                     │                              │
   └────────── hay movimiento ───────────┘                              │
   └───────────────────── se cancela a tiempo ───────────────────────────┘
```

- **REPOSO**: se vigila el umbral de impacto.
- **PICO**: se registran los máximos de aceleración y giro durante 300 ms.
- **ASENTAR**: se ignoran los primeros 500 ms (rebote del golpe) y después
  se exige quietud sostenida. **Si hay movimiento, se descarta**: es el
  caso de sentarse bruscamente.
- **PREALERTA**: se avisa y se abre el margen de cancelación.

### 4.4. El margen de cortesía

Al alcanzar la prealerta, el sistema **no avisa inmediatamente al
cuidador**: muestra una alarma local con una cuenta atrás configurable (25
segundos por defecto) que la persona puede cancelar.

Esta decisión es deliberada y asimétrica:

> Un **falso positivo cancelable** cuesta 25 segundos de molestia.
> Un **falso negativo** cuesta que nadie acuda.

Por eso el sistema está calibrado para pecar de sensible.

### 4.5. Calibración automática por persona

Un umbral fijo de quietud es una simplificación que perjudica precisamente
a quien más necesita el sistema. Una persona con temblor esencial, o con
respiración marcada, **nunca alcanza la quietud que espera un umbral
rígido**, y su caída no se detectaría jamás. En el extremo opuesto, alguien
muy estático haría saltar la alarma con cualquier tropiezo leve.

La aplicación resuelve esto midiendo cuánto se mueve **esa persona
concreta** cuando está parada. Durante los periodos en que el detector está
en reposo, acumula muestras y calcula la media de la desviación de
aceleración y de la velocidad angular. El umbral de quietud pasa a
definirse de forma relativa:

```
tolerancia = 3 × ruido_propio + margen     (acotado entre 0,08 y 0,35 g)
giro       = 3 × giro_propio  + margen     (acotado entre 12 y 80 °/s)
```

Es también el punto donde **acelerómetro y giroscopio cooperan**: para dar
un instante por «quieto» tienen que estar tranquilos los dos a la vez. Un
sensor solo se deja engañar; los dos juntos, mucho menos.

En una prueba con 600 muestras de reposo simulado, el sistema aprendió un
umbral de **0,085 g y 18 °/s**, frente a los 0,18 g y 35 °/s fijos por
defecto: menos de la mitad, es decir, un detector notablemente más
sensible para ese usuario concreto.

### 4.6. Aviso de último minuto

Una alerta confirmada que nadie atiende es el peor escenario posible: el
sistema hizo bien su trabajo y la persona sigue igualmente en el suelo.

Por eso, pasado un margen configurable sin que nadie cancele, el sistema
**vuelve a insistir**: reanuda la alarma y emite un nuevo aviso indicando
cuánto tiempo lleva la alerta desatendida. Y sigue insistiendo, con el
contador acumulándose, hasta que alguien responda.

### 4.7. Configuración del sensor

Un detalle que invalida silenciosamente todo el algoritmo si se pasa por
alto: los acelerómetros arrancan por defecto en un rango de ±2 g. **Un
impacto de caída satura esa escala**, el pico se recorta y el detector
nunca ve el valor real. La aplicación configura explícitamente un rango
amplio y un muestreo de 100 Hz.

---

## 5. Arquitectura del sistema

### 5.1. Componentes

```
┌──────────────────┐        ┌──────────────┐        ┌───────────────────┐
│ Teléfono del     │  MQTT  │   Broker     │  MQTT  │ Teléfono del      │
│ PACIENTE         │───────▶│   público    │───────▶│ CUIDADOR          │
│                  │        │  (internet)  │        │                   │
│ · sensores       │        └──────────────┘        │ · recibe avisos   │
│ · detección      │                                │ · guarda historial│
│ · alarma local   │◀───── cancelación ─────────────│ · varios pacientes│
└──────────────────┘                                └───────────────────┘
```

Cada dispositivo **sale hacia internet por su cuenta** y se conecta a un
servidor de mensajería público. Ninguno necesita conocer la dirección del
otro, ni estar en la misma red, ni tener IP fija. Esto se verificó
experimentalmente con el teléfono en red móvil (4G) y el equipo receptor
en una red doméstica distinta, y con el cable de datos físicamente
desconectado.

### 5.2. Canales de comunicación

Cada paciente tiene un **código de sala** que actúa como identificador y
como credencial. Sobre él se definen cinco canales:

| Canal | Contenido | Retenido |
|---|---|---|
| `perfil` | nombre y papel del dispositivo | sí |
| `vitales` | batería, pulso, frecuencia de muestreo, si vigila | sí |
| `evento` | alertas y cancelaciones | sí |
| `ubicacion` | coordenadas GPS, solo al saltar una alerta | sí |
| `historial` | últimos eventos, para recuperar el pasado | sí |

### 5.3. Persistencia sin servidor propio

El sistema **no dispone de servidor ni base de datos propios**. La
persistencia se resuelve por dos vías complementarias:

1. **El teléfono del cuidador es el archivo principal.** Como su
   aplicación mantiene un servicio en primer plano permanentemente
   conectado, registra cada dato que recibe: hasta 1500 entradas con
   marca temporal, nivel de batería, si la vigilancia estaba realmente
   activa, y todos los eventos. Es exportable a CSV.

2. **Los mensajes retenidos del broker actúan como respaldo.** El
   protocolo MQTT conserva el último mensaje de cada canal y lo entrega a
   quien se suscriba después. Así, un cuidador que abre la aplicación tras
   varias horas recupera lo que ocurrió mientras estaba desconectado.

Esta decisión tiene una consecuencia favorable en privacidad: **los datos
del paciente no residen en ningún servidor de terceros**, sino en el
teléfono de la persona que lo cuida.

### 5.4. Los dos frentes de implementación

Se desarrollaron dos aplicaciones que hablan el mismo protocolo:

| | Aplicación web | Aplicación nativa Android |
|---|---|---|
| Instalación | ninguna, se abre por enlace | APK |
| Muestreo medido | 56 Hz | **120–142 Hz** |
| Con pantalla apagada | **se suspende** | sigue funcionando |
| Despierta el teléfono bloqueado | no puede | sí |
| Uso previsto | demostración y respaldo | uso real, ambos papeles |

---

## 6. Resultados experimentales

Todas las cifras siguientes proceden de mediciones sobre un **Samsung
Galaxy A35 5G con Android 16**, no de estimaciones.

### 6.1. Continuidad de la vigilancia en segundo plano

Este fue el hallazgo determinante del proyecto. Se midió cuántos mensajes
enviaba cada versión con la pantalla apagada:

| Versión | Mensajes recibidos | Ventana |
|---|---|---|
| Web (segundo plano) | **1** | 20 s |
| Nativa (pantalla apagada) | **11** | 30 s |

La versión web queda suspendida por el navegador. Se detectó al observar
que el panel del cuidador seguía indicando «Todo normal» cuando en
realidad los sensores llevaban minutos detenidos: **un fallo silencioso**,
el peor comportamiento posible en un sistema de seguridad.

La corrección fue doble: la aplicación web avisa explícitamente de que la
vigilancia se pausó, y el panel del cuidador marca «SIN SEÑAL» si pasan
más de 45 segundos sin recibir datos. La solución de fondo fue desarrollar
la aplicación nativa con un servicio en primer plano.

### 6.2. Frecuencia de muestreo

- Aplicación web: **56 Hz**
- Aplicación nativa: **120–142 Hz**, estable

### 6.3. Latencia y alcance

- Alerta emitida en un dispositivo y recibida en otro en **menos de 2
  segundos**.
- Verificado con ambos equipos en **redes distintas** (móvil 4G y red
  doméstica) y sin conexión física entre ellos.
- La notificación de la aplicación nativa **despierta el teléfono
  bloqueado**: se comprobó la transición del estado del sistema de
  `Dozing` a `Awake`, con la alerta mostrada a pantalla completa sin
  necesidad de desbloquear.

### 6.4. Geolocalización

Al saltar una alerta, el dispositivo obtiene su posición y la transmite.
Precisiones obtenidas en interior: **14 a 25 metros**.

### 6.5. Validación del algoritmo

Se implementó una réplica del detector en Python que procesa los ficheros
CSV grabados por la aplicación, ejecuta la misma máquina de estados y
permite explorar el espacio de umbrales.

Sobre un conjunto de datos **sintético** de 12 grabaciones (5 caídas y 7
movimientos normales) construido con las firmas físicas descritas en la
sección 3, el detector obtuvo **5 de 5 caídas detectadas y 0 falsos
positivos**.

> **Advertencia metodológica.** Este resultado valida que la cadena
> completa funciona —la aplicación graba, el script analiza y los umbrales
> se aplican—, pero **no valida los umbrales**. Los datos sintéticos son
> más separables que los reales. La validación con grabaciones reales está
> pendiente y es el paso que más incertidumbre eliminará.

---

## 7. Limitaciones conocidas

Se enumeran de forma explícita porque condicionan el uso real:

1. **La aplicación web no vigila en segundo plano.** Es una restricción
   del navegador, no un defecto corregible. Es la razón de existir de la
   aplicación nativa.

2. **El aviso remoto requiere conexión a internet** en el dispositivo del
   paciente. Sin ella, la detección local sigue operando y el aviso se
   emite en cuanto se recupera la conexión.

3. **El control de acceso se basa solo en el código de sala.** El broker
   es público y sin autenticación: cualquiera que conozca el código puede
   leer los datos de esa sala. Se mitiga con códigos largos, pero la
   solución correcta sería un broker propio con credenciales por usuario.

4. **Los umbrales actuales no están validados con datos reales**
   (sección 6.5).

5. **La medición de pulso por cámara es orientativa.** Funciona por
   fotopletismografía con el flash, y en iPhone el flash no es accesible
   desde el navegador.

6. **No es un dispositivo médico.** Debe considerarse un complemento y
   nunca el único mecanismo de aviso.

---

## 8. Trabajo futuro

- **Grabación del conjunto de datos real** y recalibración de umbrales.
- **Pulsera dedicada con ESP32**, MPU6050 y MAX30102, como accesorio
  opcional para quien no quiera llevar el teléfono encima. El firmware
  comparte el mismo algoritmo y los mismos umbrales.
- **Autenticación real** con cuentas de usuario y un broker privado, que
  resolvería la limitación 3.
- **Aprendizaje del patrón del usuario**, ajustando los umbrales de forma
  automática según su forma de moverse.

---

## 9. Conclusiones

1. Es viable construir un detector de caídas útil con el hardware que la
   persona ya tiene, sin comprar ningún dispositivo.

2. El problema no es detectar impactos, sino **descartar los que no son
   caídas**. La inactividad posterior al impacto resultó ser el
   discriminador más fiable, y un esquema de acumulación de evidencia
   tolera mejor los casos atípicos que una cadena rígida de condiciones.

3. **La continuidad de la vigilancia en segundo plano resultó ser el
   requisito más restrictivo** de todo el proyecto, y el que obligó a
   pasar de una aplicación web a una nativa. Un sistema de seguridad que
   deja de vigilar sin avisar es peor que no tener sistema.

4. Una arquitectura sin servidor propio, apoyada en mensajería pública y
   almacenamiento en el dispositivo del cuidador, es suficiente para este
   alcance y además mejora la privacidad, a costa de un control de acceso
   más débil.

---

## 10. Referencias

- Bourke, A. K., O'Brien, J. V., Lyons, G. M. *Evaluation of a
  threshold-based tri-axial accelerometer fall detection algorithm.* Gait
  & Posture, 2007.
- Kangas, M. et al. *Comparison of low-complexity fall detection
  algorithms for body attached accelerometers.* Gait & Posture, 2008.
- Organización Mundial de la Salud. *Caídas* — nota descriptiva.
- Especificación MQTT 3.1.1, OASIS.
- Documentación de Android: *Foreground services* y *Sensors Overview*.

---

*Proyecto escolar. No constituye un dispositivo médico ni sustituye a la
supervisión profesional.*
