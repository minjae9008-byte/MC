package com.rpgcore.plugin.config;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Typed view over the three config files. Values are read once per reload and
 * cached in fields, so nothing on a hot path parses YAML.
 *
 *   settings.yml  the handful of knobs an operator actually tunes
 *   jobs.yml      job definitions, read by JobService
 *   config.yml    the long lists: item classes, anvil recipes, GUI, chat
 *
 * Both files get the jar's copy attached as defaults, so a config written by
 * an older build keeps working instead of reading back empty.
 */
public final class RpgConfig {

    public static final String SETTINGS_FILE = "settings.yml";
    public static final String JOBS_FILE = "jobs.yml";

    /**
     * Every relayable event, which is also the list written into config.yml.
     * Naming them here rather than reading whatever keys happen to be in the
     * file means a typo switches nothing on by accident.
     */
    private static final List<String> DISCORD_EVENTS = List.of(
            "server-start", "server-stop", "join", "quit", "death", "level-up", "achievement");

    private static final List<String> DISPLAY_SEGMENTS = List.of("party", "title", "level", "job");
    private static final List<String> DISPLAY_SURFACES = List.of("nameplate", "tablist");
    private static final Map<String, String> DISPLAY_LABELS =
            Map.of("party", "파티", "title", "칭호", "level", "레벨", "job", "직업");

    private final RpgCorePlugin plugin;
    private FileConfiguration settings;

    // --- settings.yml ---
    private int maxLevel;
    private int xpBase;
    private int xpGrowth;
    private double xpMultiplier;
    private int startingPoints;
    private int pointsPerLevel;
    private int xpPerMobKill;
    private int xpPerTreeLog;
    private boolean enchantRespectVanilla;
    private int enchantMaxLevel;
    private boolean jobsEnabled;
    private boolean partyEnabled;
    private boolean tradeEnabled;
    private boolean leaderboardEnabled;
    private boolean treeFellEnabled;
    private boolean durabilityScalingEnabled;
    private boolean anvilEnabled;
    private boolean proximityEnabled;
    private boolean hudEnabled;
    private boolean nameplateEnabled;
    private boolean tablistEnabled;
    private boolean weightLoreEnabled;
    private boolean achievementsEnabled;
    private boolean collectionEnabled;
    private boolean duelEnabled;
    private boolean auctionEnabled;
    private boolean marketEnabled;
    private boolean bankEnabled;
    private boolean companyEnabled;
    private boolean blueprintEnabled;

    private int blueprintMaxBlocks;
    private int blueprintMaxDimension;
    private int blueprintMaxPerPlayer;
    private int blueprintBlocksPerSecond;
    private double blueprintMarginPercent;
    private double blueprintImportMarkupPercent;
    private int blueprintFallbackPrice;
    private boolean blueprintConsumeStock;
    private int blueprintRushCostPerBlock;
    private org.bukkit.Material blueprintWand;

    // --- config.yml ---
    private int baseHp;
    private int hpPerLevel;
    private int hpPerVit;
    private double attackPerStr;
    private double attackSpeedPerDex;
    private double speedPerAgi;
    private double jumpPerAgi;
    private double luckPerLuck;
    private int statSoftCap;
    private int statBeyondCapPercent;
    private double knockbackPerStr;
    private double miningPerDex;
    private double sweepPerDex;
    private double knockbackResistPerVit;
    private double safeFallPerAgi;
    private double luckKillChancePerPoint;
    private int luckKillMaxChance;

    private int weightBase;
    private int weightPerStr;
    private int weightScanBatch;
    private int weightRescanInterval;
    private String weightLoreFormat;

    private int treeFellMaxBlocks;
    private int treeFellPerTick;
    private int treeFellRadius;
    private boolean treeFellRespectProtection;
    private boolean treeFellDamageTool;
    private boolean treeFellSneakDisables;

    private int durabilityFullAbove;
    private int durabilityMinPerformance;
    private boolean durabilityAffectsAttack;
    private boolean durabilityAffectsArmor;
    private boolean durabilityAffectsMining;
    private boolean durabilityAffectsRanged;

    private int hudInterval;
    private int persistenceFlushTicks;

    /** Segment name -> format string, and surface.segment -> shown. */
    private final Map<String, String> displayFormats = new HashMap<>();
    private final Set<String> displayShown = new HashSet<>();

