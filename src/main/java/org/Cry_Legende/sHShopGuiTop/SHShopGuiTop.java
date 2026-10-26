package org.Cry_Legende.sHShopGuiTop;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.brcdev.shopgui.ShopGuiPlusApi;
import net.brcdev.shopgui.event.ShopPreTransactionEvent;
import net.brcdev.shopgui.shop.ShopManager;
import net.brcdev.shopgui.shop.item.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.Locale;

/**
 * Plugin qui enregistre les ventes de tous les articles (de tous les joueurs) pour réaliser un top,
 * propose des placeholders via PlaceholderAPI, rend les statistiques persistantes
 * et écoute l’événement réel de vente (ShopPreTransactionEvent) émis par ShopGUI+.
 */
public class SHShopGuiTop extends JavaPlugin {

    private static SHShopGuiTop instance;
    private File salesFile;
    private YamlConfiguration salesConfig;
    // Ventes enregistrées depuis la dernière écriture de sales.yml
    private volatile boolean dirty;
    private final Object fileLock = new Object();

    /**
     * Retourne le nom localisé de l'item en français en se basant sur la traduction intégrée de Minecraft.
     * Si la méthode getI18NDisplayName() est disponible, on l'utilise.
     *
     * @param minecraftId L'ID Minecraft (ex : "minecraft:oak_log")
     * @return Le nom traduit en français, ou, en cas d'erreur, l'ID Minecraft.
     */
    public static String getMinecraftTranslatedName(String minecraftId) {
        String materialName = minecraftId.replace("minecraft:", "").toUpperCase(Locale.ROOT);
        Material mat = Material.matchMaterial(materialName);
        if (mat != null) {
            ItemStack tmp = new ItemStack(mat);
            try {
                // Utilise la méthode Paper/Purpur pour obtenir le nom localisé.
                return tmp.getI18NDisplayName();
            } catch (NoSuchMethodError e) {
                // En cas d'absence de la méthode, on retourne l'ID tel quel.
            }
        }
        return minecraftId;
    }

