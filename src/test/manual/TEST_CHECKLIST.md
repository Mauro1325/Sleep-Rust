# Manual test checklist

- [ ] Disconnect with full inventory, hotbar, armor and offhand.
- [ ] Loot one item from a second account.
- [ ] Reconnect owner; looted item must not return.
- [ ] Remaining items return without overwriting occupied slots or duplicating.
- [ ] Restart server while a body exists.
- [ ] Simultaneous looters cannot duplicate stacks.
- [ ] Repeated reconnects do not duplicate items.
- [ ] Body in unloaded chunk and in another dimension.
- [ ] Normal death while online does not create a disconnect body.
- [ ] Enchantments, durability, custom names and container items retain NBT.
- [ ] Crash during logout/recovery does not lose or duplicate items.

- [ ] Hit the body falling from a jump (fully charged, not sprinting): critical particles and x1.5 damage.
- [ ] Body with a totem in the hotbar slot that was selected at logout, or in the offhand: it pops, health 1, totem consumed. Owner logs in with those effects.
- [ ] Body with the totem in a non-selected hotbar slot does NOT pop.
- [ ] Body in water: loses air, then 2 damage/second; Respiration helmet slows it; death message is the drowning one.
- [ ] Disconnect with invisibility: the body model is hidden but sleep particles remain visible; it can still be targeted, opened and looted until the effect expires.
- [ ] With Curios installed, the body and looter panels keep the compact layout and show empty as well as occupied slots; populated body slots remain lootable and restore to their matching slots.
- [ ] Disconnect with low health, absorption hearts and active potion effects: the body starts with the same state; damage it (including a hit absorbed entirely by absorption), reconnect, and verify the player's health/absorption match the remaining body state and the effects are restored.
- [ ] Repeat body recovery after its chunk unloads or a server restart to verify health, absorption and active effects come from the persisted snapshot.
- [ ] Owner logs out at base, body is moved/owner is moved with /tp while offline (also to another dimension): on login the owner appears at the body with all items and health.
- [ ] Same as above with the body's chunk unloaded (nobody nearby, restart the server in between).
- [ ] Delete the body with /kill @e[type=persistentbodies:player_corpse] (or remove it from the chunk): on login the owner gets items from the backup snapshot, and the entity never duplicates them.
- [ ] Body wearing armor + offhand: go far away until the chunk unloads (or restart the server), reconnect: armor returns to the armor slots, hotbar layout intact.
- [ ] Body standing in a cactus: 1 damage every ~0.5 s, not every tick.
- [ ] Skin (arms, sleeves, hat, jacket, pants) looks aligned with the torso, slim and wide models.

The unloaded-chunk, cross-dimension, crash-recovery and concurrent-looting cases are not guaranteed by this prototype and are release blockers.

## Integración BlockZ / slots extra (nuevo)

- [ ] Con BlockZ: matar el cuerpo dormido -> aparece UN cuerpo de BlockZ en el lugar del cuerpo, con inventario, armadura, offhand, mochila, chaleco, guantes y máscara. No quedan ítems en el suelo.
- [ ] Reconectar al dueño: muere, sin segundo cuerpo, sin mensaje de muerte duplicado, y reaparece con inventario vacío.
- [ ] El cuerpo de BlockZ se puede abrir y lootear completo (incluidos los accesorios de Curios que aparecen como loot suelto desde el slot 45).
- [ ] Mochila con contenido (NBT anidado) y chaleco con contenido: el contenido llega intacto al cuerpo de BlockZ.
- [ ] Sin BlockZ: matar el cuerpo dormido -> los ítems caen al suelo como antes.
- [ ] `enable_corpse=false` en BlockZ: ítems al suelo (no cuerpo de BlockZ).
- [ ] Desconectar con Curios (anillos, collar, etc.): todos los slots se guardan; reconectar -> cada ítem vuelve a su slot exacto.
- [ ] Cambiar el orden/cantidad de slots de Curios entre desconexión y reconexión: los ítems siguen yendo a su identificador correcto.
- [ ] Mochila/chaleco/máscara con Curios + BlockZ: tras desconectar NO existen duplicados (ni en el jugador ni en el cuerpo).
- [ ] Reconectar con vida: mochila/chaleco/guantes/máscara vuelven y se ven en el cliente sin relog.
- [ ] Cuerpo con más slots extra que `maxExtraSlots`: los que no entran se quedan con el jugador, ninguno se pierde.
- [ ] `keepInventory=true`: matar el cuerpo dormido no tira ítems; el dueño los recupera y muere conservándolos.
- [ ] Cuerpo guardado con la versión anterior (Curios por posición) todavía se puede lootear y devolver.
- [ ] Reiniciar el servidor entre la desconexión y la muerte del cuerpo, y entre la muerte y la reconexión.
- [ ] Matar el cuerpo con el chunk descargado / en otra dimensión.
- [ ] Forzar cierre del servidor justo tras matar el cuerpo: revisar que no haya ítems duplicados (limitación conocida).


## Muerte offline del cuerpo (bloqueante para release)