    private double proximityRange;
    private boolean proximityHideOutOfRange;
    private String proximityFormat;

    private int leaderboardSize;
    private int leaderboardCacheSeconds;

    private int partyMaxSize;
    private int partyNameMaxLength;
    private int partyInviteSeconds;
    private boolean partyFriendlyFire;
    private String partyChatPrefix;
    private double partyXpShareRange;
    private double partyXpBonusPerMember;

    private int tradeRequestSeconds;
    private double tradeMaxDistance;

    private int startingGold;
    private int goldPerMobKill;
    private int goldPerLevel;
    private String goldSymbol;
    private boolean goldTransferAllowed;

    private String collectionTitle;
    private String titleMenuTitle;
    private String guiTitle;
    private int guiSize;
    private String tradeTitle;
    private String menuTitle;
    private String auctionTitle;
    private int auctionDurationMinutes;
    private int auctionMaxListings;
    private int auctionMinPrice;
    private int auctionMaxPrice;
    private int auctionListingFeePercent;
    private int auctionTaxPercent;
    private int auctionBidIncrementPercent;
    private int auctionAntiSnipeSeconds;
    private boolean auctionAnnounce;

    private int duelRequestSeconds;
    private double duelMaxDistance;
    private int duelMaxGold;
    private int duelMaxSeconds;
    private int duelCountdownSeconds;
    private boolean duelHealBeforeStart;
    private int duelCooldownSeconds;
    private boolean duelAnnounce;

    // --- discord ---
    private boolean discordEnabled;
    private String discordWebhookUrl;
    private String discordUsername;
    private String discordAvatarUrl;
    private long discordShutdownWaitMs;
    private int discordLevelMinimum;
    private int discordLevelStep;
    private boolean discordAllAchievements;
    private final Set<String> discordEvents = new HashSet<>();

