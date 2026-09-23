package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.blueprint.Blueprint;
import com.rpgcore.plugin.blueprint.BlueprintService;
import com.rpgcore.plugin.blueprint.ConstructionSite;
import com.rpgcore.plugin.economy.MarketItem;
import com.rpgcore.plugin.economy.MarketService;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /blueprint - select, save, price and build.
 *
 * The cost breakdown is the command that matters. Before anybody spends six
 * figures on a build they should be able to see exactly which material is
 * eating the budget and whether the market can even supply it - and that is
 * a table, which is a thing chat does better than a chest window.
 */
public final class BlueprintCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "wand", "pos1", "pos2", "save", "list", "cost", "build", "rush",
            "cancel", "status", "delete", "help");

    private final RpgCorePlugin plugin;

    public BlueprintCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.blueprints().enabled()) {
            player.sendMessage(ChatColor.RED + "[청사진] 이 서버에서는 청사진을 쓸 수 없습니다.");
            return true;
        }
        BlueprintService service = plugin.blueprints();
        if (args.length == 0) {
            plugin.blueprintMenu().open(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "wand" -> {
                player.getInventory().addItem(plugin.blueprintListener().wand());
                player.sendMessage(ChatColor.GREEN + "[청사진] 설계 지팡이를 드렸습니다. "
                        + ChatColor.GRAY + "좌클릭 1번 모서리, 우클릭 2번 모서리.");
            }
            case "pos1" -> service.setCorner(player, true, player.getLocation());
            case "pos2" -> service.setCorner(player, false, player.getLocation());
            case "save" -> {
                if (args.length < 2) {
                    player.sendMessage(ChatColor.YELLOW + "/blueprint save <이름>");
                    return true;
                }
                service.capture(player, String.join(" ",
                        java.util.Arrays.copyOfRange(args, 1, args.length)));
            }
            case "list" -> list(player);
            case "cost", "info" -> {
                Blueprint blueprint = require(player, args);
                if (blueprint != null) {
                    cost(player, blueprint);
                }
            }
            case "build" -> {
                Blueprint blueprint = require(player, args);
                if (blueprint == null) {
                    return true;
                }
                boolean company = args.length > 2 && (args[2].equalsIgnoreCase("company")
                        || args[2].equalsIgnoreCase("회사"));
                service.build(player, blueprint, player.getLocation(), company);
            }
            case "rush" -> service.rush(player);
            case "cancel" -> service.cancel(player);
            case "status" -> status(player);
            case "delete" -> {
                Blueprint blueprint = require(player, args);
                if (blueprint != null) {
                    service.delete(player, blueprint);
                }
            }
            default -> help(player);
        }
        return true;
    }

    private Blueprint require(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "/blueprint " + args[0] + " <이름>");
            return null;
        }
        Blueprint blueprint = plugin.blueprints().byName(player, args[1]);
        if (blueprint == null) {
            player.sendMessage(ChatColor.RED + "[청사진] 그런 청사진이 없습니다: " + args[1]);
        }
        return blueprint;
    }

    /** The bill of materials, and whether the market can actually supply it. */
    private void cost(Player player, Blueprint blueprint) {
        BlueprintService.Estimate estimate = plugin.blueprints().estimate(blueprint, player);
        player.sendMessage(ChatColor.GOLD + "===== " + blueprint.name() + " =====");
        player.sendMessage(ChatColor.GRAY + " " + blueprint.width() + "x" + blueprint.height()
                + "x" + blueprint.length() + " · 블록 " + comma(blueprint.solidCount())
                + " · 자재 " + estimate.items().size() + "종");
        int shown = 0;
        for (Map.Entry<Material, Integer> entry : estimate.items().entrySet()) {
            if (shown++ >= 12) {
                player.sendMessage(ChatColor.DARK_GRAY + "  ... 외 "
                        + (estimate.items().size() - 12) + "종");
                break;
            }
            MarketItem item = entry.getKey().isItem()
                    ? plugin.market().byMaterial(entry.getKey()) : null;
            String price = item == null
                    ? ChatColor.DARK_GRAY + "시장 밖 " + plugin.rpgConfig().blueprintFallbackPrice()
                    : ChatColor.GRAY + MarketService.money(item.ask(plugin.economyConfig()));
            player.sendMessage(ChatColor.GRAY + "  " + entry.getKey().getKey().getKey()
                    + " x" + comma(entry.getValue()) + ChatColor.DARK_GRAY + " @ " + price);
        }
        if (estimate.hasShortfall()) {
            player.sendMessage(ChatColor.RED + " 시장 재고가 모자란 자재:");
            for (Map.Entry<Material, Long> entry : estimate.shortfall().entrySet()) {
                player.sendMessage(ChatColor.DARK_GRAY + "  " + entry.getKey().getKey().getKey()
                        + " " + comma(entry.getValue()) + "개 부족 → 수입 할증 "
                        + (int) plugin.rpgConfig().blueprintImportMarkupPercent() + "%");
            }
        }
        player.sendMessage(ChatColor.WHITE + " 자재 " + comma(estimate.materials())
                + ChatColor.GRAY + " + 시공비 " + comma(estimate.margin())
                + (estimate.imported() > 0 ? " + 수입 " + comma(estimate.imported()) : "")
                + ChatColor.WHITE + " = " + ChatColor.GOLD + comma(estimate.total())
                + plugin.rpgConfig().goldSymbol());
        int seconds = Math.max(1, blueprint.solidCount()
                / Math.max(1, plugin.rpgConfig().blueprintBlocksPerSecond()));
        player.sendMessage(ChatColor.GRAY + " 예상 공기 " + BlueprintService.formatDuration(seconds)
                + " · 보유 " + comma(plugin.economy().balance(player)));
        player.sendMessage(ChatColor.YELLOW + " /blueprint build " + blueprint.name()
                + ChatColor.GRAY + " (회사 자금으로 지으려면 뒤에 company)");
    }

    private void list(Player player) {
        List<Blueprint> all = plugin.blueprints().all();
        player.sendMessage(ChatColor.GOLD + "===== 청사진 =====");
        if (all.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + " 없습니다. 지팡이로 구역을 찍고 /blueprint save <이름>");
            return;
        }
        for (Blueprint blueprint : all) {
            player.sendMessage(ChatColor.GRAY + " " + blueprint.name() + ChatColor.DARK_GRAY
                    + " · " + blueprint.width() + "x" + blueprint.height() + "x" + blueprint.length()
                    + " · " + comma(blueprint.solidCount()) + "블록 · 설계 " + blueprint.ownerName());
        }
    }

    private void status(Player player) {
        List<ConstructionSite> sites = plugin.blueprints().sites();
        player.sendMessage(ChatColor.GOLD + "===== 공사 현장 =====");
        if (sites.isEmpty()) {
            player.sendMessage(ChatColor.GRAY + " 진행 중인 공사가 없습니다.");
            return;
        }
        for (ConstructionSite site : sites) {
            player.sendMessage(ChatColor.GRAY + " " + site.blueprintName() + " · "
                    + ChatColor.WHITE + site.percent() + "%" + ChatColor.GRAY
                    + " (" + comma(site.placed()) + "/" + comma(site.solidTotal()) + ") · "
                    + site.ownerName() + " · " + site.originX() + ", " + site.originY()
                    + ", " + site.originZ());
        }
    }

    private void help(Player player) {
        player.sendMessage(ChatColor.GOLD + "===== /blueprint =====");
        player.sendMessage(ChatColor.GRAY + " /blueprint" + ChatColor.DARK_GRAY + " - 청사진 화면");
        player.sendMessage(ChatColor.GRAY + " /blueprint wand" + ChatColor.DARK_GRAY + " - 설계 지팡이 받기");
        player.sendMessage(ChatColor.GRAY + " /blueprint pos1 · pos2" + ChatColor.DARK_GRAY
                + " - 서 있는 자리를 모서리로");
        player.sendMessage(ChatColor.GRAY + " /blueprint save <이름>" + ChatColor.DARK_GRAY + " - 구역을 뜹니다");
        player.sendMessage(ChatColor.GRAY + " /blueprint cost <이름>" + ChatColor.DARK_GRAY + " - 자재와 비용");
        player.sendMessage(ChatColor.GRAY + " /blueprint build <이름> [company]"
                + ChatColor.DARK_GRAY + " - 서 있는 자리에 착공");
        player.sendMessage(ChatColor.GRAY + " /blueprint rush · cancel · status · list · delete <이름>");
    }

    private static String comma(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return prefixed(SUBS, args[0]);
        }
        if (args.length == 2 && List.of("cost", "info", "build", "delete").contains(
                args[0].toLowerCase(Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            for (Blueprint blueprint : plugin.blueprints().all()) {
                names.add(blueprint.name());
            }
            return prefixed(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("build")) {
            return prefixed(List.of("company"), args[2]);
        }
        return List.of();
    }

    private static List<String> prefixed(List<String> options, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                out.add(option);
            }
        }
        return out;
    }
}
