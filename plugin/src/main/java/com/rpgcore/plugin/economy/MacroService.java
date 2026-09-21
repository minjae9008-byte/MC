package com.rpgcore.plugin.economy;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.util.DeferredSave;
import com.rpgcore.plugin.util.RpgScoreboard;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scoreboard.Objective;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The central bank, the national accounts, and the clock they run on.
 *
 * This is the half of the economy nobody trades with directly. Once per
 * economic day it closes the books: the market produces and reprices, the
 * bank pays and charges interest, and then everything that happened is
 * measured - prices, money, output, inequality - and the policy rate is set
 * for tomorrow from what the measurements say.
 *
 * The measurements are real rather than decorative. Each one is computed from
 * state that exists anyway:
 *
 *   물가지수 CPI   a weighted basket of market prices against their base
 *   통화량 M0/M1/M2 gold in wallets, plus demand deposits, plus term deposits
 *   GDP            the value of everything traded in the day
 *   화폐유통속도 V  GDP over M2 - how hard each coin is working
 *   통화승수        M2 over M0 - how much money the bank's lending created
 *   지니계수        inequality of net worth, 0 = equal, 1 = one player has it all
 *
 * And the policy rate is a Taylor rule over two of them:
 *
 *   정책금리 = 중립금리 + 물가상승률 + a(물가상승률 - 목표) + b(산출갭)
 *
 * so a server that mines a fortune and spends it into rising prices gets
 * expensive credit, and a server in a slump gets cheap credit. Nothing here
 * is scripted: turn the wage up in economy.yml and inflation follows, and the
 * rate follows that.
 *
 * Time does not pass while the server is down. Accruing a month of interest
 * on a player who was away is technically correct and a terrible thing to
 * come back to.
 */
public final class MacroService {

    /** A rate decision, kept for the dashboard's history strip. */
    public record PolicyNote(int day, double rate, double inflation, String reason) {
    }

    private static final String FILE = "macro.yml";
    private static final int POLICY_LOG = 8;
    /**
     * How far the output gap may read either way, in percent. One busy
     * evening on an otherwise quiet server is arithmetically a 400% boom, and
     * the rate rule would take that literally.
     */
    private static final double MAX_OUTPUT_GAP = 20.0;

    private final RpgCorePlugin plugin;
    private final EconomyConfig config;
    private final MarketService market;
    private final BankService bank;
    private final DeferredSave writer;
    private final File file;

    private int day = 1;
    private long dayStartedAtMs = System.currentTimeMillis();

    private double policyRate;
    private double cpi = 100;
    private double inflation;
    private final Deque<Double> cpiHistory = new ArrayDeque<>();
    private final Deque<Double> gdpHistory = new ArrayDeque<>();
    private double potentialGdp;
    private double outputGap;

    /** Gross value traded so far today; becomes GDP when the day closes. */
    private long tradeValueToday;
    private long gdpToday;
    private long lifetimeTrade;

    private long m0;
    private long m1;
    private long m2;
    /** Yesterday's M2, for the money-growth reading. */
    private long previousM2;
    private double moneyGrowth;
    private double velocity;
    private double moneyMultiplier;
    private double gini;
    private int measuredWallets;
    private long lastMoneyReadMs;

    private long printedTotal;
    private long feeTake;

    private final List<PolicyNote> policyLog = new ArrayList<>();

    public MacroService(RpgCorePlugin plugin, EconomyConfig config,
                        MarketService market, BankService bank) {
        this.plugin = plugin;
        this.config = config;
        this.market = market;
        this.bank = bank;
        this.file = new File(plugin.getDataFolder(), FILE);
        this.policyRate = config.neutralRatePercent() + config.inflationTargetPercent();
        this.writer = new DeferredSave(plugin, FILE, this::build);
    }

    // ------------------------------------------------------------------ clock

    /**
     * Called from the tick pump twenty times a second, so it is one long
     * comparison until the day actually turns.
     */
    public void tick() {
        if (!plugin.rpgConfig().marketEnabled() && !plugin.rpgConfig().bankEnabled()) {
            return;
        }
        if (System.currentTimeMillis() - dayStartedAtMs < config.dayMillis()) {
            return;
        }
        dayStartedAtMs = System.currentTimeMillis();
        advanceDay();
    }