    public RpgConfig(RpgCorePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();
        ConfigFiles.applyDefaults(plugin, c, "config.yml");

        boolean firstRun = !ConfigFiles.exists(plugin, SETTINGS_FILE);
        settings = ConfigFiles.load(plugin, SETTINGS_FILE);
        if (firstRun) {
            adoptLegacySettings(c);
        }

        maxLevel = Math.max(0, settings.getInt("level.max", 0));
        xpBase = Math.max(1, settings.getInt("level.xp-base", 80));
        xpGrowth = Math.max(0, settings.getInt("level.xp-growth", 30));
        xpMultiplier = Math.clamp(settings.getDouble("level.xp-multiplier", 1.022D), 1.0D, 10.0D);
        startingPoints = Math.max(0, settings.getInt("level.starting-points", 5));
        pointsPerLevel = Math.max(0, settings.getInt("level.points-per-level", 1));

        xpPerMobKill = Math.max(0, settings.getInt("xp-sources.per-mob-kill", 15));
        xpPerTreeLog = Math.max(0, settings.getInt("xp-sources.per-tree-log", 2));

        enchantRespectVanilla = settings.getBoolean("enchant.respect-vanilla-limits", false);
        enchantMaxLevel = Math.clamp(settings.getInt("enchant.max-level", 255), 1, 255);

        jobsEnabled = settings.getBoolean("features.jobs", true);
        partyEnabled = settings.getBoolean("features.parties", true);
        tradeEnabled = settings.getBoolean("features.trading", true);
        leaderboardEnabled = settings.getBoolean("features.leaderboard", true);
        treeFellEnabled = settings.getBoolean("features.tree-felling", true);
        durabilityScalingEnabled = settings.getBoolean("features.durability-scaling", true);
        anvilEnabled = settings.getBoolean("features.anvil-recipes", true);
        proximityEnabled = settings.getBoolean("features.proximity-chat", true);
        hudEnabled = settings.getBoolean("features.hud", true);
        nameplateEnabled = settings.getBoolean("features.nameplate", true);
        tablistEnabled = settings.getBoolean("features.tablist", true);
        weightLoreEnabled = settings.getBoolean("features.item-weight-lore", true);
        achievementsEnabled = settings.getBoolean("features.achievements", true);
        collectionEnabled = settings.getBoolean("features.collection", true);
        duelEnabled = settings.getBoolean("features.duels", true);
        auctionEnabled = settings.getBoolean("features.auction", true);
        marketEnabled = settings.getBoolean("features.market", true);
        bankEnabled = settings.getBoolean("features.bank", true);
        companyEnabled = settings.getBoolean("features.companies", true);
        blueprintEnabled = settings.getBoolean("features.blueprints", true);

        blueprintMaxBlocks = Math.clamp(c.getInt("blueprint.max-blocks", 50_000), 1, 2_000_000);
        blueprintMaxDimension = Math.clamp(c.getInt("blueprint.max-dimension", 128), 1, 512);
        blueprintMaxPerPlayer = Math.clamp(c.getInt("blueprint.max-per-player", 10), 1, 200);
        blueprintBlocksPerSecond = Math.clamp(c.getInt("blueprint.blocks-per-second", 40), 1, 4000);
        blueprintMarginPercent = Math.clamp(c.getDouble("blueprint.margin-percent", 20), 0, 500);
        blueprintImportMarkupPercent =
                Math.clamp(c.getDouble("blueprint.import-markup-percent", 50), 0, 1000);
        blueprintFallbackPrice = Math.max(0, c.getInt("blueprint.fallback-price", 3));
        blueprintConsumeStock = c.getBoolean("blueprint.consume-market-stock", true);
        blueprintRushCostPerBlock = Math.max(0, c.getInt("blueprint.rush-cost-per-block", 4));
        org.bukkit.Material wand = org.bukkit.Material
                .matchMaterial(c.getString("blueprint.wand", "minecraft:golden_hoe"));
        blueprintWand = wand == null || !wand.isItem() ? org.bukkit.Material.GOLDEN_HOE : wand;

        baseHp = c.getInt("stats.base-hp", 20);
        hpPerLevel = c.getInt("stats.hp-per-level", 1);
        hpPerVit = c.getInt("stats.hp-per-vit", 2);
        attackPerStr = c.getDouble("stats.attack-damage-per-str", 0.35);
        attackSpeedPerDex = c.getDouble("stats.attack-speed-per-dex", 0.06);
        speedPerAgi = c.getDouble("stats.movement-speed-per-agi", 0.0012);
        jumpPerAgi = c.getDouble("stats.jump-strength-per-agi", 0.007);
        luckPerLuck = c.getDouble("stats.luck-per-luck", 0.4);
        knockbackPerStr = c.getDouble("stats.attack-knockback-per-str", 0.02);
        miningPerDex = c.getDouble("stats.mining-speed-per-dex", 0.008);
        sweepPerDex = c.getDouble("stats.sweeping-damage-per-dex", 0.01);
        knockbackResistPerVit = c.getDouble("stats.knockback-resistance-per-vit", 0.008);
        safeFallPerAgi = c.getDouble("stats.safe-fall-blocks-per-agi", 0.15);

        // 0 turns the curve off and every point is worth the last one again.
        statSoftCap = Math.max(0, c.getInt("stats.soft-cap", 20));
        statBeyondCapPercent = Math.clamp(c.getInt("stats.beyond-soft-cap-percent", 35), 0, 100);

        luckKillChancePerPoint = Math.max(0.0D, c.getDouble("economy.luck-double-chance-per-point", 2.0));
        luckKillMaxChance = Math.clamp(c.getInt("economy.luck-double-chance-max", 60), 0, 100);

        loadDiscord(c);

        weightBase = c.getInt("weight.base-capacity", 100);
        weightPerStr = c.getInt("weight.capacity-per-str", 8);
        weightScanBatch = Math.max(1, c.getInt("weight.scans-per-tick", 8));
        weightRescanInterval = Math.max(20, c.getInt("weight.safety-rescan-ticks", 200));
        weightLoreFormat = c.getString("weight.lore-format", "&8무게 %weight%");

        // Clamped: a zero here silently turns chain felling off, which looks
        // exactly like the feature being broken.
        treeFellMaxBlocks = Math.max(1, c.getInt("tree-felling.max-blocks", 256));
        treeFellPerTick = Math.max(1, c.getInt("tree-felling.blocks-per-tick", 12));
        treeFellRadius = Math.clamp(c.getInt("tree-felling.max-horizontal-radius", 5), 1, 32);
        treeFellRespectProtection = c.getBoolean("tree-felling.respect-protection-plugins", true);
        treeFellDamageTool = c.getBoolean("tree-felling.damage-tool", true);
        treeFellSneakDisables = c.getBoolean("tree-felling.sneak-disables", true);

        durabilityFullAbove = Math.clamp(c.getInt("durability-scaling.full-performance-above", 80), 1, 100);
        durabilityMinPerformance = Math.clamp(c.getInt("durability-scaling.minimum-performance", 50), 0, 100);
        durabilityAffectsAttack = c.getBoolean("durability-scaling.affects.attack-damage", true);
        durabilityAffectsArmor = c.getBoolean("durability-scaling.affects.armor", true);
        durabilityAffectsMining = c.getBoolean("durability-scaling.affects.mining-speed", true);
        durabilityAffectsRanged = c.getBoolean("durability-scaling.affects.ranged-damage", true);

        hudInterval = Math.max(5, c.getInt("hud.interval-ticks", 20));
        // How long a change may sit in memory before it reaches disk. Lower is
        // safer against a hard crash and costs more; the write itself is off
        // the main thread either way, so the floor is about write frequency
        // rather than about tick time.
        persistenceFlushTicks = Math.clamp(c.getInt("persistence.flush-interval-ticks", 20), 1, 1200);

        displayFormats.clear();
        displayShown.clear();
        for (String segment : DISPLAY_SEGMENTS) {
            displayFormats.put(segment, c.getString("display." + segment + "-format", ""));
            for (String surface : DISPLAY_SURFACES) {
                // Defaulted per surface: the plate has room for all three, a
                // player-list row is narrow and the job would crowd it out.
                boolean fallback = !("tablist".equals(surface) && "job".equals(segment));
                if (c.getBoolean("display." + surface + ".show-" + segment, fallback)) {
                    displayShown.add(surface + '.' + segment);
                }
            }
        }

        proximityRange = c.getDouble("proximity-chat.range", 24);
        proximityHideOutOfRange = c.getBoolean("proximity-chat.hide-out-of-range", true);
        proximityFormat = c.getString("proximity-chat.format", "&7[근접] &f%player%&7: &f%message%");

        leaderboardSize = Math.clamp(c.getInt("leaderboard.size", 10), 1, 50);
        leaderboardCacheSeconds = Math.max(0, c.getInt("leaderboard.cache-seconds", 30));

        partyMaxSize = Math.max(2, c.getInt("party.max-size", 5));
        partyNameMaxLength = Math.clamp(c.getInt("party.name-max-length", 16), 1, 32);
        partyInviteSeconds = Math.max(5, c.getInt("party.invite-timeout-seconds", 60));
        partyFriendlyFire = c.getBoolean("party.friendly-fire", false);
        partyChatPrefix = c.getString("party.chat-prefix", "&d[%party%] ");
        partyXpShareRange = Math.max(0, c.getDouble("party.xp.share-range", 50));
        partyXpBonusPerMember = Math.max(0, c.getDouble("party.xp.bonus-per-member", 0.05));

        tradeRequestSeconds = Math.max(5, c.getInt("trade.request-timeout-seconds", 60));
        tradeMaxDistance = Math.max(0, c.getDouble("trade.max-distance", 0));

        startingGold = Math.max(0, c.getInt("economy.starting-gold", 0));
        goldPerMobKill = Math.max(0, c.getInt("economy.gold-per-mob-kill", 6));
        goldPerLevel = Math.max(0, c.getInt("economy.gold-per-level-up", 120));
        goldSymbol = c.getString("economy.symbol", "G");
        goldTransferAllowed = c.getBoolean("economy.allow-player-transfer", true);

        collectionTitle = c.getString("collection.title", "&8도감");
        titleMenuTitle = c.getString("titles.menu-title", "&8칭호");
        guiTitle = c.getString("gui.title", "&8캐릭터 정보");
        // Rounded to a legal chest here rather than at each screen that opens
        // one. Bukkit throws on a size that is not 9..54 in steps of nine, and
        // that throw surfaces as a command failing for whoever typed it -
        // clamping at the source means no caller can get it wrong.
        guiSize = legalChestSize(c.getInt("gui.size", 27), 27);
        tradeTitle = c.getString("trade.title", "&8거래");
        menuTitle = c.getString("menu.title", "&8RPG 메뉴");

        auctionTitle = c.getString("auction.title", "&8경매장");
        // Clamped rather than trusted: a zero duration would close every lot
        // on the tick after it opened, and a zero increment would let a bidder
        // hold the top spot forever for one extra gold.
        auctionDurationMinutes = Math.clamp(c.getInt("auction.duration-minutes", 1440), 1, 20160);
        auctionMaxListings = Math.clamp(c.getInt("auction.max-listings-per-player", 5), 1, 45);
        auctionMinPrice = Math.max(1, c.getInt("auction.min-price", 1));
        auctionMaxPrice = Math.max(auctionMinPrice, c.getInt("auction.max-price", 1_000_000));
        auctionListingFeePercent = Math.clamp(c.getInt("auction.listing-fee-percent", 5), 0, 100);
        auctionTaxPercent = Math.clamp(c.getInt("auction.tax-percent", 5), 0, 100);
        auctionBidIncrementPercent = Math.clamp(c.getInt("auction.bid-increment-percent", 5), 1, 100);
        auctionAntiSnipeSeconds = Math.clamp(c.getInt("auction.anti-snipe-seconds", 60), 0, 3600);
        auctionAnnounce = c.getBoolean("auction.announce-new-lots", true);

        duelRequestSeconds = Math.max(5, c.getInt("duel.request-timeout-seconds", 60));
        duelMaxDistance = Math.max(0, c.getDouble("duel.max-distance", 32));
        duelMaxGold = Math.max(0, c.getInt("duel.max-gold-wager", 1000));
        duelMaxSeconds = Math.max(0, c.getInt("duel.max-duration-seconds", 300));
        duelCountdownSeconds = Math.clamp(c.getInt("duel.countdown-seconds", 3), 0, 10);
        duelHealBeforeStart = c.getBoolean("duel.heal-before-start", true);
        duelCooldownSeconds = Math.max(0, c.getInt("duel.cooldown-seconds", 30));
        duelAnnounce = c.getBoolean("duel.announce-result", true);
    }

