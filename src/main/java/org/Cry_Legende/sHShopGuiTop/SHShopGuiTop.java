package org.Cry_Legende.sHShopGuiTop;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.Cry_Legende.sHShopGuiTop.network.NetworkSales;
import org.Cry_Legende.sHShopGuiTop.network.SalesDatabase;
import org.Cry_Legende.sHShopGuiTop.network.SalesStore;
import net.brcdev.shopgui.event.ShopPreTransactionEvent;
import net.brcdev.shopgui.shop.ShopManager;
import net.brcdev.shopgui.shop.item.ShopItem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
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
    // Classement commun aux serveurs du réseau ; null sans SHNetwork, sales.yml fait alors foi
    private NetworkSales network;
    private volatile Messages messages;

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

        // Crée messages.yml au premier démarrage
        Messages.prepare(this);
        try {
            messages = Messages.load(this);
        } catch (IOException | InvalidConfigurationException e) {
            getLogger().warning("messages.yml illisible, textes du jar utilisés : " + e.getMessage());
            messages = Messages.defaults(this);
        }
        AdminCommand admin = new AdminCommand(this);
        getCommand("shshopguitop").setExecutor(admin);
        getCommand("shshopguitop").setTabCompleter(admin);
        // Ancienne commande, même effet que /shshopguitop record
        getCommand("recordsale").setExecutor(admin);
        getServer().getPluginManager().registerEvents(new ShopSaleListener(), this);

        enableNetwork();

        // Sauvegarde périodique hors du thread principal : un crash ne fait plus perdre toutes les ventes depuis le démarrage
        if (network == null) {
            getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                if (dirty) {
                    dirty = false;
                    saveSales();
                }
            }, 6000L, 6000L);
        }

        getLogger().info("SHShopGuiTop activé !");
    }

    @Override
    public void onDisable() {
        if (network != null) {
            network.disable();
            getLogger().info("Les ventes en attente ont été écrites en base.");
        } else if (saveSales()) {
            getLogger().info("Les données de ventes ont été sauvegardées avec succès.");
        }
    }

    /**
     * Passe en mode réseau si SHNetwork est installé et 'reseau.actif' est vrai : les ventes de tous les serveurs
     * s'additionnent en base. Sans SHNetwork, rien de ce qui touche à la base n'est chargé et sales.yml reste la
     * référence, comme avant.
     */
    private void enableNetwork() {
        if (!getConfig().getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork")) return;
        String url = getConfig().getString("reseau.base-de-donnees.url", "");
        String password = getConfig().getString("reseau.base-de-donnees.mot-de-passe", "");
        if (url.isBlank() || password.equals("CHANGE_ME")) {
            getLogger().warning("SHNetwork détecté mais reseau.base-de-donnees n'est pas renseignée : classement local à ce serveur.");
            return;
        }
        try {
            SalesStore store = new SalesDatabase(url,
                getConfig().getString("reseau.base-de-donnees.utilisateur", ""), password,
                Math.max(250, getConfig().getInt("reseau.base-de-donnees.delai-ms", 3000)));
            NetworkSales sales = new NetworkSales(this, store);
            sales.enable(Math.max(1, getConfig().getInt("reseau.ecriture-secondes", 10)),
                Math.max(1, getConfig().getInt("reseau.rafraichissement-secondes", 60)),
                SalesManager.getInstance().allRows());
            SalesManager.getInstance().useNetwork(sales);
            network = sales;
            getLogger().info("SHNetwork détecté : ventes additionnées en base pour tous les serveurs.");
        } catch (RuntimeException | LinkageError e) {
            network = null;
            getLogger().log(java.util.logging.Level.SEVERE, "SHNetwork présent mais inutilisable, classement local à ce serveur : " + e);
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

    /** Envoie le message de messages.yml ; chaque paire de {@code pairs} remplace un {nom} par sa valeur. */
    void send(CommandSender sender, String key, String... pairs) {
        String text = messages.get(key, pairs);
        if (text != null) {
            sender.sendMessage(text);
        }
    }

    /**
     * Relit config.yml et messages.yml. Les deux sont lus à part et ne remplacent l'état en cours que s'ils sont
     * valides ; sinon l'ancienne configuration reste active et le message donne le fichier et l'erreur. Les ventes
     * (sales.yml ou base) ne sont pas touchées ; la section reseau ne se recharge pas à chaud.
     */
    void reload(CommandSender sender) {
        String file = "config.yml";
        try {
            new YamlConfiguration().load(new File(getDataFolder(), file));
            file = "messages.yml";
            Messages loaded = Messages.load(this);
            reloadConfig();
            messages = loaded;
            send(sender, "reloaded");
        } catch (IOException | InvalidConfigurationException e) {
            String error = e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse("");
            getLogger().warning(file + " illisible, l'ancienne configuration reste active : " + error);
            send(sender, "reload-failed", "file", file, "error", error);
        }
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
        // Classement commun aux serveurs : s'il est là, les ventes et le top passent par lui
        private volatile NetworkSales network;

        void useNetwork(NetworkSales network) {
            this.network = network;
        }

        /** Toutes les ventes connues de ce serveur, pour les reprendre dans la base commune. */
        synchronized List<SalesStore.Row> allRows() {
            List<SalesStore.Row> rows = new ArrayList<>(salesData.size());
            for (SalesStatsEntry e : salesData.values()) {
                rows.add(new SalesStore.Row(e.getInternalName(), e.getMinecraftId(), e.getSold(), e.getRevenue()));
            }
            return rows;
        }

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
            if (network != null) {
                network.record(internalName, minecraftId, amount, revenue);
                return;
            }
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
            if (network != null) {
                return network.top();
            }
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
