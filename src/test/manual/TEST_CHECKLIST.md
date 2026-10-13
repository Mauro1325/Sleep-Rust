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
