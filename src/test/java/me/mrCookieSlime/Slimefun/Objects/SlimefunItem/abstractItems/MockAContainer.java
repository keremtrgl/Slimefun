package me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems;

import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;

class MockAContainer extends AContainer {

    MockAContainer(ItemGroup itemGroup, SlimefunItemStack item, RecipeType recipeType, ItemStack[] recipe) {
        super(itemGroup, item, recipeType, recipe);
    }

    @Override
    public ItemStack getProgressBar() {
        return new ItemStack(org.bukkit.Material.ARROW);
    }

    @Override
    public @javax.annotation.Nonnull String getMachineIdentifier() {
        return "MOCK_ACONTAINER";
    }

    void tickForTesting(Block b) {
        tick(b);
    }
}
