package fr.mattmunich.iceBoatRacing;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.util.StringUtil;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static fr.mattmunich.iceBoatRacing.Main.c;
import static fr.mattmunich.iceBoatRacing.Messages.getMessage;

public class IBRCommand implements BasicCommand {

    /**
     * Follows the lowercase {@code iceboatracing.*} scheme used everywhere else (the old
     * camelCase {@code iceBoatRacing.reload} was declared nowhere and matched nothing).
     */
    public static final String RELOAD_PERMISSION = "iceboatracing.command.reload";

    private final Main main;

    public IBRCommand(Main main) {
        this.main = main;
    }

    @Override
    public void execute(@NonNull CommandSourceStack source, String @NonNull [] args) {
        CommandSender s = source.getSender();
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!s.hasPermission(RELOAD_PERMISSION)) {
                s.sendMessage(getMessage("error.noPermission"));
                return;
            }
            s.sendMessage(getMessage("prefix").append(c(" §r§b§oReloading...")));
            Bukkit.getScheduler().runTask(main, () -> {
                main.reload();
                s.sendMessage(getMessage("prefix").append(c(" §r§bDone reloading!")));
            });
        } else if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            s.sendMessage(getMessage("ibr.info"));
        } else {
            s.sendMessage(getMessage("ibr.help"));
        }
    }

    @Override
    public @NonNull Collection<String> suggest(@NonNull CommandSourceStack commandSourceStack, String @NonNull [] args) {
        if (args.length <= 1) {
            return CommandUtil.filter(List.of("reload", "info"), args.length == 0 ? "" : args[0]);
        }
        return List.of();
    }

    @Override
    public boolean canUse(@NonNull CommandSender sender) {
        return BasicCommand.super.canUse(sender);
    }

    @Override
    public @Nullable String permission() {
        return "iceboatracing.command.plugin";
    }
}
