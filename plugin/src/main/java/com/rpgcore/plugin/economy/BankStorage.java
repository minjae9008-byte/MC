package com.rpgcore.plugin.economy;

import com.rpgcore.plugin.RpgCorePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads and writes bank.yml.
 *
 * This file is somebody's savings. A line that will not parse is reported
 * loudly and the account is kept with whatever did parse, rather than dropped
 * - a dropped account is a player's deposit disappearing, and doing that
 * quietly is worse than doing it at all.
 */
final class BankStorage {

    static final String FILE = "bank.yml";

    private final RpgCorePlugin plugin;
    private final File file;

    BankStorage(RpgCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE);
    }

    void restore(BankService bank) {
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        bank.restoreLedger(
                yaml.getLong("vault.cash", 0),
                yaml.getLong("vault.central-bank-loans", 0),
                yaml.getLong("vault.recapitalised", 0),
                yaml.getLong("vault.written-off", 0));

        ConfigurationSection root = yaml.getConfigurationSection("accounts");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection node = root.getConfigurationSection(key);
            if (node == null) {
                continue;
            }
            UUID owner;
            try {
                owner = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe(FILE + ": account key '" + key
                        + "' is not a UUID - skipped. Its balance will need restoring by hand.");
                continue;
            }
            BankAccount account = new BankAccount(owner, node.getString("name", "?"),
                    node.getInt("credit", bank.config().creditStart()));
            account.checking(node.getLong("checking", 0));
            account.level(node.getInt("level", 1));
            account.restoreCounters(
                    node.getInt("loans-taken", 0),
                    node.getInt("loans-repaid", 0),
                    node.getInt("defaults", 0),
                    node.getLong("interest-earned", 0),
                    node.getLong("interest-paid", 0));
            account.lastTouchedDay(node.getInt("last-day", 0));

            for (Map<?, ?> row : node.getMapList("deposits")) {
                try {
                    account.deposits().add(new TimeDeposit(
                            uuid(row.get("id")),
                            longOf(row.get("principal")),
                            doubleOf(row.get("rate")),
                            intOf(row.get("opened")),
                            Math.max(1, intOf(row.get("term"))),
                            doubleOf(row.get("accrued"))));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().severe(FILE + ": a term deposit of " + account.name()
                            + " is malformed (" + e.getMessage() + ") - skipped.");
                }
            }
            for (Map<?, ?> row : node.getMapList("loans")) {
                try {
                    account.loans().add(new Loan(
                            uuid(row.get("id")),
                            longOf(row.get("principal")),
                            doubleOf(row.get("outstanding")),
                            doubleOf(row.get("rate")),
                            intOf(row.get("opened")),
                            intOf(row.get("due")),
                            longOf(row.get("repaid")),
                            intOf(row.get("overdue-days"))));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().severe(FILE + ": a loan of " + account.name()
                            + " is malformed (" + e.getMessage() + ") - skipped."
                            + " That is a debt the server has just forgiven.");
                }
            }
            bank.restoreAccount(account);
        }
    }

    YamlConfiguration build(BankService bank) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "RPGCore - 은행 원장입니다. 플러그인이 계속 다시 씁니다.",
                "금리와 한도 설정은 economy.yml 의 bank: 항목에 있습니다."));
        yaml.set("vault.cash", bank.cash());
        yaml.set("vault.central-bank-loans", bank.centralBankLoans());
        yaml.set("vault.recapitalised", bank.recapitalised());
        yaml.set("vault.written-off", bank.writtenOff());

        for (BankAccount account : bank.accounts().values()) {
            // An account that has never held anything is not worth a line;
            // one that has is kept even at zero, because its credit record is.
            if (account.empty() && account.creditScore() == bank.config().creditStart()) {
                continue;
            }
            String path = "accounts." + account.owner();
            yaml.set(path + ".name", account.name());
            yaml.set(path + ".checking", account.checking());
            yaml.set(path + ".credit", account.creditScore());
            yaml.set(path + ".level", account.level());
            yaml.set(path + ".loans-taken", account.loansTaken());
            yaml.set(path + ".loans-repaid", account.loansRepaid());
            yaml.set(path + ".defaults", account.defaults());
            yaml.set(path + ".interest-earned", account.interestEarned());
            yaml.set(path + ".interest-paid", account.interestPaid());
            yaml.set(path + ".last-day", account.lastTouchedDay());

            List<Map<String, Object>> deposits = new ArrayList<>();
            for (TimeDeposit deposit : account.deposits()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", deposit.id().toString());
                row.put("principal", deposit.principal());
                row.put("rate", round(deposit.annualRate()));
                row.put("opened", deposit.openedDay());
                row.put("term", deposit.termDays());
                row.put("accrued", round(deposit.accrued()));
                deposits.add(row);
            }
            yaml.set(path + ".deposits", deposits);

            List<Map<String, Object>> loans = new ArrayList<>();
            for (Loan loan : account.loans()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", loan.id().toString());
                row.put("principal", loan.principal());
                row.put("outstanding", round(loan.outstanding()));
                row.put("rate", round(loan.annualRate()));
                row.put("opened", loan.openedDay());
                row.put("due", loan.dueDay());
                row.put("repaid", loan.repaid());
                row.put("overdue-days", loan.overdueDays());
                loans.add(row);
            }
            yaml.set(path + ".loans", loans);
        }
        return yaml;
    }

    private static UUID uuid(Object value) {
        return value == null ? UUID.randomUUID() : UUID.fromString(String.valueOf(value));
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static double doubleOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