- [ ] Con el sistema de caída activado, matar el cuerpo de un jugador offline -> al reconectar el dueño muere de verdad (sin volver a quedar caído) y ve "Te mataron durmiendo".
- [ ] No instalar PlayerRevive para estas pruebas: no es compatible ni está soportado por Sleep-Rust.
- [ ] Matar el cuerpo con un jugador atacante y comprobar que la pantalla dice quién fue; repetir con lava/caída y comprobar que muestra la causa.
- [ ] La marca de muerte pendiente sobrevive a un reinicio / cierre forzado del servidor y se procesa en el siguiente login.
- [ ] Si el jugador se desconecta durante los reintentos, la marca sobrevive y se procesa en el siguiente login.

## Jugador caído y reanimación (sistema propio, sin PlayerRevive)

Preparación: servidor Forge 1.20.1 (47.4.10) SIN el JAR de PlayerRevive, 2 jugadores (A, B), modo supervivencia.
Ajustes útiles en `serverconfig/persistentbodies-downed.toml`: `reviveHoldSeconds` (8 por defecto), `downedTimeoutSeconds` (150 por defecto; 0 = nunca), `reviveDistance`, `costMode`, `hungerCost`, `saturationCost`.

1. [ ] A recibe daño letal (mob/caída) -> queda caído, tendido, sin morir; B lo ve tumbado.
2. [ ] Caído: no camina, no salta, no ataca, no usa objetos, no abre inventario, no tira ni recoge objetos, no recibe más daño, no regenera.
3. [ ] B mantiene click derecho mirando a A durante 8 s -> A vuelve con EXACTAMENTE 4.0 de vida (2 corazones), se mueve al instante, conserva inventario/XP/equipo, sin cuerpo nuevo ni duplicados.
4. [ ] `costMode=HUNGER`: B pierde `hungerCost` de hambre al COMPLETAR (no al empezar). Repetir con `SATURATION` y `BOTH`.
5. [ ] B suelta click derecho a mitad / se aleja / recibe daño -> reanimación cancelada y B no pierde hambre ni saturación.
6. [ ] B sin recursos suficientes (hambre o saturación según el modo) -> mensaje y no puede iniciar/completar.
7. [ ] Un jugador caído no puede reanimarse a sí mismo (el click derecho no hace nada sobre uno mismo; paquete forjado rechazado por el servidor).
8. [ ] No se puede reanimar a un jugador desconectado.
9. [ ] A caído se DESCONECTA -> muere de verdad (se ve en el log "disconnected while downed"), sus objetos siguen las reglas de muerte normal (BlockZ / drops / keepInventory), NO aparece un cuerpo dormido de persistentbodies.
10. [ ] A reconecta tras (9): reaparece (respawn automático), no está caído ni bloqueado, no recupera objetos.
11. [ ] A VIVO se desconecta -> cuerpo dormido como antes; B lo saquea; al volver A no recupera lo saqueado.
12. [ ] B y C intentan reanimar a A a la vez -> solo uno lo consigue (el otro recibe "ya lo están reanimando"); un solo cobro.
13. [ ] Reiniciar el servidor con A caído -> A muere al desconectarse el servidor; al volver reaparece sin quedar bloqueado.
14. [ ] B se desconecta o muere a mitad de la reanimación -> A sigue caído, sin coste cobrado. A muere a mitad (/kill) -> sesión cancelada.
15. [ ] `downedTimeoutSeconds=150`: A muere a los 2:30 si nadie lo reanima; con 0 A sigue caído indefinidamente.
16. [ ] /kill y el vacío sobre un caído lo matan de verdad. /kill sobre un vivo lo mata sin pasar por caído.
17. [ ] Servidor dedicado, partida alojada (LAN) y 3+ jugadores: el HUD, la pose y el bloqueo de entrada se ven bien en todos los clientes.
18. [ ] El JAR compila y el servidor arranca sin ningún JAR de PlayerRevive en `mods/`.

## 0.2.1 (A y B en supervivencia)
1. [ ] A caído, en su pantalla: la cámara ya NO salta; la pantalla se ve borrosa y rojiza.
2. [ ] A caído se arrastra muy lento (≈35 %); no puede saltar ni correr.
3. [ ] B mirando a A y con click derecho sostenido: barra y tiempo restante; B se agacha, mueve brazos, partículas sobre A. B con comida/arco en mano: no se usa el ítem.
4. [ ] B mira a otro lado >0,5 s o suelta el click: se cancela. B mira de nuevo: empieza de cero.
5. [ ] B detrás de una pared / sin mirar la hitbox: no avanza.
6. [ ] Completar tras 8 s (`reviveHoldSeconds`; bajar el valor para probar): A se levanta con 2 corazones, B paga el coste. A muere a los 2:30 si no lo reaniman (`downedTimeoutSeconds`).
7. [ ] B con click izquierdo sobre A caído: A muere (mensaje "A fue asesinado por B"); con `allowFinishOff=false` o PvP off no pasa nada. Tótem en la mano de A: no lo salva.
8. [ ] Muerte offline: A se desconecta, B mata el cuerpo, A reconecta (muere), pulsa Respawn: A NO vuelve a morir (esperar >10 s). Repetir provocando un fallo al guardar si es posible.
