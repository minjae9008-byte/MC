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

/**
 * The company screen: what it owns, what it makes, and the buttons that
 * change either.
 *
 * Two views rather than one. The overview is the running business - plants,
 * warehouse, money - and the second is the catalogue of plants that could be
 * built. Mixing them put a "spend 70,000 gold" button next to a "sell this
 * factory" button, which is the kind of neighbourhood a mis-click is
 * expensive in.
 */
public final class CompanyMenu {

    public enum View {
        OVERVIEW, FACTORY_SHOP
    }

    public enum Action {
        DEPOSIT, WITHDRAW, SUPPLY, ISSUE, SELL_POLICY, DIVIDEND_POLICY, AUTO_BUY,
        FACTORY_SHOP, BACK_TO_OVERVIEW, STOCKS, BLUEPRINTS, BACK, CLOSE
    }

    private static final int SIZE = 54;

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, Action> actions = new HashMap<>();
        private final Map<Integer, Integer> factorySlots = new HashMap<>();
        private final Map<Integer, String> buildSlots = new HashMap<>();
        private final Map<Integer, Material> warehouseSlots = new HashMap<>();
        private View view = View.OVERVIEW;

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        public Action actionAt(int slot) {
            return actions.get(slot);
        }

        public Integer factoryAt(int slot) {
            return factorySlots.get(slot);
        }

        public String buildAt(int slot) {
            return buildSlots.get(slot);
        }

        public Material warehouseAt(int slot) {
            return warehouseSlots.get(slot);
        }

