# Auditoría histórica 0.1.17 — muerte offline + PlayerRevive 2.0.31

Este informe describe la versión 0.1.17 y no representa el estado de compatibilidad actual. Desde la versión 0.2.3, PlayerRevive no es compatible ni está soportado; Sleep-Rust no está diseñado para funcionar junto con ese mod. Consultá `README.md`.

Estado de verificación (leer primero):

| Qué | Estado |
|---|---|
| Código de PlayerRevive leído | **Sí**: ZIP `PlayerRevive-1.20` con `mod_version=2.0.31`, `creativecore 2.11.20`. Es el código fuente de la rama; **no se pudo inspeccionar el JAR instalado** (no fue entregado). |
| Compilación con Gradle/ForgeGradle (MC 1.20.1, Forge 47.4.10) | **NO realizada**: el entorno no tiene red (no puede bajar Forge/MCP/Gradle 8.8) y no hay JDK 17 con Gradle en caché. **No se generó JAR.** |
| Pruebas en servidor Forge real con el JAR real de PlayerRevive | **NO realizadas.** |
| Lo que sí se ejecutó | `PlayerReviveBridge` y `DeathRetryPolicy` reales compilados con `javac` contra stubs mínimos de Minecraft y una **réplica** (escrita a mano, copiando el orden de sentencias de PlayerRevive 2.0.31) de `kill()`, `Bleeding` y los manejadores de muerte/daño. 45 aserciones, todas OK (`src/test/sim/run.sh`). Es una simulación, no el juego. |
| Sintaxis del resto de ficheros modificados | Revisada con `javac` (solo errores por dependencias ausentes) y a mano. Los puntos de API de Minecraft nuevos son 2: `PlayerList#saveAll()` y `DimensionDataStorage#save()`; hay que confirmarlos al compilar. |

## 1. Diagnóstico de la causa raíz

**Causa original (demostrada por lectura de código y reproducida en la réplica, test T1):**
`PlayerConnectionHandler` mataba al dueño con `player.kill()`. `ReviveEventServer.playerDied` (prioridad HIGHEST) intercepta `LivingDeathEvent` de cualquier jugador cuya fuente no esté en `bypassDamageSources`, llama a `startBleeding`, **cancela el evento** y deja `setHealth(bleedingHealth=10)`. Resultado: el jugador queda vivo, derribado, en el sitio. Ni "poner la vida a 0" ni `player.kill()` sirven.

**API correcta (verificada en el código 2.0.31):** `team.creative.playerrevive.server.PlayerReviveServer#kill(Player)` existe, es `public static void`, y ejecuta en este orden: evento `PlayerBleedOutEvent` → `setHealth(0)` → `forceBledOut()` → `player.die(fuente bled_to_death)` → `resetPlayer` → `revive.revive()`. Su propio manejador deja pasar esa muerte (la fuente `bled_to_death` está en `doesByPass` y además `bledOut()` es true). La versión 0.1.16 ya la llamaba por reflexión con la firma correcta (`getMethod("kill", Player.class)`): **esa parte es correcta**.

**Lo que 0.1.16 seguía haciendo mal (defectos confirmados):**

| # | Sev. | Defecto | Evidencia |
|---|---|---|---|
| C1 | Crítico | "Muerte confirmada" = `!player.isAlive()`. Pero `kill()` hace `setHealth(0)` **antes** de `die()`; si `die()` lanza o falla, el jugador queda con 0 de vida, sin muerte procesada, PlayerRevive aún `bleeding=true`, y la marca se **borraba**. | `PlayerReviveServer.java` l.87-89; test T4 (el puente viejo devuelve `true` con `deaths=0`). |
| C2 | Crítico | Fallback "marcar `forceBledOut` y luego `player.kill()`": una vez `bledOut()`, `playerDamage` **cancela** todo golpe (`ReviveEventServer` l.145-146), así que `player.kill()` no hace nada; y deja `bleeding=true, timeLeft=0`, lo que hace que el tick de PlayerRevive llame a `kill()` en **cada tick** (`playerTick` l.76-79) desde su propio manejador. Si `kill()` es lo que falla, el error se lanza dentro del tick de PlayerRevive. | Test T5. |
| C3 | Funcional | Todo el flujo corría sin `try/catch` dentro de `PlayerLoggedInEvent`; cualquier excepción (Curios/BlockZ/PlayerRevive) escapa del evento. | `onLogin` → `tryFinishOfflineDeath` → `carriesNothing`/`CuriosBridge`. |
| C4 | Funcional | Si el dueño volvía a desconectarse mientras la muerte seguía pendiente, `onLogout` creaba **otro cadáver** (el registro ya se había borrado en `die()`). | `onLogout` no consultaba la marca. |
| C5 | Estabilidad | Los reintentos dependían de un mapa en memoria rellenado solo en `onLogin`, a cadencia fija de 1 s sin límite de log (stack trace por intento). | `onServerTick`. |
| C6 | Persistencia | Marca y datos del jugador se guardan en autosaves distintos (jugadores primero, `SavedData` después). Tras el respawn, un cierre brusco antes del autosave de `SavedData` dejaba la marca pendiente → **segunda muerte inmerecida** al reconectar. Forzar solo el flush de la marca invertiría el riesgo (muerte perdida), por eso el orden nuevo es: guardar jugador muerto → resolver marca → flush. | Lectura de `MinecraftServer` autosave / `PlayerList`. No reproducido en vivo. |