    /** How far through the current economic day we are, 0-100. */
    public int dayProgressPercent() {
        long elapsed = System.currentTimeMillis() - dayStartedAtMs;
        return (int) Math.clamp(elapsed * 100 / Math.max(1, config.dayMillis()), 0, 100);
    }

    public int day() {
        return day;
    }

    /** Closes the books and opens tomorrow. Public so /rpgcore can force one. */
    public void advanceDay() {
        day++;
        if (plugin.rpgConfig().marketEnabled()) {
            market.dayTick(day);
        }
        if (plugin.rpgConfig().bankEnabled()) {
            bank.dailyClose(day);
        }
        measure();
        setPolicy();
        openMarketOperations();
        report();
        tradeValueToday = 0;
        save();
    }

    // -------------------------------------------------------------- measuring

    private void measure() {
        cpi = priceIndex();
        cpiHistory.addLast(cpi);
        while (cpiHistory.size() > Math.max(config.daysPerYear() * 2, 24)) {
            cpiHistory.removeFirst();
        }
        // Smoothed rather than taken raw. The raw reading is the one number
        // here with real noise in it - a year is only a few days long, so a
        // single day's wobble annualises into a figure that would have the
        // central bank raising and cutting on alternate days.
        double measured = annualInflation();
        inflation = cpiHistory.size() <= config.inflationMinDays()
                ? measured
                : inflation * (1 - config.inflationSmoothing())
                        + measured * config.inflationSmoothing();

        gdpToday = tradeValueToday;
        lifetimeTrade += tradeValueToday;
        gdpHistory.addLast((double) gdpToday);
        while (gdpHistory.size() > Math.max(config.daysPerYear() * 2, 24)) {
            gdpHistory.removeFirst();
        }
        // Potential output is just the trend: an exponential average of what
        // this server actually does. A fixed target would call a quiet server
        // permanently depressed and a busy one permanently overheating.
        potentialGdp = potentialGdp <= 0
                ? gdpToday
                : potentialGdp * (1 - config.gdpSmoothing()) + gdpToday * config.gdpSmoothing();

        // The gap is measured from a few days of trade, not one.
        //
        // A server this size does all of a day's trading in one evening
        // session, so a single day is either "somebody logged on" or zero.
        // Fed straight into the rate rule that read as a boom and a slump on
        // alternate days, and the policy rate flipped between its floor and
        // 6.5% every twenty minutes. Three days of trade, clamped and then
        // smoothed, is a number that means something.
        double recent = recentGdp(3);
        double raw = potentialGdp <= 0 ? 0 : (recent - potentialGdp) / potentialGdp * 100.0;
        raw = Math.clamp(raw, -MAX_OUTPUT_GAP, MAX_OUTPUT_GAP);
        outputGap = outputGap * (1 - config.gdpSmoothing()) + raw * config.gdpSmoothing();

        measureMoney();
        applyQuantityTheory();
    }

    /**
     * MV = PQ, applied once a day.
     *
     * The server mints gold every time somebody kills a mob, levels up or
     * takes out a loan, and destroys it every time a fee or a tax is paid.
     * If none of that reached prices, the money supply would be a number on a
     * screen: a server could triple its gold and a loaf of bread would still
     * cost seven.
     *
     * So the gap between how fast money grew and how fast output grew is
     * pushed into the market's price level. Only part of it - the rest is
     * taken up by velocity, which is what the pass-through setting means -
     * and the daily step is capped, because one player cashing in a fortune
     * should move prices, not detonate them.
     *
     * This is also what closes the loop on monetary policy. Dear credit means
     * fewer loans, fewer loans mean less money created, and less money means
     * the pressure here fades. That is the whole transmission mechanism, and
     * it runs on measured numbers rather than on a script.
     */
    private void applyQuantityTheory() {
        if (previousM2 <= 0) {
            previousM2 = m2;
            return;
        }
        moneyGrowth = (m2 - previousM2) / (double) previousM2;
        previousM2 = m2;

        double outputGrowth = 0;
        if (gdpHistory.size() >= 2) {
            double previous = recentGdpAt(1);
            if (previous > 0) {
                outputGrowth = Math.clamp((gdpToday - previous) / previous, -0.5, 0.5);
            }
        }
        double pressure = Math.clamp((moneyGrowth - outputGrowth) * config.moneyPassThrough(),
                -config.maxPriceDrift(), config.maxPriceDrift());
        market.driftPriceLevel(pressure);
    }