        public View view() {
            return view;
        }
    }

    private final RpgCorePlugin plugin;

    public CompanyMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        open(player, View.OVERVIEW);
    }

    public void open(Player player, View view) {
        if (!plugin.corps().enabled()) {
            player.sendMessage(ChatColor.RED + "[기업] 이 서버에서는 기업 기능을 쓸 수 없습니다.");
            return;
        }
        Company company = plugin.corps().employerOf(player);
        if (company == null) {
            player.sendMessage(ChatColor.YELLOW + "[기업] 아직 회사가 없습니다.");
            player.sendMessage(ChatColor.GRAY + "  /company create <이름> 으로 설립하거나, "
                    + "/stocks 에서 남의 회사에 투자할 수 있습니다.");
            return;
        }

        Holder holder = new Holder();
        holder.view = view;
        Inventory inv = plugin.getServer().createInventory(holder, SIZE,
                ChatColor.DARK_GRAY + company.name() + " (" + company.ticker() + ")"
                        + (view == View.FACTORY_SHOP ? " · 공장 건설" : ""));
        holder.setInventory(inv);
        for (int slot = 0; slot < SIZE; slot++) {
            inv.setItem(slot, filler());
        }

        inv.setItem(4, card(company));
        if (view == View.FACTORY_SHOP) {
            drawFactoryShop(inv, holder, company);
        } else {
            drawOverview(inv, holder, player, company);
        }

        button(inv, holder, 45, Action.BACK, Material.ARROW, "&c뒤로",
                List.of(ChatColor.GRAY + "/menu 로 돌아갑니다"));
        button(inv, holder, 47, Action.STOCKS, Material.EMERALD, "&a거래소",
                List.of(ChatColor.GRAY + "다른 회사 주식을 사고팝니다", ChatColor.YELLOW + "클릭"));
        button(inv, holder, 51, Action.BLUEPRINTS, Material.FILLED_MAP, "&b청사진",
                List.of(ChatColor.GRAY + "회사 자금으로 건물을 올립니다", ChatColor.YELLOW + "클릭"));
        button(inv, holder, 53, Action.CLOSE, Material.RED_STAINED_GLASS_PANE, "&c닫기", List.of());
        player.openInventory(inv);
    }

    private void drawOverview(Inventory inv, Holder holder, Player player, Company company) {
        CorpConfig config = plugin.corps().config();
        boolean manager = company.manages(player.getUniqueId());

        button(inv, holder, 10, Action.DEPOSIT, Material.GOLD_INGOT, "&a자금 투입", List.of(
                ChatColor.GRAY + "내 지갑에서 회사로 넣습니다.",
                ChatColor.GRAY + "회사 현금 " + ChatColor.WHITE + comma(company.cash()),
                "",
                ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 1,000 · "
                        + ChatColor.YELLOW + "Shift+좌클릭" + ChatColor.GRAY + " 10,000",
                ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + " 100,000"));
        button(inv, holder, 11, Action.WITHDRAW, Material.GOLD_NUGGET, "&e자금 회수", List.of(
                manager ? ChatColor.GRAY + "회사에서 내 지갑으로 뺍니다."
                        : ChatColor.RED + "대표나 임원만 쓸 수 있습니다.",
                "",
                ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 1,000 · "
                        + ChatColor.YELLOW + "Shift+좌클릭" + ChatColor.GRAY + " 10,000",
                ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + " 100,000"));
        button(inv, holder, 12, Action.SUPPLY, Material.HOPPER, "&b납품", List.of(
                ChatColor.GRAY + "손에 든 물건을 회사 창고에 넘기고",
                ChatColor.GRAY + "시장 매도가만큼 대금을 받습니다.",
                "",
                ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 손에 든 묶음 · "
                        + ChatColor.YELLOW + "Shift+좌클릭" + ChatColor.GRAY + " 같은 품목 전량"));
        button(inv, holder, 13, Action.FACTORY_SHOP, Material.BLAST_FURNACE, "&6공장 건설", List.of(
                ChatColor.GRAY + "지을 수 있는 공장 목록을 엽니다.",
                ChatColor.GRAY + "보유 공장 " + ChatColor.WHITE + company.factories().size() + "개",
                ChatColor.YELLOW + "클릭하여 열기"));
        button(inv, holder, 14, Action.SELL_POLICY, Material.CHEST_MINECART, "&f출고 정책", List.of(
                ChatColor.GRAY + "매 경제일마다 창고의 " + ChatColor.WHITE + company.sellPercent()
                        + "%" + ChatColor.GRAY + " 를 시장에 팝니다.",
                "",
                ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " +10% · "
                        + ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + " -10%"));
        button(inv, holder, 15, Action.DIVIDEND_POLICY, Material.EMERALD, "&f배당 정책", List.of(
                ChatColor.GRAY + "이익의 " + ChatColor.WHITE + company.dividendPercent() + "%"
                        + ChatColor.GRAY + " 를 주주에게 나눕니다.",
                ChatColor.GRAY + "지난 배당 " + ChatColor.WHITE + comma(company.lastDividends()),
                "",
                ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " +10% · "
                        + ChatColor.YELLOW + "우클릭" + ChatColor.GRAY + " -10%"));
        button(inv, holder, 16, Action.AUTO_BUY, Material.COMPARATOR, "&f원료 자동 조달", List.of(
                ChatColor.GRAY + "지금 " + (company.autoBuyInputs()
                        ? ChatColor.GREEN + "켜짐" : ChatColor.RED + "꺼짐"),
                ChatColor.GRAY + "모자란 원료를 시장에서 사 옵니다.",
                ChatColor.DARK_GRAY + "끄면 창고에 있는 만큼만 만듭니다.",
                "",
                ChatColor.YELLOW + "클릭하여 전환"));

        // --- factories ---
        int slot = 19;
        if (company.factories().isEmpty()) {
            inv.setItem(slot, plain(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7공장 없음",
                    List.of(ChatColor.DARK_GRAY + "공장이 있어야 매출이 생깁니다.")));
        }
        for (int i = 0; i < company.factories().size() && slot <= 25; i++, slot++) {
            Factory factory = company.factories().get(i);
            CorpConfig.FactoryType type = config.factory(factory.typeId());
            List<String> lore = new ArrayList<>();
            if (type == null) {
                lore.add(ChatColor.RED + "설비 정보 없음 (" + factory.typeId() + ")");
                inv.setItem(slot, plain(Material.BARRIER, "&c알 수 없는 공장", lore));
                continue;
            }
            lore.add(ChatColor.GRAY + "Lv." + factory.level() + " / " + config.maxLevel()
                    + " · 하루 " + ChatColor.WHITE
                    + Math.round(config.outputAt(type, factory.level())) + "개");
            lore.add(ChatColor.GRAY + "유지비 " + ChatColor.WHITE
                    + comma(config.upkeepAt(type, factory.level())) + ChatColor.GRAY + "/일");
            if (!type.inputs().isEmpty()) {
                StringBuilder inputs = new StringBuilder();
                for (Map.Entry<Material, Double> entry : type.inputs().entrySet()) {
                    inputs.append(entry.getKey().getKey().getKey()).append(" x")
                            .append(trim(entry.getValue())).append(" ");
                }
                lore.add(ChatColor.GRAY + "원료 " + ChatColor.DARK_GRAY + inputs.toString().trim());
            }
            lore.add(factory.idleReason() == null
                    ? ChatColor.GREEN + "가동 중 · 어제 " + comma(factory.lastOutput()) + "개"
                    : ChatColor.RED + factory.idleReason());
            lore.add("");
            if (factory.level() < config.maxLevel()) {
                lore.add(ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 증설 ("
                        + comma(config.upgradeCostAt(type, factory.level())) + "골드)");
            }
            lore.add(ChatColor.YELLOW + "Shift+우클릭" + ChatColor.GRAY + " 매각 (건설비의 70%)");
            ItemStack icon = plain(type.icon(), "&6" + type.name() + " &7Lv." + factory.level(), lore);
            inv.setItem(slot, icon);
            holder.factorySlots.put(slot, i);
        }

        // --- warehouse ---
        slot = 28;
        if (company.warehouse().isEmpty()) {
            inv.setItem(slot, plain(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7창고 비어 있음",
                    List.of(ChatColor.DARK_GRAY + "생산하거나 납품받으면 여기 쌓입니다.")));
        }
        List<Map.Entry<Material, Long>> stock = new ArrayList<>(company.warehouse().entrySet());
        stock.sort(Map.Entry.<Material, Long>comparingByValue().reversed());
        for (int i = 0; i < stock.size() && slot <= 34; i++, slot++) {
            Material material = stock.get(i).getKey();
            long amount = stock.get(i).getValue();
            MarketItem item = plugin.market().byMaterial(material);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "보관 " + ChatColor.WHITE + comma(amount) + "개");
            if (item != null) {
                lore.add(ChatColor.GRAY + "시장 매도가 " + ChatColor.YELLOW
                        + MarketService.money(item.bid(plugin.economyConfig()))
                        + ChatColor.GRAY + " · 전량 약 " + ChatColor.YELLOW
                        + comma(Math.round(item.bid(plugin.economyConfig()) * amount)));
            }
            lore.add("");
            lore.add(ChatColor.YELLOW + "클릭" + ChatColor.GRAY + " 지금 전량 출고");
            inv.setItem(slot, plain(material, "&f" + material.getKey().getKey(), lore));
            holder.warehouseSlots.put(slot, material);
        }

        inv.setItem(37, ledger(company));
        button(inv, holder, 38, Action.ISSUE, Material.PAPER, "&f신주 발행", List.of(
                ChatColor.GRAY + "발행 " + ChatColor.WHITE + comma(company.sharesIssued())
                        + ChatColor.GRAY + "주 · 자사주 " + ChatColor.WHITE
                        + comma(company.treasuryShares()),
                ChatColor.GRAY + "자사주가 없으면 아무도 투자할 수 없습니다.",
                ChatColor.DARK_GRAY + "발행하면 기존 주주 지분이 희석됩니다.",
                "",
                ChatColor.YELLOW + "좌클릭" + ChatColor.GRAY + " 1,000주 · "
                        + ChatColor.YELLOW + "Shift+좌클릭" + ChatColor.GRAY + " 10,000주"));
        inv.setItem(39, staffCard(company));
    }

    private void drawFactoryShop(Inventory inv, Holder holder, Company company) {
        CorpConfig config = plugin.corps().config();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        List<CorpConfig.FactoryType> types = config.factories();
        for (int i = 0; i < slots.length && i < types.size(); i++) {
            CorpConfig.FactoryType type = types.get(i);
            MarketItem item = plugin.market().byMaterial(type.output());
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "생산 " + ChatColor.WHITE + type.output().getKey().getKey()
                    + ChatColor.GRAY + " · 하루 " + ChatColor.WHITE
                    + Math.round(type.perDay()) + "개");
            if (item != null) {
                double revenue = item.bid(plugin.economyConfig()) * type.perDay();
                lore.add(ChatColor.GRAY + "지금 시세로 하루 매출 약 " + ChatColor.YELLOW
                        + comma(Math.round(revenue)));
                lore.add(ChatColor.GRAY + "유지비 " + ChatColor.WHITE + comma(type.upkeep())
                        + ChatColor.GRAY + " → 하루 이익 약 "
                        + (revenue - type.upkeep() >= 0 ? ChatColor.GREEN : ChatColor.RED)
                        + comma(Math.round(revenue - type.upkeep())));
            }
            if (!type.inputs().isEmpty()) {
                lore.add("");
                lore.add(ChatColor.GRAY + "필요 원료 (산출 1개당):");
                for (Map.Entry<Material, Double> entry : type.inputs().entrySet()) {
                    lore.add(ChatColor.DARK_GRAY + "  " + entry.getKey().getKey().getKey()
                            + " x" + trim(entry.getValue()));
                }
                lore.add(ChatColor.DARK_GRAY + "  창고에 없으면 시장에서 사 옵니다");
            }
            lore.add("");
            lore.add(ChatColor.GRAY + "건설비 " + ChatColor.GOLD + comma(type.buildCost()));
            lore.add(company.cash() >= type.buildCost()
                    ? ChatColor.YELLOW + "클릭하여 건설"
                    : ChatColor.RED + "회사 현금이 부족합니다");
            inv.setItem(slots[i], plain(type.icon(), "&6" + type.name(), lore));
            holder.buildSlots.put(slots[i], type.id());
        }
        button(inv, holder, 49, Action.BACK_TO_OVERVIEW, Material.ARROW, "&e회사 화면으로",
                List.of(ChatColor.YELLOW + "클릭"));
    }

    private ItemStack card(Company company) {
        CorpService corps = plugin.corps();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "현금 " + (company.cash() < 0 ? ChatColor.RED : ChatColor.YELLOW)
                + comma(company.cash()));
        lore.add(ChatColor.GRAY + "창고 가치 " + ChatColor.WHITE
                + comma(Math.round(corps.warehouseValue(company)))
                + ChatColor.GRAY + " · 설비 " + ChatColor.WHITE
                + comma(Math.round(corps.factoryValue(company))));
        lore.add(ChatColor.GRAY + "기업가치 " + ChatColor.GOLD
                + comma(Math.round(corps.fairValue(company))));
        lore.add("");
        lore.add(ChatColor.GRAY + "주가 " + ChatColor.WHITE
                + MarketService.money(company.sharePrice())
                + ChatColor.GRAY + " · 발행 " + comma(company.sharesIssued()) + "주");
        lore.add(ChatColor.GRAY + "어제 매출 " + ChatColor.WHITE + comma(company.lastRevenue())
                + ChatColor.GRAY + " · 이익 "
                + (company.lastProfit() >= 0 ? ChatColor.GREEN : ChatColor.RED)
                + comma(company.lastProfit()));
        lore.add(ChatColor.GRAY + "영업 " + company.daysOperating() + "일차");
        return plain(Material.WRITABLE_BOOK, "&6" + company.name() + " &7(" + company.ticker() + ")", lore);
    }

    private ItemStack ledger(Company company) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "매출 " + ChatColor.WHITE + comma(company.lastRevenue()));
        lore.add(ChatColor.GRAY + "비용 " + ChatColor.WHITE + comma(company.lastCosts())
                + ChatColor.DARK_GRAY + " (유지비 · 원료)");
        lore.add(ChatColor.GRAY + "임금 " + ChatColor.WHITE + comma(company.lastWages()));
        lore.add(ChatColor.GRAY + "배당 " + ChatColor.WHITE + comma(company.lastDividends()));
        lore.add(ChatColor.GRAY + "이익 " + (company.lastProfit() >= 0
                ? ChatColor.GREEN : ChatColor.RED) + comma(company.lastProfit()));
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "누적 매출 " + comma(company.lifetimeRevenue())
                + " · 누적 배당 " + comma(company.lifetimeDividends()));
        return plain(Material.BOOK, "&f지난 경제일 손익", lore);
    }

    private ItemStack staffCard(Company company) {
        CorpConfig config = plugin.corps().config();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "인원 " + ChatColor.WHITE + company.headcount()
                + " / " + config.maxEmployees());
        lore.add(ChatColor.GRAY + "일당 " + ChatColor.WHITE + comma(config.wagePerEmployee())
                + ChatColor.GRAY + " (대표 x" + trim(config.ceoWageMultiplier()) + ")");
        lore.add("");
        int shown = 0;
        for (Company.Employee employee : company.employees().values()) {
            if (shown++ >= 8) {
                lore.add(ChatColor.DARK_GRAY + " ...");
                break;
            }
            lore.add(ChatColor.DARK_GRAY + " " + employee.name() + " · " + employee.role().label()
                    + (employee.unpaidDays() > 0
                    ? ChatColor.RED + " (체불 " + employee.unpaidDays() + "일)" : ""));
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "/company hire <플레이어> 로 채용");
        return plain(Material.PLAYER_HEAD, "&f사원", lore);
    }

    private static String comma(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String trim(double value) {
        return com.rpgcore.plugin.economy.MarketMenu.trim(value);
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
