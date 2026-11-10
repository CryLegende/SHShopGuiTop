package org.Cry_Legende.sHShopGuiTop.network;

import org.Cry_Legende.sHShopGuiTop.SHShopGuiTop.SalesStatsEntry;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Classement des ventes commun à tous les serveurs. Chaque vente est comptée en mémoire, puis ajoutée à la base
 * par lots ({@code sold = sold + ?}) : deux serveurs qui vendent le même article s'additionnent au lieu de
 * s'écraser. Le classement affiché est relu en base de temps en temps ; les placeholders ne lisent que cette copie.
 *
 * <p>Une seule tâche à la fois parle à la base (fil dédié), jamais le fil principal.</p>
 */
public final class NetworkSales {

    private static final int TOP_SIZE = 10;

    private final JavaPlugin plugin;
    private final SalesStore store;
    private final SalesBuffer buffer = new SalesBuffer();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "SHShopGuiTop-base");
        thread.setDaemon(true);
        return thread;
    });

    private volatile List<SalesStatsEntry> top = List.of();
    private volatile long lastWarning;

    public NetworkSales(JavaPlugin plugin, SalesStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    /**
     * Démarre l'écriture et la relecture périodiques. Si la base est vide, y reprend d'abord les ventes déjà
     * comptées dans l'ancien sales.yml de ce serveur.
     */
    public void enable(int writeSeconds, int refreshSeconds, List<SalesStore.Row> localSales) {
        worker.execute(() -> {
            try {
                store.createTable();
                if (!localSales.isEmpty() && store.isEmpty()) {
                    store.importMissing(localSales);
                    plugin.getLogger().info("Base des ventes vide : " + localSales.size() + " articles repris de sales.yml.");
                }
            } catch (SQLException e) {
                warn("Table des ventes inaccessible", e);
            }
            refresh();
        });
        worker.scheduleWithFixedDelay(this::flush, writeSeconds, writeSeconds, TimeUnit.SECONDS);
        worker.scheduleWithFixedDelay(this::refresh, refreshSeconds, refreshSeconds, TimeUnit.SECONDS);
    }

    /** Compte une vente. Appelé par le fil principal : rien ne touche à la base ici. */
    public void record(String item, String minecraftId, int amount, double revenue) {
        buffer.add(item, minecraftId, amount, revenue);
    }

    /** Dernier classement lu en base. */
    public List<SalesStatsEntry> top() {
        return top;
    }

    /** Arrête les tâches, écrit ce qui reste puis ferme la base. */
    public void disable() {
        worker.shutdown();
        try {
            worker.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flush();
        store.close();
    }

    private void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        Map<String, SalesBuffer.Delta> taken = buffer.drain();
        try {
            store.add(taken);
        } catch (SQLException | RuntimeException e) {
            buffer.restore(taken);
            warn("Ventes non écrites, nouvel essai au prochain passage", e);
        }
    }

    private void refresh() {
        try {
            List<SalesStatsEntry> list = new ArrayList<>(TOP_SIZE);
            for (SalesStore.Row row : store.top(TOP_SIZE)) {
                list.add(new SalesStatsEntry(row.item(), row.minecraftId(), (int) Math.min(Integer.MAX_VALUE, row.sold()), row.revenue()));
            }
            top = List.copyOf(list);
        } catch (SQLException | RuntimeException e) {
            warn("Classement non relu, l'ancien reste affiché", e);
        }
    }

    /** Une alerte par minute au plus : une base absente ne remplit pas la console. */
    private void warn(String message, Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastWarning > 60_000L) {
            lastWarning = now;
            plugin.getLogger().log(Level.WARNING, message + " : " + e);
        }
    }
}