    /** The day-before-last's output, for a growth reading. */
    private double recentGdpAt(int back) {
        List<Double> points = new ArrayList<>(gdpHistory);
        int index = points.size() - 1 - back;
        return index < 0 ? 0 : points.get(index);
    }

    /** The consumer price index: a fixed basket, priced today against day one. */
    public double priceIndex() {
        double now = 0;
        double base = 0;
        for (MarketItem item : market.items().values()) {
            if (item.basket() <= 0) {
                continue;
            }
            now += item.basket() * item.mid();
            base += item.basket() * item.basePrice();
        }
        return base <= 0 ? 100 : now / base * 100.0;
    }

    /**
     * Year-on-year inflation, annualised from however much history exists.
     *
     * A young economy has fewer days than a year, and reporting "0%" until it
     * has a full one would hide exactly the runaway that the first days
     * produce. So the oldest point available is used and the growth is scaled
     * up to a year.
     */
    private double annualInflation() {
        // Below the minimum span there is no reading worth having: one day of
        // a random walk annualises a 2% wobble into 27% a year, and the rate
        // rule would chase it.
        if (cpiHistory.size() <= config.inflationMinDays()) {
            return 0;
        }
        List<Double> points = new ArrayList<>(cpiHistory);
        int span = Math.min(points.size() - 1, config.daysPerYear());
        double then = points.get(points.size() - 1 - span);
        double nowValue = points.get(points.size() - 1);
        if (then <= 0) {
            return 0;
        }
        double growth = nowValue / then;
        double annualised = Math.pow(growth, config.daysPerYear() / (double) span) - 1.0;
        // Bounded: one item spiking on day two can otherwise annualise into a
        // number with no meaning, and the policy rule would chase it.
        return Math.clamp(annualised * 100.0, -95.0, 500.0);
    }

    /**
     * Counts the money.
     *
     * M0 is read straight off the scoreboard mirror, which holds every player
     * who has ever joined - so it is the whole circulating stock, not just
     * whoever is online. The market's purse and the bank's vault are excluded:
     * gold sitting in an institution is not in circulation, which is the same
     * treatment a central bank's reserves get.
     */
    private void measureMoney() {
        Map<String, BankAccount> byName = new HashMap<>();
        for (BankAccount account : bank.accounts().values()) {
            byName.put(account.name().toLowerCase(Locale.ROOT), account);
        }

        // Read off the mirror directly rather than through the leaderboard.
        // That one caches for a few seconds, which is right for a command a
        // player can spam and wrong for this: the money supply is the number
        // everything else here reacts to, and a stale reading meant gold
        // minted since the last cache fill simply did not exist as far as
        // prices were concerned.
        RpgScoreboard board = plugin.scoreboard();
        Objective initialised = board.objectiveByName(RpgScoreboard.INITIALISED);
        Objective goldObjective = board.objectiveByName(RpgScoreboard.GOLD);

        long wallet = 0;
        int counted = 0;
        List<Double> netWorth = new ArrayList<>();
        for (String entry : board.entries()) {
            // The init marker is what tells RPGCore's own entries apart from
            // whatever else shares the main scoreboard.
            if (board.read(entry, initialised) != 1) {
                continue;
            }
            counted++;
            long gold = board.read(entry, goldObjective);
            wallet += gold;
            BankAccount account = byName.get(entry.toLowerCase(Locale.ROOT));
            long worth = gold;
            if (account != null) {
                worth += account.totalDeposits() - account.totalDebt();
            }
            // A player deeper in debt than they own counts as zero rather than
            // as negative: the Gini coefficient is not defined for negative
            // holdings, and clamping is the standard treatment.
            netWorth.add((double) Math.max(0, worth));
        }
        measuredWallets = counted;
        m0 = wallet;
        m1 = m0 + bank.demandDeposits();
        m2 = m1 + bank.termDeposits();
        moneyMultiplier = m0 <= 0 ? 0 : m2 / (double) m0;
        velocity = m2 <= 0 ? 0 : (double) gdpToday * config.daysPerYear() / m2;
        gini = giniOf(netWorth);
    }

