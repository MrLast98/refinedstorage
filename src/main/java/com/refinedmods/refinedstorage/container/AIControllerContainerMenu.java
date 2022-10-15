package com.refinedmods.refinedstorage.container;

import com.refinedmods.refinedstorage.RSContainerMenus;
import com.refinedmods.refinedstorage.blockentity.AIControllerBlockEntity;
import net.minecraft.world.entity.player.Player;

public class AIControllerContainerMenu extends BaseContainerMenu {
    public AIControllerContainerMenu(AIControllerBlockEntity controller, Player player, int windowId) {
        super(RSContainerMenus.AI_CONTROLLER.get(), controller, player, windowId);

        addPlayerInventory(8, 99);
    }
}
