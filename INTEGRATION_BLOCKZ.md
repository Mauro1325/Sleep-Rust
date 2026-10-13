# Integración PPB ↔ BlockZ (y slots extra de Curios)

Estado: **código escrito y revisado; NO compilado ni probado** (ver "Estado de verificación").
Todo vive dentro de PPB. BlockZ no se modifica y no es dependencia obligatoria (se usa por reflexión).

## Qué hace

1. **Cuerpo dormido muerto -> cuerpo de BlockZ.** Si matan el cuerpo de un jugador desconectado y BlockZ está
   instalado con `enable_corpse = true`, PPB crea en ese momento un `blockz:corpse` con todos los ítems, en el lugar
   del cuerpo. Ya no quedan ítems tirados ni aparece un cuerpo vacío al reconectar.
2. **Al reconectar, el dueño muere sin generar un segundo cuerpo ni un segundo mensaje de muerte.**
3. **Slots extra dinámicos.** Cada slot de Curios se guarda como `curios:<identificador>:<índice>` (antes: los primeros
   13 por posición, que se corrían si cambiaba el orden de slots). Se guardan también los 4 slots de BlockZ
   (`blockz:0..3` = mochila, chaleco, guantes, máscara). Máximo configurable (`maxExtraSlots`, 48 por defecto, tope 64).
4. **Sin duplicación mochila/chaleco/máscara.** BlockZ guarda una copia de los Curios `back`/`body`/`head` en su
   capability; PPB guarda un solo ejemplar y limpia ambos lados.
5. **Orden seguro al desconectar:** primero se copia, se crea el cuerpo y se registra; recién después se vacía al jugador.
6. **keepInventory:** si el mundo lo tiene activo, el cuerpo muerto no tira ítems; el dueño los recupera al reconectar.
7. **Sincronización al cliente** de mochila/chaleco/guantes/máscara de BlockZ tras restaurarlos.

## Mapeo de slots PPB -> cuerpo de BlockZ

| PPB | BlockZ `CorpseEntity` |
|---|---|
| 0-35 (hotbar + inventario) | +9 (9-17 hotbar, 18+ bolsillos) |
| 36 botas / 37 pantalón / 38 pechera / 39 casco | 5 / 3 / 2 / 4 |
| 40 mano secundaria | 6 |
| `blockz:0` mochila / `:1` chaleco / `:2` guantes / `:3` máscara | 0 / 1 / 8 / 7 |
| accesorios de Curios | desde el slot 45 (como loot suelto) |

## Configuración nueva (`persistentbodies-server.toml`, sección `general`)

- `blockzHandoff` (true): activar la conversión a cuerpo de BlockZ.
- `suppressLoginDeathMessage` (true): ocultar el segundo mensaje de muerte al reconectar.
- `maxExtraSlots` (48, 0-64): tope de slots extra por cuerpo. Lo que excede se queda con el jugador.

## Fallbacks (siempre sin perder ítems)

- BlockZ ausente, `enable_corpse=false`, `blockzHandoff=false` o error al crear el cuerpo -> comportamiento anterior (ítems al suelo).
- Si el jugador conserva algo al reconectar tras su muerte offline -> muerte normal (BlockZ le crea un cuerpo con eso).
- Cuerpos guardados con la versión anterior (Curios por posición) -> se leen como `legacy:n` y se devuelven igual.

## Limitaciones conocidas

- **Skin:** el renderer de BlockZ busca la skin del dueño entre los jugadores conectados. Con el dueño offline el cuerpo
  de BlockZ se ve con skin por defecto. Arreglarlo requiere cambiar BlockZ.
- **Curios cosméticos** no se mueven al cuerpo (solo los slots normales).
- **Mochila/chaleco/máscara** se restauran en la capability de BlockZ; el slot espejo de Curios no se rellena.
- **Crash:** el ítem, el cuerpo y el `SavedData` siguen guardándose por separado; un crash en mal momento puede
  duplicar ítems. No hay journal transaccional (pendiente desde el README original).
- **Cuerpo en el vacío:** si el cuerpo cae al vacío, el cuerpo de BlockZ se crea ahí.

## Estado de verificación

| Nivel | Estado |
|---|---|
| Código inspeccionado | Sí (PPB completo; BlockZ: entidad, muerte, capability, Curios, menú de cuerpos, red) |
| Sintaxis | Chequeada con el parser de javac (sin errores) |
| Compilado contra Forge 1.20.1 | **No** (sin red/JARs en el entorno de desarrollo) |
| Probado en juego / servidor dedicado | **No** |

Nombres de clases y métodos de BlockZ/Curios usados por reflexión: ver comentario de clase en `compat/BlockZBridge.java`
y `curios/CuriosBridge.java`.
