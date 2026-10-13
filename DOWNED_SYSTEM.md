# Sistema de caída y reanimación

La sección inicial describe el comportamiento actual; las notas 0.2.0 y 0.2.1 de abajo son historial.

**PlayerRevive no es compatible ni está soportado; Sleep-Rust no está diseñado para funcionar junto con ese mod.** El código conserva un manejo interno limitado para completar ciertas muertes offline si detecta PlayerRevive, pero esto no constituye compatibilidad: la caída y reanimación normales no están integradas ni garantizadas. Para una partida soportada, quitá PlayerRevive del servidor y de los clientes. Las muertes pendientes de cuerpos conservan el atacante o la causa ambiental.

## Comportamiento actual

- Mantener click derecho durante **8 segundos** para completar la reanimación (`reviveHoldSeconds`).
- El jugador tiene **150 segundos (2:30)** desde que cae para ser reanimado; luego muere (`downedTimeoutSeconds`). El valor `0` desactiva el límite.
- Las claves anteriores `reviveDurationSeconds` y `bleedOutSeconds` se reemplazaron. Los TOML viejos adoptan los valores nuevos en lugar de conservar 150 segundos de click o el desangrado desactivado.
- La muerte pendiente causada por la destrucción del cuerpo ahora usa una protección explícita contra la transición a caído; no depende de que el daño genérico tenga el tag vanilla de bypass.

## Estados
- **Vivo**: normal.
- **Caído**: daño letal (no `/kill`, no vacío) -> la muerte se cancela, vida = `downedHealth` (1.0), pose tumbada, inmovilizado, invulnerable, sin regeneración, sin acciones. Solo en memoria del servidor (nunca se escribe a disco).
- **Muerto**: muerte vanilla (reglas de BlockZ / Curios / keepInventory sin cambios).

Los tótems siguen teniendo prioridad (se consumen antes de que exista la caída).

## Reanimación
Mantener **R** (configurable en Controles) cerca de un caído. Cliente: latido cada 5 ticks. Servidor: revalida cada tick (reanimador vivo y no caído, objetivo conectado y caído, misma dimensión, distancia, recursos, latido <15 ticks, un solo reanimador por objetivo). Al completar: sesión cerrada -> `revive()` (vida exactamente **4.0**, 2 s de invulnerabilidad configurable) -> cobro. Cancelar no cobra.

Coste (`costMode`): `HUNGER` (puntos de hambre), `SATURATION` (puntos de saturación) o `BOTH`. Regla determinista en `logic/ReviveCostRule`: exige tener el coste completo de cada recurso, cobra todo o nada, valores acotados (hambre 0..20, saturación 0..hambre, costes negativos = 0). Nota: la saturación vanilla suele ser 0 en un jugador hambriento, por eso el modo por defecto es `HUNGER`.

## Desconexión
- **Caído que se desconecta** (`DownedManager.handleLogout`, llamado primero desde `PlayerConnectionHandler.onLogout`): se cancela cualquier reanimación, se quita el estado caído (idempotente) y se mata con daño genérico -> muerte vanilla igual que conectado. **No** se crea `PlayerCorpseEntity`. No depende de paquetes al cliente. Se guarda la marca `DownedLogout` (en `CorpseSavedData`, etiqueta NBT opcional) y al volver el jugador se respawnea automáticamente.
- **Vivo que se desconecta**: sin cambios (cuerpo dormido, saqueo, snapshot).
- Parar/reiniciar el servidor desconecta a todos: un caído muere según lo anterior. Tras una caída brusca del servidor (crash) no hay marca: el jugador vuelve vivo con la vida que tuviera, nunca bloqueado.

## Archivos
Nuevos: `downed/{DownedManager,ReviveManager,DownedEvents}`, `network/DownedNetwork`, `client/DownedClient`, `config/DownedConfig`, `logic/{DownedRegistry,ReviveCostRule}`.
Modificados: `PersistentBodies` (registro), `PlayerConnectionHandler` (hooks de login/logout; se quitó la rama PlayerRevive de `dieAfterOfflineDeath`), `PlayerCorpseEntity#die` (usa `DownedManager.isDowned`), `CorpseSavedData` (marca `DownedLogout`), `ClientModEvents` (tecla), `DeathRetryPolicy` (comentario), `mods.toml`, lang es/en, tests y docs.
Se quitó la dependencia declarada de `playerrevive` en `mods.toml`. El puente de muerte offline que aún existe en el código no es una integración soportada; no usar PlayerRevive como mod compatible.

## Configuración (`persistentbodies-downed.toml`)
`enabled`, `downedHealth`, `reviveHoldSeconds` (8 s por defecto), `downedTimeoutSeconds` (150 s por defecto; 0 = nunca), `reviveDistance`, `costMode`, `hungerCost`, `saturationCost`, `creativeReviverIsFree`, `cancelOnReviverDamage`, `mobsIgnoreDowned`, `reviveInvulnerabilityTicks`, `announce`.

