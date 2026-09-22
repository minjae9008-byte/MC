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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The dashboard: the whole economy on one screen.
 *
 * Everything on it is measured rather than decorative, and every tile says
 * both the number and what the number means - an indicator nobody can read is
 * an indicator nobody uses. The tiles are grouped the way the economy works:
 * prices and the rate that answers them on the top row, money and output
 * under it, then the institutions, then what the market actually did today.
 */
public final class EconomyMenu {

    public enum Action {
        REFRESH, MARKET, BANK, BACK, CLOSE
    }

    private static final int SIZE = 54;

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, Action> actions = new HashMap<>();

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
    }

    private final RpgCorePlugin plugin;

    public EconomyMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        MacroService macro = plugin.macro();
        // The money supply is a stock, so it is counted when somebody looks
        // rather than only when the day turns.
        macro.refreshMoney();
        MarketService market = plugin.market();
        BankService bank = plugin.bank();
        EconomyConfig config = plugin.economyConfig();

        Holder holder = new Holder();
        Inventory inv = plugin.getServer().createInventory(holder, SIZE,
                ChatColor.DARK_GRAY + "경제 지표 · " + macro.day() + "일차");
        holder.setInventory(inv);
        for (int slot = 0; slot < SIZE; slot++) {
            inv.setItem(slot, filler());
        }

        inv.setItem(4, tile(Material.CLOCK, "&6경제 시계", List.of(
                ChatColor.GRAY + "오늘은 " + ChatColor.WHITE + macro.day() + ChatColor.GRAY + "일차",
                ChatColor.GRAY + "하루 " + ChatColor.WHITE + config.dayMinutes() + "분"
                        + ChatColor.GRAY + " · 1년 " + ChatColor.WHITE + config.daysPerYear() + "일",
                ChatColor.GRAY + "다음 정산까지 " + ChatColor.WHITE
                        + (100 - macro.dayProgressPercent()) + "%",
                ChatColor.DARK_GRAY + bar(macro.dayProgressPercent()),
                "",
                ChatColor.DARK_GRAY + "정산 때 하는 일: 재고 회복 → 가격 재계산",
                ChatColor.DARK_GRAY + "→ 지표 집계 → 금리 조정 → 이자 정산")));

        // --- 물가와 금리 ---
        inv.setItem(10, tile(Material.PAPER, "&f물가지수 (CPI)", List.of(
                ChatColor.GRAY + "지금 " + ChatColor.YELLOW
                        + String.format(Locale.ROOT, "%.1f", macro.cpi())
                        + ChatColor.DARK_GRAY + "  (첫날 100)",
                ChatColor.AQUA + MarketItem.spark(macro.cpiHistory()),
                ChatColor.GRAY + "통화 요인 물가수준 " + ChatColor.WHITE
                        + String.format(Locale.ROOT, "x%.3f", market.priceLevel()),
                "",
                ChatColor.DARK_GRAY + "물가바스켓에 든 품목들의 값을",
                ChatColor.DARK_GRAY + "가중평균해서 첫날과 비교한 것입니다.",
                ChatColor.DARK_GRAY + "물가수준은 그중 통화량이 밀어올린 몫입니다.")));
        inv.setItem(11, tile(inflationIcon(macro.inflation(), config), "&f물가상승률", List.of(
                ChatColor.GRAY + "연 " + inflationColour(macro.inflation(), config)
                        + MacroService.signed(macro.inflation()),
                ChatColor.GRAY + "목표 " + ChatColor.WHITE
                        + BankService.percent(config.inflationTargetPercent()),
                "",
                ChatColor.DARK_GRAY + "물가가 오르면 같은 골드로 살 수 있는 것이 줄어듭니다.",
                ChatColor.DARK_GRAY + "몬스터 처치·업적으로 골드가 계속 새로 생기므로",
                ChatColor.DARK_GRAY + "아무것도 안 하면 물가는 오르는 쪽입니다.")));
        inv.setItem(12, tile(Material.BELL, "&f정책금리", List.of(
                ChatColor.GRAY + "지금 " + ChatColor.YELLOW + BankService.percent(macro.policyRate()),
                ChatColor.GRAY + "실질금리 " + ChatColor.WHITE + MacroService.signed(macro.realRate())
                        + ChatColor.DARK_GRAY + " (금리 - 물가상승률)",
                "",
                ChatColor.DARK_GRAY + "테일러 준칙으로 매일 다시 정합니다:",
                ChatColor.DARK_GRAY + "중립금리 + 물가상승률",
                ChatColor.DARK_GRAY + "  + " + trim(config.taylorInflationWeight()) + " x (물가 - 목표)",
                ChatColor.DARK_GRAY + "  + " + trim(config.taylorOutputWeight()) + " x 산출갭",
                "",
                ChatColor.GRAY + "예금 " + BankService.percent(bank.depositRate())
                        + " · 대출 " + BankService.percent(macro.policyRate()
                        + config.loanMarginPercent()) + " 부터")));
        inv.setItem(13, tile(Material.GOLD_BLOCK, "&f통화량", List.of(
                ChatColor.GRAY + "M0 (지갑) " + ChatColor.YELLOW + comma(macro.m0()),
                ChatColor.GRAY + "M1 (+요구불예금) " + ChatColor.YELLOW + comma(macro.m1()),
                ChatColor.GRAY + "M2 (+정기예금) " + ChatColor.YELLOW + comma(macro.m2()),
                "",
                ChatColor.GRAY + "통화승수 " + ChatColor.WHITE
                        + String.format(Locale.ROOT, "%.2f", macro.moneyMultiplier())
                        + ChatColor.DARK_GRAY + " (한계 "
                        + String.format(Locale.ROOT, "%.1f", 100.0 / config.reserveRatioPercent()) + ")",
                ChatColor.DARK_GRAY + "은행이 예금을 다시 빌려주면서",
                ChatColor.DARK_GRAY + "돈이 몇 배로 불어났는지를 나타냅니다.",
                "",
                ChatColor.GRAY + "지난 하루 증가율 " + ChatColor.WHITE
                        + MacroService.signed(macro.moneyGrowth() * 100),
                ChatColor.DARK_GRAY + "MV=PQ: 돈이 생산보다 빨리 늘면 그만큼",
                ChatColor.DARK_GRAY + "물가로 넘어갑니다 (반영률 "
                        + (int) (config.moneyPassThrough() * 100) + "%).",
                "",
                ChatColor.GRAY + "누적 발권 " + ChatColor.RED + comma(macro.printedTotal()),
                ChatColor.DARK_GRAY + "시장 금고나 은행이 비면 중앙은행이 찍습니다.")));
        inv.setItem(14, tile(Material.BLAST_FURNACE, "&f생산 (GDP)", List.of(
                ChatColor.GRAY + "어제 하루 " + ChatColor.YELLOW + comma(macro.gdp()),
                ChatColor.GRAY + "오늘 지금까지 " + ChatColor.WHITE + comma(macro.gdpToday()),
                ChatColor.GRAY + "누적 거래액 " + ChatColor.WHITE + comma(macro.lifetimeTrade()),
                "",
                ChatColor.GRAY + "산출갭 " + ChatColor.WHITE + MacroService.signed(macro.outputGap()),
                ChatColor.GRAY + "경기 " + macro.cycleColour() + macro.cyclePhase(),
                "",
                ChatColor.DARK_GRAY + "산출갭은 오늘 거래액이 추세보다",
                ChatColor.DARK_GRAY + "얼마나 위/아래인지입니다. 금리가 여기 반응합니다.")));
        inv.setItem(15, tile(Material.MINECART, "&f화폐유통속도", List.of(
                ChatColor.GRAY + "V = " + ChatColor.YELLOW
                        + String.format(Locale.ROOT, "%.2f", macro.velocity()),
                "",
                ChatColor.DARK_GRAY + "한 해 거래액 / 통화량(M2).",
                ChatColor.DARK_GRAY + "같은 골드가 몇 번이나 손을 바꾸는가입니다.",
                ChatColor.DARK_GRAY + "낮으면 다들 쌓아두고만 있다는 뜻입니다.")));
        inv.setItem(16, tile(Material.COMPARATOR, "&f지니계수 (불평등)", List.of(
                ChatColor.GRAY + "G = " + giniColour(macro.gini())
                        + String.format(Locale.ROOT, "%.3f", macro.gini()),
                ChatColor.DARK_GRAY + bar((int) (macro.gini() * 100)),
                ChatColor.GRAY + comma(macro.measuredWallets()) + "명 기준 (순자산)",
                "",
                ChatColor.DARK_GRAY + "0 이면 모두가 똑같이 가진 것이고",
                ChatColor.DARK_GRAY + "1 이면 한 사람이 다 가진 것입니다.",
                ChatColor.DARK_GRAY + "0.4 를 넘으면 보통 격차가 크다고 봅니다.")));

        // --- 시장과 은행 ---
        inv.setItem(19, tile(Material.EMERALD, "&a시장 규모", List.of(
                ChatColor.GRAY + "품목 " + ChatColor.WHITE + market.size() + "종",
                ChatColor.GRAY + "시가총액 " + ChatColor.YELLOW
                        + String.format(Locale.ROOT, "%,.0f", market.totalMarketCap()),
                ChatColor.DARK_GRAY + "(재고 전부를 현재가로 매긴 값)",
                "",
                ChatColor.GRAY + "국고(시장 금고) " + ChatColor.YELLOW + comma(market.treasury()),
                ChatColor.GRAY + "누적 세금 " + ChatColor.WHITE + comma(market.taxTake()),
                ChatColor.DARK_GRAY + "거래세 " + trim(config.salesTaxPercent()) + "% 가 여기 쌓입니다.")));
        inv.setItem(20, tile(Material.BARREL, "&a비축물자", List.of(
                ChatColor.GRAY + "기준 공급 대비 " + ChatColor.WHITE
                        + String.format(Locale.ROOT, "%.0f%%", market.reservePercent()),
                "",
                ChatColor.DARK_GRAY + "물가가 목표에서 "
                        + trim(config.omoBandPercent()) + "%p 이상 벗어나면",
                ChatColor.DARK_GRAY + "중앙은행이 이 창고를 풀거나 사들여",
                ChatColor.DARK_GRAY + "공급을 직접 움직입니다 (공개시장운영).",
                market.reservePercent() < 5
                        ? ChatColor.RED + "비축물자가 거의 없습니다 - 개입 여력 없음"
                        : ChatColor.GREEN + "개입 여력 있음")));
        inv.setItem(21, tile(Material.CHEST, "&a은행 · 예금", List.of(
                ChatColor.GRAY + "계좌 " + ChatColor.WHITE + bank.accountCount() + "개",
                ChatColor.GRAY + "요구불 " + ChatColor.YELLOW + comma(bank.demandDeposits()),
                ChatColor.GRAY + "정기 " + ChatColor.YELLOW + comma(bank.termDeposits()),
                ChatColor.GRAY + "금고 현금 " + ChatColor.WHITE + comma(bank.cash()),
                ChatColor.GRAY + "지급준비금 " + ChatColor.WHITE + comma(bank.requiredReserves())
                        + ChatColor.DARK_GRAY + " (" + (int) config.reserveRatioPercent() + "%)")));
        inv.setItem(22, tile(Material.WRITABLE_BOOK, "&a은행 · 대출", List.of(
                ChatColor.GRAY + "대출 잔액 " + ChatColor.YELLOW + comma(bank.totalLoans()),
                ChatColor.GRAY + "대출 여력 " + ChatColor.GREEN + comma(bank.lendingCapacity()),
                ChatColor.GRAY + "예대율 " + ChatColor.WHITE
                        + String.format(Locale.ROOT, "%.0f%%", bank.loanToDepositPercent()),
                ChatColor.GRAY + "연체율 " + delinquencyColour(bank.delinquencyPercent())
                        + String.format(Locale.ROOT, "%.1f%%", bank.delinquencyPercent()),
                "",
                ChatColor.GRAY + "자기자본 " + (bank.equity() < 0 ? ChatColor.RED : ChatColor.WHITE)
                        + comma(bank.equity()),
                ChatColor.GRAY + "떼인 돈 " + ChatColor.RED + comma(bank.writtenOff()),
                bank.recapitalised() > 0
                        ? ChatColor.RED + "구제금융 누적 " + comma(bank.recapitalised())
                        : ChatColor.DARK_GRAY + "구제금융 이력 없음")));

        // --- 오늘의 시장 ---
        inv.setItem(28, tile(Material.REDSTONE, "&c급등 Top 5", moverLore(market, true)));
        inv.setItem(29, tile(Material.LAPIS_LAZULI, "&9급락 Top 5", moverLore(market, false)));
        inv.setItem(30, tile(Material.TNT, "&d최근 시황", shockLore(market)));
        inv.setItem(31, tile(Material.BELL, "&b통화정책 이력", policyLore(macro)));
        inv.setItem(32, tile(Material.BREAD, "&f물가바스켓", basketLore(market)));
        inv.setItem(33, tile(Material.WRITABLE_BOOK, "&6기업", corporateLore()));
        inv.setItem(34, tile(Material.IRON_PICKAXE, "&f고용", labourLore(macro)));
        inv.setItem(25, tile(Material.EMERALD_BLOCK, "&f주가지수", stockLore(macro)));

        button(inv, holder, 45, Action.BACK, Material.ARROW, "&c뒤로",
                List.of(ChatColor.GRAY + "/menu 로 돌아갑니다"));
        button(inv, holder, 47, Action.MARKET, Material.EMERALD_BLOCK, "&a시장 열기",
                List.of(ChatColor.YELLOW + "클릭"));
        button(inv, holder, 49, Action.REFRESH, Material.SUNFLOWER, "&e새로고침",
                List.of(ChatColor.GRAY + "지금 숫자로 다시 그립니다"));
        button(inv, holder, 51, Action.BANK, Material.GOLD_INGOT, "&6은행 열기",
                List.of(ChatColor.YELLOW + "클릭"));
        button(inv, holder, 53, Action.CLOSE, Material.RED_STAINED_GLASS_PANE, "&c닫기", List.of());

        player.openInventory(inv);
    }

    private List<String> moverLore(MarketService market, boolean gainers) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "전일 종가 대비");
        for (MarketItem item : market.movers(gainers, 5)) {
            lore.add(ChatColor.GRAY + " " + item.id() + " " + MarketService.change(item.changePercent())
                    + ChatColor.DARK_GRAY + " " + MarketService.money(item.mid()));
        }
        return lore;
    }

    private List<String> shockLore(MarketService market) {
        List<String> lore = new ArrayList<>();
        if (market.shocks().isEmpty()) {
            lore.add(ChatColor.DARK_GRAY + "아직 별일 없었습니다.");
            return lore;
        }
        for (MarketService.Shock shock : market.shocks()) {
            lore.add(ChatColor.GRAY + " " + shock.day() + "일차 " + ChatColor.WHITE + shock.item()
                    + ChatColor.DARK_GRAY + " - " + shock.text() + " ("
                    + String.format(Locale.ROOT, "%.0f%%", shock.magnitude()) + ")");
        }
        return lore;
    }

    private List<String> policyLore(MacroService macro) {
        List<String> lore = new ArrayList<>();
        if (macro.policyLog().isEmpty()) {
            lore.add(ChatColor.DARK_GRAY + "아직 금리를 움직인 적이 없습니다.");
            return lore;
        }
        for (MacroService.PolicyNote note : macro.policyLog()) {
            lore.add(ChatColor.GRAY + " " + note.day() + "일차 " + ChatColor.WHITE
                    + BankService.percent(note.rate()) + ChatColor.DARK_GRAY + " - " + note.reason());
        }
        return lore;
    }

    /** What the corporate sector did, from the same numbers the exchange uses. */
    private List<String> corporateLore() {
        List<String> lore = new ArrayList<>();
        if (!plugin.rpgConfig().companyEnabled()) {
            lore.add(ChatColor.DARK_GRAY + "이 서버에서는 꺼져 있습니다.");
            return lore;
        }
        var corps = plugin.corps();
        long cap = 0;
        long profit = 0;
        long cash = 0;
        int factories = 0;
        for (var company : corps.all()) {
            cap += Math.round(corps.marketCap(company));
            profit += company.lastProfit();
            cash += company.cash();
            factories += company.factories().size();
        }
        lore.add(ChatColor.GRAY + "상장 " + ChatColor.WHITE + corps.count() + "개"
                + ChatColor.GRAY + " (공모 " + corps.countNpc() + " · 공기업 "
                + corps.countState() + ")");
        int watchlisted = 0;
        for (var company : corps.all()) {
            if (company.watchlisted()) {
                watchlisted++;
            }
        }
        if (watchlisted > 0) {
            lore.add(ChatColor.RED + "관리종목 " + watchlisted + "개" + ChatColor.DARK_GRAY
                    + " (자본잠식 또는 과다 부채)");
        }
        lore.add(ChatColor.GRAY + "시가총액 " + ChatColor.YELLOW + comma(cap));
        lore.add(ChatColor.GRAY + "보유 현금 " + ChatColor.WHITE + comma(cash)
                + ChatColor.DARK_GRAY + " (통화량에는 안 들어갑니다)");
        lore.add(ChatColor.GRAY + "공장 " + ChatColor.WHITE + factories + "개"
                + ChatColor.GRAY + " · 어제 합산 이익 "
                + (profit >= 0 ? ChatColor.GREEN : ChatColor.RED) + comma(profit));
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "기업이 만든 물건은 시장 재고가 되어 값을 내리고,");
        lore.add(ChatColor.DARK_GRAY + "청사진 공사는 그 재고를 사 가서 값을 올립니다.");
        List<String> top = new ArrayList<>();
        for (var company : corps.listed()) {
            if (top.size() >= 5) {
                break;
            }
            top.add(ChatColor.GRAY + " " + company.ticker() + " "
                    + MarketService.money(company.sharePrice())
                    + ChatColor.DARK_GRAY + " 시총 " + comma(Math.round(corps.marketCap(company))));
        }
        if (!top.isEmpty()) {
            lore.add("");
            lore.add(ChatColor.GRAY + "시총 상위:");
            lore.addAll(top);
        }
        return lore;
    }

    /** The labour market, measured over the people who are actually here. */
    private List<String> labourLore(MacroService macro) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "취업 " + ChatColor.WHITE + macro.employed()
                + ChatColor.GRAY + " / 접속 " + ChatColor.WHITE + macro.workforce() + "명");
        lore.add(ChatColor.GRAY + "실업률 " + (macro.unemployment() > 50
                ? ChatColor.RED : ChatColor.GREEN)
                + String.format(Locale.ROOT, "%.0f%%", macro.unemployment()));
        lore.add(ChatColor.DARK_GRAY + bar((int) (100 - macro.unemployment())));
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "회사에 들어가면 경제일마다 일당이 나옵니다.");
        lore.add(ChatColor.DARK_GRAY + "직업에 따라 급여와 회사 생산이 달라집니다.");
        return lore;
    }

    /** The exchange as one number, indexed to 1,000 on its first day. */
    private List<String> stockLore(MacroService macro) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "지수 " + ChatColor.YELLOW
                + String.format(Locale.ROOT, "%,.0f", macro.stockIndex())
                + ChatColor.DARK_GRAY + " (첫날 1,000)");
        String chart = MarketItem.spark(macro.stockHistory());
        if (!chart.isEmpty()) {
            lore.add(ChatColor.AQUA + chart);
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "상장 기업 전체의 시가총액을 첫날과 비교한 것입니다.");
        lore.add(ChatColor.DARK_GRAY + "기업이 돈을 벌면 오르고, 망하면 내려갑니다.");
        return lore;
    }

    private List<String> basketLore(MarketService market) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "물가를 재는 장바구니입니다. 가중치가 클수록");
        lore.add(ChatColor.DARK_GRAY + "그 품목의 값이 물가지수를 더 많이 움직입니다.");
        int shown = 0;
        for (MarketItem item : market.items().values()) {
            if (item.basket() <= 0) {
                continue;
            }
            if (shown++ >= 10) {
                lore.add(ChatColor.DARK_GRAY + " ...");
                break;
            }
            lore.add(ChatColor.GRAY + " " + item.id() + " x" + trim(item.basket())
                    + ChatColor.DARK_GRAY + "  " + MarketService.money(item.mid())
                    + " (" + String.format(Locale.ROOT, "%.0f%%", item.valuationPercent()) + ")");
        }
        return lore;
    }

    // ------------------------------------------------------------- formatting

    private static String comma(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String trim(double value) {
        return MarketMenu.trim(value);
    }

    /** A twenty-block bar for a percentage. */
    private static String bar(int percent) {
        int filled = Math.clamp(percent / 5, 0, 20);
        return "▮".repeat(filled) + "▯".repeat(20 - filled);
    }

    private static Material inflationIcon(double inflation, EconomyConfig config) {
        if (inflation > config.inflationTargetPercent() + config.omoBandPercent()) {
            return Material.BLAZE_POWDER;
        }
        if (inflation < config.inflationTargetPercent() - config.omoBandPercent()) {
            return Material.BLUE_ICE;
        }
        return Material.SUNFLOWER;
    }

    private static ChatColor inflationColour(double inflation, EconomyConfig config) {
        if (inflation > config.inflationTargetPercent() + config.omoBandPercent()) {
            return ChatColor.RED;
        }
        if (inflation < config.inflationTargetPercent() - config.omoBandPercent()) {
            return ChatColor.BLUE;
        }
        return ChatColor.GREEN;
    }

    private static ChatColor giniColour(double gini) {
        if (gini > 0.5) {
            return ChatColor.RED;
        }
        if (gini > 0.35) {
            return ChatColor.YELLOW;
        }
        return ChatColor.GREEN;
    }

    private static ChatColor delinquencyColour(double percent) {
        if (percent > 20) {
            return ChatColor.RED;
        }
        if (percent > 5) {
            return ChatColor.YELLOW;
        }
        return ChatColor.GREEN;
    }

    private void button(Inventory inv, Holder holder, int slot, Action action,
                        Material material, String name, List<String> lore) {
        inv.setItem(slot, tile(material, name, lore));
        holder.actions.put(slot, action);
    }

    private ItemStack filler() {
        return tile(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
    }

    private ItemStack tile(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }
}