    /** Mean output over the last few days, for the gap. */
    private double recentGdp(int days) {
        if (gdpHistory.isEmpty()) {
            return 0;
        }
        List<Double> points = new ArrayList<>(gdpHistory);
        int from = Math.max(0, points.size() - days);
        double sum = 0;
        for (int i = from; i < points.size(); i++) {
            sum += points.get(i);
        }
        return sum / (points.size() - from);
    }

    /** Standard Gini over a list of holdings. 0 is equality, 1 is one owner. */
    private static double giniOf(List<Double> values) {
        if (values.size() < 2) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double sum = 0;
        double weighted = 0;
        for (int i = 0; i < sorted.size(); i++) {
            sum += sorted.get(i);
            weighted += (i + 1) * sorted.get(i);
        }
        if (sum <= 0) {
            return 0;
        }
        int n = sorted.size();
        return Math.clamp(2.0 * weighted / (n * sum) - (n + 1.0) / n, 0.0, 1.0);
    }

    // --------------------------------------------------------- monetary policy

    /**
     * The Taylor rule, with a speed limit.
     *
     * The limit is not decoration: without it a single noisy day of prices
     * moves the rate twenty points, every outstanding loan reprices around it,
     * and borrowing becomes a lottery on when you happened to sign.
     */
    private void setPolicy() {
        double target = config.neutralRatePercent()
                + inflation
                + config.taylorInflationWeight() * (inflation - config.inflationTargetPercent())
                + config.taylorOutputWeight() * outputGap;
        target = Math.clamp(target, config.rateFloorPercent(), config.rateCeilingPercent());

        double move = Math.clamp(target - policyRate,
                -config.rateMoveMaxPercent(), config.rateMoveMaxPercent());
        double previous = policyRate;
        policyRate = Math.clamp(policyRate + move,
                config.rateFloorPercent(), config.rateCeilingPercent());

        // A quarter point is the smallest move worth telling the server
        // about; anything below it is the rule breathing, not a decision.
        if (Math.abs(policyRate - previous) < 0.25) {
            policyRate = previous;
            return;
        }
        boolean up = policyRate > previous;
        String reason = up
                ? (inflation > config.inflationTargetPercent() ? "물가 상승 억제" : "경기 과열 진정")
                : (inflation < config.inflationTargetPercent() ? "물가 하락 방어" : "경기 부양");
        policyLog.add(0, new PolicyNote(day, policyRate, inflation, reason));
        while (policyLog.size() > POLICY_LOG) {
            policyLog.remove(policyLog.size() - 1);
        }
        if (config.broadcastPolicyChange()) {
            plugin.getServer().broadcastMessage(ChatColor.AQUA + "[중앙은행] " + ChatColor.WHITE
                    + "정책금리를 " + BankService.percent(previous) + " 에서 "
                    + BankService.percent(policyRate) + " 로 "
                    + (up ? "올렸습니다" : "내렸습니다") + ". " + ChatColor.GRAY + "(" + reason
                    + " · 물가상승률 " + BankService.percent(inflation) + ")");
        }
    }

