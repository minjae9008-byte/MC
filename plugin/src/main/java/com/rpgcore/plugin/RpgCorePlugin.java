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
import com.rpgcore.plugin.command.JobCommand;
import com.rpgcore.plugin.command.LeaderboardCommand;
import com.rpgcore.plugin.command.PartyChatCommand;
import com.rpgcore.plugin.command.PartyCommand;
import com.rpgcore.plugin.command.TradeCommand;
import com.rpgcore.plugin.gui.MenuListener;
import com.rpgcore.plugin.gui.StatsMenu;
import com.rpgcore.plugin.job.JobMenu;
import com.rpgcore.plugin.job.JobService;
import com.rpgcore.plugin.leaderboard.LeaderboardService;
import com.rpgcore.plugin.party.PartyListener;
import com.rpgcore.plugin.party.PartyService;
import com.rpgcore.plugin.trade.TradeListener;
import com.rpgcore.plugin.trade.TradeService;
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
 * Everything is event-driven and server-side, so Java and Bedrock players get
 * identical behaviour with no client mod and no datapack.
 *
 * The vanilla scoreboard is written to as a mirror: it gives free persistence
 * with the world, lets admins read RPG values with /scoreboard, and is what
 * the leaderboard ranks - including players who are offline. Hot paths read
 * cached {@link PlayerData} from memory instead.
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
    private JobService jobs;
    private JobMenu jobMenu;
    private LeaderboardService leaderboard;
    private PartyService parties;
    private TradeService trades;

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

        this.jobs = new JobService(this);
        this.jobs.load();
        this.parties = new PartyService(this);
        this.parties.load();
        this.trades = new TradeService(this);
        this.leaderboard = new LeaderboardService(this, scoreboard);

        this.statsMenu = new StatsMenu(this);
        this.jobMenu = new JobMenu(this);

        getServer().getPluginManager().registerEvents(new PlayerSessionListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new WeightListener(this), this);
        getServer().getPluginManager().registerEvents(new TreeFellListener(this, treeFell), this);
        getServer().getPluginManager().registerEvents(new GearListener(this), this);
        getServer().getPluginManager().registerEvents(new RangedListener(this), this);
        getServer().getPluginManager().registerEvents(new AnvilListener(this, anvil), this);
        getServer().getPluginManager().registerEvents(new PartyListener(this), this);
        getServer().getPluginManager().registerEvents(new TradeListener(this), this);
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

        registerCommand("job", new JobCommand(this));
        registerCommand("leaderboard", new LeaderboardCommand(this));
        registerCommand("party", new PartyCommand(this));
        registerCommand("p", new PartyChatCommand(this));
        registerCommand("trade", new TradeCommand(this));

        new VoiceChatHook(this).check();

        // Players are already online after a /reload.
        for (Player player : getServer().getOnlinePlayers()) {
            stats.recalculate(player);
        }

        getLogger().info("RPGCore plugin enabled.");
    }

    @Override
    public void onDisable() {
        // Items sitting in an open trade window belong to their owners, not to
        // the void, so every live trade is unwound before anything else.
        for (Player player : getServer().getOnlinePlayers()) {
            trades.endIfTrading(player, "서버가 종료됩니다.");
        }
        parties.save();
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
                jobs.load();
                leaderboard.invalidate();
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
                jobs.clear(target, data);
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

    /**
     * Registers an executor (and tab completer, when the class is one) for a
     * command declared in plugin.yml.
     */
    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        org.bukkit.command.PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command '" + name + "' is missing from plugin.yml - skipped.");
            return;
        }
        command.setExecutor(executor);
        if (executor instanceof org.bukkit.command.TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }

    /** Opens the stats GUI. Same screen for Java and Bedrock players. */
    public void openStatsMenu(Player player) {
        statsMenu.open(player);
    }

    public JobService jobs() {
        return jobs;
    }

    public JobMenu jobMenu() {
        return jobMenu;
    }

    public LeaderboardService leaderboard() {
        return leaderboard;
    }

    public PartyService parties() {
        return parties;
    }

    public TradeService trades() {
        return trades;
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

}
