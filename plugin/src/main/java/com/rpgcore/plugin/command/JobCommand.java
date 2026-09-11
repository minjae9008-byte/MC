package com.rpgcore.plugin.command;

import com.rpgcore.plugin.RpgCorePlugin;
import com.rpgcore.plugin.job.RpgJob;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /job - opens the picker, or takes a job id directly. */
public final class JobCommand implements CommandExecutor, TabCompleter {

    private final RpgCorePlugin plugin;

    public JobCommand(RpgCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("이 명령어는 플레이어만 사용할 수 있습니다.");
            return true;
        }
        if (!plugin.rpgConfig().jobsEnabled() || plugin.jobs().isEmpty()) {
            player.sendMessage(ChatColor.RED + "[RPGCore] 이 서버에는 직업이 설정되어 있지 않습니다.");
            return true;
        }
        if (args.length == 0) {
            plugin.jobMenu().open(player);
            return true;
        }
        RpgJob job = plugin.jobs().byId(args[0]);
        if (job == null) {
            player.sendMessage(ChatColor.RED + "[RPGCore] 그런 직업이 없습니다: " + args[0]);
            return true;
        }
        plugin.jobs().choose(player, job);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (RpgJob job : plugin.jobs().jobs()) {
            if (job.id().startsWith(args[0].toLowerCase())) {
                out.add(job.id());
            }
        }
        return out;
    }
}
