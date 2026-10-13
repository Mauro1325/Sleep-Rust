> **Archivo histórico de parches:** las secciones de abajo describen cambios de versiones anteriores y no son documentación vigente. PlayerRevive no es compatible ni está soportado desde la versión 0.2.3; Sleep-Rust no está diseñado para funcionar con ese mod. Ver `README.md` y `DOWNED_SYSTEM.md`.

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


---

# Auditoría y correcciones: muerte offline + PlayerRevive (0.1.16)

## Cambios aplicados

- `CorpseSavedData`: se agregaron `isDeadPending`, `getDeadKiller` y `resolveDead`. La marca de muerte ya no se consume al entrar al servidor; se conserva hasta confirmar que el jugador realmente murió.
- `PlayerConnectionHandler`: si hay una muerte pendiente, no entra en el flujo normal de recuperación del cuerpo/snapshot. Esto evita restaurar la vida o el inventario antiguos mientras se intenta completar la muerte.
- `PlayerConnectionHandler`: si PlayerRevive impide completar la muerte, el servidor conserva la marca y vuelve a intentarlo en el tick del servidor (máximo una vez por segundo). Si el jugador se desconecta, el marcador persistente queda guardado para el siguiente login.
- `PlayerReviveBridge`: distingue entre “el mod está instalado” y “la API se pudo resolver”. Si PlayerRevive está instalado y falla la reflexión, no se ejecuta silenciosamente `player.kill()` como fallback inseguro. Primero intenta la API de muerte definitiva; después marca al jugador como desangrado y recién entonces vuelve a intentar la muerte.
- El mensaje/pantalla “Te mataron durmiendo” se muestra solo después de confirmar la muerte real.
- `build.gradle`: se eliminó el incremento automático de versión al compilar, porque modificaba `gradle.properties` y hacía que las compilaciones no fueran reproducibles. La versión se incrementó manualmente a `0.1.16`.

## Límites y observaciones pendientes

- La compilación no pudo ejecutarse en el entorno de auditoría porque Gradle 8.8 no estaba en caché y el entorno no tenía conectividad para descargar `services.gradle.org`. Por eso estos cambios requieren compilarse localmente con JDK 17.
- La persistencia de Minecraft/Forge no equivale a una transacción fsync por cada operación. El nuevo marcador se conserva hasta confirmar la muerte y reduce la ventana de inconsistencia, pero ningún mod puede garantizar recuperación perfecta ante corte de energía en cualquier instrucción sin un protocolo transaccional más amplio y pruebas específicas.
- La API de PlayerRevive se invoca por reflexión porque el proyecto no tiene una dependencia de compilación obligatoria. Debe probarse con el JAR exacto de PlayerRevive para Forge 1.20.1 que utiliza el servidor.
- Curios y BlockZ no se modificaron funcionalmente, conforme a lo pedido. Se conservan sus integraciones actuales.

## Pruebas obligatorias en un servidor de prueba

1. Jugador A se desconecta con objetos; B mata el cuerpo; A reconecta: A debe morir realmente, ver “Te mataron durmiendo” y reaparecer sin recuperar los objetos que el cuerpo soltó.
2. Repetir con PlayerRevive instalado y sin él.
3. Repetir con `keepInventory=true` y `keepInventory=false`.
4. Reiniciar limpiamente el servidor después de matar el cuerpo y antes de la reconexión.
5. Forzar cierre del servidor después de matar el cuerpo y antes de que A vuelva: comprobar que el marcador se conserva y no se restaura el snapshot antiguo.
6. Probar un jugador que vuelva a desconectarse durante el reintento.
7. Confirmar que una muerte normal mientras el jugador está conectado sigue funcionando y no genera cuerpos duplicados.


---

# Auditoría y correcciones 0.1.17 (muerte offline + PlayerRevive 2.0.31)

Informe completo, tablas de evidencia y límites en **AUDITORIA_0.1.17.md**. Resumen:

- El puente ya no usa el fallback `forceBledOut + player.kill()` (PlayerRevive cancela ese golpe y deja un estado que
  provoca `kill()` en cada tick). Si `PlayerReviveServer.kill(Player)` falla se registra la excepción real, se revierte
  el estado a medias y se reintenta con backoff (1 s .. 60 s, sin límite, sin borrar la marca).
- "Muerte confirmada" ahora exige salud <= 0 **y** que PlayerRevive ya no marque `bleeding` (antes bastaba `!isAlive()`,
  que es verdadero aunque `die()` haya fallado porque `kill()` pone la vida a 0 antes).
- Orden de persistencia: guardar al jugador muerto -> resolver marca -> flush de `SavedData`. La marca se escribe a
  disco en cuanto el cadáver muere.
- Sin cadáver nuevo si el dueño vuelve a salir con la muerte pendiente; `try/catch` en todo el flujo de login.
- Banco de pruebas offline: `src/test/sim/run.sh` (solo JDK). **No sustituye** compilar con Gradle y probar en servidor.
- **No compilado con Forge ni probado en servidor real** (entorno sin red). Compilar con JDK 17 y seguir la lista
  de `src/test/manual/TEST_CHECKLIST.md` (sección PlayerRevive) antes de usarlo con datos valiosos.
