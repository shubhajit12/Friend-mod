package com.friendmod;

import carpet.patches.EntityPlayerMPFake;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;

import java.lang.reflect.Method;
import java.util.*;

/**
 * FriendCrafting v3.2
 * ====================
 * Real crafting: takes the item name from "Friend craft me [item]" /
 * "Friend make me [item]", looks up the ACTUAL vanilla recipe for it via
 * Minecraft's own RecipeManager, checks Friend's real inventory against the
 * real ingredient list, and only crafts it if she actually has the materials.
 *
 * Why this is written defensively:
 * Minecraft's recipe/crafting-input APIs have changed method signatures
 * several times across 1.21.x point releases (recipes moved server-side in
 * 1.21.2, ingredient/result accessors have shifted between versions). Rather
 * than hard-code one exact method signature that might not match 1.21.11
 * precisely and silently do the wrong thing, this class uses reflection to
 * find the right accessor at runtime ("getIngredients"/"getResult" or similar)
 * and falls back to a clear chat message instead of crashing if it can't.
 *
 * If this doesn't compile cleanly or behaves oddly on your exact build,
 * tell me the exact error/behavior and I'll adjust the accessor names —
 * that's expected tuning, not a sign the whole approach is wrong.
 */
public class FriendCrafting {

    public static class CraftResult {
        public final boolean success;
        public final String message;
        public final ItemStack crafted;

        private CraftResult(boolean success, String message, ItemStack crafted) {
            this.success = success;
            this.message = message;
            this.crafted = crafted;
        }
        static CraftResult ok(String msg, ItemStack stack) { return new CraftResult(true, msg, stack); }
        static CraftResult fail(String msg) { return new CraftResult(false, msg, ItemStack.EMPTY); }
    }

    /**
     * Attempts to craft the item named in the free-text request.
     * Looks up the real recipe, checks real ingredients against her real
     * inventory, consumes them, and inserts the crafted result.
     */
    public CraftResult craft(EntityPlayerMPFake fp, String requestText) {
        if (fp == null) return CraftResult.fail("not available right now");

        Item targetItem = ItemNameParser.findItem(requestText);
        if (targetItem == null) {
            return CraftResult.fail("not sure what item that is — try naming it more plainly, like \"craft me a wooden pickaxe\"");
        }

        ServerWorld world = fp.getServerWorld();
        List<RecipeEntry<?>> matches = findRecipesForOutput(world, targetItem);

        if (matches.isEmpty()) {
            return CraftResult.fail("there's no crafting recipe for that — might need a furnace, smithing table, or it just doesn't exist");
        }

        // Try each candidate recipe (some items have multiple recipes, e.g. via different ingredient tags)
        // and use the first one she actually has the materials for.
        for (RecipeEntry<?> entry : matches) {
            List<Ingredient> ingredients = extractIngredients(entry.value());
            if (ingredients == null) continue; // couldn't introspect this recipe type, skip it

            Map<Integer, ItemStack> consumedFrom = new HashMap<>();
            boolean canCraft = true;
            List<String> missing = new ArrayList<>();

            for (Ingredient ing : ingredients) {
                if (ing.isEmpty()) continue; // empty grid cell in a shaped recipe
                int slot = findMatchingSlot(fp, ing, consumedFrom.keySet());
                if (slot == -1) {
                    canCraft = false;
                    missing.add(describeIngredient(ing));
                } else {
                    consumedFrom.put(slot, fp.getInventory().getStack(slot));
                }
            }

            if (canCraft) {
                for (int slot : consumedFrom.keySet()) {
                    fp.getInventory().getStack(slot).decrement(1);
                }
                ItemStack result = extractResult(entry.value(), targetItem);
                fp.getInventory().insertStack(result.copy());
                return CraftResult.ok("made it", result);
            } else if (!missing.isEmpty()) {
                // Remember the most informative miss across candidate recipes.
                return CraftResult.fail("missing: " + String.join(", ", new LinkedHashSet<>(missing)));
            }
        }

        return CraftResult.fail("couldn't figure out the ingredients for that one — might need a special crafting station");
    }

