package com.refinedmods.refinedstorage.item.blockitem;

import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.NetworkType;
import com.refinedmods.refinedstorage.block.AIControllerBlock;
import com.refinedmods.refinedstorage.util.ColorMap;
import net.minecraft.network.chat.Component;

import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class AIControllerBlockItem extends EnergyBlockItem {
    private final Component displayName;

    public AIControllerBlockItem(AIControllerBlock block, DyeColor color, Component displayName) {
        super(block, new Item.Properties().tab(RS.CREATIVE_MODE_TAB).stacksTo(1), block.getType() == NetworkType.CREATIVE, () -> RS.SERVER_CONFIG.getController().getCapacity());

        if (color != ColorMap.DEFAULT_COLOR) {
            this.displayName = Component.translatable("color.minecraft." + color.getName())
                    .append(" ")
                    .append(displayName);
        } else {
            this.displayName = displayName;
        }

    }

    @Override
    public Component getName(ItemStack stack) {
        return displayName;
    }
}
