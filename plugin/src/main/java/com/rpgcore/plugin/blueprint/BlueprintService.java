package com.rpgcore.plugin.blueprint;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.corp.Company;
import com.rpgcore.plugin.economy.MarketItem;
import com.rpgcore.plugin.economy.MarketService;
import com.rpgcore.plugin.util.DeferredSave;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Blueprints: copy a building once, pay for it at market prices, and let it
 * put itself up.
 *
 * The three parts are deliberately separate. Capturing is a snapshot of
 * blocks. Costing runs the material list through the real market - the same
 * price curve a player pays, so a castle of diamond blocks costs what diamond
 * blocks cost today and a big order pushes that price up while it fills.
 * Building is a queue that places a bounded number of blocks per tick, so a
 * fifty-thousand-block project cannot stall the server.
 *
 * Paying market prices is the point rather than a formality. Construction is
 * the demand side of this economy: it takes goods off the shelf, which raises
 * prices, which is what makes somebody's mine worth building. A blueprint
 * priced from a fixed table would be a separate game running alongside the
 * economy instead of inside it.
 */
public final class BlueprintService {

    /** The price of a build, broken down so the payer can see what for. */
    public record Estimate(long materials, long margin, long imported, long total,
                           Map<Material, Integer> items, Map<Material, Long> shortfall) {

        public boolean hasShortfall() {
            return !shortfall.isEmpty();
        }
    }

    /** A player's current corner selection. */
    public static final class Selection {
        private Location first;
        private Location second;

        public Location first() {
            return first;
        }

        public Location second() {
            return second;
        }

        public boolean complete() {
            return first != null && second != null
                    && first.getWorld() != null
                    && first.getWorld().equals(second.getWorld());
        }

        public int volume() {
            if (!complete()) {
                return 0;
            }
            return (Math.abs(first.getBlockX() - second.getBlockX()) + 1)
                    * (Math.abs(first.getBlockY() - second.getBlockY()) + 1)
                    * (Math.abs(first.getBlockZ() - second.getBlockZ()) + 1);
        }
    }

    private final RpgCorePlugin plugin;
    private final BlueprintStorage storage;
    private final DeferredSave libraryWriter;
    private final DeferredSave siteWriter;

    private final Map<UUID, Blueprint> blueprints = new LinkedHashMap<>();
    private final Map<UUID, ConstructionSite> sites = new LinkedHashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    /** When each player was last told they were standing in a building site. */
    private final Map<UUID, Long> lastPushMs = new HashMap<>();

    public BlueprintService(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.storage = new BlueprintStorage(plugin);
        this.libraryWriter = new DeferredSave(plugin, BlueprintStorage.LIBRARY,
                () -> storage.buildLibrary(blueprints.values()));
        this.siteWriter = new DeferredSave(plugin, BlueprintStorage.SITES,
                () -> storage.buildSites(sites.values()));
    }

    public boolean enabled() {
        return plugin.rpgConfig().blueprintEnabled();
    }

    public void load() {
        blueprints.clear();
        sites.clear();
        for (Blueprint blueprint : storage.loadLibrary()) {
            blueprints.put(blueprint.id(), blueprint);
        }
        for (ConstructionSite site : storage.loadSites()) {
            if (blueprints.containsKey(site.blueprintId())) {
                sites.put(site.id(), site);
            } else {
                plugin.getLogger().warning("A construction site refers to a blueprint that is gone ("
                        + site.blueprintName() + ") - dropped. Its owner paid for it.");
            }
        }
        plugin.getLogger().info("Blueprints loaded: " + blueprints.size()
                + ", construction sites running: " + sites.size() + ".");
    }

    public void save() {
        libraryWriter.markDirty();
    }

    public void saveSites() {
        siteWriter.markDirty();
    }

    public void saveNow() {
        libraryWriter.flushNow();
        siteWriter.flushNow();
    }

    public Runnable pendingLibraryWrite() {
        return libraryWriter.pendingWrite();
    }

