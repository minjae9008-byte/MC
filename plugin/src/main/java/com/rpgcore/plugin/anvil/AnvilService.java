package com.rpgcore.plugin.anvil;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.util.Enchantments;
import com.rpgcore.plugin.util.MaterialSets;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Loads and applies the custom anvil recipes.
 *
 * The recipes are pure config: a target set (ids or item tags, vanilla or from
 * any datapack the server has installed), an ingredient, a level cost, and
 * what the craft does -
 * restore durability, bump an enchantment, or both. Adding "flint sharpens a
 * sword" needs no code.
 *
 * Nothing here touches the anvil menu; {@link AnvilListener} does that and
 * only asks this class "does anything match, and what would come out".
 */
public final class AnvilService {

    /**
     * Level cost a recipe gets when it does not name one.
     *
     * Not zero, on purpose. Vanilla's anvil has historically gated the result
     * slot on the repair cost being above zero - a free result is shown but
     * cannot be picked up - and whether a given server build still does that
     * is not something this plugin can ask. One level is the cheapest cost
     * that is safe under either behaviour, so a recipe that says nothing about
     * cost still works. An operator who knows their server allows it can put
     * level-cost back to 0 explicitly; the load warning below says as much.
     */
    private static final int DEFAULT_LEVEL_COST = 1;

    private final RpgCorePlugin plugin;
    private final List<AnvilRecipe> recipes = new ArrayList<>();

    public AnvilService(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isEmpty() {
        return recipes.isEmpty();
    }

    public List<AnvilRecipe> recipes() {
        return recipes;
    }

    public void load() {
        recipes.clear();
        if (!plugin.rpgConfig().anvilEnabled()) {
            plugin.getLogger().info("Custom anvil recipes are disabled.");
            return;
        }

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("anvil.recipes");
        if (section == null) {
            plugin.getLogger().warning("anvil.recipes missing from config.yml - no custom anvil recipes.");
            return;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection node = section.getConfigurationSection(id);
            if (node == null) {
                continue;
            }
            AnvilRecipe recipe = parse(id, node);
            if (recipe != null) {
                recipes.add(recipe);
            }
        }
        plugin.getLogger().info("Anvil recipes loaded: " + recipes.size() + ".");
        warnAboutFreeRecipes();
    }

    /**
     * Names any recipe an operator has explicitly set to cost nothing.
     *
     * A free result is the one custom-anvil failure that looks like the plugin
     * not being installed at all: the recipe matches, the result renders, and
     * the click that should take it does nothing. If that is what a server
     * sees, this line in the log is the difference between a five-minute fix
     * and a bug report.
     */
    private void warnAboutFreeRecipes() {
        List<String> free = new ArrayList<>();
        for (AnvilRecipe recipe : recipes) {
            if (recipe.levelCost() <= 0) {
                free.add(recipe.id());
            }
        }
        if (!free.isEmpty()) {
            plugin.getLogger().warning("Anvil recipes with level-cost 0: " + String.join(", ", free)
                    + ". Vanilla can refuse to hand over a result that costs nothing, which looks like"
                    + " the recipe doing nothing at all. Give them level-cost 1 or more if the result"
                    + " cannot be taken out of the anvil.");
        }
    }

    private AnvilRecipe parse(String id, ConfigurationSection node) {
        String context = "anvil.recipes." + id;

        Material ingredient = Material.matchMaterial(node.getString("ingredient", ""));
        if (ingredient == null) {
            plugin.getLogger().warning(context + ": unknown or missing 'ingredient' - recipe skipped.");
            return null;
        }

        Set<Material> targets = MaterialSets.resolve(plugin, Tag.REGISTRY_ITEMS,
                node.getStringList("target"), context + ".target");
        if (targets.isEmpty()) {
            plugin.getLogger().warning(context + ": 'target' resolved to no items - recipe skipped.");
            return null;
        }

        List<AnvilRecipe.Grant> grants = new ArrayList<>();
        ConfigurationSection enchants = node.getConfigurationSection("enchantments");
        if (enchants != null) {
            for (String key : enchants.getKeys(false)) {
                Enchantment enchantment = Enchantments.byId(key);
                if (enchantment == null) {
                    plugin.getLogger().warning(context + ": unknown enchantment " + key + " - skipped.");
                    continue;
                }
                int levels = Math.max(1, enchants.getInt(key + ".levels", enchants.getInt(key, 1)));
                // No vanilla ceiling by default: a recipe can be applied as
                // many times as the player has materials for. Set max-level
                // explicitly to put a limit back.
                // settings.yml sets the server-wide ceiling; a recipe may set
                // its own max-level, and the lower of the two wins.
                int serverCap = plugin.rpgConfig().enchantRespectVanilla()
                        ? enchantment.getMaxLevel()
                        : plugin.rpgConfig().enchantMaxLevel();
                int maxLevel = Math.min(serverCap, enchants.getInt(key + ".max-level", serverCap));
                grants.add(new AnvilRecipe.Grant(enchantment, levels,
                        Math.clamp(maxLevel, 1, AnvilRecipe.HARD_LEVEL_CEILING)));
            }
        }

        int repairPercent = Math.clamp(node.getInt("repair-percent", 0), 0, 100);
        if (grants.isEmpty() && repairPercent == 0) {
            plugin.getLogger().warning(context + ": neither 'repair-percent' nor 'enchantments' does anything"
                    + " - recipe skipped.");
            return null;
        }

        return new AnvilRecipe(id,
                ChatColor.translateAlternateColorCodes('&', node.getString("name", id)),
                targets,
                ingredient,
                Math.max(1, node.getInt("ingredient-amount", 1)),
                Math.max(0, node.getInt("level-cost", DEFAULT_LEVEL_COST)),
                repairPercent,
                List.copyOf(grants));
    }

    /** The first recipe whose target and ingredient both match, or null. */
    public AnvilRecipe match(ItemStack target, ItemStack ingredient) {
        if (target == null || ingredient == null
                || target.getType().isAir() || ingredient.getType().isAir()) {
            return null;
        }
        for (AnvilRecipe recipe : recipes) {
            if (recipe.accepts(target.getType())
                    && ingredient.getType() == recipe.ingredient()
                    && ingredient.getAmount() >= recipe.ingredientAmount()) {
                return recipe;
            }
        }
        return null;
    }

    /**
     * Builds what the recipe would produce, or null when it would change
     * nothing - an already-pristine item under a repair recipe, or an
     * enchantment already at its ceiling. Returning null there means the anvil
     * shows an empty result instead of charging levels for a no-op.
     */
    public ItemStack buildResult(ItemStack target, AnvilRecipe recipe) {
        ItemStack result = target.clone();
        result.setAmount(1);
        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            return null;
        }

        // Vanilla makes every anvil use of an item more expensive than the
        // last and eventually refuses outright ("Too Expensive!"). That is the
        // cap on how many times gear can be worked, so custom recipes carry
        // the prior work cost across unchanged instead of raising it.
        int priorWorkCost = meta instanceof Repairable repairable ? repairable.getRepairCost() : 0;

        boolean changed = false;

        if (recipe.repairPercent() > 0 && meta instanceof Damageable damageable && damageable.getDamage() > 0) {
            int max = damageable.hasMaxDamage() ? damageable.getMaxDamage() : result.getType().getMaxDurability();
            if (max > 0) {
                int healed = Math.max(1, max * recipe.repairPercent() / 100);
                damageable.setDamage(Math.max(0, damageable.getDamage() - healed));
                changed = true;
            }
        }

        for (AnvilRecipe.Grant grant : recipe.grants()) {
            int current = meta.getEnchantLevel(grant.enchantment());
            if (current >= grant.maxLevel()) {
                continue;
            }
            int next = Math.min(grant.maxLevel(), current + grant.levels());
            // true: this is the point of the feature, so conflicting-enchantment
            // rules are not enforced here - the recipe's target list is.
            meta.addEnchant(grant.enchantment(), next, true);
            changed = true;
        }

        if (!changed) {
            return null;
        }
        if (meta instanceof Repairable repairable) {
            repairable.setRepairCost(priorWorkCost);
        }
        result.setItemMeta(meta);
        return result;
    }

