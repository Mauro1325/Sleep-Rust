package com.example.persistentbodies.registry;

import com.example.persistentbodies.PersistentBodies;
import com.example.persistentbodies.menu.CorpseMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, PersistentBodies.MOD_ID);
    public static final RegistryObject<MenuType<CorpseMenu>> CORPSE_MENU = MENUS.register("corpse", () -> IForgeMenuType.create(CorpseMenu::new));
    private ModMenus() { }
}