    /**
     * Carries values an operator had tuned in the old single-file layout into
     * the settings.yml being created for them, so upgrading does not quietly
     * reset the server's balance.
     */
    private void adoptLegacySettings(FileConfiguration legacy) {
        record Moved(String from, String to) {
        }
        Moved[] moves = {
                new Moved("leveling.xp-base", "level.xp-base"),
                new Moved("leveling.xp-growth", "level.xp-growth"),
                new Moved("leveling.starting-points", "level.starting-points"),
                new Moved("leveling.points-per-level", "level.points-per-level"),
                new Moved("xp-sources.per-mob-kill", "xp-sources.per-mob-kill"),
                new Moved("xp-sources.per-tree-log", "xp-sources.per-tree-log"),
                new Moved("tree-felling.enabled", "features.tree-felling"),
                new Moved("durability-scaling.enabled", "features.durability-scaling"),
                new Moved("anvil.enabled", "features.anvil-recipes"),
                new Moved("jobs.enabled", "features.jobs"),
                new Moved("party.enabled", "features.parties"),
                new Moved("trade.enabled", "features.trading"),
                new Moved("proximity-chat.enabled", "features.proximity-chat"),
                new Moved("hud.enabled", "features.hud"),
        };

        int carried = 0;
        for (Moved move : moves) {
            // isSet is false for keys only served by the bundled defaults, so
            // this copies what the operator actually wrote and nothing else.
            if (legacy.isSet(move.from())) {
                settings.set(move.to(), legacy.get(move.from()));
                carried++;
            }
        }
        if (carried > 0) {
            ConfigFiles.save(plugin, settings, SETTINGS_FILE);
            plugin.getLogger().info("Moved " + carried + " setting(s) from config.yml into "
                    + SETTINGS_FILE + ". The old keys in config.yml are now ignored and can be deleted.");
        }
    }

