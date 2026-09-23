package com.rpgcore.plugin.economy;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The market, as one chest screen per category.
 *
 * Every good is drawn as itself, with its price, its supply and its chart
 * written into the tooltip. That is the whole point of the screen: a player
 * should be able to answer "is iron expensive right now?" by looking at iron,
 * not by reading a table of numbers somewhere else.
 *
 * Buying and selling are both clicks on the same icon - left buys, right
 * sells, shift does a stack - so there is no mode to be in and no way to sell
 * something while thinking you are buying it. The amounts are fixed rather
 * than typed because a chest GUI has nowhere to type, and Bedrock players
 * cannot be given an anvil text box.
 */
public final class MarketMenu {

    /** How the goods on a page are ordered. */
    public enum Sort {
        CATALOGUE("기본순"),
        PRICE("가격 높은순"),
        CHANGE("등락률순"),
        SUPPLY("재고 적은순");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final int SIZE = 54;
    private static final int[] ITEM_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, String> goods = new HashMap<>();
        private final Map<Integer, String> tabs = new HashMap<>();
        private String category = "";
        private Sort sort = Sort.CATALOGUE;
        private int page;
        private int previousSlot = -1;
        private int nextSlot = -1;
        private int backSlot = -1;
        private int sortSlot = -1;
        private int sellHandSlot = -1;
        private int bankSlot = -1;
        private int dashboardSlot = -1;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public String goodAt(int slot) {
            return goods.get(slot);
        }

        public String tabAt(int slot) {
            return tabs.get(slot);
        }

        public String category() {
            return category;
        }

        public Sort sort() {
            return sort;
        }

        public int page() {
            return page;
        }

        public int previousSlot() {
            return previousSlot;
        }

        public int nextSlot() {
            return nextSlot;
        }

        public int backSlot() {
            return backSlot;
        }

        public int sortSlot() {
            return sortSlot;
        }

        public int sellHandSlot() {
            return sellHandSlot;
        }

        public int bankSlot() {
            return bankSlot;
        }

        public int dashboardSlot() {
            return dashboardSlot;
        }
    }

    private final RpgCorePlugin plugin;

