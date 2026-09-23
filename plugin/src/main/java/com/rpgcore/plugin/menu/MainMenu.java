package com.rpgcore.plugin.menu;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.data.PlayerData;
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
import java.util.Map;

/**
 * The hub: one screen that reaches every other one.
 *
 * RPGCore grew a command per feature - /stats, /job, /titles, /collection,
 * /achievements, /leaderboard, /party, /auction - which is fine once you know
 * them all and hopeless before then. This is the screen a player opens when
 * they do not yet know what the server has, so every feature is a visible
 * button rather than a command they have to have been told about.
 *
 * A feature the operator has switched off keeps its slot and shows as off,
 * rather than shuffling everything else along: the layout a player learns on
 * one server should read the same on the next.
 */
public final class MainMenu {

    /** What a button does when it is clicked. */
    public enum Action {
        STATS, JOBS, TITLES, COLLECTION, ACHIEVEMENTS, LEADERBOARD,
        PARTY, TRADE_HELP, DUEL_HELP, AUCTION, MAILBOX,
        MARKET, BANK, ECONOMY, COMPANY, STOCKS, BLUEPRINT, CLOSE
    }

    private static final int SIZE = 45;

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

        /** Null for furniture and for buttons whose feature is switched off. */
        public Action actionAt(int slot) {
            return actions.get(slot);
        }
    }

    private final RpgCorePlugin plugin;

    public MainMenu(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        PlayerData data = plugin.players().get(player);
        Holder holder = new Holder();
        Inventory inv = plugin.getServer().createInventory(holder, SIZE,
                ChatColor.translateAlternateColorCodes('&', plugin.rpgConfig().menuTitle()));
        holder.setInventory(inv);

        for (int slot = 0; slot < SIZE; slot++) {
            inv.setItem(slot, filler());
        }

        inv.setItem(4, card(player, data));

        button(inv, holder, 10, Action.STATS, true, Material.IRON_SWORD,
                "&b스탯", "레벨, 스탯 포인트, 무게, 장비 상태",
                "남은 포인트: &e" + data.points() + "&7개");
        button(inv, holder, 11, Action.JOBS, plugin.rpgConfig().jobsEnabled(), Material.GOLDEN_HELMET,
                "&b직업", "직업을 고르거나 바꿉니다", jobLine(player));
        button(inv, holder, 12, Action.TITLES, plugin.rpgConfig().achievementsEnabled(), Material.NAME_TAG,
                "&b칭호", "이름 앞에 붙일 칭호를 고릅니다", titleLine(player));
        button(inv, holder, 13, Action.COLLECTION, plugin.rpgConfig().collectionEnabled(), Material.BOOK,
                "&b도감", "모은 아이템을 기록합니다",
                "&7분류 " + plugin.collections().categories().size() + "개");
        button(inv, holder, 14, Action.ACHIEVEMENTS, plugin.rpgConfig().achievementsEnabled(), Material.EXPERIENCE_BOTTLE,
                "&b업적", "달성한 업적과 진행도를 봅니다", null);
        button(inv, holder, 15, Action.LEADERBOARD, plugin.rpgConfig().leaderboardEnabled(), Material.GOLDEN_APPLE,
                "&b순위표", "서버 순위를 봅니다", null);
        button(inv, holder, 16, Action.PARTY, plugin.rpgConfig().partyEnabled(), Material.WHITE_BANNER,
                "&b파티", "파티를 만들고 관리합니다", partyLine(player));

        boolean market = plugin.rpgConfig().marketEnabled();
        boolean bankOn = plugin.rpgConfig().bankEnabled();
        button(inv, holder, 20, Action.MARKET, market, Material.EMERALD_BLOCK,
                "&a시장", "수요와 공급으로 값이 움직입니다", marketLine());
        button(inv, holder, 22, Action.BANK, bankOn, Material.GOLD_INGOT,
                "&6은행", "예금 · 정기예금 · 대출", bankLine(player));
        button(inv, holder, 24, Action.ECONOMY, market || bankOn, Material.CLOCK,
                "&e경제 지표", "물가 · 금리 · 통화량 · 경기", economyLine());

        boolean corps = plugin.rpgConfig().companyEnabled();
        button(inv, holder, 28, Action.COMPANY, corps, Material.WRITABLE_BOOK,
                "&6기업", "공장을 돌려 물건을 만들고 팝니다", companyLine(player));
        button(inv, holder, 29, Action.STOCKS, corps, Material.EMERALD,
                "&a거래소", "남의 회사에 투자하고 배당을 받습니다", stocksLine(player));
        button(inv, holder, 30, Action.AUCTION, plugin.rpgConfig().auctionEnabled(), Material.GOLD_BLOCK,
                "&6경매장", "골드로 물건을 사고팝니다",
                "&7진행 중 " + plugin.auctions().count() + "건");
        button(inv, holder, 31, Action.MAILBOX, true, mailboxIcon(player),
                "&e우편함", "경매와 대결에서 돌려받을 것", mailboxLine(player));
        inv.setItem(32, goldCard(data));
        button(inv, holder, 33, Action.TRADE_HELP, plugin.rpgConfig().tradeEnabled(), Material.CHEST,
                "&b거래", "다른 플레이어와 직접 교환합니다", "&7/trade <플레이어>");
        button(inv, holder, 34, Action.DUEL_HELP, plugin.rpgConfig().duelEnabled(), Material.IRON_AXE,
                "&b대결", "걸고 싸우는 1:1", "&7/duel <플레이어> [골드|hand]");
        button(inv, holder, 26, Action.BLUEPRINT, plugin.rpgConfig().blueprintEnabled(),
                Material.FILLED_MAP, "&b청사진", "구역을 떠서 자동으로 짓습니다", blueprintLine(player));

        button(inv, holder, 40, Action.CLOSE, true, Material.RED_STAINED_GLASS_PANE, "&c닫기", null, null);

        player.openInventory(inv);
    }

    // ---------------------------------------------------------------- pieces

    /**
     * Places one button, or a grey stand-in when its feature is switched off.
     * The stand-in is deliberately not registered as an action, so clicking it
     * does nothing at all rather than opening an empty screen.
     */
    private void button(Inventory inv, Holder holder, int slot, Action action, boolean enabled,
                        Material icon, String name, String description, String detail) {
        if (!enabled) {
            inv.setItem(slot, item(Material.GRAY_STAINED_GLASS_PANE,
                    ChatColor.DARK_GRAY + ChatColor.stripColor(colour(name)),
                    List.of(ChatColor.DARK_GRAY + "이 서버에서는 꺼져 있습니다.")));
            return;
        }
        List<String> lore = new ArrayList<>();
        if (description != null) {
            lore.add(ChatColor.GRAY + description);
        }
        if (detail != null) {
            lore.add(colour(detail));
        }
        if (action != Action.CLOSE) {
            lore.add("");
            lore.add(ChatColor.YELLOW + "클릭하여 열기");
        }
        inv.setItem(slot, item(icon, colour(name), lore));
        holder.actions.put(slot, action);
    }

    private ItemStack card(Player player, PlayerData data) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.YELLOW + "Lv. " + data.level());
        lore.add(ChatColor.GREEN + "XP " + data.xp() + " / " + data.xpNeed());
        lore.add(ChatColor.GRAY + "무게 " + data.weight() + " / " + data.weightMax()
                + " (" + data.loadPercent() + "%)");
        return item(Material.WRITABLE_BOOK, ChatColor.GOLD + player.getName(), lore);
    }

    private ItemStack goldCard(PlayerData data) {
        return item(Material.GOLD_INGOT, ChatColor.GOLD + "골드",
                List.of(ChatColor.YELLOW + String.valueOf(data.gold())
                                + ChatColor.GRAY + plugin.rpgConfig().goldSymbol(),
                        "",
                        ChatColor.GRAY + "/pay <플레이어> <금액> 으로 보낼 수 있습니다."));
    }

    private Material mailboxIcon(Player player) {
        // A chest that is visibly different when something is in it: the point
        // of the mailbox is that a player notices without being told twice.
        return plugin.mailbox().pending(player.getUniqueId()) > 0
                ? Material.ENDER_CHEST : Material.BARREL;
    }

    private String mailboxLine(Player player) {
        int pending = plugin.mailbox().pending(player.getUniqueId());
        return pending > 0
                ? "&e받을 것이 " + pending + "개 있습니다"
                : "&8비어 있습니다";
    }

    /** Today's headline number, so the button is worth looking at. */
    private String marketLine() {
        if (!plugin.rpgConfig().marketEnabled()) {
            return null;
        }
        return "&7" + plugin.market().size() + "종 · 물가 "
                + String.format(java.util.Locale.ROOT, "%.0f", plugin.macro().cpi());
    }

    private String bankLine(Player player) {
        if (!plugin.rpgConfig().bankEnabled()) {
            return null;
        }
        var account = plugin.bank().peek(player.getUniqueId());
        if (account == null || account.totalDeposits() + account.totalDebt() == 0) {
            return "&7예금 금리 " + com.rpgcore.plugin.economy.BankService
                    .percent(plugin.bank().depositRate());
        }
        return "&7예금 " + account.totalDeposits()
                + (account.totalDebt() > 0 ? " &c· 채무 " + account.totalDebt() : "");
    }

    private String economyLine() {
        return "&7" + plugin.macro().day() + "일차 · 금리 "
                + com.rpgcore.plugin.economy.BankService.percent(plugin.macro().policyRate());
    }

    private String jobLine(Player player) {
        var job = plugin.jobs().of(player);
        return job == null ? "&8아직 직업 없음" : "&7현재: " + job.displayName();
    }

    private String titleLine(Player player) {
        var worn = plugin.titles().worn(player);
        return worn == null ? "&8착용 중인 칭호 없음" : "&7착용 중: " + worn.display();
    }

    private String companyLine(Player player) {
        if (!plugin.rpgConfig().companyEnabled()) {
            return null;
        }
        var company = plugin.corps().employerOf(player);
        return company == null
                ? "&8회사 없음 - /company create <이름>"
                : "&7" + company.name() + " · 현금 " + company.cash()
                        + " · 공장 " + company.factories().size() + "개";
    }

    private String stocksLine(Player player) {
        if (!plugin.rpgConfig().companyEnabled()) {
            return null;
        }
        var holdings = plugin.corps().portfolioOf(player.getUniqueId());
        return holdings.isEmpty()
                ? "&7상장 " + plugin.corps().count() + "개 · 아직 투자 없음"
                : "&7보유 " + holdings.size() + "종목";
    }

    private String blueprintLine(Player player) {
        if (!plugin.rpgConfig().blueprintEnabled()) {
            return null;
        }
        var sites = plugin.blueprints().sitesOf(player.getUniqueId());
        return sites.isEmpty()
                ? "&7저장된 청사진 " + plugin.blueprints().count() + "개"
                : "&e공사 중 " + sites.get(0).percent() + "%";
    }

    private String partyLine(Player player) {
        var party = plugin.parties().partyOf(player);
        return party == null ? "&8파티 없음" : "&7현재: " + party.name() + " (" + party.size() + "명)";
    }

    private static String colour(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private ItemStack filler() {
        return item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