    /** The jobs.yml view, loaded fresh so /rpgcore reload picks up edits. */
    public FileConfiguration jobs() {
        return ConfigFiles.load(plugin, JOBS_FILE);
    }


    public int maxLevel() {
        return maxLevel;
    }

    public int xpBase() {
        return xpBase;
    }

    public int xpGrowth() {
        return xpGrowth;
    }

    public double xpMultiplier() {
        return xpMultiplier;
    }

    public int startingPoints() {
        return startingPoints;
    }

    public int pointsPerLevel() {
        return pointsPerLevel;
    }

    public int xpPerMobKill() {
        return xpPerMobKill;
    }

    public int xpPerTreeLog() {
        return xpPerTreeLog;
    }

    public boolean enchantRespectVanilla() {
        return enchantRespectVanilla;
    }

    public int enchantMaxLevel() {
        return enchantMaxLevel;
    }

    public boolean jobsEnabled() {
        return jobsEnabled;
    }

    public boolean partyEnabled() {
        return partyEnabled;
    }

    public boolean tradeEnabled() {
        return tradeEnabled;
    }

    public boolean leaderboardEnabled() {
        return leaderboardEnabled;
    }

    public boolean treeFellEnabled() {
        return treeFellEnabled;
    }

    public boolean durabilityScalingEnabled() {
        return durabilityScalingEnabled;
    }

