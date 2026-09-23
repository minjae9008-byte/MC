package com.rpgcore.plugin.blueprint;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The blueprint library.
 *
 * Building is deliberately awkward from this screen - shift plus right click,
 * and only where the player is standing. A single left click that dropped a
 * castle on somebody's head would be the most expensive mis-click in the
 * plugin, and the cost is not refundable.
 */
public final class BlueprintMenu {

    public enum Action {
        WAND, SELECTION, SITE, BACK, CLOSE
    }

    private static final int SIZE = 54;
    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34};

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, UUID> blueprints = new HashMap<>();
        private final Map<Integer, Action> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public UUID blueprintAt(int slot) {
            return blueprints.get(slot);
        }

        public Action actionAt(int slot) {
            return actions.get(slot);
        }
    }

    private final RpgCorePlugin plugin;

    public BlueprintMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!plugin.blueprints().enabled()) {
            player.sendMessage(ChatColor.RED + "[청사진] 이 서버에서는 청사진을 쓸 수 없습니다.");
            return;
        }
        BlueprintService service = plugin.blueprints();
        Holder holder = new Holder();
        Inventory inv = plugin.getServer().createInventory(holder, SIZE,
                ChatColor.DARK_GRAY + "청사진");
        holder.setInventory(inv);
        for (int slot = 0; slot < SIZE; slot++) {
            inv.setItem(slot, filler());
        }

        List<Blueprint> mine = service.ownedBy(player.getUniqueId());
        List<Blueprint> others = new ArrayList<>();
        for (Blueprint blueprint : service.all()) {
            if (!blueprint.owner().equals(player.getUniqueId())) {
                others.add(blueprint);
            }
        }
        List<Blueprint> shown = new ArrayList<>(mine);
        shown.addAll(others);

        if (shown.isEmpty()) {
            inv.setItem(22, plain(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7저장된 청사진 없음", List.of(
                    ChatColor.DARK_GRAY + "설계 지팡이로 두 모서리를 찍고",
                    ChatColor.DARK_GRAY + "/blueprint save <이름> 하세요.")));
        }
        for (int i = 0; i < SLOTS.length && i < shown.size(); i++) {
            Blueprint blueprint = shown.get(i);
            inv.setItem(SLOTS[i], icon(player, blueprint));
            holder.blueprints.put(SLOTS[i], blueprint.id());
        }

        button(inv, holder, 37, Action.WAND, plugin.rpgConfig().blueprintWand(), "&b설계 지팡이", List.of(
                ChatColor.GRAY + "좌클릭 1번 모서리 · 우클릭 2번 모서리",
                ChatColor.YELLOW + "클릭하여 받기"));
        inv.setItem(38, selectionCard(player));
        inv.setItem(40, siteCard(player));

        button(inv, holder, 45, Action.BACK, Material.ARROW, "&c뒤로",
                List.of(ChatColor.GRAY + "/menu 로 돌아갑니다"));
        button(inv, holder, 53, Action.CLOSE, Material.RED_STAINED_GLASS_PANE, "&c닫기", List.of());
        player.openInventory(inv);
    }

    private ItemStack icon(Player player, Blueprint blueprint) {
        BlueprintService.Estimate estimate = plugin.blueprints().estimate(blueprint, player);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + String.valueOf(blueprint.width()) + "x" + blueprint.height() + "x"
                + blueprint.length() + " · 블록 " + ChatColor.WHITE
                + comma(blueprint.solidCount()));
        lore.add(ChatColor.DARK_GRAY + "설계 " + blueprint.ownerName());
        lore.add("");
        lore.add(ChatColor.GRAY + "자재값 " + ChatColor.WHITE + comma(estimate.materials())
                + ChatColor.GRAY + " + 시공비 " + ChatColor.WHITE + comma(estimate.margin()));
        if (estimate.imported() > 0) {
            lore.add(ChatColor.RED + "수입 자재 " + comma(estimate.imported())
                    + ChatColor.GRAY + " (시장 재고 부족)");
        }
        lore.add(ChatColor.GRAY + "합계 " + ChatColor.GOLD + comma(estimate.total())
                + plugin.rpgConfig().goldSymbol());
        int seconds = Math.max(1, blueprint.solidCount()
                / Math.max(1, plugin.rpgConfig().blueprintBlocksPerSecond()));
        lore.add(ChatColor.GRAY + "예상 공기 " + ChatColor.WHITE
                + BlueprintService.formatDuration(seconds));
        lore.add("");
        lore.add(ChatColor.GRAY + "주요 자재:");
        int listed = 0;
        for (Map.Entry<Material, Integer> entry : estimate.items().entrySet()) {
            if (listed++ >= 5) {
                lore.add(ChatColor.DARK_GRAY + "  ... 외 " + (estimate.items().size() - 5) + "종");
                break;
            }
            lore.add(ChatColor.DARK_GRAY + "  " + entry.getKey().getKey().getKey() + " x"
                    + comma(entry.getValue()));
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 자재 내역을 채팅으로");
        lore.add(ChatColor.YELLOW + "Shift+우클릭" + ChatColor.GRAY + " 서 있는 자리에 착공");
        lore.add(ChatColor.DARK_GRAY + "  (회사 자금으로 지으려면 /blueprint build <이름> company)");
        return plain(blueprint.icon(), "&b" + blueprint.name(), lore);
    }

    private ItemStack selectionCard(Player player) {
        BlueprintService.Selection selection = plugin.blueprints().selection(player);
        List<String> lore = new ArrayList<>();
        if (!selection.complete()) {
            lore.add(ChatColor.DARK_GRAY + "모서리를 두 개 찍어야 합니다.");
            lore.add(ChatColor.DARK_GRAY + "1번 " + describe(selection.first()));
            lore.add(ChatColor.DARK_GRAY + "2번 " + describe(selection.second()));
        } else {
            lore.add(ChatColor.GRAY + "1번 " + describe(selection.first()));
            lore.add(ChatColor.GRAY + "2번 " + describe(selection.second()));
            lore.add(ChatColor.GRAY + "범위 " + ChatColor.WHITE + comma(selection.volume())
                    + ChatColor.GRAY + "블록 / 최대 "
                    + comma(plugin.rpgConfig().blueprintMaxBlocks()));
            lore.add("");
            lore.add(ChatColor.YELLOW + "/blueprint save <이름> 으로 저장");
        }
        return plain(Material.STRING, "&f지금 선택한 구역", lore);
    }

    private String describe(org.bukkit.Location location) {
        return location == null ? "-" : location.getBlockX() + ", " + location.getBlockY()
                + ", " + location.getBlockZ();
    }

    private ItemStack siteCard(Player player) {
        List<ConstructionSite> sites = plugin.blueprints().sitesOf(player.getUniqueId());
        List<String> lore = new ArrayList<>();
        if (sites.isEmpty()) {
            lore.add(ChatColor.DARK_GRAY + "진행 중인 공사가 없습니다.");
        } else {
            for (ConstructionSite site : sites) {
                lore.add(ChatColor.GRAY + site.blueprintName() + " · " + ChatColor.WHITE
                        + site.percent() + "%");
                lore.add(ChatColor.DARK_GRAY + "  " + comma(site.placed()) + " / "
                        + comma(site.solidTotal()) + "블록 · " + site.originX() + ", "
                        + site.originY() + ", " + site.originZ());
            }
            lore.add("");
            lore.add(ChatColor.YELLOW + "/blueprint rush" + ChatColor.GRAY + " 로 즉시 완공 (급행비)");
            lore.add(ChatColor.YELLOW + "/blueprint cancel" + ChatColor.GRAY + " 로 중단");
        }
        return plain(Material.SCAFFOLDING, "&f공사 현장", lore);
    }

    private static String comma(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private void button(Inventory inv, Holder holder, int slot, Action action,
                        Material material, String name, List<String> lore) {
        inv.setItem(slot, plain(material, name, lore));
        holder.actions.put(slot, action);
    }

    private ItemStack filler() {
        return plain(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
    }

    private ItemStack plain(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        return stack;
    }
}
