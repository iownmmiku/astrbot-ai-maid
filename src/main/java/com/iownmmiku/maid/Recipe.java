package com.iownmmiku.maid;

import java.util.*;

/**
 * 配方库：工作台配方 + 熔炉配方。
 * 简化版：不考虑摆放位置，只要材料够就能合成。
 */
public final class Recipe {
    public static class Ingredient {
        public final String item;
        public final int count;

        public Ingredient(String item, int count) {
            this.item = item;
            this.count = count;
        }
    }

    public static class CraftRecipe {
        public final String result;
        public final int resultCount;
        public final List<Ingredient> ingredients;
        public final boolean needsWorkbench;

        public CraftRecipe(String result, int resultCount, boolean needsWorkbench, Ingredient... ingredients) {
            this.result = result;
            this.resultCount = resultCount;
            this.needsWorkbench = needsWorkbench;
            this.ingredients = Arrays.asList(ingredients);
        }
    }

    public static class SmeltRecipe {
        public final String input;
        public final String output;

        public SmeltRecipe(String input, String output) {
            this.input = input;
            this.output = output;
        }
    }

    private static final Map<String, CraftRecipe> CRAFTING = new HashMap<>();
    private static final Map<String, SmeltRecipe> SMELTING = new HashMap<>();

    static {
        // 工作台本身（2x2 可做）
        CRAFTING.put("crafting_table", new CraftRecipe("crafting_table", 1, false,
                new Ingredient("oak_planks", 4)));
        
        // 木板（2x2）
        CRAFTING.put("oak_planks", new CraftRecipe("oak_planks", 4, false,
                new Ingredient("oak_log", 1)));
        
        // 木棍（2x2）
        CRAFTING.put("stick", new CraftRecipe("stick", 4, false,
                new Ingredient("oak_planks", 2)));
        
        // 木镐
        CRAFTING.put("wooden_pickaxe", new CraftRecipe("wooden_pickaxe", 1, true,
                new Ingredient("oak_planks", 3),
                new Ingredient("stick", 2)));
        
        // 木斧
        CRAFTING.put("wooden_axe", new CraftRecipe("wooden_axe", 1, true,
                new Ingredient("oak_planks", 3),
                new Ingredient("stick", 2)));
        
        // 木剑
        CRAFTING.put("wooden_sword", new CraftRecipe("wooden_sword", 1, true,
                new Ingredient("oak_planks", 2),
                new Ingredient("stick", 1)));
        
        // 石镐
        CRAFTING.put("stone_pickaxe", new CraftRecipe("stone_pickaxe", 1, true,
                new Ingredient("cobblestone", 3),
                new Ingredient("stick", 2)));
        
        // 石斧
        CRAFTING.put("stone_axe", new CraftRecipe("stone_axe", 1, true,
                new Ingredient("cobblestone", 3),
                new Ingredient("stick", 2)));
        
        // 石剑
        CRAFTING.put("stone_sword", new CraftRecipe("stone_sword", 1, true,
                new Ingredient("cobblestone", 2),
                new Ingredient("stick", 1)));
        
        // 熔炉
        CRAFTING.put("furnace", new CraftRecipe("furnace", 1, true,
                new Ingredient("cobblestone", 8)));
        
        // 铁镐
        CRAFTING.put("iron_pickaxe", new CraftRecipe("iron_pickaxe", 1, true,
                new Ingredient("iron_ingot", 3),
                new Ingredient("stick", 2)));
        
        // 铁斧
        CRAFTING.put("iron_axe", new CraftRecipe("iron_axe", 1, true,
                new Ingredient("iron_ingot", 3),
                new Ingredient("stick", 2)));
        
        // 铁剑
        CRAFTING.put("iron_sword", new CraftRecipe("iron_sword", 1, true,
                new Ingredient("iron_ingot", 2),
                new Ingredient("stick", 1)));
        
        // 铁头盔/胸甲/护腿/靴子
        CRAFTING.put("iron_helmet", new CraftRecipe("iron_helmet", 1, true,
                new Ingredient("iron_ingot", 5)));
        CRAFTING.put("iron_chestplate", new CraftRecipe("iron_chestplate", 1, true,
                new Ingredient("iron_ingot", 8)));
        CRAFTING.put("iron_leggings", new CraftRecipe("iron_leggings", 1, true,
                new Ingredient("iron_ingot", 7)));
        CRAFTING.put("iron_boots", new CraftRecipe("iron_boots", 1, true,
                new Ingredient("iron_ingot", 4)));
        
        // 钻石镐
        CRAFTING.put("diamond_pickaxe", new CraftRecipe("diamond_pickaxe", 1, true,
                new Ingredient("diamond", 3),
                new Ingredient("stick", 2)));
        
        // 钻石剑
        CRAFTING.put("diamond_sword", new CraftRecipe("diamond_sword", 1, true,
                new Ingredient("diamond", 2),
                new Ingredient("stick", 1)));
        
        // 锄头
        CRAFTING.put("wooden_hoe", new CraftRecipe("wooden_hoe", 1, true,
                new Ingredient("oak_planks", 2), new Ingredient("stick", 2)));
        CRAFTING.put("stone_hoe", new CraftRecipe("stone_hoe", 1, true,
                new Ingredient("cobblestone", 2), new Ingredient("stick", 2)));
        CRAFTING.put("iron_hoe", new CraftRecipe("iron_hoe", 1, true,
                new Ingredient("iron_ingot", 2), new Ingredient("stick", 2)));
        
        // 桶
        CRAFTING.put("bucket", new CraftRecipe("bucket", 1, true,
                new Ingredient("iron_ingot", 3)));
        
        // 床
        CRAFTING.put("white_bed", new CraftRecipe("white_bed", 1, true,
                new Ingredient("white_wool", 3),
                new Ingredient("oak_planks", 3)));
        
        // 箱子
        CRAFTING.put("chest", new CraftRecipe("chest", 1, true,
                new Ingredient("oak_planks", 8)));
        
        // 熔炼配方
        SMELTING.put("iron_ore", new SmeltRecipe("iron_ore", "iron_ingot"));
        SMELTING.put("deepslate_iron_ore", new SmeltRecipe("deepslate_iron_ore", "iron_ingot"));
        SMELTING.put("raw_iron", new SmeltRecipe("raw_iron", "iron_ingot"));
        SMELTING.put("beef", new SmeltRecipe("beef", "cooked_beef"));
        SMELTING.put("porkchop", new SmeltRecipe("porkchop", "cooked_porkchop"));
        SMELTING.put("chicken", new SmeltRecipe("chicken", "cooked_chicken"));
        SMELTING.put("mutton", new SmeltRecipe("mutton", "cooked_mutton"));
        SMELTING.put("cod", new SmeltRecipe("cod", "cooked_cod"));
        SMELTING.put("salmon", new SmeltRecipe("salmon", "cooked_salmon"));
    }

    public static CraftRecipe getCraftRecipe(String item) {
        return CRAFTING.get(item);
    }

    public static SmeltRecipe getSmeltRecipe(String input) {
        return SMELTING.get(input);
    }

    public static Set<String> allCraftable() {
        return CRAFTING.keySet();
    }
}
