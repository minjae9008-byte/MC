package com.rpgcore.plugin;

import com.rpgcore.plugin.anvil.AnvilListener;
import com.rpgcore.plugin.auction.AuctionMenu;
import com.rpgcore.plugin.auction.AuctionService;
import com.rpgcore.plugin.anvil.AnvilRecipe;
import com.rpgcore.plugin.anvil.AnvilService;
import com.rpgcore.plugin.chat.ProximityChatListener;
import com.rpgcore.plugin.collection.CollectionMenu;
import com.rpgcore.plugin.collection.CollectionService;
import com.rpgcore.plugin.config.RpgConfig;
import com.rpgcore.plugin.data.PlayerData;
import com.rpgcore.plugin.data.PlayerDataManager;
import com.rpgcore.plugin.discord.DiscordListener;
import com.rpgcore.plugin.discord.DiscordNotifier;
import com.rpgcore.plugin.discord.DiscordService;
import com.rpgcore.plugin.display.NameplateService;
import com.rpgcore.plugin.gear.GearListener;
import com.rpgcore.plugin.gear.GearService;
import com.rpgcore.plugin.gear.RangedListener;
import com.rpgcore.plugin.command.AchievementCommand;
import com.rpgcore.plugin.command.AuctionCommand;
import com.rpgcore.plugin.command.CollectionCommand;
import com.rpgcore.plugin.blueprint.BlueprintListener;
import com.rpgcore.plugin.blueprint.BlueprintMenu;
import com.rpgcore.plugin.blueprint.BlueprintService;
import com.rpgcore.plugin.command.BankCommand;
import com.rpgcore.plugin.command.BlueprintCommand;
import com.rpgcore.plugin.command.CompanyCommand;
import com.rpgcore.plugin.command.StockCommand;
import com.rpgcore.plugin.corp.CompanyMenu;
import com.rpgcore.plugin.corp.CorpConfig;
import com.rpgcore.plugin.corp.CorpService;
import com.rpgcore.plugin.corp.StockMenu;
import com.rpgcore.plugin.command.DuelCommand;
import com.rpgcore.plugin.command.EconomyCommand;
import com.rpgcore.plugin.command.MarketCommand;
import com.rpgcore.plugin.command.GoldCommand;
import com.rpgcore.plugin.command.JobCommand;
import com.rpgcore.plugin.command.LeaderboardCommand;
import com.rpgcore.plugin.command.MenuCommand;
import com.rpgcore.plugin.command.PartyChatCommand;
import com.rpgcore.plugin.command.PartyCommand;
import com.rpgcore.plugin.command.PayCommand;
import com.rpgcore.plugin.command.TitleCommand;
import com.rpgcore.plugin.command.TradeCommand;
import com.rpgcore.plugin.duel.DuelListener;
import com.rpgcore.plugin.economy.BankMenu;
import com.rpgcore.plugin.economy.BankService;
import com.rpgcore.plugin.economy.EconomyConfig;
import com.rpgcore.plugin.economy.EconomyMenu;
import com.rpgcore.plugin.economy.MacroService;
import com.rpgcore.plugin.economy.MarketMenu;
import com.rpgcore.plugin.economy.MarketService;
import com.rpgcore.plugin.duel.DuelService;
import com.rpgcore.plugin.gui.MenuListener;
import com.rpgcore.plugin.gui.StatsMenu;
import com.rpgcore.plugin.job.JobMenu;
import com.rpgcore.plugin.job.JobService;
import com.rpgcore.plugin.leaderboard.LeaderboardService;
import com.rpgcore.plugin.mail.MailboxService;
import com.rpgcore.plugin.menu.MainMenu;
import com.rpgcore.plugin.party.PartyListener;
import com.rpgcore.plugin.party.PartyService;
import com.rpgcore.plugin.progress.AchievementService;
import com.rpgcore.plugin.progress.EconomyService;
import com.rpgcore.plugin.progress.ProgressListener;
import com.rpgcore.plugin.progress.TitleMenu;
import com.rpgcore.plugin.progress.TitleService;
import com.rpgcore.plugin.trade.TradeListener;
import com.rpgcore.plugin.trade.TradeService;
import com.rpgcore.plugin.hud.HudTask;
import com.rpgcore.plugin.stats.PlayerSessionListener;
import com.rpgcore.plugin.stats.StatType;
import com.rpgcore.plugin.stats.StatsService;
import com.rpgcore.plugin.tree.TreeFellListener;
import com.rpgcore.plugin.tree.TreeFellService;
import com.rpgcore.plugin.util.RpgScoreboard;
import com.rpgcore.plugin.util.SaveQueue;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private NameplateService nameplates;
    private EconomyService economy;
    private TitleService titles;
    private TitleMenu titleMenu;
    private AchievementService achievements;
    private CollectionService collections;
    private CollectionMenu collectionMenu;
    private DuelService duels;
    private MailboxService mailbox;
    private AuctionService auctions;
    private CorpConfig corpConfig;
    private CorpService corps;
    private CompanyMenu companyMenu;
    private StockMenu stockMenu;
    private BlueprintService blueprints;
    private BlueprintMenu blueprintMenu;
    private BlueprintListener blueprintListener;
    private EconomyConfig economyConfig;
    private MarketService market;
    private BankService bank;
    private MacroService macro;
    private MarketMenu marketMenu;
    private BankMenu bankMenu;
    private EconomyMenu economyMenu;
    private AuctionMenu auctionMenu;
    private MainMenu mainMenu;
    private ProgressListener progress;
    /**
     * The HUD task, kept so hud.interval-ticks can be re-read on reload: a
     * repeating task's period is fixed when it is scheduled, so honouring a
     * changed interval means replacing the task, not re-reading a field.
     */
    private HudTask hudTask;
    /** The single thread every file write goes through. */
    /**
     * Set while a round of store writes is on the writer thread. The tick pump
     * skips building the next round until it clears, which keeps at most one
     * round's worth of snapshots alive however slow the disk is.
     */
    private final AtomicBoolean batchWriting = new AtomicBoolean();
    private SaveQueue saveQueue;
    /** Delivery to the Discord webhook, and what the messages look like. */
    private DiscordService discord;
    private DiscordNotifier notifier;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Before any store: each builds a writer against it on construction.
        this.saveQueue = new SaveQueue(this);
        this.rpgConfig = new RpgConfig(this);
        this.scoreboard = new RpgScoreboard();
        // Early, so anything constructed below can report through it.
        this.discord = new DiscordService(this);
        this.notifier = new DiscordNotifier(this);
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

        // Titles are a registry the other two fill in, so it comes first and
        // both of them declare into it as they load.
        this.economy = new EconomyService(this);
        this.titles = new TitleService(this);
        this.achievements = new AchievementService(this);
        this.achievements.load();
        this.collections = new CollectionService(this);
        this.collections.load();
        // The mailbox comes before anything that can owe a player something,
        // because those all hand their loose ends to it.
        this.mailbox = new MailboxService(this);
        this.mailbox.load();
        this.duels = new DuelService(this);
        this.auctions = new AuctionService(this);
        this.auctions.load();
        // The economy is built in the order it depends on itself: the
        // catalogue, then the market that prices it, then the bank that lends
        // against it, and last the central bank that measures both and sets
        // the rate they run on.
        this.economyConfig = new EconomyConfig(this);
        this.market = new MarketService(this, economyConfig);
        this.market.load();
        this.bank = new BankService(this, economyConfig);
        this.bank.load();
        this.macro = new MacroService(this, economyConfig, market, bank);
        this.macro.load();

        // Companies come after the market they sell into and before the
        // blueprints that can be paid for out of a company's cash.
        this.corpConfig = new CorpConfig(this);
        this.corps = new CorpService(this, corpConfig);
        this.corps.load();
        this.blueprints = new BlueprintService(this);
        this.blueprints.load();

        this.nameplates = new NameplateService(this);

        this.statsMenu = new StatsMenu(this);
        this.jobMenu = new JobMenu(this);
        this.titleMenu = new TitleMenu(this);
        this.collectionMenu = new CollectionMenu(this);
        this.auctionMenu = new AuctionMenu(this);
        this.marketMenu = new MarketMenu(this);
        this.companyMenu = new CompanyMenu(this);
        this.stockMenu = new StockMenu(this);
        this.blueprintMenu = new BlueprintMenu(this);
        this.bankMenu = new BankMenu(this);
        this.economyMenu = new EconomyMenu(this);
        this.mainMenu = new MainMenu(this);

        getServer().getPluginManager().registerEvents(new PlayerSessionListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new WeightListener(this), this);
        getServer().getPluginManager().registerEvents(new TreeFellListener(this, treeFell), this);
        getServer().getPluginManager().registerEvents(new GearListener(this), this);
        getServer().getPluginManager().registerEvents(new RangedListener(this), this);
        getServer().getPluginManager().registerEvents(new AnvilListener(this, anvil), this);
        getServer().getPluginManager().registerEvents(new PartyListener(this), this);
        getServer().getPluginManager().registerEvents(new TradeListener(this), this);
        this.progress = new ProgressListener(this);
        getServer().getPluginManager().registerEvents(progress, this);
        getServer().getPluginManager().registerEvents(new DuelListener(this), this);
        this.blueprintListener = new BlueprintListener(this);
        getServer().getPluginManager().registerEvents(blueprintListener, this);
        // Registered unconditionally; the listener itself honours the toggle,
        // so features.proximity-chat responds to /rpgcore reload like the rest.
        getServer().getPluginManager().registerEvents(new ProximityChatListener(this), this);
        // Registered unconditionally like the rest; every handler checks its
        // own toggle, so discord.enabled responds to /rpgcore reload.
        getServer().getPluginManager().registerEvents(new DiscordListener(this), this);

        // Two light repeating tasks total: one tick pump for the weight/tree
        // queues, and the HUD on its own slower interval.
        new BukkitRunnable() {
            private int sinceFlush;

            @Override
            public void run() {
                weight.tick();
                gear.tick();
                treeFell.tick();
                duels.tick();
                auctions.tick();
                blueprints.tick();
                // One long comparison until the economic day actually turns;
                // the whole economy moves on that one call.
                macro.tick();
                // Everything that changed since the last pass is written out
                // together, once, off the main thread. All four stores flush
                // in the same tick on purpose: an auction lot and the mailbox
                // entry it paid into are one transaction, and writing them at
                // different moments is how a crash between the two loses an
                // item that no longer exists anywhere else.
                if (++sinceFlush >= rpgConfig.persistenceFlushTicks()) {
                    sinceFlush = 0;
                    flushStores();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
        scheduleHudTask();

        registerCommand("menu", new MenuCommand(this));
        registerCommand("auction", new AuctionCommand(this));
        registerCommand("job", new JobCommand(this));
        registerCommand("leaderboard", new LeaderboardCommand(this));
        registerCommand("party", new PartyCommand(this));
        registerCommand("p", new PartyChatCommand(this));
        registerCommand("trade", new TradeCommand(this));
        registerCommand("titles", new TitleCommand(this));
        registerCommand("achievements", new AchievementCommand(this));
        registerCommand("collection", new CollectionCommand(this));
        registerCommand("duel", new DuelCommand(this));
        registerCommand("gold", new GoldCommand(this));
        registerCommand("pay", new PayCommand(this));
        registerCommand("market", new MarketCommand(this));
        registerCommand("bank", new BankCommand(this));
        registerCommand("economy", new EconomyCommand(this));
        registerCommand("company", new CompanyCommand(this));
        registerCommand("stocks", new StockCommand(this));
        registerCommand("blueprint", new BlueprintCommand(this));

        new VoiceChatHook(this).check();

        if (StatType.values().length > StatsMenu.shownStatCount()) {
            getLogger().warning("The stats GUI has room for " + StatsMenu.shownStatCount()
                    + " stats but StatType declares " + StatType.values().length
                    + "; the extra ones are allocatable only through the scoreboard mirror."
                    + " Give them their own slots in StatsMenu before shipping them.");
        }

        // Players are already online after a /reload.
        for (Player player : getServer().getOnlinePlayers()) {
            stats.recalculate(player);
            loadProgress(player);
        }

        // On the first tick rather than here: at this point the server is
        // still loading and has not accepted a connection, so announcing now
        // would promise something that has not happened yet - and would be a
        // lie if a later plugin fails and takes the startup down with it.
        getServer().getScheduler().runTask(this, () -> notifier.serverStarted());

        getLogger().info("RPGCore plugin enabled.");
    }

    @Override
    public void onDisable() {
        // A failed enable still calls this, so every field here may be null;
        // shutting down half-built must not bury the error that caused it.
        if (trades != null) {
            // Items sitting in an open trade window belong to their owners,
            // not to the void, so live trades are unwound before anything else.
            for (Player player : getServer().getOnlinePlayers()) {
                trades.endIfTrading(player, "서버가 종료됩니다.");
            }
        }
        // Same reasoning for duels: a stake in escrow belongs to whoever put
        // it up, not to the void.
        if (duels != null) {
            duels.endAll("서버가 종료되어 무승부입니다.");
        }
        // Shutdown writes on this thread: a queued async task would never run.
        if (parties != null) {
            parties.saveNow();
        }
        // Lots are not settled on the way down - they are meant to outlive a
        // restart - but the file has to be current in case this is the last
        // write the process gets.
        if (auctions != null) {
            auctions.saveNow();
        }
        // A company register holds people's shareholdings and a construction
        // site holds a build somebody has already paid for.
        if (corps != null) {
            corps.saveNow();
        }
        if (blueprints != null) {
            blueprints.saveNow();
        }
        // The market holds no items, but the bank holds deposits - gold that
        // has already left players' balances and exists nowhere else.
        if (market != null) {
            market.saveNow();
        }
        if (bank != null) {
            bank.saveNow();
        }
        if (macro != null) {
            macro.saveNow();
        }
        if (players != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                if (nameplates != null) {
                    nameplates.clear(player);
                }
                // Attribute modifiers and the rewritten max_health base are
                // saved with the player, not with the plugin, so anything left
                // on here survives the plugin being removed with nothing left
                // to undo it. Taking them off is part of shutting down.
                if (stats != null) {
                    stats.clearModifiers(player);
                }
                if (weight != null) {
                    weight.clearModifiers(player);
                }
                if (gear != null) {
                    gear.clearModifiers(player);
                }
                players.unload(player);
            }
        }
        if (hudTask != null) {
            hudTask.cancel();
            hudTask = null;
        }
        // Last: unwinding trades and duels above can both post to it.
        if (mailbox != null) {
            mailbox.saveNow();
        }
        // Every store has now written synchronously; this only waits out
        // anything the writer thread still had in hand so the process cannot
        // exit part-way through a file.
        if (saveQueue != null) {
            saveQueue.shutdown();
        }
        // Last, and it blocks: the sender is a daemon thread, so a message
        // that is only queued when the JVM exits is a message nobody sees.
        // The wait is bounded by discord.shutdown-wait-ms.
        if (discord != null) {
            discord.shutdown(notifier == null ? null : notifier.serverStoppingEmbed());
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
            sender.sendMessage(ChatColor.YELLOW + "/rpgcore reload | check | recipes | givexp <player> <amount> | givegold <player> <amount> | reset <player>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                boolean auctionWasOn = rpgConfig.auctionEnabled();
                rpgConfig.reload();
                // Switching the auction house off leaves every live lot
                // holding a seller's item and a bidder's gold with no screen
                // left to reach them through, so they come home now rather
                // than sitting escrowed until the clock runs out.
                if (auctionWasOn && !rpgConfig.auctionEnabled()) {
                    auctions.refundAll("경매장이 꺼져 반환");
                    sender.sendMessage(ChatColor.YELLOW
                            + "[RPGCore] 경매장을 끄면서 진행 중이던 물건과 입찰을 모두 돌려주었습니다.");
                }
                weightTable.load();
                weight.lore().reload();
                progress.load();
                treeFell.load();
                anvil.load();
                jobs.load();
                // The catalogue can gain or lose goods and change the wage,
                // so base prices and supply levels are re-derived; live stock
                // is kept, because a reload reprices the world rather than
                // emptying its warehouses.
                economyConfig.reload();
                market.applyConfig();
                // Both declare their titles, so the registry is rebuilt from
                // scratch rather than accumulating renamed duplicates.
                titles.clear();
                achievements.load();
                collections.load();
                for (Player player : getServer().getOnlinePlayers()) {
                    loadProgress(player);
                }
                duels.endAll("설정을 다시 불러와 무승부입니다.");
                // The HUD interval is baked into the running task, so a new
                // value only takes effect if the task is replaced.
                scheduleHudTask();
                // Factory output and upkeep are read from the catalogue every
                // pass, so retuning it retunes the plants already standing.
                corpConfig.reload();
                leaderboard.invalidate();
                // A reload is how an operator fixes a mistyped webhook, so it
                // is also where the one-warning-per-session mute is lifted.
                discord.unmute();
                for (Player player : getServer().getOnlinePlayers()) {
                    stats.recalculate(player);
                }
                sender.sendMessage(ChatColor.GREEN + "[RPGCore] 설정을 다시 불러왔습니다.");
                return true;
            }
            case "check" -> {
                report(sender);
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
                // addXp refuses anything at or below zero, so reporting a
                // grant for one would be a plain lie to the operator.
                if (amount <= 0) {
                    sender.sendMessage(ChatColor.RED + "XP 는 1 이상이어야 합니다. "
                            + "(레벨과 XP 를 되돌리려면 /rpgcore reset <player>)");
                    return true;
                }
                stats.addXp(target, amount);
                sender.sendMessage(ChatColor.GREEN + "[RPGCore] " + target.getName() + " 에게 XP " + amount + " 지급.");
                return true;
            }
            case "givegold" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "/rpgcore givegold <player> <amount>");
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
                if (amount == 0) {
                    sender.sendMessage(ChatColor.RED + "0 은 아무것도 하지 않습니다. (보유 "
                            + economy.balance(target) + ")");
                    return true;
                }
                if (amount > 0) {
                    economy.give(target, amount);
                } else if (!economy.take(target, -amount)) {
                    sender.sendMessage(ChatColor.RED + "골드가 부족합니다. (보유 "
                            + economy.balance(target) + ")");
                    return true;
                }
                sender.sendMessage(ChatColor.GREEN + "[RPGCore] " + target.getName() + " 골드 "
                        + (amount >= 0 ? "+" : "") + amount + " → " + economy.balance(target) + ".");
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
                achievements.reset(target, data);
                collections.reset(target);
                titles.reset(target);
                stats.recalculate(target, data);
                players.flush(target, data);
                // Gold is deliberately left alone: an admin resetting someone's
                // build should not also confiscate what they earned.
                sender.sendMessage(ChatColor.GREEN + "[RPGCore] " + target.getName()
                        + " 의 스탯/업적/칭호/도감을 초기화했습니다. (골드 "
                        + economy.balance(target) + " 은(는) 그대로)");
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.YELLOW + "/rpgcore reload | check | recipes | givexp <player> <amount> | givegold <player> <amount> | reset <player>");
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

    /**
     * Prints what every subsystem actually loaded. A feature whose item list
     * or recipe section came back empty behaves exactly like a broken plugin,
     * so this makes that state readable instead of leaving operators guessing.
     */
    private void report(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "===== RPGCore 점검 =====");
        line(sender, "직업", rpgConfig.jobsEnabled(), jobs.jobs().size() + "종 (jobs.yml)");
        line(sender, "연쇄 벌목", rpgConfig.treeFellEnabled(),
                treeFell.logCount() + "종 원목 / " + treeFell.toolCount() + "종 도구, 반경 "
                        + rpgConfig.treeFellRadius() + ", 최대 " + rpgConfig.treeFellMaxBlocks() + "블록");
        line(sender, "모루 강화", rpgConfig.anvilEnabled(), anvil.recipes().size() + "종 조합법");
        line(sender, "소지 무게", true, weightTable.size() + "종 분류");
        line(sender, "내구도 페널티", rpgConfig.durabilityScalingEnabled(),
                rpgConfig.durabilityFullAbove() + "% 이상 무패널티, 최저 " + rpgConfig.durabilityMinPerformance() + "%");
        line(sender, "파티", rpgConfig.partyEnabled(), parties.count() + "개 활성");
        line(sender, "거래", rpgConfig.tradeEnabled(), "최대 거리 "
                + (rpgConfig.tradeMaxDistance() > 0 ? (int) rpgConfig.tradeMaxDistance() + "블록" : "제한 없음"));
        line(sender, "순위표", rpgConfig.leaderboardEnabled(), rpgConfig.leaderboardSize() + "명 표시");
        line(sender, "근접 채팅", rpgConfig.proximityEnabled(), (int) rpgConfig.proximityRange() + "블록");
        line(sender, "HUD", rpgConfig.hudEnabled(), rpgConfig.hudInterval() + "틱 간격");
        line(sender, "머리 위 이름표", rpgConfig.nameplateEnabled(), rpgConfig.displaySummary(NameplateService.NAMEPLATE));
        line(sender, "플레이어 목록", rpgConfig.tablistEnabled(), rpgConfig.displaySummary(NameplateService.TABLIST));
        line(sender, "무게 툴팁", rpgConfig.weightLoreEnabled(), rpgConfig.weightLoreFormat().replace("%weight%", "n"));
        line(sender, "업적", rpgConfig.achievementsEnabled(),
                achievements.count() + "종, 칭호 " + titles.count() + "종, 집계 대상 광석 "
                        + progress.oreCount() + "종 / 원목 " + progress.logCount() + "종");
        line(sender, "도감", rpgConfig.collectionEnabled(),
                collections.categories().size() + "개 분류 / " + collections.entryCount() + "종");
        line(sender, "대결", rpgConfig.duelEnabled(), "최대 " + rpgConfig.duelMaxGold()
                + "골드, " + rpgConfig.duelCountdownSeconds() + "초 카운트다운, 제한 "
                + rpgConfig.duelMaxSeconds() + "초, 진행 중 " + duels.count() + "건");
        line(sender, "기업", rpgConfig.companyEnabled(), corps.count() + "개 ("
                + corps.countNpc() + "개 공모 / " + corps.countState() + "개 공기업), 공장 "
                + corpConfig.factories().size() + "종, 창업비 " + corpConfig.createCost()
                + ", 일당 " + corpConfig.wagePerEmployee() + ", 배당 "
                + corpConfig.defaultDividendPercent() + "%, 법인세 "
                + (int) corpConfig.corporateTaxPercent() + "%");
        line(sender, "부실·파산", rpgConfig.companyEnabled(), "부채비율 한계 "
                + (int) corpConfig.debtRatioLimitPercent() + "%, 자본잠식 "
                + corpConfig.capitalErosionDays() + "일이면 파산 (청산 시 은행이 우선)");
        line(sender, "공기업", rpgConfig.companyEnabled() && corpConfig.stateEnabled(),
                "재고 " + (int) corpConfig.shortageThresholdPercent() + "% 미만이 "
                        + corpConfig.shortageDays() + "일 이어지면 설립 (최대 "
                        + corpConfig.stateMaxTotal() + "개, 자본금 "
                        + corpConfig.stateStartupCapital() + "), 재고 "
                        + (int) corpConfig.surplusThresholdPercent() + "% 초과 "
                        + corpConfig.privatiseDays() + "일이면 민영화");
        line(sender, "청사진", rpgConfig.blueprintEnabled(), blueprints.count() + "장, 공사 중 "
                + blueprints.sites().size() + "곳 · 최대 " + rpgConfig.blueprintMaxBlocks()
                + "블록 / 한 변 " + rpgConfig.blueprintMaxDimension() + " · 초당 "
                + rpgConfig.blueprintBlocksPerSecond() + "블록 · 시공비 "
                + (int) rpgConfig.blueprintMarginPercent() + "%");
        line(sender, "경매장", rpgConfig.auctionEnabled(), auctions.count() + "건 진행 중, "
                + (rpgConfig.auctionDurationMinutes() / 60) + "시간, 등록 수수료 "
                + rpgConfig.auctionListingFeePercent() + "% / 판매 수수료 "
                + rpgConfig.auctionTaxPercent() + "%, 1인 " + rpgConfig.auctionMaxListings() + "개");
        line(sender, "골드", true, "처치 +" + rpgConfig.goldPerMobKill() + " / 레벨업 +"
                + rpgConfig.goldPerLevel() + (rpgConfig.goldTransferAllowed() ? ", /pay 허용" : ", /pay 금지"));
        line(sender, "시장", rpgConfig.marketEnabled(), market.size() + "품목 / "
                + economyConfig.categories().size() + "분류, 시급 " + (int) economyConfig.hourlyWage()
                + " 기준 · 초기공급 " + economyConfig.initialSupplyHours() + "시간 x "
                + economyConfig.referencePlayers() + "명 · 스프레드 "
                + (int) economyConfig.spreadPercent() + "% / 거래세 "
                + (int) economyConfig.salesTaxPercent() + "% · 금고 " + market.treasury());
        line(sender, "은행", rpgConfig.bankEnabled(), bank.accountCount() + "계좌, 예금 "
                + bank.totalDeposits() + " / 대출 " + bank.totalLoans() + " · 지급준비율 "
                + (int) economyConfig.reserveRatioPercent() + "% · 대출여력 "
                + bank.lendingCapacity() + " · 연체율 "
                + String.format(java.util.Locale.ROOT, "%.1f%%", bank.delinquencyPercent())
                + " · 국채 잔액 " + bank.bondsOutstanding() + " (연 "
                + com.rpgcore.plugin.economy.BankService.percent(bank.bondRate()) + ")");
        line(sender, "중앙은행", rpgConfig.marketEnabled() || rpgConfig.bankEnabled(),
                macro.day() + "일차 (하루 " + economyConfig.dayMinutes() + "분, 1년 "
                        + economyConfig.daysPerYear() + "일) · 물가 "
                        + String.format(java.util.Locale.ROOT, "%.1f", macro.cpi()) + " ("
                        + MacroService.signed(macro.inflation()) + ", 목표 "
                        + economyConfig.inflationTargetPercent() + "%) · 정책금리 "
                        + com.rpgcore.plugin.economy.BankService.percent(macro.policyRate()));

        // Reports whether the webhook is set, never what it is: the URL is a
        // credential, and /rpgcore check is run in front of other people.
        line(sender, "Discord", rpgConfig.discordEnabled(),
                (rpgConfig.discordWebhookUrl().isBlank()
                        ? "웹훅 주소가 비어 있음"
                        : "웹훅 설정됨") + " · 보내는 알림 " + discordEventSummary());

        sender.sendMessage(ChatColor.GRAY + "레벨: 최대 "
                + (rpgConfig.maxLevel() > 0 ? String.valueOf(rpgConfig.maxLevel()) : "무제한")
                + ", 곡선 x" + rpgConfig.xpMultiplier()
                + " (Lv2 " + stats.xpNeedFor(1) + " / Lv10 " + stats.xpNeedFor(9)
                + " / Lv20 " + stats.xpNeedFor(19) + " XP)");
        sender.sendMessage(ChatColor.GRAY + "인챈트 한계: "
                + (rpgConfig.enchantRespectVanilla() ? "바닐라 최대 레벨" : "최대 " + rpgConfig.enchantMaxLevel()));
    }

    /** Which relays are on, by their config names. Never includes the URL. */
    private String discordEventSummary() {
        List<String> on = new ArrayList<>();
        for (String event : RpgConfig.discordEventNames()) {
            if (rpgConfig.discordEvent(event)) {
                on.add(event);
            }
        }
        return on.isEmpty() ? "없음" : String.join(", ", on);
    }

    private void line(CommandSender sender, String label, boolean enabled, String detail) {
        sender.sendMessage((enabled ? ChatColor.GREEN + " O " : ChatColor.DARK_GRAY + " X ")
                + ChatColor.WHITE + label + ChatColor.GRAY + " - " + (enabled ? detail : "꺼짐"));
    }

    /**
     * Queues a write for any store whose file no longer matches memory - all of
     * them as ONE task, in one fixed order.
     *
     * The order is the point. Items cross between these files: an auction lot
     * that sells moves into the mailbox, and a wound-up company pays its
     * shareholders through it. The file giving the item away has to be written before
     * the file receiving it, or a crash in between leaves the item in both -
     * and the mailbox, which only ever receives, is therefore always last.
     *
     * When each store queued itself this held only by luck. Both of them
     * coalesced their own writes independently, so they drifted: over 3,000
     * measured rounds the receiving file was written ahead of the giving one 7
     * times, once by 649 rounds' worth of state.
     */
    private void flushStores() {
        if (batchWriting.get()) {
            // The last round is still on the disk. Leaving the stores dirty
            // means the next tick builds one fresh snapshot instead of a
            // backlog of stale ones - the coalescing that used to live in each
            // store, in the one place that can still guarantee the order.
            return;
        }
        List<Runnable> writes = new ArrayList<>(6);
        add(writes, parties.pendingWrite());
        add(writes, auctions.pendingWrite());
        // The market gives gold to the bank's borrowers and takes it from its
        // depositors, so it is written first for the same reason as above.
        add(writes, market.pendingWrite());
        add(writes, bank.pendingWrite());
        add(writes, macro.pendingWrite());
        add(writes, corps.pendingWrite());
        add(writes, blueprints.pendingLibraryWrite());
        add(writes, blueprints.pendingSiteWrite());
        // Last: everything above can post into it, nothing it holds goes back.
        add(writes, mailbox.pendingWrite());
        if (writes.isEmpty()) {
            return;
        }
        batchWriting.set(true);
        saveQueue.submit(() -> {
            try {
                for (Runnable write : writes) {
                    write.run();
                }
            } finally {
                batchWriting.set(false);
            }
        });
    }

    private static void add(List<Runnable> writes, Runnable write) {
        if (write != null) {
            writes.add(write);
        }
    }

    /** (Re)schedules the HUD task on the interval currently configured. */
    private void scheduleHudTask() {
        if (hudTask != null) {
            hudTask.cancel();
        }
        hudTask = new HudTask(this);
        hudTask.runTaskTimer(this, 20L, rpgConfig.hudInterval());
    }

    /** Opens the stats GUI. Same screen for Java and Bedrock players. */
    public void openStatsMenu(Player player) {
        statsMenu.open(player);
    }

    /** The three per-player sets that are kept in memory rather than re-read. */
    private void loadProgress(Player player) {
        collections.load(player);
        achievements.load(player);
        titles.load(player);
    }

    /**
     * True for the inventories RPGCore itself puts on screen. Listeners that
     * rewrite items in passing use this to keep their hands off menu furniture.
     */
    public boolean isOwnMenu(org.bukkit.inventory.InventoryHolder holder) {
        return holder instanceof StatsMenu.Holder
                || holder instanceof JobMenu.Holder
                || holder instanceof TitleMenu.Holder
                || holder instanceof CollectionMenu.Holder
                || holder instanceof MainMenu.Holder
                || holder instanceof AuctionMenu.Holder
                || holder instanceof MarketMenu.Holder
                || holder instanceof BankMenu.Holder
                || holder instanceof EconomyMenu.Holder
                || holder instanceof CompanyMenu.Holder
                || holder instanceof StockMenu.Holder
                || holder instanceof BlueprintMenu.Holder
                || holder instanceof com.rpgcore.plugin.trade.TradeSession;
    }

    public NameplateService nameplates() {
        return nameplates;
    }

    public EconomyService economy() {
        return economy;
    }

    public TitleService titles() {
        return titles;
    }

    public TitleMenu titleMenu() {
        return titleMenu;
    }

    public AchievementService achievements() {
        return achievements;
    }

    public CollectionService collections() {
        return collections;
    }

    public CollectionMenu collectionMenu() {
        return collectionMenu;
    }

    public DuelService duels() {
        return duels;
    }

    public SaveQueue saveQueue() {
        return saveQueue;
    }

    public MailboxService mailbox() {
        return mailbox;
    }

    public AuctionService auctions() {
        return auctions;
    }

    public CorpService corps() {
        return corps;
    }

    public CorpConfig corpConfig() {
        return corpConfig;
    }

    public CompanyMenu companyMenu() {
        return companyMenu;
    }

    public StockMenu stockMenu() {
        return stockMenu;
    }

    public BlueprintService blueprints() {
        return blueprints;
    }

    public BlueprintMenu blueprintMenu() {
        return blueprintMenu;
    }

    public BlueprintListener blueprintListener() {
        return blueprintListener;
    }

    public EconomyConfig economyConfig() {
        return economyConfig;
    }

    public MarketService market() {
        return market;
    }

    public BankService bank() {
        return bank;
    }

    public MacroService macro() {
        return macro;
    }

    public MarketMenu marketMenu() {
        return marketMenu;
    }

    public BankMenu bankMenu() {
        return bankMenu;
    }

    public EconomyMenu economyMenu() {
        return economyMenu;
    }

    public AuctionMenu auctionMenu() {
        return auctionMenu;
    }

    public MainMenu mainMenu() {
        return mainMenu;
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

    public DiscordService discord() {
        return discord;
    }

    public DiscordNotifier notifier() {
        return notifier;
    }

    /**
     * The scoreboard mirror.
     *
     * Exposed for the money supply, which has to count every wallet on the
     * server - including offline players - and has to count them now rather
     * than from the leaderboard's few-second cache.
     */
    public RpgScoreboard scoreboard() {
        return scoreboard;
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
