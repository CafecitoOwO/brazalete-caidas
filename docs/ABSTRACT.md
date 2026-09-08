# CuidAPP — Resumen

**Detección automática de caídas y aviso remoto mediante los sensores
inerciales de un teléfono móvil**

---

Las caídas son la principal causa de lesión en personas mayores, y su
gravedad depende menos del golpe que del tiempo que la persona pasa en el
suelo sin auxilio. Los dispositivos comerciales que resuelven esto son
caros y exigen que el usuario adquiera hardware específico.

Este trabajo presenta **CuidAPP**, un sistema que detecta caídas usando el
acelerómetro y el giroscopio que ya lleva cualquier teléfono, y avisa a un
cuidador a cualquier distancia. El sistema consta de una aplicación nativa
de Android y una aplicación web equivalente, que se comunican entre sí por
un protocolo de mensajería ligero (MQTT) sin necesidad de un servidor
propio.

El problema central no es reconocer un impacto, que es trivial, sino
distinguir una caída real de acciones cotidianas que producen un impacto
parecido, como sentarse bruscamente. Para ello se implementó un
**clasificador por acumulación de evidencia**: exige impacto e inactividad
posterior como condiciones necesarias, y suma confianza si además detecta
caída libre previa, un pico de velocidad angular elevado o un cambio de
orientación corporal. Ante un aviso, el sistema concede un margen de
cortesía configurable (25 s por defecto) para que el usuario lo cancele,
priorizando deliberadamente los falsos positivos cancelables sobre los
falsos negativos.

Se verificó experimentalmente que la aplicación nativa mantiene el
muestreo a 120–142 Hz con la pantalla apagada, frente a los 56 Hz de la
versión web, que además queda suspendida por el navegador al pasar a
segundo plano. La comunicación entre dispositivos se validó con los
equipos en redes distintas y sin conexión física entre ellos.

**Palabras clave:** detección de caídas, acelerometría, telemonitorización,
salud digital, MQTT, sistemas embebidos.

---

*Proyecto escolar. No constituye un dispositivo médico.*
