# Patch for missing entity renderer

Added `client/ClientModEvents.java` to register a renderer for `ModEntities.PLAYER_CORPSE` on the Forge MOD event bus and only on the client.
Added `client/PlayerCorpseRenderer.java` as a minimal placeholder so the entity renderer dispatcher no longer encounters a null renderer for this entity.

## Important
This patch targets the crash in `latest.log` caused by a missing `EntityRenderer`. It is not a completed corpse visual implementation: the placeholder renderer does not draw the prone player model, skin, or armor. The project has not been compiled in this environment, so run `gradlew.bat build` with JDK 17 and test in a disposable world with a backup. Do not use valuable items until inventory persistence/recovery is made crash-safe.

---

# Parche: Curios / PlayerRevive / tótem (sin compilar)

1. **Curios en el cuerpo:** `CuriosBridge` ahora llega al inventario de Curios por `CuriosApi.getCuriosInventory`
   (acepta `LazyOptional` y `Optional`) y, si eso falla, por `CuriosCapability.INVENTORY`. Antes, si el método de la API
   no coincidía con la versión instalada, los slots se ignoraban sin avisar. Al desconectarse se escribe en el log una línea
   `Body of <jugador>: Curios loaded=..., N Curios slots listed, M with items, K moved to the body.` para diagnosticar.
   Los slots de Curios se ven en el panel "Accesorios" a la derecha del inventario del cuerpo (ya existía).
2. **PlayerRevive:** nuevo `compat/PlayerReviveBridge`. Al reconectarse tras la muerte offline del cuerpo, el dueño muere
   con `PlayerReviveServer.kill(player)` (reflexión) en vez de `player.kill()`, que PlayerRevive convertía en "caído".
3. **Tótem en Curios:** el cuerpo usa un tótem de cualquier slot de Curios (después de mano principal y secundaria) y
   consume 1 como la mano secundaria. Además el cliente ahora reproduce partículas y sonido del tótem (evento 35), que antes
   no se manejaba.

**Corrección Curios (causa real):** la clase `ICuriosItemHandler` está en `top.theillusivec4.curios.api.type.capability`
(no en `...type.inventory`). Con el nombre equivocado `CuriosBridge.resolve()` fallaba siempre y todos los slots se ignoraban.
Verificado contra el código fuente de Curios 5.14.1+1.20.1: `CuriosApi.getCuriosInventory` devuelve `LazyOptional`,
`getCurios()` devuelve `Map<String, ICurioStacksHandler>` y `getStacks()` devuelve `IDynamicStackHandler` (extiende `IItemHandlerModifiable`).

---

# Parche: inventario del cuerpo y estética del cuerpo dormido (sin compilar)

- **Inventario del cuerpo:** título "Inventario de <jugador>" (sin línea extra). Panel derecho superior "Accesorios" (Curios del cuerpo);
  panel derecho inferior "Tus accesorios" (tus Curios, solo lectura, para comparar). Se agregó tu mano secundaria bajo tu armadura.
  Alto de la GUI 230 -> 246. Los paneles crecen en columnas (máx. 5 filas).
- **Partícula propia:** `registry/ModParticles` (`persistentbodies:sleep_z`) + `client/SleepZParticle`; texturas en
  `textures/particle/sleep_z_{0,1,2}.png` y `particles/sleep_z.json` (z chica -> Z grande según la edad). Sale de la cabeza cada ~1.2 s.
- **Animación:** el cuerpo respira (escala suave) y gira la cabeza lentamente, con leve movimiento de brazos (`PlayerCorpseModel#applySleep`).
- **Nombre:** solo el nombre del jugador, y solo mientras lo mirás.
- **Pantalla de muerte:** si matan el cuerpo offline, al reconectar el dueño ve "Te mataron durmiendo: <asesino>"
  (se guarda el asesino en `CorpseSavedData`, clave `DeadKiller`; se reemplaza la pantalla con un segundo `ClientboundPlayerCombatKillPacket`).