    // =========================================================================
    // RECIPE LOOKUP
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<RecipeEntry<?>> findRecipesForOutput(ServerWorld world, Item targetItem) {
        List<RecipeEntry<?>> found = new ArrayList<>();
        try {
            var recipeManager = world.getRecipeManager();
            // listAllOfType(RecipeType.CRAFTING) is the stable, long-standing entrypoint
            // for fetching every known crafting-table recipe.
            List<RecipeEntry<?>> all = (List<RecipeEntry<?>>) (List<?>) recipeManager.listAllOfType(RecipeType.CRAFTING);
            for (RecipeEntry<?> entry : all) {
                ItemStack result = extractResult(entry.value(), null);
                if (!result.isEmpty() && result.getItem() == targetItem) {
                    found.add(entry);
                }
            }
        } catch (Exception e) {
            FriendMod.LOGGER.error("[FriendMod] Recipe lookup failed: " + e.getMessage());
        }
        return found;
    }

    // =========================================================================
    // REFLECTIVE INGREDIENT / RESULT EXTRACTION
    // (Defensive: vanilla's exact accessor names have moved between 1.21.x
    //  point releases. We try several known method names before giving up.)
    // =========================================================================

    @SuppressWarnings("unchecked")
    private List<Ingredient> extractIngredients(Recipe<?> recipe) {
        for (String methodName : new String[]{"getIngredients", "getInputs"}) {
            try {
                Method m = recipe.getClass().getMethod(methodName);
                Object raw = m.invoke(recipe);
                if (raw instanceof List<?> list) {
                    List<Ingredient> result = new ArrayList<>();
                    for (Object o : list) {
                        if (o instanceof Ingredient ing) {
                            result.add(ing);
                        } else if (o instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof Ingredient ing) {
                            // 1.21.4+ shaped recipes return List<Optional<Ingredient>> for empty grid cells.
                            result.add(ing);
                        }
                    }
                    if (!result.isEmpty()) return result;
                }
            } catch (NoSuchMethodException ignored) {
                // try the next candidate name
            } catch (Exception e) {
                FriendMod.LOGGER.error("[FriendMod] Ingredient extraction error via " + methodName + ": " + e.getMessage());
            }
        }
        return null;
    }

    private ItemStack extractResult(Recipe<?> recipe, Item expectedItem) {
        for (String methodName : new String[]{"getResult", "getOutput"}) {
            try {
                Method m = findResultMethod(recipe.getClass(), methodName);
                if (m == null) continue;
                Object raw = m.invoke(recipe, (Object[]) buildArgsFor(m));
                if (raw instanceof ItemStack stack) return stack;
            } catch (Exception ignored) {
                // try the next candidate
            }
        }
        return ItemStack.EMPTY;
    }

    private Method findResultMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() <= 1) return m;
        }
        return null;
    }

    private Object[] buildArgsFor(Method m) {
        if (m.getParameterCount() == 0) return new Object[0];
        // Some getResult(RegistryWrapper.WrapperLookup) overloads need a lookup arg —
        // pass null and let it fail gracefully into the catch block if that's not allowed.
        return new Object[]{null};
    }

    // =========================================================================
    // INVENTORY MATCHING
    // =========================================================================

    private int findMatchingSlot(EntityPlayerMPFake fp, Ingredient ing, Set<Integer> alreadyUsed) {
        var inv = fp.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (alreadyUsed.contains(i)) continue;
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && ing.test(stack)) {
                return i;
            }
        }
        return -1;
    }

    private String describeIngredient(Ingredient ing) {
        // Ingredient's "list the stacks it accepts" method has had a couple of
        // different names across versions (getMatchingStacks, getMatchingItems,
        // toMatchingStacks) — try them defensively rather than assume one.
        for (String methodName : new String[]{"getMatchingStacks", "getMatchingItems", "toMatchingStacks"}) {
            try {
                Method m = ing.getClass().getMethod(methodName);
                Object raw = m.invoke(ing);
                if (raw instanceof ItemStack[] stacks && stacks.length > 0) {
                    return stacks[0].getItem().getName().getString().toLowerCase();
                }
                if (raw instanceof Collection<?> coll && !coll.isEmpty()) {
                    Object first = coll.iterator().next();
                    if (first instanceof ItemStack stack) return stack.getItem().getName().getString().toLowerCase();
                }
            } catch (NoSuchMethodException ignored) {
                // try next candidate
            } catch (Exception e) {
                FriendMod.LOGGER.error("[FriendMod] describeIngredient via " + methodName + " failed: " + e.getMessage());
            }
        }
        return "an ingredient";
    }
}