    /**
     * Formate un nombre en ajoutant des suffixes (k pour milliers, m pour millions).
     * Exemple : 128 -> "128", 1500 -> "1.5k", 2000000 -> "2.0m"
     */
    public static String formatNumber(double number) {
        if (number >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fm", number / 1_000_000);
        } else if (number >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", number / 1_000);
        } else {
            return String.valueOf((int) number);
        }
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        salesFile = new File(getDataFolder(), "sales.yml");
        if (!salesFile.exists()) {
            try {
                getDataFolder().mkdirs();
                salesFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Impossible de créer le fichier sales.yml !");
            }
        }
        salesConfig = YamlConfiguration.loadConfiguration(salesFile);
        SalesManager.getInstance().loadFromConfiguration(salesConfig);

        new SHShopGuiTopExpansion(this).register();
        getLogger().info("Extension PlaceholderAPI enregistrée avec succès.");

        getCommand("recordsale").setExecutor(new RecordSaleCommand());
        getServer().getPluginManager().registerEvents(new ShopSaleListener(), this);

        // Sauvegarde périodique hors du thread principal : un crash ne fait plus perdre toutes les ventes depuis le démarrage
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (dirty) {
                dirty = false;
                saveSales();
            }
        }, 6000L, 6000L);

        getLogger().info("SHShopGuiTop activé !");
    }

    @Override
    public void onDisable() {
        if (saveSales()) {
            getLogger().info("Les données de ventes ont été sauvegardées avec succès.");
        }
    }

    /** Écrit sales.yml (fichier temporaire puis remplacement : un arrêt en plein milieu ne le tronque pas). */
    private boolean saveSales() {
        synchronized (fileLock) {
            YamlConfiguration yaml = new YamlConfiguration();
            SalesManager.getInstance().saveToConfiguration(yaml);
            File tmp = new File(salesFile.getParentFile(), salesFile.getName() + ".tmp");
            try {
                yaml.save(tmp);
                Files.move(tmp.toPath(), salesFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return true;
            } catch (IOException e) {
                dirty = true;
                getLogger().severe("Erreur lors de la sauvegarde des données de ventes : " + e.getMessage());
                return false;
            }
        }
    }

    void markDirty() {
        dirty = true;
    }

    public static SHShopGuiTop getInstance() {
        return instance;
    }

    // --- Extension PlaceholderAPI ---
    public class SHShopGuiTopExpansion extends PlaceholderExpansion {

        private final SHShopGuiTop plugin;

        public SHShopGuiTopExpansion(SHShopGuiTop plugin) {
            this.plugin = plugin;
        }

        @Override
        public String getIdentifier() {
            return "shshopguitop";
        }

        @Override
        public String getAuthor() {
            return "Cry_Legende";
        }

        @Override
        public String getVersion() {
            return "1.0";
        }

        // Sans cela, /papi reload désenregistre les placeholders
        @Override
        public boolean persist() {
            return true;
        }

        /**
         * Format attendu : %shshopguitop_topX% avec X de 1 à 10.
         * Tokens remplacés :
         * - {index}   : le rang (1 à 10)
         * - {item}    : le nom (traduit via le code du jeu) de l’item
         * - {sold}    : nombre d’exemplaires vendus (formaté)
         * - {revenue} : revenu total (formaté)
         */
        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (identifier.startsWith("top")) {
                String numberStr = identifier.substring(3);
                int rank;
                try {
                    rank = Integer.parseInt(numberStr);
                } catch (NumberFormatException e) {
                    return "";
                }
                if (rank < 1 || rank > 10) return "";

                List<SalesStatsEntry> topList = SalesManager.getInstance().getTopSales();
                if (topList.size() < rank) return "";
                SalesStatsEntry entry = topList.get(rank - 1);

                // On utilise ici la traduction intégrée pour obtenir le nom en français.
                String displayName = getMinecraftTranslatedName(entry.getMinecraftId());

                String format = plugin.getConfig().getString("placeholder.format", "Top {index}: {item} - Vendus: {sold} - Revenu: {revenue}");
                format = format.replace("{index}", String.valueOf(rank))
                    .replace("{item}", displayName)
                    .replace("{sold}", formatNumber(entry.getSold()))
                    .replace("{revenue}", formatNumber(entry.getRevenue()));

                return ChatColor.translateAlternateColorCodes('&', format);
            }
            return null;
        }
    }

    // --- Gestionnaire des ventes (statistiques persistantes) ---
    public static class SalesManager {

        private static SalesManager instance;
        // Accès sous verrou : écrit par le thread principal, lu par PlaceholderAPI (parfois asynchrone) et la sauvegarde
        private final Map<String, SalesStatsEntry> salesData = new HashMap<>();
        // Copie triée du top 10, recalculée seulement après une vente (les placeholders sont demandés très souvent)
        private List<SalesStatsEntry> topCache;

        public static SalesManager getInstance() {
            if (instance == null) {
                instance = new SalesManager();
            }
            return instance;
        }

        /**
         * Enregistre une vente pour l’item donné.
         *
         * @param internalName nom interne défini dans la config du shop (clé)
         * @param minecraftId  l'ID Minecraft (ex: "minecraft:oak_log")
         * @param amount       nombre d’exemplaires vendus
         * @param revenue      revenu généré par la vente
         */
        public synchronized void recordSale(String internalName, String minecraftId, int amount, double revenue) {
            SalesStatsEntry entry = salesData.get(internalName);
            if (entry == null) {
                entry = new SalesStatsEntry(internalName, minecraftId, 0, 0.0);
                salesData.put(internalName, entry);
            }
            entry.addSale(amount, revenue);
            topCache = null;
            SHShopGuiTop plugin = SHShopGuiTop.getInstance();
            if (plugin != null) plugin.markDirty();
        }

        /**
         * Renvoie la liste triée des ventes (du plus vendu au moins vendu), limitée aux 10 premiers éléments.
         */
        public synchronized List<SalesStatsEntry> getTopSales() {
            if (topCache == null) {
                List<SalesStatsEntry> list = new ArrayList<>(salesData.size());
                for (SalesStatsEntry e : salesData.values()) {
                    list.add(new SalesStatsEntry(e.getInternalName(), e.getMinecraftId(), e.getSold(), e.getRevenue()));
                }
                list.sort((e1, e2) -> Integer.compare(e2.getSold(), e1.getSold()));
                topCache = List.copyOf(list.size() > 10 ? list.subList(0, 10) : list);
            }
            return topCache;
        }

        /**
         * Charge les statistiques depuis une configuration YAML.
         * Structure attendue :
         * internalName:
         * minecraftId: <string>
         * sold: <nombre>
         * revenue: <valeur>
         */
        public synchronized void loadFromConfiguration(YamlConfiguration config) {
            topCache = null;
            salesData.clear();
            for (String key : config.getKeys(false)) {
                ConfigurationSection section = config.getConfigurationSection(key);
                if (section != null) {
                    int sold = section.getInt("sold", 0);
                    double revenue = section.getDouble("revenue", 0.0);
                    String minecraftId = section.getString("minecraftId", key);
                    salesData.put(key, new SalesStatsEntry(key, minecraftId, sold, revenue));
                }
            }
        }

        /**
         * Sauvegarde les statistiques dans une configuration YAML.
         */
        public synchronized void saveToConfiguration(YamlConfiguration config) {
            config.getKeys(false).forEach(key -> config.set(key, null));
            for (Map.Entry<String, SalesStatsEntry> entry : salesData.entrySet()) {
                ConfigurationSection section = config.createSection(entry.getKey());
                section.set("minecraftId", entry.getValue().getMinecraftId());
                section.set("sold", entry.getValue().getSold());
                section.set("revenue", entry.getValue().getRevenue());
            }
        }
    }

    // --- Représentation des statistiques d'un article ---
    public static class SalesStatsEntry {
        private final String internalName;
        private final String minecraftId;
        private int sold;
        private double revenue;

        public SalesStatsEntry(String internalName, String minecraftId, int sold, double revenue) {
            this.internalName = internalName;
            this.minecraftId = minecraftId;
            this.sold = sold;
            this.revenue = revenue;
        }

        public String getInternalName() {
            return internalName;
        }

        public String getMinecraftId() {
            return minecraftId;
        }

        public int getSold() {
            return sold;
        }

        public double getRevenue() {
            return revenue;
        }

        public void addSale(int amount, double rev) {
            this.sold += amount;
            this.revenue += rev;
        }
    }

    // --- Commande de test pour enregistrer une vente manuellement ---
    public static class RecordSaleCommand implements CommandExecutor {

        /**
         * Pour un joueur : /recordsale <amount>
         * Le plugin récupère l’item tenu en main, vérifie via ShopGUI+ s'il fait partie d'un shop,
         * récupère le prix de vente et enregistre la vente.
         * <p>
         * Pour la console : /recordsale <itemName> <amount> <revenue>
         */
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (sender instanceof Player) {
                Player player = (Player) sender;
                ItemStack item = player.getInventory().getItemInMainHand();
                if (item == null || item.getType() == Material.AIR) {
                    player.sendMessage(ChatColor.RED + "Vous devez tenir un item de shop en main.");
                    return true;
                }
                ShopItem shopItem = ShopGuiPlusApi.getItemStackShopItem(player, item);
                if (shopItem == null) {
                    player.sendMessage(ChatColor.RED + "L'item en main ne fait pas partie d'un shop.");
                    return true;
                }
                if (args.length != 1) {
                    player.sendMessage(ChatColor.RED + "Usage: /recordsale <amount>");
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "Le montant doit être un nombre.");
                    return true;
                }
                double price = ShopGuiPlusApi.getItemStackPriceSell(player, item);
                if (price < 0) {
                    player.sendMessage(ChatColor.RED + "Impossible de récupérer le prix de vente.");
                    return true;
                }
                double revenue = price * amount;
                String internalName = getInternalName(shopItem);
                String minecraftId = "minecraft:" + item.getType().toString().toLowerCase(Locale.ROOT);
                SalesManager.getInstance().recordSale(internalName, minecraftId, amount, revenue);
                player.sendMessage(ChatColor.GREEN + "Vente enregistrée : " + amount + " " + internalName + " (Revenu: " + revenue + ")");
                return true;
            } else {
                if (args.length != 3) {
                    sender.sendMessage("Usage: /recordsale <itemName> <amount> <revenue>");
                    return true;
                }
                String key = args[0].toLowerCase(Locale.ROOT);
                int amount;
                double revenue;
                try {
                    amount = Integer.parseInt(args[1]);
                    revenue = Double.parseDouble(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("Format numérique invalide.");
                    return true;
                }
                SalesManager.getInstance().recordSale(key, key, amount, revenue);
                sender.sendMessage("Vente enregistrée pour " + key);
                return true;
            }
        }

        private String getInternalName(ShopItem shopItem) {
            String id = shopItem.getId();
            if (id != null && !id.trim().isEmpty()) {
                return id.toLowerCase(Locale.ROOT);
            }
            return shopItem.toString().toLowerCase(Locale.ROOT);
        }
    }

    // --- Listener pour les ventes réelles via ShopGUI+ ---
    public static class ShopSaleListener implements org.bukkit.event.Listener {

        /**
         * Cet événement est déclenché avant une transaction dans ShopGUI+.
         * Si l'événement n'est pas annulé et que l'action est SELL ou SELL_ALL,
         * on enregistre la vente.
         */
        // MONITOR + ignoreCancelled : seules les ventes que les autres plugins n'ont pas annulées sont comptées
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
        public void onShopPreTransaction(ShopPreTransactionEvent event) {
            if (event.getShopAction() == ShopManager.ShopAction.SELL || event.getShopAction() == ShopManager.ShopAction.SELL_ALL) {
                ShopItem shopItem = event.getShopItem();
                if (shopItem == null) return;

                int amount = event.getAmount();
                // Ignore les enregistrements avec un montant nul
                if (amount <= 0) return;

                // --------------------- CORRECTION APPLIQUÉE ICI ---------------------
                // D'après l'API de ShopGUI+, event.getPrice() retourne déjà le prix TOTAL
                // de la transaction (prix unitaire * quantité).
                // Il ne faut donc PAS le multiplier à nouveau par la quantité.
                double totalPrice = event.getPrice();
                // L'ancien calcul erroné était : double totalPrice = event.getPrice() * amount;
                // ----------------------------------------------------------------------

                String internalName = getInternalName(shopItem);
                ItemStack item = shopItem.getItem();
                String minecraftId = (item != null)
                    ? "minecraft:" + item.getType().toString().toLowerCase(Locale.ROOT)
                    : "unknown";
                SalesManager.getInstance().recordSale(internalName, minecraftId, amount, totalPrice);
            }
        }

        private String getInternalName(ShopItem shopItem) {
            String id = shopItem.getId();
            if (id != null && !id.trim().isEmpty()) {
                return id.toLowerCase(Locale.ROOT);
            }
            // Un fallback si l'item n'a pas d'ID custom.
            // Il est préférable que tous les items aient un ID dans la config de ShopGUI+.
            return shopItem.toString().toLowerCase(Locale.ROOT);
        }
    }
}