    public boolean anvilEnabled() {
        return anvilEnabled;
    }

    public boolean proximityEnabled() {
        return proximityEnabled;
    }

    public boolean hudEnabled() {
        return hudEnabled;
    }

    public boolean nameplateEnabled() {
        return nameplateEnabled;
    }

    public boolean tablistEnabled() {
        return tablistEnabled;
    }

    public boolean weightLoreEnabled() {
        return weightLoreEnabled;
    }

    public boolean achievementsEnabled() {
        return achievementsEnabled;
    }

    public boolean collectionEnabled() {
        return collectionEnabled;
    }

    public boolean duelEnabled() {
        return duelEnabled;
    }

    public int startingGold() {
        return startingGold;
    }

    public int goldPerMobKill() {
        return goldPerMobKill;
    }

    public int goldPerLevel() {
        return goldPerLevel;
    }

    public String goldSymbol() {
        return goldSymbol;
    }

    public boolean goldTransferAllowed() {
        return goldTransferAllowed;
    }

    public String collectionTitle() {
        return collectionTitle;
    }

    public String titleMenuTitle() {
        return titleMenuTitle;
    }

    public String guiTitle() {
        return guiTitle;
    }

    /** Raw value; StatsMenu clamps it to a legal chest size. */
    public int guiSize() {
        return guiSize;
    }

    public String tradeTitle() {
        return tradeTitle;
    }

    public String menuTitle() {
        return menuTitle;
    }

    public boolean auctionEnabled() {
        return auctionEnabled;
    }

    public boolean marketEnabled() {
        return marketEnabled;
    }

    public boolean bankEnabled() {
        return bankEnabled;
    }

    public boolean companyEnabled() {
        return companyEnabled;
    }

    public boolean blueprintEnabled() {
        return blueprintEnabled;
    }

    public int blueprintMaxBlocks() {
        return blueprintMaxBlocks;
    }

    public int blueprintMaxDimension() {
        return blueprintMaxDimension;
    }

    public int blueprintMaxPerPlayer() {
        return blueprintMaxPerPlayer;
    }

    public int blueprintBlocksPerSecond() {
        return blueprintBlocksPerSecond;
    }

    public double blueprintMarginPercent() {
        return blueprintMarginPercent;
    }

    public double blueprintImportMarkupPercent() {
        return blueprintImportMarkupPercent;
    }

    public int blueprintFallbackPrice() {
        return blueprintFallbackPrice;
    }

    public boolean blueprintConsumeStock() {
        return blueprintConsumeStock;
    }

    public int blueprintRushCostPerBlock() {
        return blueprintRushCostPerBlock;
    }

    public org.bukkit.Material blueprintWand() {
        return blueprintWand;
    }

    public String auctionTitle() {
        return auctionTitle;
    }

    public int auctionDurationMinutes() {
        return auctionDurationMinutes;
    }

    public int auctionMaxListings() {
        return auctionMaxListings;
    }

    public int auctionMinPrice() {
        return auctionMinPrice;
    }

    public int auctionMaxPrice() {
        return auctionMaxPrice;
    }

    public int auctionListingFeePercent() {
        return auctionListingFeePercent;
    }

    public int auctionTaxPercent() {
        return auctionTaxPercent;
    }

    public int auctionBidIncrementPercent() {
        return auctionBidIncrementPercent;
    }

    public int auctionAntiSnipeSeconds() {
        return auctionAntiSnipeSeconds;
    }

