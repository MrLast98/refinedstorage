package com.raoulvdberge.refinedstorage.item;

import com.raoulvdberge.refinedstorage.RS;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

public abstract class ItemBase extends Item {
    private final String domain;
    private final String name;

    public ItemBase(String name) {
        this(RS.ID, name);
    }

    public ItemBase(String domain, String name) {
        this.domain = domain;
        this.name = name;

        setRegistryName(domain, name);
        setCreativeTab(RS.INSTANCE.tab);
    }

    @Override
    public String getUnlocalizedName() {
        return "item." + domain + ":" + name;
    }

    @Override
    public String getUnlocalizedName(ItemStack stack) {
        if (getHasSubtypes()) {
            return getUnlocalizedName() + "." + stack.getItemDamage();
        }

        return getUnlocalizedName();
    }
}