    /**
     * Price stabilisation through the national reserve.
     *
     * Only goods move, never gold. When prices run hot the state releases what
     * it stockpiled, which is supply and therefore cheaper prices; when they
     * fall too far it buys the glut back off the shelf. The reserve is finite,
     * so the dashboard can and does say "비축물자 고갈" rather than pretending
     * the state can hold a price forever. It cannot.
     */
    private void openMarketOperations() {
        if (!config.openMarketOperations() || !plugin.rpgConfig().marketEnabled()) {
            return;
        }
        double miss = inflation - config.inflationTargetPercent();
        if (Math.abs(miss) < config.omoBandPercent()) {
            return;
        }
        // How far outside the band, as a share of the band itself, capped at
        // one: a wild reading should not sell the entire reserve in a day.
        double intensity = Math.min(1.0, (Math.abs(miss) - config.omoBandPercent())
                / Math.max(1.0, config.omoBandPercent()));
        boolean release = miss > 0;
        double moved = 0;
        int touched = 0;
        for (MarketItem item : market.items().values()) {
            if (item.basket() <= 0) {
                continue;
            }
            double size = item.baseStock() * config.omoMaxSharePercent() / 100.0 * intensity;
            double done = market.intervene(item, release ? size : -size);
            if (Math.abs(done) > 0.01) {
                moved += Math.abs(done);
                touched++;
            }
        }
        if (touched == 0) {
            return;
        }
        plugin.getServer().broadcastMessage(ChatColor.AQUA + "[중앙은행] " + ChatColor.WHITE
                + (release ? "비축물자를 풀었습니다" : "과잉 물량을 사들였습니다")
                + ChatColor.GRAY + " - " + touched + "개 품목, 약 "
                + String.format(Locale.ROOT, "%,.0f", moved) + "개. ("
                + (release ? "물가를 낮추기 위해" : "값을 떠받치기 위해") + ")");
        market.save();
    }

    private void report() {
        if (!config.dailyReport()) {
            return;
        }
        plugin.getServer().broadcastMessage(ChatColor.GOLD + "[경제] " + ChatColor.WHITE + day
                + "일차 " + ChatColor.GRAY + "· 물가 " + String.format(Locale.ROOT, "%.1f", cpi)
                + " (" + signed(inflation) + ") · 정책금리 " + BankService.percent(policyRate)
                + " · 통화량 " + String.format(Locale.ROOT, "%,d", m2)
                + " · " + cyclePhase());
    }

    // -------------------------------------------------------------- recording

    /** The value of one trade, for the day's output. */
    public void recordTrade(long value) {
        if (value > 0) {
            tradeValueToday += value;
        }
    }

    /** Gold created out of nothing, wherever it happened. */
    public void recordPrinting(long amount) {
        if (amount > 0) {
            printedTotal += amount;
            save();
        }
    }

    /** A fee taken out of circulation rather than moved between players. */
    public void collectFee(long amount) {
        if (amount > 0) {
            feeTake += amount;
            save();
        }
    }

    // --------------------------------------------------------------- readings

    public double policyRate() {
        return policyRate;
    }

    public double cpi() {
        return cpi;
    }

    public double inflation() {
        return inflation;
    }

    /** The policy rate minus inflation: what credit really costs. */
    public double realRate() {
        return policyRate - inflation;
    }

    public long m0() {
        return m0;
    }

    public long m1() {
        return m1;
    }

    public long m2() {
        return m2;
    }

    public long gdp() {
        return gdpToday;
    }

    public long gdpToday() {
        return tradeValueToday;
    }

    public long lifetimeTrade() {
        return lifetimeTrade;
    }

    public double velocity() {
        return velocity;
    }

    public double moneyMultiplier() {
        return moneyMultiplier;
    }

    /** How fast M2 grew on the last close, as a fraction. */
    public double moneyGrowth() {
        return moneyGrowth;
    }

    public double gini() {
        return gini;
    }

    public double outputGap() {
        return outputGap;
    }

    public long printedTotal() {
        return printedTotal;
    }

    public long feeTake() {
        return feeTake;
    }

    public int measuredWallets() {
        return measuredWallets;
    }

    public List<PolicyNote> policyLog() {
        return List.copyOf(policyLog);
    }

    public List<Double> cpiHistory() {
        return new ArrayList<>(cpiHistory);
    }

    /** Where the cycle is, read off the output gap. */
    public String cyclePhase() {
        if (outputGap > 15) {
            return "호황";
        }
        // At trend is normal, not a slump: the gap is measured against this
        // server's own average, so zero means "a typical day here".
        if (outputGap > -2) {
            return "확장";
        }
        if (outputGap > -20) {
            return "둔화";
        }
        return "침체";
    }

    public ChatColor cycleColour() {
        return switch (cyclePhase()) {
            case "호황" -> ChatColor.RED;
            case "확장" -> ChatColor.GREEN;
            case "둔화" -> ChatColor.YELLOW;
            default -> ChatColor.BLUE;
        };
    }

