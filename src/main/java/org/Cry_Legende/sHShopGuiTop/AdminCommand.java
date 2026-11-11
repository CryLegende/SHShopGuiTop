package org.Cry_Legende.sHShopGuiTop;

import net.brcdev.shopgui.ShopGuiPlusApi;
import net.brcdev.shopgui.shop.item.ShopItem;
import org.Cry_Legende.sHShopGuiTop.SHShopGuiTop.SalesManager;
import org.Cry_Legende.sHShopGuiTop.SHShopGuiTop.SalesStatsEntry;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /shshopguitop (record, top, reload) et l'ancienne commande /recordsale, qui fait la même chose que
 * /shshopguitop record.
 */
final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("record", "top", "reload");

    private final SHShopGuiTop plugin;

    AdminCommand(SHShopGuiTop plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("recordsale")) {
            if (can(sender, "record")) {
                record(sender, args);
            } else {
                plugin.send(sender, "no-permission");
            }
            return true;
        }
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            plugin.send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!allowed.contains(sub)) {
            plugin.send(sender, "usage", "commands", String.join("|", allowed));
            return true;
        }
        switch (sub) {
            case "record" -> record(sender, Arrays.copyOfRange(args, 1, args.length));
            case "top" -> top(sender);
            default -> plugin.reload(sender);
        }
        return true;
    }

    private static boolean can(CommandSender sender, String sub) {
        return sender.hasPermission("shshopguitop.admin." + sub);
    }

    /** Sous-commandes que l'expéditeur peut utiliser. */
    private static List<String> allowed(CommandSender sender) {
        List<String> allowed = new ArrayList<>();
        for (String sub : SUBS) {
            if (can(sender, sub)) {
                allowed.add(sub);
            }
        }
        return allowed;
    }

    /**
     * Pour un joueur avec un seul argument : record &lt;quantité&gt;, sur l'objet de shop tenu en main (prix de vente
     * lu dans ShopGUI+). Pour tout expéditeur avec trois arguments : record &lt;article&gt; &lt;quantité&gt; &lt;revenu&gt;.
     */
    private void record(CommandSender sender, String[] args) {
        if (args.length == 3) {
            String key = args[0].toLowerCase(Locale.ROOT);
            int amount;
            double revenue;
            try {
                amount = Integer.parseInt(args[1]);
                revenue = Double.parseDouble(args[2]);
            } catch (NumberFormatException e) {
                plugin.send(sender, "record-number-invalid");
                return;
            }
            SalesManager.getInstance().recordSale(key, key, amount, revenue);
            plugin.send(sender, "record-done-item", "item", key);
            return;
        }
        if (!(sender instanceof Player player) || args.length != 1) {
            plugin.send(sender, "record-usage");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            plugin.send(player, "record-hand-empty");
            return;
        }
        ShopItem shopItem = ShopGuiPlusApi.getItemStackShopItem(player, item);
        if (shopItem == null) {
            plugin.send(player, "record-not-shop");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            plugin.send(player, "record-amount-invalid");
            return;
        }
        double price = ShopGuiPlusApi.getItemStackPriceSell(player, item);
        if (price < 0) {
            plugin.send(player, "record-price-error");
            return;
        }
        double revenue = price * amount;
        String internalName = internalName(shopItem);
        String minecraftId = "minecraft:" + item.getType().toString().toLowerCase(Locale.ROOT);
        SalesManager.getInstance().recordSale(internalName, minecraftId, amount, revenue);
        plugin.send(player, "record-done-hand", "amount", String.valueOf(amount), "item", internalName,
            "revenue", String.valueOf(revenue));
    }

    /** Les dix premiers articles avec leurs quantités et revenus exacts. */
    private void top(CommandSender sender) {
        List<SalesStatsEntry> top = SalesManager.getInstance().getTopSales();
        if (top.isEmpty()) {
            plugin.send(sender, "top-empty");
            return;
        }
        plugin.send(sender, "top-header", "count", String.valueOf(top.size()));
        int rank = 1;
        for (SalesStatsEntry entry : top) {
            plugin.send(sender, "top-line", "rank", String.valueOf(rank++),
                "item", SHShopGuiTop.getMinecraftTranslatedName(entry.getMinecraftId()),
                "id", entry.getInternalName(),
                "sold", String.valueOf(entry.getSold()),
                "revenue", String.format(Locale.ROOT, "%.2f", entry.getRevenue()));
        }
    }

    private static String internalName(ShopItem shopItem) {
        String id = shopItem.getId();
        if (id != null && !id.trim().isEmpty()) {
            return id.toLowerCase(Locale.ROOT);
        }
        return shopItem.toString().toLowerCase(Locale.ROOT);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("shshopguitop") && args.length == 1) {
            StringUtil.copyPartialMatches(args[0], allowed(sender), completions);
        }
        return completions;
    }
}
