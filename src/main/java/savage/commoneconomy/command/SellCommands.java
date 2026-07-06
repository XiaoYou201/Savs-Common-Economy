package savage.commoneconomy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import savage.commoneconomy.EconomyManager;
import savage.commoneconomy.util.PermissionsHelper;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Commands for selling/buying items and checking their worth.
 */
public class SellCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (!EconomyManager.getInstance().isSellEnabled()) return;

        // /worth
        dispatcher.register(Commands.literal("worth")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.worth", true))
                .executes(SellCommands::checkHandWorth)
                .then(Commands.literal("all")
                        .executes(SellCommands::checkAllWorth))
                .then(Commands.literal("list")
                        .executes(SellCommands::listWorth))
                .then(Commands.argument("item", StringArgumentType.string())
                        .executes(SellCommands::checkItemWorth)));

        // /sell
        dispatcher.register(Commands.literal("sell")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.sell", true))
                .executes(SellCommands::sellHand)
                .then(Commands.literal("all")
                        .executes(SellCommands::sellAll)));
                        
        // /buy <item> [amount]
        dispatcher.register(Commands.literal("buy")
                .requires(source -> PermissionsHelper.check(source, "savscommoneconomy.command.buy", true))
                .then(Commands.argument("item", StringArgumentType.string())
                        .executes(ctx -> buyItem(ctx, 1))
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1, 6400))
                                .executes(ctx -> buyItem(ctx, IntegerArgumentType.getInteger(ctx, "amount"))))));
    }

    private static int checkHandWorth(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();

        if (stack.isEmpty()) {
            context.getSource().sendFailure(Component.literal("你手上没有拿任何物品。"));
            return 0;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(Component.literal("该物品无法出售。"));
            return 0;
        }

        BigDecimal stackValue = price.multiply(BigDecimal.valueOf(stack.getCount()));
        context.getSource().sendSuccess(() -> Component.literal(stack.getCount() + "个 " + itemId + " 的价值:" + EconomyManager.getInstance().format(stackValue) + "(每个 " + EconomyManager.getInstance().format(price) + ")"), false);
        return 1;
    }

    private static int checkAllWorth(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack handStack = player.getMainHandItem();

        if (handStack.isEmpty()) {
            context.getSource().sendFailure(Component.literal("你手上没有拿任何物品。"));
            return 0;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(handStack.getItem()).toString();
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(Component.literal("该物品无法出售。"));
            return 0;
        }

        int totalCount = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == handStack.getItem()) {
                totalCount += stack.getCount();
            }
        }

        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(totalCount));
        int finalTotalCount = totalCount;
        context.getSource().sendSuccess(() -> Component.literal("背包中全部 " + finalTotalCount + "个 " + itemId + " 的价值:" + EconomyManager.getInstance().format(totalValue)), false);
        return 1;
    }

    private static int listWorth(CommandContext<CommandSourceStack> context) {
        Map<String, BigDecimal> sellPrices = EconomyManager.getInstance().getAllSellPrices();
        Map<String, BigDecimal> buyPrices = EconomyManager.getInstance().getAllBuyPrices();
        
        java.util.Set<String> allItems = new java.util.TreeSet<>();
        allItems.addAll(sellPrices.keySet());
        allItems.addAll(buyPrices.keySet());

        if (allItems.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("尚未配置任何物品的价格。"), false);
            return 1;
        }

        context.getSource().sendSuccess(() -> Component.literal("--- 物品价格表 ---").withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD), false);
        for (String item : allItems) {
            BigDecimal sell = sellPrices.getOrDefault(item, BigDecimal.ZERO);
            BigDecimal buy = buyPrices.getOrDefault(item, BigDecimal.ZERO);
            
            String sellStr = sell.compareTo(BigDecimal.ZERO) > 0 ? EconomyManager.getInstance().format(sell) : "N/A";
            String buyStr = buy.compareTo(BigDecimal.ZERO) > 0 ? EconomyManager.getInstance().format(buy) : "N/A";
            
            Component line = Component.literal("- ").withStyle(net.minecraft.ChatFormatting.DARK_GRAY)
                .append(Component.literal(item).withStyle(net.minecraft.ChatFormatting.AQUA))
                .append(Component.literal(" | ").withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
                .append(Component.literal("买入: ").withStyle(net.minecraft.ChatFormatting.YELLOW))
                .append(Component.literal(buyStr).withStyle(buy.compareTo(BigDecimal.ZERO) > 0 ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.DARK_RED))
                .append(Component.literal(" | ").withStyle(net.minecraft.ChatFormatting.DARK_GRAY))
                .append(Component.literal("卖出: ").withStyle(net.minecraft.ChatFormatting.YELLOW))
                .append(Component.literal(sellStr).withStyle(sell.compareTo(BigDecimal.ZERO) > 0 ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.DARK_RED));
            
            context.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int checkItemWorth(CommandContext<CommandSourceStack> context) {
        String itemId = StringArgumentType.getString(context, "item");
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(Component.literal("物品「" + itemId + "」无法出售或不存在。"));
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal(itemId + " 的价格:每个 " + EconomyManager.getInstance().format(price)), false);
        return 1;
    }

    private static int sellHand(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack stack = player.getMainHandItem();

        if (stack.isEmpty()) {
            context.getSource().sendFailure(Component.literal("你手上没有拿任何物品。"));
            return 0;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(Component.literal("该物品无法出售。"));
            return 0;
        }

        int count = stack.getCount();
        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(count));

        var server1 = context.getSource().getServer();
        EconomyManager.getInstance().addBalance(player.getUUID(), totalValue).thenAccept(success -> {
            if (success) {
                // Must modify inventory on the main server thread
                server1.execute(() -> {
                    player.getInventory().removeItem(stack); // In 26.1 use removeItem or set to Empty
                    context.getSource().sendSuccess(() -> Component.literal("已出售 " + count + "个 " + itemId + ",获得 " + EconomyManager.getInstance().format(totalValue)), false);
                    savage.commoneconomy.util.TransactionLogger.log("SELL", player.getName().getString(), "Server", totalValue, "Sold " + count + "x " + itemId);
                });
            } else {
                context.getSource().sendFailure(Component.literal("交易失败。"));
            }
        });

        return 1;
    }

    private static int sellAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ItemStack handStack = player.getMainHandItem();

        if (handStack.isEmpty()) {
            context.getSource().sendFailure(Component.literal("你手上没有拿任何物品。"));
            return 0;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(handStack.getItem()).toString();
        BigDecimal price = EconomyManager.getInstance().getSellPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(Component.literal("该物品无法出售。"));
            return 0;
        }

        int totalCount = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == handStack.getItem()) {
                totalCount += stack.getCount();
            }
        }

        if (totalCount == 0) return 0;

        BigDecimal totalValue = price.multiply(BigDecimal.valueOf(totalCount));
        int finalCount = totalCount;
        var server2 = context.getSource().getServer();
        EconomyManager.getInstance().addBalance(player.getUUID(), totalValue).thenAccept(success -> {
            if (success) {
                // Must modify inventory on the main server thread
                server2.execute(() -> {
                    for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                        ItemStack stack = player.getInventory().getItem(i);
                        if (!stack.isEmpty() && stack.getItem() == handStack.getItem()) {
                            player.getInventory().setItem(i, ItemStack.EMPTY);
                        }
                    }
                    context.getSource().sendSuccess(() -> Component.literal("已出售全部 " + finalCount + "个 " + itemId + ",获得 " + EconomyManager.getInstance().format(totalValue)), false);
                    savage.commoneconomy.util.TransactionLogger.log("SELL_ALL", player.getName().getString(), "Server", totalValue, "Sold all " + finalCount + "x " + itemId);
                });
            } else {
                context.getSource().sendFailure(Component.literal("交易失败。"));
            }
        });

        return 1;
    }

    private static int buyItem(CommandContext<CommandSourceStack> context, int amount) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String itemInput = StringArgumentType.getString(context, "item");
        
        Item item = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.parse(itemInput))
                .orElse(Items.AIR);

        if (item == Items.AIR) {
             context.getSource().sendFailure(Component.literal("未找到物品:" + itemInput));
             return 0;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
        BigDecimal price = EconomyManager.getInstance().getBuyPrice(itemId);

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            context.getSource().sendFailure(Component.literal("该物品未上架出售。"));
            return 0;
        }

        BigDecimal totalCost = price.multiply(BigDecimal.valueOf(amount));

        var server3 = context.getSource().getServer();
        EconomyManager.getInstance().removeBalance(player.getUUID(), totalCost).thenAccept(success -> {
            if (success) {
                // Must modify inventory on the main server thread
                server3.execute(() -> {
                    ItemStack stack = new ItemStack(item, amount);
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                    }
                    context.getSource().sendSuccess(() -> Component.literal("已购买 " + amount + "个 " + itemId + ",花费 " + EconomyManager.getInstance().format(totalCost)), false);
                    savage.commoneconomy.util.TransactionLogger.log("BUY", "Server", player.getName().getString(), totalCost, "Bought " + amount + "x " + itemId);
                });
            } else {
                context.getSource().sendFailure(Component.literal("余额不足!需要:" + EconomyManager.getInstance().format(totalCost)));
            }
        });

        return 1;
    }
}