    public static String signed(double percent) {
        return String.format(Locale.ROOT, "%+.2f%%", percent);
    }

    /**
     * Re-counts the money and the inequality now.
     *
     * Prices and output are flows - they belong to the day that has closed -
     * but the money supply is a stock, and a dashboard that told a player
     * their own gold was not in M0 yet would simply be wrong. The read is a
     * cached leaderboard pass, so looking twice costs nothing.
     */
    public void refreshMoney() {
        // Throttled: the count walks every entry the scoreboard has ever
        // held, and /economy is a command players will lean on.
        long now = System.currentTimeMillis();
        if (now - lastMoneyReadMs < 2000L) {
            return;
        }
        lastMoneyReadMs = now;
        measureMoney();
    }

    /** The whole dashboard as chat lines, for /economy report and consoles. */
    public void print(CommandSender to) {
        refreshMoney();
        to.sendMessage(ChatColor.GOLD + "===== 경제 지표 (" + day + "일차, 하루 "
                + config.dayMinutes() + "분) =====");
        to.sendMessage(ChatColor.WHITE + " 물가지수 " + ChatColor.YELLOW
                + String.format(Locale.ROOT, "%.1f", cpi) + ChatColor.GRAY
                + " (기준 100) · 물가상승률 " + ChatColor.YELLOW + signed(inflation)
                + ChatColor.GRAY + " / 목표 " + BankService.percent(config.inflationTargetPercent()));
        to.sendMessage(ChatColor.WHITE + " 정책금리 " + ChatColor.YELLOW
                + BankService.percent(policyRate) + ChatColor.GRAY + " · 실질금리 "
                + signed(realRate()) + " · 예금 " + BankService.percent(bank.depositRate())
                + " / 대출 " + BankService.percent(policyRate + config.loanMarginPercent()) + "~");
        to.sendMessage(ChatColor.WHITE + " 통화량 " + ChatColor.YELLOW
                + String.format(Locale.ROOT, "M0 %,d · M1 %,d · M2 %,d", m0, m1, m2)
                + ChatColor.GRAY + " · 통화승수 "
                + String.format(Locale.ROOT, "%.2f", moneyMultiplier)
                + " · 증가율 " + signed(moneyGrowth * 100)
                + " · 물가수준 " + String.format(Locale.ROOT, "%.3f", market.priceLevel()));
        to.sendMessage(ChatColor.WHITE + " 생산(GDP) " + ChatColor.YELLOW
                + String.format(Locale.ROOT, "%,d", gdpToday) + ChatColor.GRAY
                + "/일 · 산출갭 " + signed(outputGap) + " · 경기 " + cycleColour() + cyclePhase());
        to.sendMessage(ChatColor.WHITE + " 화폐유통속도 " + ChatColor.YELLOW
                + String.format(Locale.ROOT, "%.2f", velocity) + ChatColor.GRAY
                + " · 지니계수 " + String.format(Locale.ROOT, "%.3f", gini)
                + " (" + measuredWallets + "명 기준)");
        to.sendMessage(ChatColor.WHITE + " 시장 " + ChatColor.YELLOW + market.size() + "품목"
                + ChatColor.GRAY + " · 시가총액 "
                + String.format(Locale.ROOT, "%,.0f", market.totalMarketCap())
                + " · 국고 " + String.format(Locale.ROOT, "%,d", market.treasury())
                + " · 비축 " + String.format(Locale.ROOT, "%.0f%%", market.reservePercent()));
        to.sendMessage(ChatColor.WHITE + " 은행 " + ChatColor.YELLOW
                + String.format(Locale.ROOT, "예금 %,d · 대출 %,d", bank.totalDeposits(), bank.totalLoans())
                + ChatColor.GRAY + " · 예대율 " + String.format(Locale.ROOT, "%.0f%%", bank.loanToDepositPercent())
                + " · 연체율 " + String.format(Locale.ROOT, "%.1f%%", bank.delinquencyPercent())
                + " · 자본 " + String.format(Locale.ROOT, "%,d", bank.equity()));
        to.sendMessage(ChatColor.WHITE + " 발권 누적 " + ChatColor.YELLOW
                + String.format(Locale.ROOT, "%,d", printedTotal) + ChatColor.GRAY
                + " · 세금·수수료 누적 " + String.format(Locale.ROOT, "%,d", market.taxTake() + feeTake));
        if (!policyLog.isEmpty()) {
            PolicyNote last = policyLog.get(0);
            to.sendMessage(ChatColor.GRAY + " 최근 결정: " + last.day() + "일차 "
                    + BankService.percent(last.rate()) + " (" + last.reason() + ")");
        }
    }

