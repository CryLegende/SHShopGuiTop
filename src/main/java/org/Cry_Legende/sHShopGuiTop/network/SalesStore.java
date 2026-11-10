package org.Cry_Legende.sHShopGuiTop.network;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Ventes partagées entre les serveurs. Seule {@link SalesDatabase} l'implémente. Toutes les méthodes bloquent :
 * à appeler hors du fil principal.
 */
public interface SalesStore {

    /** Total d'un article tel qu'il est en base. */
    record Row(String item, String minecraftId, long sold, double revenue) {
    }

    /** Crée la table si elle manque. */
    void createTable() throws SQLException;

    /** Vrai si la table ne contient aucune vente. */
    boolean isEmpty() throws SQLException;

    /** Ajoute les ventes aux totaux (lignes créées à la première vente), en une instruction atomique par article. */
    void add(Map<String, SalesBuffer.Delta> deltas) throws SQLException;

    /** Reprend des totaux sans toucher aux articles déjà présents. */
    void importMissing(List<Row> rows) throws SQLException;

    /** Les articles les plus vendus, du plus vendu au moins vendu. */
    List<Row> top(int limit) throws SQLException;

    void close();
}