    public Runnable pendingSiteWrite() {
        return siteWriter.pendingWrite();
    }

    // ------------------------------------------------------------ selection

    public Selection selection(Player player) {
        return selections.computeIfAbsent(player.getUniqueId(), id -> new Selection());
    }

    public void setCorner(Player player, boolean first, Location location) {
        Selection selection = selection(player);
        if (first) {
            selection.first = location.getBlock().getLocation();
        } else {
            selection.second = location.getBlock().getLocation();
        }
        int volume = selection.volume();
        player.sendMessage(ChatColor.GREEN + "[청사진] " + (first ? "1번" : "2번") + " 모서리: "
                + location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ()
                + (selection.complete()
                ? ChatColor.GRAY + " · 범위 " + volume + "블록"
                : ChatColor.GRAY + " · 나머지 모서리를 찍으세요"));
    }

    public void clearSelection(Player player) {
        selections.remove(player.getUniqueId());
    }

    // -------------------------------------------------------------- library

    public Blueprint byName(Player player, String name) {
        if (name == null) {
            return null;
        }
        for (Blueprint blueprint : blueprints.values()) {
            if (blueprint.name().equalsIgnoreCase(name)
                    && blueprint.owner().equals(player.getUniqueId())) {
                return blueprint;
            }
        }
        // Somebody else's, by exact name: blueprints are readable by anyone,
        // because a server that designs one good town hall should be able to
        // put up more than one.
        for (Blueprint blueprint : blueprints.values()) {
            if (blueprint.name().equalsIgnoreCase(name)) {
                return blueprint;
            }
        }
        return null;
    }

    public List<Blueprint> ownedBy(UUID owner) {
        List<Blueprint> out = new ArrayList<>();
        for (Blueprint blueprint : blueprints.values()) {
            if (blueprint.owner().equals(owner)) {
                out.add(blueprint);
            }
        }
        return out;
    }

    public List<Blueprint> all() {
        return new ArrayList<>(blueprints.values());
    }

    public int count() {
        return blueprints.size();
    }

    public List<ConstructionSite> sites() {
        return new ArrayList<>(sites.values());
    }

    public List<ConstructionSite> sitesOf(UUID owner) {
        List<ConstructionSite> out = new ArrayList<>();
        for (ConstructionSite site : sites.values()) {
            if (site.owner().equals(owner)) {
                out.add(site);
            }
        }
        return out;
    }

