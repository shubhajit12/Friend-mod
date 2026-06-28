package com.friendmod;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * FriendInventory v3.1
 * ====================
 * Gives Friend the inventory habits a real player has, automatically:
 *
 *   - Best armor in each slot gets worn (compares protection value).
 *   - Best weapon in hotbar gets selected for fighting.
 *   - Best tool gets selected for the block currently being mined.
 *   - Eats food automatically when hunger drops, without being told to.
 *
 * Mirrors and feeds the same item-name summary the AI sees, so what she
 * says ("I've got a diamond sword now!") matches what she's actually
 * carrying.
 */
public class FriendInventory {

    private static final int HUNGRY_THRESHOLD = 14; // out of 20 — eat before it gets critical
    private int eatCooldown = 0;

    /** Equip the best armor piece available in each armor slot. */
    public void autoEquipArmor(EntityPlayerMPFake fp) {
        if (fp == null) return;
        var inv = fp.getInventory();

        for (EquipmentSlot slot : new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
            ItemStack worn = fp.getEquippedStack(slot);
            int wornValue = armorValue(worn, slot);
            int bestSlotIndex = -1;
            int bestValue = wornValue;

            for (int i = 0; i < inv.size(); i++) {
                ItemStack candidate = inv.getStack(i);
                if (candidate.isEmpty() || !(candidate.getItem() instanceof ArmorItem armor)) continue;
                if (armor.getSlotType() != slot) continue;

                int value = armorValue(candidate, slot);
                if (value > bestValue) {
                    bestValue = value;
                    bestSlotIndex = i;
                }
            }

            if (bestSlotIndex >= 0) {
                ItemStack best = inv.getStack(bestSlotIndex);
                ItemStack toEquip = best.copy();
                inv.setStack(bestSlotIndex, worn.isEmpty() ? ItemStack.EMPTY : worn.copy());
                fp.equipStack(slot, toEquip);
            }
        }
    }

    private int armorValue(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem armor)) return 0;
        // Rough tier ranking by material name since ArmorMaterial protection getters
        // vary across versions — name-matching is stable across 1.21.x.
        String name = armor.toString().toLowerCase();
        if (name.contains("netherite")) return 5;
        if (name.contains("diamond"))   return 4;
        if (name.contains("iron"))      return 3;
        if (name.contains("chainmail")) return 2;
        if (name.contains("golden") || name.contains("gold")) return 2;
        if (name.contains("leather"))   return 1;
        return 1;
    }

    /** Select the best weapon in the hotbar (for fighting). */
    public void autoEquipWeapon(EntityPlayerMPFake fp) {
        if (fp == null) return;
        var inv = fp.getInventory();
        int best = -1;
        int bestRank = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            int rank = weaponRank(stack);
            if (rank > bestRank) {
                bestRank = rank;
                best = i;
            }
        }
        if (best >= 0 && bestRank > 0) {
            inv.selectedSlot = best;
        }
    }

    private int weaponRank(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        String name = stack.getItem().toString().toLowerCase();
        if (!(stack.getItem() instanceof SwordItem) && !(stack.getItem() instanceof AxeItem)) return 0;
        int materialRank =
            name.contains("netherite") ? 5 :
            name.contains("diamond")   ? 4 :
            name.contains("iron")      ? 3 :
            name.contains("stone")     ? 2 :
            name.contains("golden")    ? 2 :
            name.contains("wooden")    ? 1 : 1;
        // Swords beat axes at equal material for general combat.
        return stack.getItem() instanceof SwordItem ? materialRank + 1 : materialRank;
    }

    /** Select the best mining tool in the hotbar for the block at the given registry key. */
    public void autoEquipToolFor(EntityPlayerMPFake fp, net.minecraft.block.BlockState target) {
        if (fp == null || target == null) return;
        var inv = fp.getInventory();
        Class<? extends MiningToolItem> preferred = preferredToolClass(target);
        if (preferred == null) return;

        int best = -1;
        int bestRank = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            if (!preferred.isInstance(stack.getItem())) continue;
            int rank = toolMaterialRank(stack);
            if (rank > bestRank) {
                bestRank = rank;
                best = i;
            }
        }
        if (best >= 0) inv.selectedSlot = best;
    }

    private Class<? extends MiningToolItem> preferredToolClass(net.minecraft.block.BlockState state) {
        if (state.isIn(net.minecraft.registry.tag.BlockTags.MINEABLE_WITH_PICKAXE)) return PickaxeItem.class;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.MINEABLE_WITH_AXE))     return AxeItem.class;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.MINEABLE_WITH_SHOVEL))  return ShovelItem.class;
        if (state.isIn(net.minecraft.registry.tag.BlockTags.MINEABLE_WITH_HOE))     return HoeItem.class;
        return null;
    }

    private int toolMaterialRank(ItemStack stack) {
        String name = stack.getItem().toString().toLowerCase();
        if (name.contains("netherite")) return 5;
        if (name.contains("diamond"))   return 4;
        if (name.contains("iron"))      return 3;
        if (name.contains("stone"))     return 2;
        if (name.contains("golden"))    return 2;
        return 1;
    }

    /**
     * Eats food automatically if hungry, just like a real player would
     * without needing to be told. Call once per second from the bot tick.
     * @return true if she ate something this call
     */
    public boolean autoEat(EntityPlayerMPFake fp) {
        if (fp == null) return false;
        if (eatCooldown > 0) { eatCooldown--; return false; }

        int hunger = fp.getHungerManager().getFoodLevel();
        if (hunger >= HUNGRY_THRESHOLD) return false;

        var inv = fp.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && stack.getItem().getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD)) {
                fp.getHungerManager().eat(stack.getItem(), stack);
                stack.decrement(1);
                eatCooldown = 60; // 3s between bites, like real eating animation pacing
                return true;
            }
        }
        return false;
    }

    /** Human-readable inventory summary for the AI / chat, real item counts. */
    public String summarize(EntityPlayerMPFake fp) {
        if (fp == null) return "nothing yet";
        var inv = fp.getInventory();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem().getName().getString(), stack.getCount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) return "nothing";
        return counts.entrySet().stream()
            .map(e -> e.getValue() + "x " + e.getKey())
            .collect(Collectors.joining(", "));
    }
}
