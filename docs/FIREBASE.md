# Cómo añadir Firebase más adelante

Elegiste «serverless ahora, Firebase después». Esto es lo segundo: qué
falta, por dónde se enchufa, y qué hay que hacer exactamente cuando
quieras cuentas de verdad.

**No hace falta tocar nada de esto para que el proyecto funcione.** Lo que
hay ya funciona. Esto es solo para cuando quieras dar el paso.

---

## 1. Qué se gana y qué se pierde

| | Ahora (sin servidor) | Con Firebase |
|---|---|---|
| Cuentas | no hay: solo un código de sala | correo y contraseña de verdad |
| Quién puede leer tus datos | cualquiera que sepa el código | solo a quien autorices |
| Historial | en el teléfono del cuidador | en la nube, en todos sus dispositivos |
| Si se pierde el teléfono | se pierde el historial | no se pierde |
| Panel de desarrollador | ve las salas activas | ve usuarios reales, con altas y bajas |
| Coste | cero | cero hasta un uso alto |
| Depende de | un broker público | Google |

**La limitación que resuelve** es la número 3 del informe: hoy cualquiera
que conozca un código de sala puede leer los datos de esa persona.

---

## 2. Lo único que no puedo hacer yo

Crear la cuenta. Hay que hacerlo desde el navegador:

1. Entrar en **console.firebase.google.com** con una cuenta de Google.
2. **Agregar proyecto** → nombre `cuidapp` → se puede desactivar Analytics.
3. Dentro del proyecto: **Compilación → Authentication → Comenzar** →
   habilitar **Correo electrónico/contraseña**.
4. **Compilación → Firestore Database → Crear** → modo producción, región
   la más cercana.
5. **Configuración del proyecto** (el engranaje) → bajar hasta *Tus apps*
   → icono `</>` (web) → registrar la app.
6. Copiar el bloque `firebaseConfig` que aparece y pasármelo.

Ese bloque **no es un secreto**: son identificadores públicos. Lo que
protege los datos son las reglas del punto 4.

---

## 3. Por dónde se enchufa en el código

El código ya está preparado: toda la comunicación y todo el guardado pasan
por unas pocas funciones. No hay que reescribir la app, solo darles una
segunda implementación.

### En `app.js`

| Función | Qué hace hoy | Qué haría con Firebase |
|---|---|---|
| `publicar(sala, sub, datos)` | publica en MQTT | escribe en Firestore |
| `conectarNube()` | se suscribe a MQTT | abre escuchas de Firestore |
| `anotarEvento(...)` | guarda en `localStorage` | escribe también en la nube |
| `anotarMuestra(...)` | guarda en `localStorage` | igual |
| `guardarHist()` | escribe `localStorage` | sincroniza |

Lo natural es **conservar MQTT para el aviso inmediato** (es más rápido y
funciona aunque Firestore esté lento) y usar Firestore para **cuentas e
historial**. Los dos caminos conviven sin estorbarse.

### Estructura de datos propuesta

```
usuarios/{uid}
    nombre, rol, creado

pacientes/{sala}
    nombre, uidDueño, creado
    cuidadores: [uid, uid, ...]        <-- quién puede leerlo

pacientes/{sala}/eventos/{id}
    t, tipo, datos, prueba

pacientes/{sala}/muestras/{id}
    t, bat, vig, hz
```

### Reglas de seguridad

Esto es lo que hace que un extraño no pueda leer nada. Va en
**Firestore → Reglas**:

```js
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // Cada quien lee y escribe solo su propio perfil
    match /usuarios/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }

    // Un paciente lo lee su dueño y los cuidadores autorizados
    match /pacientes/{sala} {
      allow read: if request.auth != null &&
        (resource.data.uidDueno == request.auth.uid ||
         request.auth.uid in resource.data.cuidadores);
      allow write: if request.auth != null &&
        resource.data.uidDueno == request.auth.uid;

      match /{sub}/{doc} {
        allow read: if request.auth != null &&
          (get(/databases/$(database)/documents/pacientes/$(sala)).data.uidDueno
             == request.auth.uid ||
           request.auth.uid in
             get(/databases/$(database)/documents/pacientes/$(sala)).data.cuidadores);
        allow create: if request.auth != null &&
          get(/databases/$(database)/documents/pacientes/$(sala)).data.uidDueno
            == request.auth.uid;
      }
    }
  }
}
```

Con esto, **conocer el código de sala ya no basta**: hay que estar en la
lista de cuidadores autorizados.

---

## 4. Trabajo estimado

| Tarea | Quién |
|---|---|
| Crear el proyecto y pegarme la configuración | vos, 10 min |
| Pantalla de registro e inicio de sesión | yo |
| Sustituir el código de sala por invitación con aceptación | yo |
| Escribir eventos y muestras en Firestore | yo |
| Publicar las reglas de seguridad | yo |
| Panel de desarrollador contra usuarios reales | yo |
| Migrar el historial que ya haya en los teléfonos | yo |

---

## 5. Mi recomendación sobre cuándo

**Después de la entrega, no antes.**

Lo que tenés ahora funciona y está probado. Meter cuentas justo antes de
presentar añade una dependencia externa nueva (si Firebase falla o la
contraseña no entra, te quedás sin demo) a cambio de algo que en la
presentación se explica mejor **como limitación reconocida** que como
función a medias.

De hecho, decir *«sabemos que hoy cualquiera con el código puede leer los
datos, y la solución sería autenticación con un servidor propio; lo dejamos
como trabajo futuro»* demuestra que entendiste el problema. Es una
respuesta más fuerte que un login a medio terminar.
