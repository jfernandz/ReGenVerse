package com.regenverse.command;

import com.mojang.brigadier.Command;
import com.regenverse.core.ReGenVerseManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class ReGenVerseCommands {
    private ReGenVerseCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
            Commands.literal("regenverse")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("status")
                    .executes(ctx -> {
                        String status = ReGenVerseManager.statusLine(ctx.getSource().getServer());
                        ctx.getSource().sendSuccess(() -> Component.literal(status), false);
                        return Command.SINGLE_SUCCESS;
                    }))
                .then(Commands.literal("reload")
                    .executes(ctx -> {
                        ReGenVerseManager.reloadConfig(ctx.getSource().getServer());
                        ctx.getSource().sendSuccess(() -> Component.literal("ReGenVerse config reloaded."), true);
                        return Command.SINGLE_SUCCESS;
                    }))
                .then(Commands.literal("here")
                    .executes(ctx -> {
                        if (ctx.getSource().getPlayer() == null) {
                            ctx.getSource().sendFailure(Component.literal("This command can only be used by a player."));
                            return 0;
                        }
                        String chunkStatus = ReGenVerseManager.playerChunkStatus(ctx.getSource().getPlayer());
                        ctx.getSource().sendSuccess(() -> Component.literal(chunkStatus), false);
                        return Command.SINGLE_SUCCESS;
                    }))
                .then(Commands.literal("cycle")
                    .executes(ctx -> {
                        ReGenVerseManager.forceCycle(ctx.getSource().getServer());
                        ctx.getSource().sendSuccess(() -> Component.literal("ReGenVerse cycle triggered."), true);
                        return Command.SINGLE_SUCCESS;
                    }))
        ));
    }
}
