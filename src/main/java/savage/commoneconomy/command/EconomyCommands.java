package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.model.AccountData;
import savage.commoneconomy.util.PermissionsHelper;
import savage.commoneconomy.util.TransactionLogger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Player-facing economy commands.
 */
public class EconomyCommands {

    private static final SuggestionProvider<CommandSourceStack> PLAYER_SUGGESTIONS = (context, builder) -> {
        return EconomyManager.getInstance().getAllPlayerNames().thenApply(names -> {
            List<String> suggestions = new ArrayList<>(names);
            suggestions.addAll(Arrays.asList(context.getSource().getServer().getPlayerNames()));
            return SharedSuggestionProvider.suggest(suggestions, builder);
        }).thenCompose(f -> f);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /bal and /balance
        var balCommand = Commands.literal("bal")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.bal", true))
                .executes(EconomyCommands::checkSelfBalance)
                .then(Commands.argument("target", StringArgumentType.string())
                        .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.bal.others", true))
                        .suggests(PLAYER_SUGGESTIONS)
                        .executes(EconomyCommands::checkOtherBalance));

        dispatcher.register(balCommand);
        dispatcher.register(Commands.literal("balance")
                .requires(balCommand.getRequirement())
                .executes(EconomyCommands::checkSelfBalance)
                .redirect(balCommand.build())); // Alias

        // /pay <target> <amount>
        dispatcher.register(Commands.literal("pay")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.pay", true))
                .then(Commands.argument("target", StringArgumentType.string())
                        .suggests(PLAYER_SUGGESTIONS)
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(EconomyCommands::pay))));

        dispatcher.register(Commands.literal("withdraw")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.withdraw", true))
                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                        .executes(EconomyCommands::withdraw)));

        // /baltop and /balancetop
        var baltopCommand = Commands.literal("baltop")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.baltop", true))
                .executes(EconomyCommands::balTop);

        dispatcher.register(baltopCommand);
        dispatcher.register(Commands.literal("balancetop")
                .requires(baltopCommand.getRequirement())
                .executes(EconomyCommands::balTop));
    }

    private static int checkSelfBalance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayer();
        EconomyManager.getInstance().getOrCreateAccount(player.getUUID(), player.getGameProfile().name())
            .thenAccept(account -> {
                BigDecimal balance = account.getBalance();
                context.getSource().sendSuccess(() -> Component.literal("你的余额:" + EconomyManager.getInstance().format(balance)), false);
            });
        return 1;
    }

    private static int checkOtherBalance(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String targetName = StringArgumentType.getString(context, "target");
        
        lookupUUID(context, targetName).thenAccept(targetUUID -> {
            if (targetUUID == null) {
                context.getSource().sendFailure(Component.literal("经济数据库中未找到该玩家。"));
                return;
            }

            EconomyManager.getInstance().getOrCreateAccount(targetUUID, null).thenAccept(account -> {
                BigDecimal balance = account.getBalance();
                context.getSource().sendSuccess(() -> Component.literal(account.getName() + " 的余额:" + EconomyManager.getInstance().format(balance)), false);
            });
        });
        
        return 1;
    }

    private static int pay(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer sender = context.getSource().getPlayer();
        String targetName = StringArgumentType.getString(context, "target");
        BigDecimal amount = BigDecimal.valueOf(DoubleArgumentType.getDouble(context, "amount"));

        lookupUUID(context, targetName).thenAccept(targetUUID -> {
            if (targetUUID == null) {
                context.getSource().sendFailure(Component.literal("经济数据库中未找到该玩家。"));
                return;
            }

            if (sender.getUUID().equals(targetUUID)) {
                context.getSource().sendFailure(Component.literal("你不能向自己转账。"));
                return;
            }

            EconomyManager.getInstance().removeBalance(sender.getUUID(), amount).thenAccept(success -> {
                if (success) {
                    EconomyManager.getInstance().addBalance(targetUUID, amount).thenAccept(addSuccess -> {
                        String formatted = EconomyManager.getInstance().format(amount);
                        context.getSource().sendSuccess(() -> Component.literal("已向 " + targetName + " 转账 " + formatted), false);
                        
                        ServerPlayer targetPlayer = context.getSource().getServer().getPlayerList().getPlayer(targetUUID);
                        if (targetPlayer != null) {
                            targetPlayer.sendSystemMessage(Component.literal("收到来自 " + sender.getName().getString() + " 的 " + formatted));
                        }
                        
                        TransactionLogger.log("PAY", sender.getName().getString(), targetName, amount, "Player Payment");
                    });
                } else {
                    context.getSource().sendFailure(Component.literal("余额不足。"));
                }
            });
        });
        
        return 1;
    }

    private static int withdraw(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer sender = context.getSource().getPlayerOrException();
        double amountDouble = DoubleArgumentType.getDouble(context, "amount");
        BigDecimal amount = BigDecimal.valueOf(amountDouble);

        var server = context.getSource().getServer();
        EconomyManager.getInstance().removeBalance(sender.getUUID(), amount).thenAccept(success -> {
            if (success) {
                // Must modify inventory on the main server thread
                server.execute(() -> {
                    ItemStack note = new ItemStack(Items.PAPER);
                    
                    CompoundTag tag = new CompoundTag();
                    tag.putBoolean("EconomyBankNote", true);
                    tag.putDouble("Value", amountDouble);
                    note.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
                    
                    note.set(DataComponents.CUSTOM_NAME, 
                        Component.literal("银行券:" + EconomyManager.getInstance().format(amount))
                            .withStyle(net.minecraft.ChatFormatting.GREEN));

                    if (!sender.getInventory().add(note)) {
                        sender.drop(note, false);
                    }
                    
                    context.getSource().sendSuccess(() -> Component.literal("已提取 " + EconomyManager.getInstance().format(amount) + " 为银行券。"), false);
                    TransactionLogger.log("WITHDRAW", sender.getName().getString(), "Bank Note", amount, "Withdrawal");
                });
            } else {
                context.getSource().sendFailure(Component.literal("余额不足。"));
            }
        });
        
        return 1;
    }

    private static int balTop(CommandContext<CommandSourceStack> context) {
        EconomyManager.getInstance().getTopAccounts(10).thenAccept(top -> {
            context.getSource().sendSuccess(() -> Component.literal("--- 余额排行榜 前10 ---"), false);
            for (int i = 0; i < top.size(); i++) {
                AccountData account = top.get(i);
                int rank = i + 1;
                context.getSource().sendSuccess(() -> Component.literal(rank + ". " + account.getName() + ":" + EconomyManager.getInstance().format(account.getBalance())), false);
            }
        });
        return 1;
    }

    private static CompletableFuture<UUID> lookupUUID(CommandContext<CommandSourceStack> context, String name) {
        ServerPlayer target = context.getSource().getServer().getPlayerList().getPlayerByName(name);
        if (target != null) return CompletableFuture.completedFuture(target.getUUID());
        return EconomyManager.getInstance().getUUIDFromName(name);
    }
}
