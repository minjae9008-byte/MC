package com.rpgcore.plugin.corp;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.economy.MarketItem;
import com.rpgcore.plugin.economy.MarketService;
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
 * The exchange: every listed company, what it is worth, and what a share of
 * it costs.
 *
 * The tooltip is the whole pitch. A player deciding where to put ten thousand
 * gold needs the price, what the business actually earned, what it paid out,
 * and how much of it is still for sale - and needs all of that without
 * leaving the screen, because the alternative is guessing by name.
 */
public final class StockMenu {

    public enum Action {
        PORTFOLIO, COMPANY, BACK, CLOSE
    }

    private static final int SIZE = 54;
    private static final int[] SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43};

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, UUID> companies = new HashMap<>();
        private final Map<Integer, Action> actions = new HashMap<>();
        private int page;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public UUID companyAt(int slot) {
            return companies.get(slot);
        }

        public Action actionAt(int slot) {
            return actions.get(slot);
        }

        public int page() {
            return page;
        }
    }

    private final RpgCorePlugin plugin;

    public StockMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        open(player, 0);
    }

    public void open(Player player, int page) {
        if (!plugin.corps().enabled()) {
            player.sendMessage(ChatColor.RED + "[주식] 이 서버에서는 기업 기능을 쓸 수 없습니다.");
            return;
        }
        CorpService corps = plugin.corps();
        List<Company> listed = corps.listed();
        int pages = Math.max(1, (listed.size() + SLOTS.length - 1) / SLOTS.length);
        int shown = Math.clamp(page, 0, pages - 1);

        Holder holder = new Holder();
        holder.page = shown;
        Inventory inv = plugin.getServer().createInventory(holder, SIZE,
                ChatColor.DARK_GRAY + "거래소 (" + (shown + 1) + "/" + pages + ")");
        holder.setInventory(inv);
        for (int slot = 0; slot < SIZE; slot++) {
            inv.setItem(slot, filler());
        }

        int first = shown * SLOTS.length;
        for (int i = 0; i < SLOTS.length && first + i < listed.size(); i++) {
            Company company = listed.get(first + i);
            inv.setItem(SLOTS[i], icon(player, company));
            holder.companies.put(SLOTS[i], company.id());
        }

        button(inv, holder, 45, Action.BACK, Material.ARROW, "&c뒤로",
                List.of(ChatColor.GRAY + "/menu 로 돌아갑니다"));
        button(inv, holder, 47, Action.COMPANY, Material.WRITABLE_BOOK, "&6내 회사",
                List.of(ChatColor.GRAY + "회사 화면을 엽니다", ChatColor.YELLOW + "클릭"));
        inv.setItem(49, portfolio(player));
        button(inv, holder, 53, Action.CLOSE, Material.RED_STAINED_GLASS_PANE, "&c닫기", List.of());
        player.openInventory(inv);
    }

    private ItemStack icon(Player player, Company company) {
        CorpService corps = plugin.corps();
        long mine = company.sharesOf(player.getUniqueId());
        double yield = corps.dividendYieldPercent(company);
        double pe = corps.priceEarnings(company);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + (company.npc() ? "공모 기업" : "대표 " + ceoName(company))
                + " · 영업 " + company.daysOperating() + "일");
        lore.add("");
        lore.add(ChatColor.WHITE + "살 때 " + ChatColor.GOLD
                + MarketService.money(corps.askPrice(company)) + plugin.rpgConfig().goldSymbol()
                + ChatColor.DARK_GRAY + "  /  " + ChatColor.WHITE + "팔 때 " + ChatColor.YELLOW
                + MarketService.money(corps.bidPrice(company)) + plugin.rpgConfig().goldSymbol());
        String chart = MarketItem.spark(company.priceHistory());
        if (!chart.isEmpty()) {
            lore.add(ChatColor.AQUA + chart + ChatColor.DARK_GRAY + " (최근)");
        }
        lore.add(ChatColor.GRAY + "시가총액 " + ChatColor.WHITE
                + comma(Math.round(corps.marketCap(company)))
                + ChatColor.GRAY + " · 발행 " + comma(company.sharesIssued()) + "주");
        lore.add("");
        lore.add(ChatColor.GRAY + "어제 이익 " + (company.lastProfit() >= 0
                ? ChatColor.GREEN : ChatColor.RED) + comma(company.lastProfit())
                + ChatColor.GRAY + " · 매출 " + comma(company.lastRevenue()));
        lore.add(ChatColor.GRAY + "배당수익률 " + ChatColor.WHITE
                + String.format(Locale.ROOT, "%.1f%%", yield)
                + ChatColor.GRAY + " · PER " + ChatColor.WHITE
                + (pe <= 0 ? "-" : String.format(Locale.ROOT, "%.1f", pe)));
        lore.add(ChatColor.GRAY + "공장 " + company.factories().size() + "개 · 현금 "
                + comma(company.cash()));
        lore.add(ChatColor.GRAY + "살 수 있는 물량 " + ChatColor.WHITE
                + comma(company.treasuryShares()) + "주"
                + (company.npc() ? ChatColor.DARK_GRAY + " (공모)" : ""));
        lore.add("");
        if (mine > 0) {
            lore.add(ChatColor.GREEN + "보유 " + comma(mine) + "주 · 지분 "
                    + String.format(Locale.ROOT, "%.1f%%",
                    mine * 100.0 / Math.max(1, company.sharesIssued()))
                    + " · 평가 " + comma(Math.round(mine * corps.bidPrice(company))));
        }
        lore.add(ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 10주 매수 · "
                + ChatColor.YELLOW + "Shift+좌클릭" + ChatColor.GRAY + " 100주");
        lore.add(ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + " 10주 매도 · "
                + ChatColor.YELLOW + "Shift+우클릭" + ChatColor.GRAY + " 전량");

        Material material = Material.PAPER;
        if (!company.factories().isEmpty()) {
            CorpConfig.FactoryType type = corps.config().factory(company.factories().get(0).typeId());
            if (type != null) {
                material = type.icon();
            }
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + company.ticker() + ChatColor.DARK_GRAY + " · "
                + ChatColor.WHITE + company.name());
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        stack.setItemMeta(meta);
        return stack;
    }

    private String ceoName(Company company) {
        if (company.ceo() == null) {
            return "없음";
        }
        Company.Employee employee = company.employee(company.ceo());
        return employee == null ? "?" : employee.name();
    }

    private ItemStack portfolio(Player player) {
        CorpService corps = plugin.corps();
        Map<Company, Long> holdings = corps.portfolioOf(player.getUniqueId());
        List<String> lore = new ArrayList<>();
        long value = 0;
        for (Map.Entry<Company, Long> entry : holdings.entrySet()) {
            long worth = Math.round(corps.bidPrice(entry.getKey()) * entry.getValue());
            value += worth;
            if (lore.size() < 10) {
                lore.add(ChatColor.GRAY + " " + entry.getKey().ticker() + " "
                        + comma(entry.getValue()) + "주 · " + ChatColor.WHITE + comma(worth));
            }
        }
        if (holdings.isEmpty()) {
            lore.add(ChatColor.DARK_GRAY + "가진 주식이 없습니다.");
            lore.add(ChatColor.DARK_GRAY + "회사를 클릭해 매수하세요.");
        } else {
            lore.add("");
            lore.add(ChatColor.GRAY + "평가액 합계 " + ChatColor.GOLD + comma(value));
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "지갑 " + comma(plugin.economy().balance(player)));
        return plain(Material.GOLD_INGOT, "&6내 포트폴리오", lore);
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
