package com.rpgcore.plugin.mail;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.util.DeferredSave;
import com.rpgcore.plugin.util.HandOver;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Things the server owes a player who was not there to receive them.
 *
 * Every feature that can conclude while its owner is offline ends up here: a
 * duel that timed out after both sides disconnected, an auction that sold
 * overnight, a bid refunded when someone else outbid you, a listing nobody
 * bought. Each of those has the same shape - an item or an amount of gold, a
 * rightful owner identified by UUID, and no inventory to put it in right now -
 * so they share one store rather than each inventing their own.
 *
 * Two rules make it safe to rely on:
 *
 *   - it is keyed by UUID, never by name. The player who is offline is exactly
 *     the player who might come back under a different name, and a name-keyed
 *     credit would then land in somebody else's account;
 *   - delivery never destroys anything. What does not fit in the inventory
 *     stays in the mailbox and is said out loud, rather than being dropped on
 *     the floor next to a player who may be standing in lava, or silently
 *     eaten. The player frees a slot and collects again.
 *
 * The file is not left to shutdown - the situation this exists for is
 * precisely the one where shutdown was not orderly - but nor is it written on
 * the spot for each change: a change marks it stale and the tick pump pushes
 * it out, off the main thread, within a second. That window is shared with
 * every other store on purpose, so an item that left an auction lot and
 * arrived here is written at the same moment as the lot's disappearance
 * rather than between the two. See {@link com.rpgcore.plugin.util.DeferredSave}.
 */
public final class MailboxService {

    /** One thing owed: either an item or an amount of gold, never both. */
    public record Entry(ItemStack item, int gold, String note) {

        public boolean isGold() {
            return item == null;
        }

        /** Short human-readable summary, for the collect message and the GUI. */
        public String describe() {
            if (isGold()) {
                return ChatColor.GOLD + String.valueOf(gold) + ChatColor.GRAY + " 골드";
            }
            return ChatColor.WHITE + com.rpgcore.plugin.collection.CollectionService.nameOf(item)
                    + ChatColor.GRAY + " x" + item.getAmount();
        }
    }

    private static final String FILE = "mailbox.yml";
    /**
     * A cap on how much one player's mailbox can hold. Reached only by
     * something going wrong in a loop; without it a bug elsewhere would grow
     * the file without bound and take the server's disk with it.
     */
    private static final int MAX_PER_PLAYER = 200;

    private final RpgCorePlugin plugin;
    private final File file;
    private final Map<UUID, List<Entry>> boxes = new LinkedHashMap<>();
    private final DeferredSave writer;

