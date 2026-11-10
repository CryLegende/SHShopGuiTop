package org.Cry_Legende.sHShopGuiTop.network;

import java.util.HashMap;
import java.util.Map;

/**
 * Ventes comptées sur ce serveur et pas encore écrites en base. Chaque vente s'ajoute à celles déjà en attente de
 * son article ; l'écriture prend tout d'un coup ({@link #drain()}) et, si elle échoue, remet ce qu'elle avait pris
 * ({@link #restore(Map)}) : aucune vente n'est perdue ni comptée deux fois.
 */
public final class SalesBuffer {

    /** Ventes d'un article : exemplaires et revenu depuis la dernière écriture. */
    public record Delta(String minecraftId, long sold, double revenue) {

        Delta plus(Delta other) {
            return new Delta(minecraftId, sold + other.sold, revenue + other.revenue);
        }
    }

    private Map<String, Delta> pending = new HashMap<>();

    public synchronized void add(String item, String minecraftId, int amount, double revenue) {
        pending.merge(item, new Delta(minecraftId, amount, revenue), Delta::plus);
    }

    /** Rend les ventes en attente et repart d'un tampon vide. */
    public synchronized Map<String, Delta> drain() {
        Map<String, Delta> taken = pending;
        pending = new HashMap<>();
        return taken;
    }

    /** Remet des ventes que la base n'a pas acceptées, en les ajoutant à celles arrivées depuis. */
    public synchronized void restore(Map<String, Delta> taken) {
        taken.forEach((item, delta) -> pending.merge(item, delta, Delta::plus));
    }

    public synchronized boolean isEmpty() {
        return pending.isEmpty();
    }
}