**Riesgos de la lista del encargo, descartados o confirmados:**
1. Marca eliminada antes de completar la muerte — *parcialmente real* (C1: se eliminaba con un criterio de éxito falso).
2. Cadáver desaparece y el jugador sigue vivo — causa original (arriba).
3. Firma incorrecta — **descartado** (`kill(Player)` verificada).
4. Excepción sin registrar — el puente ya registraba; faltaba el `try/catch` global (C3) y desenvolver `InvocationTargetException`.
5. Login restaura antes de procesar la muerte — **descartado** (la marca se evalúa antes de cualquier recuperación y hace `return`).
6. Muerte sobre jugador desconectado — **descartado**: offline solo se guarda una marca; la muerte corre con la entidad conectada en el login.
7. Misma muerte varias veces — idempotente dentro de un proceso (`alreadyDead`, marca = `Set`); solo reaparecía por C6.
8. Copia antigua de inventario — **descartado** para el camino con marca; con `keepInventory=true` se restaura el snapshot a propósito y luego muere.
9. Datos de jugador y cadáver en momentos incompatibles — C6.
10. Cliente ≠ servidor — **no verificable aquí**. PlayerRevive envía `ReviveUpdatePacket` al final de `kill()` y el mod envía su `ClientboundPlayerCombatKillPacket`.

**Hallazgo útil sobre PlayerRevive 2.0.31:** el `Bleeding` es una capability **sin** `ICapabilitySerializable` (no se guarda en el NBT del jugador); `playerrevive:bleeding` en `getPersistentData` solo se escribe (l.48/57) y nada lo lee. Es decir, PlayerRevive **no restaura el derribo al volver a entrar**; al desconectarse un jugador derribado lo mata (`playerLeave`, HIGHEST), por lo que no se crea cadáver (`onLogout` ve `isDeadOrDying()`).

## 2. Cambios (archivos y métodos)

- `compat/PlayerReviveBridge.java` (reescrito). Resuelve `kill`, `getBleeding`, `isBleeding`, `revive`. `kill(ServerPlayer)` devuelve `true` solo si salud ≤ 0 **y** PlayerRevive ya no marca `bleeding`. Elimina el fallback C2. Si falla: registra la excepción real (desenvuelta), revierte el estado a medias solo si no existía antes del intento y devuelve `false`. Nuevo `isBleeding(ServerPlayer)`.
- `logic/DeathRetryPolicy.java` (nuevo, Java puro). Backoff 1 s → 2 → 4 … tope 60 s, **sin límite de reintentos** y sin borrar la marca.
- `event/PlayerConnectionHandler.java`: `onLogout` no crea cadáver con muerte pendiente (C4); `attemptPendingDeath` (con `try/catch`, C3); `tryFinishOfflineDeath` con el orden de persistencia de C6; `processPendingDeaths` recorre las marcas de `SavedData` (fuente única de verdad) en vez de un mapa en memoria (C5); `dieAfterOfflineDeath` usa el resultado confirmado del puente.
- `storage/CorpseSavedData.java`: `pendingDeadOwners()` y `flush(ServerLevel)`. **Formato NBT sin cambios.**
- `entity/PlayerCorpseEntity.java#die()`: flush de la marca al registrarla (antes de `discard()`), logs, y el caso "dueño conectado y derribado por PlayerRevive cuyo cadáver se destruye" (ver límites).
- `gradle.properties`: `mod_version=0.1.17`.
- **Sin cambios**: `CuriosBridge`, `ExtraSlots`, `BlockZBridge`, `CorpseMenu`, formato de inventario, y todo lo de Curios/BlockZ.

## 3. Pruebas

Ejecutadas (simulación, `src/test/sim/run.sh`): T1 reproduce el bug original; T2 muerte normal; T3 doble intento idempotente; T4 fallo dentro de `die()` (el puente viejo da falso éxito, el nuevo no, y el reintento posterior funciona); T5 fallo previo (el viejo deja `bleeding=true,timeLeft=0`, el nuevo no toca nada); T6 rollback; T7 un derribado legítimo no se revierte; T8 dueño derribado muere; API ausente (firma distinta) → `false`, sin lanzar y sin `player.kill()`; política de reintentos.

**No ejecutadas** (requieren servidor real): las 12 pruebas del encargo en juego (derribado conectado/desconectado, reconexión, reinicio, caída del servidor, retirada de objetos por otro jugador, Curios, BlockZ). Siguen en `src/test/manual/TEST_CHECKLIST.md`.

## 4. Límites conocidos

- Sin compilar ni probar en Forge; los 2 puntos de API nuevos (`saveAll`, `DimensionDataStorage#save`) y el resto del cambio deben compilarse localmente con JDK 17 (`gradlew build`).
- El código revisado es la rama fuente 2.0.31; confirmar que coincide con el JAR instalado.
- El flush es escritura de archivo (sobrevive a un `kill -9`, no a un corte de energía). Una caída justo entre guardar jugadores y `SavedData` sigue teniendo ventanas mínimas; ningún mod puede evitarlas sin un protocolo transaccional.
- Si otro mod cancela `LivingDeathEvent` del dueño durante la muerte, `die()` no procesa la muerte; el puente lo detecta por `bleeding`, pero un mod que cancele sin tocar PlayerRevive no se puede distinguir desde aquí.
- Caso "dueño conectado y derribado": implementado de forma estrecha (solo si PlayerRevive lo marca como derribado). **Decisión pendiente tuya**: matar a un dueño conectado *no derribado* al destruir un cadáver sobrante no se hace (cambiaría el diseño actual: ese cadáver es solo un contenedor). En ese caso la pantalla muestra "Te mataron durmiendo", texto pensado para el caso offline.
- Cliente: no verificado que la pantalla de muerte aparezca al reconectar en todos los casos.
