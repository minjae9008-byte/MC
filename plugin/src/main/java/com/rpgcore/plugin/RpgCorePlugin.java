package com.rpgcore.plugin;

import com.rpgcore.plugin.anvil.AnvilListener;
import com.rpgcore.plugin.anvil.AnvilRecipe;
import com.rpgcore.plugin.anvil.AnvilService;
import com.rpgcore.plugin.chat.ProximityChatListener;
import com.rpgcore.plugin.config.RpgConfig;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.data.PlayerDataManager;
import com.rpgcore.plugin.gear.GearListener;
import com.rpgcore.plugin.gear.GearService;
import com.rpgcore.plugin.gear.RangedListener;
import com.rpgcore.plugin.gui.StatsMenu;
import com.rpgcore.plugin.gui.StatsMenuListener;
import com.rpgcore.plugin.hud.HudTask;
import com.rpgcore.plugin.stats.PlayerSessionListener;
import com.rpgcore.plugin.stats.StatType;
import com.rpgcore.plugin.stats.StatsService;
import com.rpgcore.plugin.tree.TreeFellListener;
import com.rpgcore.plugin.tree.TreeFellService;
import com.rpgcore.plugin.util.RpgScoreboard;
import com.rpgcore.plugin.voice.VoiceChatHook;
import com.rpgcore.plugin.weight.ItemWeightTable;
import com.rpgcore.plugin.weight.WeightListener;
import com.rpgcore.plugin.weight.WeightService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * RPGCore.
 *
 * All gameplay logic lives here, in the plugin. The datapack alongside it is
 * now a pure data layer (item/block classification tags) with no functions and
 * no per-tick work: everything that used to be command-driven - the 164-command
 * weight scan, the every-tick marker-item scan for tree felling, the trigger
 * polling and the JSON HUD - is event-driven Java now.
 *
 * The vanilla scoreboard is still written to, but only as a mirror: it gives
 * free persistence with the world and lets admins and other datapacks read RPG
 * values, while the hot paths read cached {@link PlayerData} from memory.
 */
public final class RpgCorePlugin extends JavaPlugin {

    private RpgConfig rpgConfig;
    private RpgScoreboard scoreboard;
    private PlayerDataManager players;
    private StatsService stats;
    private ItemWeightTable weightTable;
    private WeightService weight;
    private TreeFellService treeFell;
    private GearService gear;
    private AnvilService anvil;
    private StatsMenu statsMenu;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.rpgConfig = new RpgConfig(this);
        this.scoreboard = new RpgScoreboard();
        this.players = new PlayerDataManager(this, scoreboard);
        this.players.createObjectives();
        this.stats = new StatsService(this);

        this.weightTable = new ItemWeightTable(this);
        this.weightTable.load();
        this.weight = new WeightService(this, weightTable);

        this.treeFell = new TreeFellService(this);
        this.treeFell.load();

        this.gear = new GearService(this);
        this.anvil = new AnvilService(this);
        this.anvil.load();

        this.statsMenu = new StatsMenu(this);

        getServer().getPluginManager().registerEvents(new PlayerSessionListener(this), this);
        getServer().getPluginManager().registerEvents(new StatsMenuListener(this), this);
        getServer().getPluginManager().registerEvents(new WeightListener(this), this);
        getServer().getPluginManager().registerEvents(new TreeFellListener(this, treeFell), this);
        getServer().getPluginManager().registerEvents(new GearListener(this), this);
        getServer().getPluginManager().registerEvents(new RangedListener(this), this);
        getServer().getPluginManager().registerEvents(new AnvilListener(this, anvil), this);
        if (rpgConfig.proximityEnabled()) {
            getServer().getPluginManager().registerEvents(new ProximityChatListener(this), this);
        }

        // Two light repeating tasks total: one tick pump for the weight/tree
        // queues, and the HUD on its own slower interval.
        new BukkitRunnable() {
            @Override
            public void run() {
                weight.tick();
                gear.tick();
                treeFell.tick();
            }
        }.runTaskTimer(this, 1L, 1L);
        new HudTask(this).runTaskTimer(this, 20L, rpgConfig.hudInterval());

        new VoiceChatHook(this).check();

        // Players are already online after a /reload.
        for (Player player : getServer().getOnlinePlayers()) {
            stats.recalculate(player);
        }

        getLogger().info("RPGCore plugin enabled.");
    }

    @Override
    public void onDisable() {
        for (Player player : getServer().getOnlinePlayers()) {
            players.unload(player);
        }
        getLogger().info("RPGCore plugin disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "stats" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
                    return true;
                }
                openStatsMenu(player);
                return true;
            }
            case "rpgcore" -> {
                return adminCommand(sender, args);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean adminCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "/rpgcore reload | recipes | givexp <player> <amount> | reset <player>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                rpgConfig.reload();
                weightTable.load();
                treeFell.load();
                anvil.load();
                for (Player player : getServer().getOnlinePlayers()) {
                    stats.recalculate(player);
                }
                sender.sendMessage(ChatColor.GREEN + "[RPGCore] 설정을 다시 불러왔습니다.");
                return true;
            }
            case "recipes" -> {
                if (anvil.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "[RPGCore] 등록된 모루 조합법이 없습니다.");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "[RPGCore] 모루 조합법 (왼쪽=장비, 오른쪽=재료):");
                for (AnvilRecipe recipe : anvil.recipes()) {
                    sender.sendMessage("  " + anvil.describe(recipe));
                }
                return true;
            }
            case "givexp" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "/rpgcore givexp <player> <amount>");
                    return true;
                }
                Player target = getServer().getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "온라인이 아닌 플레이어입니다: " + args[1]);
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "숫자를 입력하세요: " + args[2]);
                    return true;
                }
                stats.addXp(target, amount);
                sender.sendMessage(ChatColor.GREEN + "[RPGCore] " + target.getName() + " 에게 XP " + amount + " 지급.");
                return true;
            }
            case "reset" -> {
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "/rpgcore reset <player>");
                    return true;
                }
                Player target = getServer().getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "온라인이 아닌 플레이어입니다: " + args[1]);
                    return true;
                }
                PlayerData data = players.get(target);
                data.level(1);
                data.xp(0);
                data.xpNeed(stats.xpNeedFor(1));
                data.points(rpgConfig.startingPoints());
                for (StatType type : StatType.values()) {
                    data.stat(type, 0);
                }
                stats.recalculate(target, data);
                players.flush(target, data);
                sender.sendMessage(ChatColor.GREEN + "[RPGCore] " + target.getName() + " 의 스탯을 초기화했습니다.");
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.YELLOW + "/rpgcore reload | recipes | givexp <player> <amount> | reset <player>");
                return true;
            }
        }
    }

    /** Opens the stats GUI. Same screen for Java and Bedrock players. */
    public void openStatsMenu(Player player) {
        statsMenu.open(player);
    }

    public RpgConfig rpgConfig() {
        return rpgConfig;
    }

    public PlayerDataManager players() {
        return players;
    }

    public StatsService stats() {
        return stats;
    }

    public WeightService weight() {
        return weight;
    }

    public GearService gear() {
        return gear;
    }

    public AnvilService anvil() {
        return anvil;
    }

    public StatsMenu statsMenu() {
        return statsMenu;
    }
}