    public MarketMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        List<EconomyConfig.Category> categories = plugin.economyConfig().categories();
        open(player, categories.isEmpty() ? "" : categories.get(0).id(), 0, Sort.CATALOGUE);
    }

    public void open(Player player, String category, int page, Sort sort) {
        if (!plugin.market().enabled()) {
            player.sendMessage(ChatColor.RED + "[시장] 이 서버에서는 시장을 쓸 수 없습니다.");
            return;
        }
        MarketService market = plugin.market();
        List<MarketItem> goods = category == null || category.isBlank()
                ? new ArrayList<>(market.items().values())
                : market.inCategory(category);
        sortGoods(goods, sort);

        int pages = Math.max(1, (goods.size() + ITEM_SLOTS.length - 1) / ITEM_SLOTS.length);
        int shown = Math.clamp(page, 0, pages - 1);

        Holder holder = new Holder();
        holder.category = category == null ? "" : category;
        holder.sort = sort;
        holder.page = shown;
        Inventory inv = plugin.getServer().createInventory(holder, SIZE,
                ChatColor.translateAlternateColorCodes('&', plugin.economyConfig().marketTitle())
                        + ChatColor.DARK_GRAY + " · " + categoryName(category)
                        + " (" + (shown + 1) + "/" + pages + ")");
        holder.setInventory(inv);

        for (int slot = 0; slot < SIZE; slot++) {
            inv.setItem(slot, filler());
        }
        drawTabs(inv, holder, category);

        // Counted once for the whole screen rather than once per good.
        Map<Material, Integer> held = market.plainCounts(player);
        int first = shown * ITEM_SLOTS.length;
        for (int i = 0; i < ITEM_SLOTS.length && first + i < goods.size(); i++) {
            MarketItem item = goods.get(first + i);
            inv.setItem(ITEM_SLOTS[i], icon(item, held.getOrDefault(item.material(), 0)));
            holder.goods.put(ITEM_SLOTS[i], item.id());
        }

        holder.backSlot = 45;
        inv.setItem(45, button(Material.ARROW, "&c뒤로", List.of(ChatColor.GRAY + "/menu 로 돌아갑니다")));
        holder.sortSlot = 46;
        inv.setItem(46, button(Material.HOPPER, "&b정렬: &f" + sort.label(),
                List.of(ChatColor.YELLOW + "클릭하여 " + sort.next().label() + " 으로")));
        holder.dashboardSlot = 47;
        inv.setItem(47, button(Material.CLOCK, "&e경제 지표",
                List.of(ChatColor.GRAY + "물가 · 금리 · 통화량 · 경기",
                        ChatColor.YELLOW + "클릭하여 열기")));
        if (shown > 0) {
            holder.previousSlot = 48;
            inv.setItem(48, button(Material.SPECTRAL_ARROW, "&e이전 쪽", List.of()));
        }
        inv.setItem(49, indexCard());
        if (shown < pages - 1) {
            holder.nextSlot = 50;
            inv.setItem(50, button(Material.SPECTRAL_ARROW, "&e다음 쪽", List.of()));
        }
        holder.bankSlot = 51;
        inv.setItem(51, button(Material.CHEST, "&a은행",
                List.of(ChatColor.GRAY + "예금 · 대출 · 신용등급",
                        ChatColor.YELLOW + "클릭하여 열기")));
        inv.setItem(52, walletCard(player));
        holder.sellHandSlot = 53;
        inv.setItem(53, button(Material.GOLD_INGOT, "&6손에 든 것 팔기",
                List.of(ChatColor.GRAY + "들고 있는 한 묶음을 즉시 시세로 팝니다",
                        ChatColor.GRAY + "명령어로는 /market sellhand")));

        player.openInventory(inv);
    }

    private void sortGoods(List<MarketItem> goods, Sort sort) {
        switch (sort) {
            case PRICE -> goods.sort(Comparator.comparingDouble(MarketItem::mid).reversed());
            case CHANGE -> goods.sort(Comparator.comparingDouble(MarketItem::changePercent).reversed());
            case SUPPLY -> goods.sort(Comparator.comparingDouble(MarketItem::supplyPercent));
            case CATALOGUE -> {
                // Left as the catalogue order, which is how the operator
                // grouped them and therefore how players learn the screen.
            }
        }
    }

    private void drawTabs(Inventory inv, Holder holder, String current) {
        int slot = 0;
        for (EconomyConfig.Category category : plugin.economyConfig().categories()) {
            if (slot > 8) {
                break;
            }
            boolean active = category.id().equalsIgnoreCase(current);
            int count = plugin.market().inCategory(category.id()).size();
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + String.valueOf(count) + "개 품목");
            lore.add(active ? ChatColor.GREEN + "보는 중" : ChatColor.YELLOW + "클릭하여 보기");
            ItemStack icon = button(category.icon(),
                    (active ? "&a▶ " : "&f") + category.name(), lore);
            if (active) {
                // A glint rather than a different material: the row must not
                // move under the player's cursor when they change tab.
                icon.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.UNBREAKING, 1);
                ItemMeta meta = icon.getItemMeta();
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
                icon.setItemMeta(meta);
            }
            inv.setItem(slot, icon);
            holder.tabs.put(slot, category.id());
            slot++;
        }
    }

    /** One good: what it costs, what it is worth, and what it has been doing. */
    private ItemStack icon(MarketItem item, int held) {
        EconomyConfig config = plugin.economyConfig();
        MarketService market = plugin.market();

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + item.id() + "  " + ChatColor.GRAY + categoryName(item.category()));
        lore.add("");
        lore.add(ChatColor.WHITE + "살 때 " + ChatColor.GOLD
                + MarketService.money(item.ask(config)) + plugin.rpgConfig().goldSymbol()
                + ChatColor.DARK_GRAY + "  /  " + ChatColor.WHITE + "팔 때 " + ChatColor.YELLOW
                + MarketService.money(item.bid(config)) + plugin.rpgConfig().goldSymbol());
        lore.add(ChatColor.GRAY + "전일 대비 " + MarketService.change(item.changePercent())
                + ChatColor.GRAY + "  기준가 대비 "
                + String.format(Locale.ROOT, "%.0f%%", item.valuationPercent()));
        String chart = item.sparkline();
        if (!chart.isEmpty()) {
            lore.add(ChatColor.AQUA + chart + ChatColor.DARK_GRAY + " (" + item.history().size() + "일)");
        }
        lore.add("");
        lore.add(ChatColor.GRAY + "재고 " + ChatColor.WHITE
                + String.format(Locale.ROOT, "%,.0f", item.stock()) + ChatColor.GRAY + " / 기준 "
                + String.format(Locale.ROOT, "%,.0f", item.baseStock()) + " ("
                + supplyColour(item.supplyPercent())
                + String.format(Locale.ROOT, "%.0f%%", item.supplyPercent()) + ChatColor.GRAY + ")");
        lore.add(ChatColor.GRAY + "시간당 채굴 " + trim(item.perHour()) + "개 · 수요탄력성 "
                + trim(item.elasticity())
                + (item.basket() > 0 ? " · 물가바스켓 " + trim(item.basket()) : ""));
        if (item.boughtToday() + item.soldToday() > 0) {
            lore.add(ChatColor.DARK_GRAY + "오늘 거래 " + (item.boughtToday() + item.soldToday())
                    + "개 (순 " + (item.netFlowToday() >= 0 ? "매수 " : "매도 ")
                    + Math.abs(item.netFlowToday()) + ")");
        }
        if (item.shockNote() != null) {
            lore.add(ChatColor.LIGHT_PURPLE + "[시황] " + item.shockNote());
        }
        lore.add("");
        lore.add(ChatColor.GRAY + "보유 " + ChatColor.WHITE + held + ChatColor.GRAY + "개"
                + (held > 0 ? " · 전량 매도 약 " + ChatColor.YELLOW
                + market.quoteSell(item, held).total() + plugin.rpgConfig().goldSymbol() : ""));
        lore.add(ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 1개 구매 · "
                + ChatColor.YELLOW + "Shift+좌클릭" + ChatColor.GRAY + " 한 묶음 구매");
        lore.add(ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + " 1개 판매 · "
                + ChatColor.YELLOW + "Shift+우클릭" + ChatColor.GRAY + " 전량 판매");

        ItemStack stack = new ItemStack(item.material());
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.WHITE + item.id() + ChatColor.DARK_GRAY + " · "
                + ChatColor.GOLD + MarketService.money(item.mid()) + plugin.rpgConfig().goldSymbol());
        meta.setLore(lore);
        // Otherwise a golden apple in the market glows like a magic item and
        // an unenchanted one next to it looks broken.
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack indexCard() {
        var macro = plugin.macro();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "물가지수 " + ChatColor.WHITE
                + String.format(Locale.ROOT, "%.1f", macro.cpi()) + ChatColor.DARK_GRAY + " (기준 100)");
        lore.add(ChatColor.GRAY + "물가상승률 " + ChatColor.WHITE + MacroService.signed(macro.inflation()));
        lore.add(ChatColor.GRAY + "정책금리 " + ChatColor.WHITE + BankService.percent(macro.policyRate()));
        lore.add(ChatColor.GRAY + "경기 " + macro.cycleColour() + macro.cyclePhase());
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + String.valueOf(macro.day()) + "일차 · 다음 정산까지 "
                + (100 - macro.dayProgressPercent()) + "%");
        return button(Material.PAPER, "&6오늘의 시황", lore);
    }

    private ItemStack walletCard(Player player) {
        var account = plugin.bank().peek(player.getUniqueId());
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "지갑 " + ChatColor.YELLOW + plugin.economy().balance(player)
                + plugin.rpgConfig().goldSymbol());
        if (account != null) {
            lore.add(ChatColor.GRAY + "예금 " + ChatColor.YELLOW + account.totalDeposits()
                    + plugin.rpgConfig().goldSymbol());
            if (account.totalDebt() > 0) {
                lore.add(ChatColor.GRAY + "채무 " + ChatColor.RED + account.totalDebt()
                        + plugin.rpgConfig().goldSymbol());
            }
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "시장 금고 "
                + String.format(Locale.ROOT, "%,d", plugin.market().treasury()));
        return button(Material.GOLD_NUGGET, "&6내 자산", lore);
    }

    private String categoryName(String id) {
        EconomyConfig.Category category = plugin.economyConfig().category(id);
        return category == null ? (id == null || id.isBlank() ? "전체" : id) : category.name();
    }

    private static ChatColor supplyColour(double percent) {
        if (percent < 40) {
            return ChatColor.RED;
        }
        if (percent < 80) {
            return ChatColor.YELLOW;
        }
        return ChatColor.GREEN;
    }

    /** Numbers as short as they can be: 18 not 18.0, 2.5 not 2.50. */
    public static String trim(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) {
            return String.format(Locale.ROOT, "%,.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private ItemStack filler() {
        return button(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