    // ------------------------------------------------------------ persistence

    public void load() {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        day = Math.max(1, yaml.getInt("day", 1));
        policyRate = Math.clamp(yaml.getDouble("policy-rate", policyRate),
                config.rateFloorPercent(), config.rateCeilingPercent());
        cpi = yaml.getDouble("cpi", 100);
        inflation = yaml.getDouble("inflation", 0);
        potentialGdp = yaml.getDouble("potential-gdp", 0);
        outputGap = yaml.getDouble("output-gap", 0);
        printedTotal = yaml.getLong("printed", 0);
        feeTake = yaml.getLong("fees", 0);
        lifetimeTrade = yaml.getLong("lifetime-trade", 0);
        previousM2 = yaml.getLong("previous-m2", 0);
        for (Object point : yaml.getList("cpi-history", List.of())) {
            if (point instanceof Number number) {
                cpiHistory.addLast(number.doubleValue());
            }
        }
        for (Object point : yaml.getList("gdp-history", List.of())) {
            if (point instanceof Number number) {
                gdpHistory.addLast(number.doubleValue());
            }
        }
        for (Map<?, ?> row : yaml.getMapList("policy-log")) {
            policyLog.add(new PolicyNote(
                    row.get("day") instanceof Number n ? n.intValue() : 0,
                    row.get("rate") instanceof Number n ? n.doubleValue() : 0,
                    row.get("inflation") instanceof Number n ? n.doubleValue() : 0,
                    row.get("reason") == null ? "" : String.valueOf(row.get("reason"))));
        }
        // The clock restarts rather than resuming: whatever fraction of a day
        // was left when the server went down is a gift, and the alternative -
        // fast-forwarding the days that passed offline - charges interest for
        // a weekend nobody played.
        dayStartedAtMs = System.currentTimeMillis();
        plugin.getLogger().info("Economy restored: day " + day + ", policy rate "
                + BankService.percent(policyRate) + ".");
    }

    public void save() {
        writer.markDirty();
    }

    public void saveNow() {
        writer.flushNow();
    }

    public Runnable pendingWrite() {
        return writer.pendingWrite();
    }

    private YamlConfiguration build() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "RPGCore - 중앙은행과 지표 상태입니다. 플러그인이 계속 다시 씁니다.",
                "정책 설정은 economy.yml 의 macro: 항목에 있습니다."));
        yaml.set("day", day);
        yaml.set("policy-rate", round(policyRate));
        yaml.set("cpi", round(cpi));
        yaml.set("inflation", round(inflation));
        yaml.set("potential-gdp", round(potentialGdp));
        yaml.set("output-gap", round(outputGap));
        yaml.set("printed", printedTotal);
        yaml.set("fees", feeTake);
        yaml.set("lifetime-trade", lifetimeTrade);
        yaml.set("previous-m2", previousM2);
        List<Double> cpiPoints = new ArrayList<>();
        for (double point : cpiHistory) {
            cpiPoints.add(round(point));
        }
        yaml.set("cpi-history", cpiPoints);
        List<Double> gdpPoints = new ArrayList<>();
        for (double point : gdpHistory) {
            gdpPoints.add(round(point));
        }
        yaml.set("gdp-history", gdpPoints);
        List<Map<String, Object>> log = new ArrayList<>();
        for (PolicyNote note : policyLog) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", note.day());
            row.put("rate", round(note.rate()));
            row.put("inflation", round(note.inflation()));
            row.put("reason", note.reason());
            log.add(row);
        }
        yaml.set("policy-log", log);
        return yaml;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
