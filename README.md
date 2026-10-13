# Sleep-Rust

Mod de Minecraft Java **1.20.1**, **Forge 47.4.10** y **Java 17**. Versión del proyecto: **0.2.4**.

Sleep-Rust agrega cuerpos saqueables cuando un jugador vivo se desconecta y un sistema propio de jugadores caídos y reanimación. Es un proyecto en desarrollo: compilá y probá en un mundo descartable con copias de seguridad antes de usarlo en un mundo importante.

## Novedades de la versión 0.2.4

- Al desconectarse, el cuerpo conserva la vida, la absorción y los efectos activos. Si recibe daño, el jugador recupera la vida restante al volver; el respaldo guarda también ese estado.
- Al recuperar al jugador, la salud del cuerpo se aplica después de devolver el inventario y se limita al máximo de vida que tenía guardado el cuerpo.
- Un cuerpo de jugador con invisibilidad permanece oculto mientras se ven sus partículas y sigue pudiendo abrirse y saquearse.
- Los paneles de accesorios mantienen el diseño compacto anterior; los slots vacíos de Curios siguen visibles junto con los ocupados.

## Cambios de la versión 0.2.3

- Se añadió el sistema propio de caída y reanimación: 8 segundos para reanimar y un plazo de 2:30 antes de morir.
- Se reforzó el procesamiento de las muertes causadas al destruir un cuerpo mientras su dueño está desconectado. La muerte pendiente se conserva hasta confirmar y guardar la muerte al reconectarse.
- La pantalla de muerte identifica al atacante o, si no lo hay, muestra la causa ambiental.
- Las pruebas de lógica incluyen regresiones para los reintentos de muerte, costes y estados de reanimación.
- Las compilaciones ya no incrementan la versión automáticamente; `mod_version` se cambia deliberadamente al preparar una versión.

## Funciones

### Cuerpos al desconectarse

- Al desconectarse un jugador vivo se crea un cuerpo con su inventario, armadura y mano secundaria. La integración disponible con Curios y BlockZ también maneja accesorios y slots adicionales.
- El cuerpo conserva la vida, los efectos activos y la absorción del jugador al desconectarse. Si el cuerpo recibe daño, el dueño recupera la vida y absorción restantes al volver; ese estado también se guarda en el respaldo del mundo.
- El dueño puede recuperar lo que quede al volver; el contenido que ya fue saqueado no se restaura.
- El estado del cuerpo y un snapshot de respaldo se guardan en los datos del mundo. La recuperación busca el cuerpo aunque esté en otra dimensión o en un chunk que necesite cargarse.
- El cuerpo tiene modelo y renderer de jugador, skin, equipo, animación de respiración, partícula de sueño, nombre al mirarlo e inventario saqueable.
- Los cuerpos reaccionan al daño y a ciertos peligros del mundo; un tótem válido puede salvarlos.
- Si el cuerpo muere, los objetos siguen las reglas de drops y de las integraciones instaladas.

La creación del cuerpo, el vaciado del inventario y el guardado de los índices son operaciones de persistencia separadas. Un cierre abrupto todavía puede causar pérdida o duplicación de objetos; la recuperación en chunks descargados, otras dimensiones y después de un crash requiere pruebas en un servidor real.

### Caída y reanimación (sistema propio)