    /** Human-readable summary of a recipe, for /rpgcore recipes. */
    public String describe(AnvilRecipe recipe) {
        StringBuilder out = new StringBuilder();
        out.append(ChatColor.AQUA).append(recipe.displayName()).append(ChatColor.GRAY).append(" - ")
                .append(ChatColor.WHITE).append(recipe.ingredientAmount()).append("x ")
                .append(key(recipe.ingredient()));
        if (recipe.levelCost() > 0) {
            out.append(ChatColor.GRAY).append(" (레벨 ").append(recipe.levelCost()).append(")");
        }
        out.append(ChatColor.GRAY).append(" -> ");

        List<String> effects = new ArrayList<>();
        if (recipe.repairPercent() > 0) {
            effects.add("내구도 +" + recipe.repairPercent() + "%");
        }
        for (AnvilRecipe.Grant grant : recipe.grants()) {
            String limit = grant.maxLevel() >= AnvilRecipe.HARD_LEVEL_CEILING
                    ? " (상한 없음)" : " (최대 " + grant.maxLevel() + ")";
            effects.add(key(grant.enchantment()) + " +" + grant.levels() + limit);
        }
        return out.append(ChatColor.GREEN).append(String.join(", ", effects)).toString();
    }

    private static String key(Material material) {
        return material.getKey().getKey();
    }

    private static String key(Enchantment enchantment) {
        return enchantment.getKey().getKey();
    }
}