## Verificación
- Ejecutado: `src/test/sim/run.sh` (solo JDK): 46 aserciones sobre la lógica pura (máquina de estados, un reanimador por objetivo, completar una sola vez, regla de coste, política de reintentos). Todas OK.
- Ejecutado: revisión de sintaxis de todo `src/main` con `javac` sin librerías (sin errores de sintaxis; los únicos errores son símbolos de Minecraft ausentes).
- **NO ejecutado**: `gradlew build` (sin red, sin Gradle ni JDK 17), y ninguna prueba en juego. Hay que compilar con JDK 17 y seguir `src/test/manual/TEST_CHECKLIST.md`.
- APIs a confirmar al compilar (escritas de memoria, sin fuentes a mano): `LivingChangeTargetEvent#setNewTarget`, `EntityItemPickupEvent`, `ItemTossEvent#getPlayer`, `MovementInputUpdateEvent#getInput` (campos de `Input`), `ScreenEvent.Opening`, `RenderGuiEvent.Post#getGuiGraphics`, `PlayerList#respawn(ServerPlayer, boolean)`, `FoodData#setSaturation`.

---

# Historial: cambios 0.2.1

## Arreglos
- **Cámara que saltaba al estar caído:** el servidor fuerza la pose "tumbado" cada tick, pero el cliente recalcula la pose de todos los jugadores cada tick (de pie / agachado) y las dos se pisaban, cambiando la altura de los ojos. Ahora `DownedClient` reafirma la pose tumbada al final de cada tick (`ClientTickEvent` y `PlayerTickEvent` del cliente) para todos los caídos.
- **Muerte doble al reconectar tras una muerte offline:** si algo fallaba *después* de matar al jugador al reconectar (p. ej. `saveAll()` con un jugador muerto), el marcador de muerte pendiente quedaba y el reintento (cada 1 s) mataba al jugador **ya respawneado**. Ahora: (1) el marcador solo puede aplicarse a la instancia de `ServerPlayer` que inició sesión (`loginInstance`); si ya respawneó, el marcador se descarta sin matar; (2) en cuanto la muerte está confirmada el marcador se consume y los pasos siguientes (guardado, pantalla de muerte) son best-effort y aislados.

## Mecánicas nuevas (histórico 0.2.1)
- **Reanimar:** mantener **click derecho** mirando la hitbox del caído. Duración `reviveDurationSeconds` (por defecto **150 s = 2 min 30 s**). El servidor comprueba cada tick con un rayo que la mirada del reanimador toca la hitbox del caído (sin bloques en medio); se tolera 0,5 s sin mirar. Soltar el click, alejarse, recibir daño, etc. cancelan como antes. El click derecho no usa el ítem en mano mientras se reanima o se apunta a un caído.
- **Animación de reanimar:** el reanimador se agacha, mueve los brazos alternadamente y salen partículas sobre el caído; al completar, corazones + sonido.
- **Rematar:** click izquierdo sobre un caído lo mata (`allowFinishOff`, requiere PvP activo). Muerte real con el atacante como causante; un tótem no lo salva. No pasa por la caída otra vez (`DownedManager.finish`).
- **Caído = arrastre lento y borroso:** ya no está clavado; se arrastra a `crawlSpeedPercent` % (35 por defecto, modificador de atributo sincronizado con el cliente). Sin salto, sin sprint. Su pantalla se ve borrosa (post-efecto `shaders/post/downed_blur.json`, usa el programa `blur` de vanilla) y con un tinte rojo.
- Se quitó la tecla R. El protocolo de red pasó a la versión "2" (cliente y servidor deben tener 0.2.1).

## Config nueva / cambiada en 0.2.1 (`persistentbodies-downed.toml`)
`reviveDurationSeconds` (reemplaza a `reviveDurationTenths`), `crawlSpeedPercent`, `allowFinishOff`. Como el nombre de la clave cambió, el valor por defecto de 150 s aplica aunque el archivo viejo ya exista.

## Sin verificar
Igual que 0.2.0: no compilado con Forge ni probado en servidor. La lógica pura sí pasa `src/test/sim/run.sh`. Lo de Minecraft/Forge (pose, blur, rayo de mirada, remate) hay que probarlo con `src/test/manual/TEST_CHECKLIST.md` (sección 0.2.1).

## Correcciones actuales

- La duración de mantener click derecho es **8 s** (`reviveHoldSeconds`); el límite para rescatar al jugador es **150 s / 2:30** (`downedTimeoutSeconds`).
- Se cambiaron los nombres anteriores `reviveDurationSeconds` y `bleedOutSeconds` para que las configuraciones existentes adopten los valores predeterminados nuevos.
- La muerte pendiente por un cuerpo destruido ahora protege explícitamente el evento de muerte contra el sistema de caída; así el jugador debe morir al reconectar, en vez de volver a quedar caído.