    public boolean auctionAnnounce() {
        return auctionAnnounce;
    }

    public int duelRequestSeconds() {
        return duelRequestSeconds;
    }

    public double duelMaxDistance() {
        return duelMaxDistance;
    }

    public int duelMaxGold() {
        return duelMaxGold;
    }

    public int duelMaxSeconds() {
        return duelMaxSeconds;
    }

    public int duelCountdownSeconds() {
        return duelCountdownSeconds;
    }

    public boolean duelHealBeforeStart() {
        return duelHealBeforeStart;
    }

    /** Seconds a player must wait after a duel before starting another. */
    public int duelCooldownSeconds() {
        return duelCooldownSeconds;
    }

    public boolean duelAnnounce() {
        return duelAnnounce;
    }

    public String weightLoreFormat() {
        return weightLoreFormat;
    }

    /** Format for one nameplate segment ("party", "level", "job"). */
    public String displayFormat(String segment) {
        return displayFormats.getOrDefault(segment, "");
    }

    /** Whether a surface ("nameplate", "tablist") shows that segment. */
    public boolean displayShows(String surface, String segment) {
        return displayShown.contains(surface + '.' + segment);
    }

    /** What one surface is set to show, for /rpgcore check. */
    public String displaySummary(String surface) {
        StringBuilder out = new StringBuilder();
        for (String segment : DISPLAY_SEGMENTS) {
            if (displayShows(surface, segment)) {
                out.append(out.isEmpty() ? "" : " + ").append(DISPLAY_LABELS.get(segment));
            }
        }
        return out.isEmpty() ? "표시할 항목 없음" : out.toString();
    }

    public int baseHp() {
        return baseHp;
    }

    public int hpPerLevel() {
        return hpPerLevel;
    }

    public int hpPerVit() {
        return hpPerVit;
    }

    public double attackPerStr() {
        return attackPerStr;
    }

    public double attackSpeedPerDex() {
        return attackSpeedPerDex;
    }

    public double speedPerAgi() {
        return speedPerAgi;
    }

    public double jumpPerAgi() {
        return jumpPerAgi;
    }

    public double luckPerLuck() {
        return luckPerLuck;
    }

    public int statSoftCap() {
        return statSoftCap;
    }

    public int statBeyondCapPercent() {
        return statBeyondCapPercent;
    }

    public double knockbackPerStr() {
        return knockbackPerStr;
    }

    public double miningPerDex() {
        return miningPerDex;
    }

    public double sweepPerDex() {
        return sweepPerDex;
    }

    public double knockbackResistPerVit() {
        return knockbackResistPerVit;
    }

    public double safeFallPerAgi() {
        return safeFallPerAgi;
    }

    /** Percentage points of double-reward chance each LUCK point buys. */
    public double luckKillChancePerPoint() {
        return luckKillChancePerPoint;
    }

    public int luckKillMaxChance() {
        return luckKillMaxChance;
    }

    public int weightBase() {
        return weightBase;
    }

    public int weightPerStr() {
        return weightPerStr;
    }

    public int weightScanBatch() {
        return weightScanBatch;
    }

    public int weightRescanInterval() {
        return weightRescanInterval;
    }

    public int treeFellMaxBlocks() {
        return treeFellMaxBlocks;
    }

    public int treeFellPerTick() {
        return treeFellPerTick;
    }

    public int treeFellRadius() {
        return treeFellRadius;
    }

    public boolean treeFellRespectProtection() {
        return treeFellRespectProtection;
    }

    public boolean treeFellDamageTool() {
        return treeFellDamageTool;
    }

    public boolean treeFellSneakDisables() {
        return treeFellSneakDisables;
    }

    public int durabilityFullAbove() {
        return durabilityFullAbove;
    }

    public int durabilityMinPerformance() {
        return durabilityMinPerformance;
    }

    public boolean durabilityAffectsAttack() {
        return durabilityAffectsAttack;
    }

    public boolean durabilityAffectsArmor() {
        return durabilityAffectsArmor;
    }

    public boolean durabilityAffectsMining() {
        return durabilityAffectsMining;
    }

    public boolean durabilityAffectsRanged() {
        return durabilityAffectsRanged;
    }

    public int hudInterval() {
        return hudInterval;
    }