- El daño letal normal puede dejar al jugador caído en vez de matarlo. `/kill` y el daño del vacío siguen pudiendo matarlo; los tótems conservan prioridad. Por defecto, hay **2 minutos y 30 segundos** para reanimarlo antes de que muera.
- Un jugador caído puede arrastrarse lentamente, pero no saltar ni correr, atacar ni usar objetos. Su pantalla muestra un efecto borroso y rojizo.
- Para reanimarlo, otro jugador mantiene **click derecho durante 8 segundos** mientras lo mira. Hay una barra y una animación; soltar el botón, perderlo de vista, alejarse o recibir daño cancela la acción.
- Al completar la reanimación, el jugador vuelve con **2 corazones**. El coste configurable de hambre, saturación o ambos se cobra solo al completar; cancelar no cobra.
- **Click izquierdo** puede rematar a un jugador caído cuando `allowFinishOff` está activado y el PvP está permitido.
- Si un jugador caído se desconecta, se procesa como una muerte normal y no crea otro cuerpo de Sleep-Rust. Al volver, reaparece automáticamente.
- Si matan el cuerpo mientras el dueño está desconectado, la muerte pendiente se conserva hasta confirmar y guardar la muerte del jugador al reconectarse. La pantalla indica quién lo mató o la causa ambiental.
- El estado de caído es solo memoria del servidor: si el servidor sufre un crash, el jugador vuelve vivo con su vida guardada.

### Compatibilidad con PlayerRevive

**PlayerRevive no es compatible ni está soportado; Sleep-Rust no está diseñado para funcionar junto con ese mod.** No se garantiza el comportamiento de las muertes, la caída ni la reanimación si PlayerRevive está instalado. Para usar Sleep-Rust, quitá PlayerRevive del servidor y de los clientes. Cualquier manejo interno limitado de una muerte offline no constituye compatibilidad ni soporte para jugar con ambos mods.

## Configuración

Forge crea la configuración de caída y reanimación en `serverconfig/persistentbodies-downed.toml`. Los valores por defecto son `reviveHoldSeconds=8` y `downedTimeoutSeconds=150` (0 desactiva el desangrado). La versión anterior usaba `reviveDurationSeconds` y `bleedOutSeconds`; esos nombres antiguos ya no se leen para que una configuración previa no conserve los 150 segundos de mantener click ni el desangrado desactivado.

Incluye, entre otras opciones:

- Activación, vida al caer y tiempo de reanimación.
- Distancia de reanimación, hambre/saturación requeridas y reanimación gratuita en creativo.
- Velocidad de arrastre, remate y tiempo opcional antes de desangrarse.
- Cancelación al recibir daño, comportamiento de mobs e invulnerabilidad después de reanimar.

Las opciones del cuerpo saqueable están en `CorpseConfig`.

## Compilar e instalar

1. Instalar un JDK de 64 bits, versión 17.
2. En Windows, ejecutar `gradlew.bat build`; en Linux/macOS, `./gradlew build`.
3. El mod compilado queda en `build/libs/`.
4. Instalar el mismo jar en el servidor Forge y en cada cliente conectado.

Los tests automáticos de Gradle no incluyen pruebas de Minecraft/Forge. El banco `src/test/sim/run.sh` verifica reglas puras de la lógica y la lista `src/test/manual/TEST_CHECKLIST.md` cubre pruebas manuales; ninguna sustituye una prueba en servidor dedicado.

### Validación pendiente

- Probar en un servidor Forge dedicado la reconexión con poca vida, absorción y efectos; golpear el cuerpo y verificar que la vida y absorción restantes vuelvan correctamente.
- Repetir la recuperación con el cuerpo en un chunk descargado y después de reiniciar el servidor.
- Confirmar visualmente que el inventario Curios mantiene el diseño compacto y muestra los slots vacíos sin salirse del GUI, con y sin Curios/BlockZ.
- Confirmar en juego que la invisibilidad oculta el modelo pero conserva visibles las partículas y permite seleccionar, abrir y saquear el cuerpo.

## Arquitectura

- `entity/PlayerCorpseEntity`, `event/PlayerConnectionHandler` y `storage/CorpseSavedData`: cuerpos, desconexión y recuperación.
- `downed/DownedManager` y `downed/ReviveManager`: estados de caída, reanimación y remate.
- `network/DownedNetwork` y `client/DownedClient`: sincronización, controles y presentación visual.
- `config/CorpseConfig` y `config/DownedConfig`: configuración.
- `compat/CuriosBridge`, `compat/BlockZBridge` y `compat/ExtraSlots`: integraciones opcionales.

## Licencia

MIT; ver [LICENSE](LICENSE).
