package com.hideandseek;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * Temporary game equipment. Every item is tagged with a custom-data marker so the mod can remove exactly its own
 * items later and never touches a player's unrelated belongings.
 */
final class Equipment {
	private static final String PEARL_TAG = "hideandseek_pearl";
	private static final String SEEKER_TAG = "hideandseek_seeker_item";

	private Equipment() {
	}

	private static ItemStack mark(ItemStack stack, String tag) {
		CompoundTag data = new CompoundTag();
		data.putBoolean(tag, true);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
		return stack;
	}

	private static boolean isMarked(ItemStack stack, String tag) {
		if (stack.isEmpty()) {
			return false;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.copyTag().contains(tag);
	}

	private static boolean give(ServerPlayer player, ItemStack stack) {
		return player.getInventory().add(stack);
	}

	/** Exactly one Ender Pearl (a stack of 1). */
	static boolean givePearl(ServerPlayer player) {
		return give(player, mark(new ItemStack(Items.ENDER_PEARL), PEARL_TAG));
	}

	/**
	 * Seeker kit: Bow (Infinity only), ONE normal Arrow, Stone Sword (Sharpness IV only).
	 * The normal arrow applies a mod-managed 5-second glowing effect on hit.
	 */
	static boolean giveSeekerKit(MinecraftServer server, ServerPlayer player) {
		var enchantments = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
		Holder<Enchantment> infinity = enchantments.getOrThrow(Enchantments.INFINITY);
		Holder<Enchantment> sharpness = enchantments.getOrThrow(Enchantments.SHARPNESS);

		ItemStack bow = new ItemStack(Items.BOW);
		bow.enchant(infinity, 1);
		ItemStack arrow = new ItemStack(Items.ARROW);
		ItemStack sword = new ItemStack(Items.STONE_SWORD);
		sword.enchant(sharpness, 4);

		boolean ok = give(player, mark(bow, SEEKER_TAG));
		ok &= give(player, mark(arrow, SEEKER_TAG));
		ok &= give(player, mark(sword, SEEKER_TAG));
		return ok;
	}

	/** Removes this mod's items (seeker kit always; the pearl too when {@code includePearl}). */
	static void remove(ServerPlayer player, boolean includePearl) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (isMarked(stack, SEEKER_TAG) || (includePearl && isMarked(stack, PEARL_TAG))) {
				inv.setItem(i, ItemStack.EMPTY);
			}
		}
	}
}