    public int persistenceFlushTicks() {
        return persistenceFlushTicks;
    }

    public double proximityRange() {
        return proximityRange;
    }

    public boolean proximityHideOutOfRange() {
        return proximityHideOutOfRange;
    }

    public String proximityFormat() {
        return proximityFormat;
    }

    public int leaderboardSize() {
        return leaderboardSize;
    }

    public int leaderboardCacheSeconds() {
        return leaderboardCacheSeconds;
    }

    public int partyMaxSize() {
        return partyMaxSize;
    }

    public int partyNameMaxLength() {
        return partyNameMaxLength;
    }

    public int partyInviteSeconds() {
        return partyInviteSeconds;
    }

    public boolean partyFriendlyFire() {
        return partyFriendlyFire;
    }

    public String partyChatPrefix() {
        return partyChatPrefix;
    }

    public double partyXpShareRange() {
        return partyXpShareRange;
    }

    public double partyXpBonusPerMember() {
        return partyXpBonusPerMember;
    }

    public int tradeRequestSeconds() {
        return tradeRequestSeconds;
    }

    public double tradeMaxDistance() {
        return tradeMaxDistance;
    }

    // ------------------------------------------------------------- discord

    /**
     * Reads the webhook block.
     *
     * The URL is a credential: anyone holding it can post into the channel as
     * the server. It is never logged and never echoed by /rpgcore check, and
     * the one warning below names the key rather than the value.
     */
    private void loadDiscord(FileConfiguration c) {
        discordEnabled = c.getBoolean("discord.enabled", false);
        discordWebhookUrl = c.getString("discord.webhook-url", "").trim();
        discordUsername = c.getString("discord.username", "");
        discordAvatarUrl = c.getString("discord.avatar-url",
                "https://mc-heads.net/avatar/%uuid%/64").trim();
        discordShutdownWaitMs = Math.clamp(
                c.getLong("discord.shutdown-wait-ms", 3000L), 0L, 15_000L);
        discordLevelMinimum = Math.max(0, c.getInt("discord.level-up.minimum-level", 10));
        // 0 and 1 both mean "every level"; clamping here saves the caller a
        // modulo by zero.
        discordLevelStep = Math.max(1, c.getInt("discord.level-up.only-multiples-of", 5));
        discordAllAchievements = c.getBoolean("discord.all-achievements", false);

        discordEvents.clear();
        for (String event : DISCORD_EVENTS) {
            if (c.getBoolean("discord.events." + event, true)) {
                discordEvents.add(event);
            }
        }

        if (discordEnabled && discordWebhookUrl.isBlank()) {
            plugin.getLogger().warning("discord.enabled 가 켜져 있지만 discord.webhook-url 이 비어 있어 "
                    + "Discord 알림을 보내지 않습니다.");
        } else if (discordEnabled && !discordWebhookUrl.startsWith("http")) {
            plugin.getLogger().warning("discord.webhook-url 이 http(s):// 로 시작하지 않습니다. "
                    + "Discord 채널 설정 > 연동 > 웹후크에서 'URL 복사'로 얻은 주소를 그대로 넣으세요.");
        }
    }

    public boolean discordEnabled() {
        return discordEnabled;
    }

    public String discordWebhookUrl() {
        return discordWebhookUrl;
    }

    public String discordUsername() {
        return discordUsername;
    }

    public String discordAvatarUrl() {
        return discordAvatarUrl;
    }

    public long discordShutdownWaitMs() {
        return discordShutdownWaitMs;
    }

    public int discordLevelMinimum() {
        return discordLevelMinimum;
    }

    public int discordLevelStep() {
        return discordLevelStep;
    }

    public boolean discordAllAchievements() {
        return discordAllAchievements;
    }

    /**
     * The nearest legal chest size at or below the requested one: 9 to 54
     * slots, in rows of nine.
     */
    private static int legalChestSize(int requested, int fallback) {
        int wanted = requested <= 0 ? fallback : requested;
        return Math.clamp(wanted / 9 * 9, 9, 54);
    }

    /** The relayable event names, in the order they appear in config.yml. */
    public static List<String> discordEventNames() {
        return DISCORD_EVENTS;
    }

    /** Whether one named event is relayed. Unknown names are never on. */
    public boolean discordEvent(String event) {
        return discordEvents.contains(event);
    }
}