    public MailboxService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
        this.writer = new DeferredSave(plugin, FILE, this::build);
    }

    public void load() {
        boxes.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("mail");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            UUID owner;
            try {
                owner = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning(FILE + ": malformed owner " + key + " - skipped.");
                continue;
            }
            List<Entry> entries = new ArrayList<>();
            for (Map<?, ?> raw : root.getMapList(key)) {
                Entry entry = fromMap(raw);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            if (!entries.isEmpty()) {
                boxes.put(owner, entries);
            }
        }
        if (!boxes.isEmpty()) {
            plugin.getLogger().info("Mailbox: " + boxes.size() + " player(s) have something waiting.");
        }
    }

    private Entry fromMap(Map<?, ?> raw) {
        Object item = raw.get("item");
        String note = raw.get("note") == null ? "" : String.valueOf(raw.get("note"));
        if (item instanceof ItemStack stack && !stack.getType().isAir()) {
            return new Entry(stack, 0, note);
        }
        Object gold = raw.get("gold");
        if (gold instanceof Number number && number.intValue() > 0) {
            return new Entry(null, number.intValue(), note);
        }
        plugin.getLogger().warning(FILE + ": an entry had neither an item nor gold - skipped.");
        return null;
    }

    // --------------------------------------------------------------- putting

    /** Holds a stack for its owner. The stack is copied, not referenced. */
    public void hold(UUID owner, ItemStack stack, String note) {
        if (stack == null || stack.getType().isAir()) {
            return;
        }
        add(owner, new Entry(stack.clone(), 0, note));
    }

    /** Holds gold for its owner. */
    public void holdGold(UUID owner, int amount, String note) {
        if (amount <= 0) {
            return;
        }
        add(owner, new Entry(null, amount, note));
    }

    /**
     * Hands something over now if the player is online and has room, and only
     * puts it in the mailbox otherwise. Callers that may or may not have a
     * live player use this rather than choosing for themselves.
     */
    public void give(UUID owner, ItemStack stack, String note) {
        Player online = plugin.getServer().getPlayer(owner);
        if (online != null && online.isOnline() && deliverStack(online, stack)) {
            online.sendMessage(ChatColor.AQUA + "[우편] " + note + ChatColor.GRAY + " - "
                    + new Entry(stack, 0, note).describe() + ChatColor.GRAY + " 을(를) 받았습니다.");
            return;
        }
        hold(owner, stack, note);
        notifyHeld(owner, note);
    }

    /** Same, for gold - which always fits, so an online player always gets it. */
    public void giveGold(UUID owner, int amount, String note) {
        if (amount <= 0) {
            return;
        }
        Player online = plugin.getServer().getPlayer(owner);
        if (online != null && online.isOnline()) {
            // refund, not give: gold changing hands is not gold earned, so it
            // must not count towards the lifetime-earned achievement.
            plugin.economy().refund(online, amount);
            online.sendMessage(ChatColor.AQUA + "[우편] " + note + ChatColor.GRAY + " - "
                    + plugin.economy().format(amount) + ChatColor.GRAY + " 을(를) 받았습니다.");
            return;
        }
        holdGold(owner, amount, note);
    }

    private void add(UUID owner, Entry entry) {
        List<Entry> box = boxes.computeIfAbsent(owner, k -> new ArrayList<>());
        // Gold is fungible, so it is merged rather than appended. Without
        // this, an ordinary auction week - every outbid refund is one entry -
        // walks a busy player's mailbox up to the cap, and the cap deletes
        // whatever arrives next. Merging keeps the count proportional to the
        // number of distinct things owed, not to how often they were owed.
        if (entry.isGold()) {
            for (int i = 0; i < box.size(); i++) {
                Entry existing = box.get(i);
                if (existing.isGold() && existing.note().equals(entry.note())) {
                    box.set(i, new Entry(null,
                            (int) Math.min((long) existing.gold() + entry.gold(), Integer.MAX_VALUE),
                            existing.note()));
                    save();
                    return;
                }
            }
        }
        if (box.size() >= MAX_PER_PLAYER) {
            plugin.getLogger().severe("Mailbox for " + owner + " is full (" + MAX_PER_PLAYER
                    + " entries); refusing to hold " + ChatColor.stripColor(entry.describe())
                    + ". This should not happen - something is filling it in a loop.");
            return;
        }
        box.add(entry);
        save();
    }

    private void notifyHeld(UUID owner, String note) {
        Player online = plugin.getServer().getPlayer(owner);
        if (online != null && online.isOnline()) {
            online.sendMessage(ChatColor.AQUA + "[우편] " + note
                    + ChatColor.GRAY + " - 인벤토리에 자리가 없어 우편함에 보관했습니다. "
                    + ChatColor.YELLOW + "/menu" + ChatColor.GRAY + " 에서 받으세요.");
        }
    }

    // -------------------------------------------------------------- getting

    public int pending(UUID owner) {
        List<Entry> box = boxes.get(owner);
        return box == null ? 0 : box.size();
    }

    public List<Entry> entries(UUID owner) {
        List<Entry> box = boxes.get(owner);
        return box == null ? List.of() : List.copyOf(box);
    }

    /**
     * Delivers everything that fits, keeps the rest, and says which. Safe to
     * call as often as you like - on join, from a button, from a command.
     */
    public void collect(Player player) {
        List<Entry> box = boxes.get(player.getUniqueId());
        if (box == null || box.isEmpty()) {
            return;
        }

        List<Entry> kept = new ArrayList<>();
        int items = 0;
        // Summed as a long: the entries are consumed whether or not the
        // payment lands, so an int that wrapped negative here would make the
        // refund a no-op and delete every coin in the mailbox.
        long gold = 0L;
        for (Entry entry : box) {
            if (entry.isGold()) {
                // Summed, then paid once below. Each refund writes the
                // player's balance through to the scoreboard mirror, and a
                // mailbox full of small credits should not cost one write per
                // credit.
                gold += entry.gold();
            } else if (deliverStack(player, entry.item())) {
                items++;
            } else {
                kept.add(entry);
            }
        }

        if (gold > 0) {
            plugin.economy().refund(player, (int) Math.min(gold, Integer.MAX_VALUE));
        }
        if (kept.isEmpty()) {
            boxes.remove(player.getUniqueId());
        } else {
            boxes.put(player.getUniqueId(), kept);
        }
        save();
        // Items just crossed from this file into an inventory. Written out
        // together, store first, so a crash in between loses the delivery
        // rather than handing it over twice.
        if (items > 0) {
            HandOver.givenToPlayer(player, writer);
        }

        if (items > 0 || gold > 0) {
            player.sendMessage(ChatColor.AQUA + "[우편] 보관 중이던 "
                    + (items > 0 ? ChatColor.WHITE + "아이템 " + items + "개" + ChatColor.AQUA : "")
                    + (items > 0 && gold > 0 ? ChatColor.GRAY + " 와(과) " + ChatColor.AQUA : "")
                    + (gold > 0 ? plugin.economy().format((int) Math.min(gold, Integer.MAX_VALUE))
                            + ChatColor.AQUA : "")
                    + " 을(를) 받았습니다.");
        }
        if (!kept.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "[우편] 인벤토리가 가득 차 " + kept.size()
                    + "개는 그대로 두었습니다. 자리를 비우고 다시 받으세요.");
        }
    }

    /**
     * Puts one stack in a player's inventory, all of it or none of it.
     *
     * addItem returns what it could not fit, and would otherwise have already
     * placed the rest - so a partial delivery has to be undone by taking the
     * placed part back out, or collecting twice would multiply it.
     */
    private boolean deliverStack(Player player, ItemStack stack) {
        ItemStack copy = stack.clone();
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(copy);
        if (leftover.isEmpty()) {
            return true;
        }
        int placed = stack.getAmount();
        for (ItemStack remaining : leftover.values()) {
            placed -= remaining.getAmount();
        }
        if (placed > 0) {
            ItemStack undo = stack.clone();
            undo.setAmount(placed);
            Map<Integer, ItemStack> stuck = player.getInventory().removeItem(undo);
            if (!stuck.isEmpty()) {
                // Should be unreachable: we are removing what we just added,
                // in the same tick. If it ever happens, the player keeps the
                // part we could not take back and the mailbox must not also
                // keep it, or collecting twice would multiply it.
                int kept = 0;
                for (ItemStack remaining : stuck.values()) {
                    kept += remaining.getAmount();
                }
                plugin.getLogger().severe("Could not undo a partial mailbox delivery to "
                        + player.getName() + "; " + kept + " of "
                        + stack.getType() + " stayed with them and is treated as delivered.");
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------- storage

    /** Marks the file stale; the write is coalesced off the main thread. */
    private void save() {
        writer.markDirty();
    }

    /** Builds and writes on this thread. For shutdown only. */
    public void saveNow() {
        writer.flushNow();
    }

    /** The pending write for this store, or null if its file is up to date. */
    public Runnable pendingWrite() {
        return writer.pendingWrite();
    }

    private YamlConfiguration build() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, List<Entry>> box : boxes.entrySet()) {
            List<Map<String, Object>> serialised = new ArrayList<>();
            for (Entry entry : box.getValue()) {
                Map<String, Object> map = new LinkedHashMap<>();
                if (entry.isGold()) {
                    map.put("gold", entry.gold());
                } else {
                    // Cloned: this is turned into YAML on a writer thread, and
                    // the entry's own stack outlives that call here.
                    map.put("item", entry.item().clone());
                }
                map.put("note", entry.note());
                serialised.add(map);
            }
            yaml.set("mail." + box.getKey(), serialised);
        }
        return yaml;
    }
}
