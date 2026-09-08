package com.rpgcore.plugin;

import com.rpgcore.plugin.chat.ProximityChatListener;
import com.rpgcore.plugin.gui.StatsMenu;
import com.rpgcore.plugin.gui.StatsMenuListener;
import com.rpgcore.plugin.util.RpgScoreboard;
import com.rpgcore.plugin.voice.SimpleVoiceChatHook;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Companion plugin for the RPGCore datapack. The datapack (see /datapack)
 * remains the single source of truth for every stat, level and weight value -
 * this plugin only reads/writes the same vanilla scoreboard objectives and
 * adds presentation-layer features a pure datapack cannot provide on its own:
 * a real chest-GUI menu, and server-side proximity chat / voice chat glue.
 */
public final class RpgCorePlugin extends JavaPlugin {

    private RpgScoreboard scoreboard;
    private StatsMenu statsMenu;
    private SimpleVoiceChatHook voiceChatHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.scoreboard = new RpgScoreboard();
        this.statsMenu = new StatsMenu(this, scoreboard);

        getServer().getPluginManager().registerEvents(new StatsMenuListener(this, statsMenu), this);

        if (getConfig().getBoolean("proximity-chat.enabled", true)) {
            getServer().getPluginManager().registerEvents(new ProximityChatListener(this), this);
        }

        this.voiceChatHook = new SimpleVoiceChatHook(this);
        this.voiceChatHook.tryHook();

        getLogger().info("RPGCore plugin enabled.");
    }

    @Override
    public void onDisable() {
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
                statsMenu.open(player);
                return true;
            }
            case "rpgcorereload" -> {
                reloadConfig();
                sender.sendMessage("[RPGCore] 설정을 다시 불러왔습니다.");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    public RpgScoreboard scoreboard() {
        return scoreboard;
    }
}