    /**
     * Captures the selected region.
     *
     * Reads blocks on the main thread, which is the only place it is legal to
     * read them, and is why the volume is capped: a million-block region would
     * be a million world lookups in one tick.
     */
    public Blueprint capture(Player player, String name) {
        if (!enabled()) {
            player.sendMessage(ChatColor.RED + "[청사진] 이 서버에서는 청사진을 쓸 수 없습니다.");
            return null;
        }
        Selection selection = selection(player);
        if (!selection.complete()) {
            player.sendMessage(ChatColor.RED + "[청사진] 먼저 두 모서리를 찍으세요. "
                    + "/blueprint wand 로 도구를 받거나 /blueprint pos1 · pos2 를 쓰세요.");
            return null;
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 24) {
            player.sendMessage(ChatColor.RED + "[청사진] 이름은 1~24자여야 합니다.");
            return null;
        }
        if (ownedBy(player.getUniqueId()).size() >= plugin.rpgConfig().blueprintMaxPerPlayer()) {
            player.sendMessage(ChatColor.RED + "[청사진] 저장할 수 있는 청사진은 "
                    + plugin.rpgConfig().blueprintMaxPerPlayer() + "개까지입니다.");
            return null;
        }

        Location a = selection.first();
        Location b = selection.second();
        World world = a.getWorld();
        int minX = Math.min(a.getBlockX(), b.getBlockX());
        int minY = Math.min(a.getBlockY(), b.getBlockY());
        int minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        int width = Math.abs(a.getBlockX() - b.getBlockX()) + 1;
        int height = Math.abs(a.getBlockY() - b.getBlockY()) + 1;
        int length = Math.abs(a.getBlockZ() - b.getBlockZ()) + 1;

        int maxDimension = plugin.rpgConfig().blueprintMaxDimension();
        if (width > maxDimension || height > maxDimension || length > maxDimension) {
            player.sendMessage(ChatColor.RED + "[청사진] 한 변은 " + maxDimension
                    + "블록까지입니다. (지금 " + width + "x" + height + "x" + length + ")");
            return null;
        }
        long volume = (long) width * height * length;
        if (volume > plugin.rpgConfig().blueprintMaxBlocks()) {
            player.sendMessage(ChatColor.RED + "[청사진] 범위가 너무 큽니다. 최대 "
                    + plugin.rpgConfig().blueprintMaxBlocks() + "블록, 지금 " + volume + "블록.");
            return null;
        }

        List<String> palette = new ArrayList<>();
        Map<String, Integer> paletteIndex = new HashMap<>();
        palette.add("minecraft:air");
        paletteIndex.put("minecraft:air", Blueprint.AIR);

        int[] blocks = new int[(int) volume];
        int position = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    Block block = world.getBlockAt(minX + x, minY + y, minZ + z);
                    int index = Blueprint.AIR;
                    if (!block.getType().isAir()) {
                        String data = block.getBlockData().getAsString();
                        Integer known = paletteIndex.get(data);
                        if (known == null) {
                            known = palette.size();
                            palette.add(data);
                            paletteIndex.put(data, known);
                        }
                        index = known;
                    }
                    blocks[position++] = index;
                }
            }
        }

        Blueprint blueprint = new Blueprint(UUID.randomUUID(), trimmed, player.getUniqueId(),
                player.getName(), width, height, length, palette, blocks, System.currentTimeMillis());
        blueprints.put(blueprint.id(), blueprint);
        save();

        player.sendMessage(ChatColor.GREEN + "[청사진] " + ChatColor.WHITE + trimmed
                + ChatColor.GREEN + " 을(를) 저장했습니다.");
        player.sendMessage(ChatColor.GRAY + "  " + width + "x" + height + "x" + length
                + " · 블록 " + blueprint.solidCount() + "개 · 자재 "
                + blueprint.materials().size() + "종 · 팔레트 " + (palette.size() - 1) + "종");
        player.sendMessage(ChatColor.GRAY + "  /blueprint cost " + trimmed + " 으로 비용을 봅니다.");
        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8F, 1.2F);
        return blueprint;
    }

    public boolean delete(Player player, Blueprint blueprint) {
        if (blueprint == null) {
            player.sendMessage(ChatColor.RED + "[청사진] 그런 청사진이 없습니다.");
            return false;
        }
        if (!blueprint.owner().equals(player.getUniqueId()) && !player.hasPermission("rpgcore.admin")) {
            player.sendMessage(ChatColor.RED + "[청사진] 자기 청사진만 지울 수 있습니다.");
            return false;
        }
        blueprints.remove(blueprint.id());
        save();
        player.sendMessage(ChatColor.YELLOW + "[청사진] " + blueprint.name() + " 을(를) 지웠습니다.");
        return true;
    }

    // ----------------------------------------------------------------- cost

    /**
     * Prices a blueprint against the live market.
     *
     * Every material walks the same price curve a player's order would, so a
     * thousand stone bricks cost more per block than ten do, and a material
     * the market has run out of has to be imported at a markup. The margin on
     * top is labour: the build puts itself up, and that is not free.
     */
    public Estimate estimate(Blueprint blueprint) {
        return estimate(blueprint, null);
    }

    /**
     * The same estimate, with the builder's trade taken into account: an
     * architect pays less for the labour, never less for the materials -
     * the market charges everyone the same for a brick.
     */
    public Estimate estimate(Blueprint blueprint, Player builder) {
        Map<Material, Integer> items = blueprint.materials();
        Map<Material, Long> shortfall = new LinkedHashMap<>();
        long materials = 0;
        long imported = 0;

        for (Map.Entry<Material, Integer> entry : items.entrySet()) {
            Material material = entry.getKey();
            int units = entry.getValue();
            MarketItem item = material.isItem() ? plugin.market().byMaterial(material) : null;
            if (item == null) {
                // Not traded here: charged at the fallback rate so an
                // untradable block is never free, and never blocks a build.
                materials += (long) units * plugin.rpgConfig().blueprintFallbackPrice();
                continue;
            }
            int available = (int) Math.min(units, Math.floor(item.stock()));
            materials += plugin.market().bulkAskCost(item, available);
            int missing = units - available;
            if (missing > 0) {
                shortfall.put(material, (long) missing);
                double unit = item.ask(plugin.economyConfig())
                        * (1 + plugin.rpgConfig().blueprintImportMarkupPercent() / 100.0);
                imported += Math.round(unit * missing);
            }
        }
        double marginPercent = plugin.rpgConfig().blueprintMarginPercent();
        if (builder != null && plugin.jobs() != null) {
            double discount = plugin.jobs().economyOf(builder).buildDiscount();
            marginPercent *= Math.max(0, 1 - Math.min(90, discount) / 100.0);
        }
        long margin = Math.round((materials + imported) * marginPercent / 100.0);
        return new Estimate(materials, margin, imported,
                materials + margin + imported, items, shortfall);
    }

    // ------------------------------------------------------------- building

    /**
     * Pays for a blueprint and starts putting it up.
     *
     * The order is: check, take the materials off the market, take the gold,
     * then create the site. Taking the goods before the gold means a failure
     * to pay leaves the market untouched, and creating the site last means
     * there is never a site nobody paid for.
     */
    public boolean build(Player player, Blueprint blueprint, Location origin, boolean useCompany) {
        if (!enabled()) {
            player.sendMessage(ChatColor.RED + "[청사진] 이 서버에서는 청사진을 쓸 수 없습니다.");
            return false;
        }
        if (blueprint == null || origin.getWorld() == null) {
            player.sendMessage(ChatColor.RED + "[청사진] 그런 청사진이 없습니다.");
            return false;
        }
        if (!sitesOf(player.getUniqueId()).isEmpty()) {
            player.sendMessage(ChatColor.RED + "[청사진] 이미 공사 중인 현장이 있습니다. "
                    + "끝나기를 기다리거나 /blueprint cancel 로 중단하세요.");
            return false;
        }
        if (overlapsExistingSite(origin, blueprint)) {
            player.sendMessage(ChatColor.RED + "[청사진] 다른 공사 현장과 겹칩니다.");
            return false;
        }

        Company company = useCompany ? plugin.corps().employerOf(player) : null;
        if (useCompany && (company == null || !company.manages(player.getUniqueId()))) {
            player.sendMessage(ChatColor.RED + "[청사진] 회사 자금으로 지으려면 대표나 임원이어야 합니다.");
            return false;
        }

        Estimate estimate = estimate(blueprint, player);
        long total = estimate.total();
        if (company != null) {
            if (company.cash() < total) {
                player.sendMessage(ChatColor.RED + "[청사진] 회사 현금이 부족합니다. 필요 " + total
                        + ", 현금 " + company.cash() + ".");
                return false;
            }
        } else if (total > Integer.MAX_VALUE
                || plugin.economy().balance(player) < total) {
            player.sendMessage(ChatColor.RED + "[청사진] 골드가 부족합니다. 필요 " + total
                    + ", 보유 " + plugin.economy().balance(player) + ".");
            return false;
        }

        // Materials actually come off the shelf. This is what makes a big
        // build move prices - and what makes somebody's factory worth having.
        long spentOnMarket = 0;
        if (plugin.rpgConfig().blueprintConsumeStock()) {
            for (Map.Entry<Material, Integer> entry : estimate.items().entrySet()) {
                MarketItem item = entry.getKey().isItem()
                        ? plugin.market().byMaterial(entry.getKey()) : null;
                if (item == null) {
                    continue;
                }
                MarketService.Fill fill = plugin.market()
                        .buyBulk(item, entry.getValue(), Long.MAX_VALUE / 4);
                spentOnMarket += fill.gold();
            }
        }
        // The margin and anything imported never reached a seller, so it
        // leaves circulation through the treasury like any other fee.
        long fees = Math.max(0, total - spentOnMarket);

        if (company != null) {
            company.addCash(-total);
            plugin.corps().save();
        } else if (!plugin.economy().take(player, (int) total)) {
            player.sendMessage(ChatColor.RED + "[청사진] 결제에 실패했습니다.");
            return false;
        }
        plugin.market().creditTreasury(fees);
        plugin.macro().collectFee(fees);

        ConstructionSite site = new ConstructionSite(UUID.randomUUID(), blueprint.id(),
                blueprint.name(), origin.getWorld().getUID(),
                origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(),
                player.getUniqueId(), player.getName(),
                company == null ? null : company.id(), total, System.currentTimeMillis(),
                0, 0, blueprint.solidCount());
        sites.put(site.id(), site);
        saveSites();

        int seconds = Math.max(1, blueprint.solidCount()
                / Math.max(1, plugin.rpgConfig().blueprintBlocksPerSecond()));
        player.sendMessage(ChatColor.GREEN + "[청사진] " + blueprint.name() + " 착공. "
                + ChatColor.GOLD + total + plugin.rpgConfig().goldSymbol() + ChatColor.GREEN
                + " 를 지불했습니다" + (company == null ? "" : " (회사 자금)") + ".");
        player.sendMessage(ChatColor.GRAY + "  자재 " + estimate.materials()
                + " + 시공비 " + estimate.margin()
                + (estimate.imported() > 0 ? " + 수입 자재 " + estimate.imported() : "")
                + " · 예상 공기 " + formatDuration(seconds));
        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7F, 0.9F);
        return true;
    }

    private boolean overlapsExistingSite(Location origin, Blueprint blueprint) {
        for (ConstructionSite site : sites.values()) {
            if (!site.world().equals(origin.getWorld().getUID())) {
                continue;
            }
            Blueprint other = blueprints.get(site.blueprintId());
            if (other == null) {
                continue;
            }
            boolean apart = origin.getBlockX() + blueprint.width() <= site.originX()
                    || site.originX() + other.width() <= origin.getBlockX()
                    || origin.getBlockY() + blueprint.height() <= site.originY()
                    || site.originY() + other.height() <= origin.getBlockY()
                    || origin.getBlockZ() + blueprint.length() <= site.originZ()
                    || site.originZ() + other.length() <= origin.getBlockZ();
            if (!apart) {
                return true;
            }
        }
        return false;
    }

    /** Finishes a site immediately, for a price. A gold sink with a purpose. */
    public boolean rush(Player player) {
        List<ConstructionSite> mine = sitesOf(player.getUniqueId());
        if (mine.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[청사진] 공사 중인 현장이 없습니다.");
            return false;
        }
        ConstructionSite site = mine.get(0);
        int remaining = Math.max(0, site.solidTotal() - site.placed());
        long cost = (long) remaining * plugin.rpgConfig().blueprintRushCostPerBlock();
        if (cost > Integer.MAX_VALUE || !plugin.economy().take(player, (int) cost)) {
            player.sendMessage(ChatColor.RED + "[청사진] 급행비 " + cost + " 골드가 부족합니다.");
            return false;
        }
        plugin.market().creditTreasury(cost);
        plugin.macro().collectFee(cost);
        Blueprint blueprint = blueprints.get(site.blueprintId());
        if (blueprint != null) {
            // Placed in one go, bounded by what is left rather than by the
            // per-tick budget: the player paid precisely to skip the queue.
            place(site, blueprint, remaining, Integer.MAX_VALUE);
        }
        player.sendMessage(ChatColor.GREEN + "[청사진] 급행 시공으로 공사를 마쳤습니다. (" + cost + "골드)");
        return true;
    }

    public boolean cancel(Player player) {
        List<ConstructionSite> mine = sitesOf(player.getUniqueId());
        if (mine.isEmpty()) {
            player.sendMessage(ChatColor.RED + "[청사진] 공사 중인 현장이 없습니다.");
            return false;
        }
        ConstructionSite site = mine.get(0);
        sites.remove(site.id());
        saveSites();
        // No refund: the materials are already in the walls that did go up,
        // and a refundable build would let a player move the market's stock
        // around for free.
        player.sendMessage(ChatColor.YELLOW + "[청사진] 공사를 중단했습니다. "
                + ChatColor.GRAY + "(이미 쓴 자재는 돌려받지 못합니다)");
        return true;
    }

    /**
     * Advances every site by one tick's worth of blocks.
     *
     * Two budgets, not one: blocks actually placed, and positions looked at.
     * A blueprint that is mostly empty space would otherwise scan millions of
     * air positions in a single tick looking for something to place.
     */
    public void tick() {
        if (sites.isEmpty()) {
            return;
        }
        int perTick = Math.max(1, plugin.rpgConfig().blueprintBlocksPerSecond() / 20);
        List<ConstructionSite> finished = new ArrayList<>();
        for (ConstructionSite site : sites.values()) {
            Blueprint blueprint = blueprints.get(site.blueprintId());
            if (blueprint == null) {
                finished.add(site);
                continue;
            }
            clearOfPeople(site, blueprint);
            place(site, blueprint, perTick, perTick * 40);
            if (site.done(blueprint.volume())) {
                finished.add(site);
                announceFinished(site, blueprint);
            }
        }
        for (ConstructionSite site : finished) {
            sites.remove(site.id());
        }
        if (!finished.isEmpty()) {
            saveSites();
        }
    }

    /**
     * Lifts anybody standing inside the site out of it.
     *
     * Found the hard way: /blueprint build puts the building where the player
     * is standing, which is where they want it and also exactly where the
     * walls are about to be. The first test build suffocated the player who
     * ordered it. Nobody should be killed by their own construction site, so
     * anyone inside the footprint is put on top of it - above the full
     * height, so a rising roof cannot catch them either.
     */
    private void clearOfPeople(ConstructionSite site, Blueprint blueprint) {
        World world = plugin.getServer().getWorld(site.world());
        if (world == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            Location at = player.getLocation();
            int x = at.getBlockX();
            int y = at.getBlockY();
            int z = at.getBlockZ();
            if (x < site.originX() || x >= site.originX() + blueprint.width()
                    || z < site.originZ() || z >= site.originZ() + blueprint.length()
                    || y < site.originY() - 1 || y >= site.originY() + blueprint.height()) {
                continue;
            }
            // Sideways, not upwards. Lifting somebody onto the roof looks
            // right for one tick and then gravity drops them back inside,
            // and the site teleports them again, every tick, forever. Out
            // through the nearest wall is a move that sticks.
            int westGap = x - site.originX();
            int eastGap = site.originX() + blueprint.width() - 1 - x;
            int northGap = z - site.originZ();
            int southGap = site.originZ() + blueprint.length() - 1 - z;
            int best = Math.min(Math.min(westGap, eastGap), Math.min(northGap, southGap));
            double outX = at.getX();
            double outZ = at.getZ();
            if (best == westGap) {
                outX = site.originX() - 1.5;
            } else if (best == eastGap) {
                outX = site.originX() + blueprint.width() + 0.5;
            } else if (best == northGap) {
                outZ = site.originZ() - 1.5;
            } else {
                outZ = site.originZ() + blueprint.length() + 0.5;
            }
            int groundY = world.getHighestBlockYAt((int) outX, (int) outZ) + 1;
            player.teleport(new Location(world, outX, Math.max(groundY, site.originY()),
                    outZ, at.getYaw(), at.getPitch()));
            // Rate limited: somebody who insists on standing in a building
            // site gets moved every time, but told about it occasionally.
            long now = System.currentTimeMillis();
            Long last = lastPushMs.get(player.getUniqueId());
            if (last == null || now - last > 5000) {
                lastPushMs.put(player.getUniqueId(), now);
                player.sendMessage(ChatColor.YELLOW + "[청사진] 공사 구역 밖으로 옮겼습니다.");
            }
        }
    }

    /** Places up to {@code budget} blocks, scanning at most {@code scanCap}. */
    private void place(ConstructionSite site, Blueprint blueprint, int budget, int scanCap) {
        World world = plugin.getServer().getWorld(site.world());
        if (world == null) {
            return;
        }
        BlockData[] palette = blueprint.parsedPalette();
        int placed = 0;
        int scanned = 0;
        int cursor = site.cursor();
        int volume = blueprint.volume();
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();

        while (cursor < volume && placed < budget && scanned < scanCap) {
            int index = blueprint.paletteAt(cursor);
            int x = site.originX() + blueprint.xOf(cursor);
            int y = site.originY() + blueprint.yOf(cursor);
            int z = site.originZ() + blueprint.zOf(cursor);
            cursor++;
            scanned++;
            if (index == Blueprint.AIR || palette[index] == null) {
                continue;
            }
            if (y < minHeight || y >= maxHeight) {
                // Outside the world: counted as placed so progress still
                // reaches 100% rather than hanging on blocks that cannot exist.
                placed++;
                site.placed(site.placed() + 1);
                continue;
            }
            Block block = world.getBlockAt(x, y, z);
            // Physics off: sand must not fall and water must not flow while
            // the walls around them are still going up.
            block.setBlockData(palette[index], false);
            placed++;
            site.placed(site.placed() + 1);
        }
        site.cursor(cursor);
        if (placed > 0) {
            saveSites();
            if (plugin.getServer().getCurrentTick() % 20 == 0) {
                world.spawnParticle(Particle.CLOUD,
                        site.originX() + blueprint.width() / 2.0,
                        site.originY() + Math.min(blueprint.height(),
                                blueprint.yOf(Math.min(cursor, volume - 1))) + 1.0,
                        site.originZ() + blueprint.length() / 2.0,
                        6, 1.5, 0.5, 1.5, 0.01);
            }
        }
    }

    private void announceFinished(ConstructionSite site, Blueprint blueprint) {
        Player owner = plugin.getServer().getPlayer(site.owner());
        if (owner != null) {
            // Putting a building up is progression too, and the tally is what
            // the construction achievements are written against.
            plugin.achievements().bump(owner,
                    com.rpgcore.plugin.progress.CounterType.BUILT, blueprint.solidCount());
            plugin.stats().addXp(owner, Math.clamp(blueprint.solidCount() / 20, 1, 500));
        }
        String where = site.originX() + ", " + site.originY() + ", " + site.originZ();
        if (owner != null) {
            owner.sendMessage(ChatColor.GREEN + "[청사진] " + ChatColor.WHITE + blueprint.name()
                    + ChatColor.GREEN + " 공사가 끝났습니다. " + ChatColor.GRAY + "(" + where + ")");
            owner.playSound(owner.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9F, 1.0F);
        }
        plugin.getServer().broadcastMessage(ChatColor.DARK_AQUA + "[건설] " + ChatColor.WHITE
                + site.ownerName() + ChatColor.GRAY + " 님의 " + ChatColor.WHITE + blueprint.name()
                + ChatColor.GRAY + " 이(가) 완공되었습니다. (" + blueprint.solidCount() + "블록)");
    }

    public static String formatDuration(int seconds) {
        if (seconds < 60) {
            return seconds + "초";
        }
        if (seconds < 3600) {
            return (seconds / 60) + "분 " + (seconds % 60) + "초";
        }
        return (seconds / 3600) + "시간 " + ((seconds % 3600) / 60) + "분";
    }

    public static String money(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    Map<UUID, Blueprint> library() {
        return blueprints;
    }

    Map<UUID, ConstructionSite> siteMap() {
        return sites;
    }
}
