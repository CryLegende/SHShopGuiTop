<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHShopGuiTop

*Classement des items les plus vendus au shop ShopGUI+, en placeholders.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Classement des items les plus vendus au shop ShopGUI+, en placeholders.

Utilisé sur : **Opale**.

Avec **SHNetwork** (facultatif), les ventes de tous les serveurs s'additionnent dans la base commune (table `shshopguitop_sales`, ajout atomique `sold = sold + ?`, écriture par lots) et le classement affiché est relu en base toutes les 60 secondes. Au premier démarrage, si la base est vide, les ventes de `sales.yml` y sont reprises. Sans SHNetwork, ou avec `reseau.actif: false`, `sales.yml` reste la référence de ce serveur.

## Dépendances

- Obligatoires : `PlaceholderAPI`, `ShopGUIPlus`
- Facultatives : `SHNetwork`

## Commandes

| Commande | Description | Permission |
|---|---|---|
| `/shshopguitop record <quantité>` | Enregistre une vente de l'objet de shop en main (test). | `shshopguitop.admin.record` |
| `/shshopguitop record <article> <quantité> <revenu>` | Idem pour un article nommé, depuis la console. | `shshopguitop.admin.record` |
| `/shshopguitop top` | Affiche le classement avec les quantités et revenus exacts. | `shshopguitop.admin.top` |
| `/shshopguitop reload` | Recharge `config.yml` et `messages.yml` sans toucher aux ventes ; si un fichier est illisible, l'ancienne configuration reste active. La section `reseau` demande un redémarrage. | `shshopguitop.admin.reload` |
| `/recordsale` | Ancienne commande, identique à `/shshopguitop record`. | `shshopguitop.admin.record` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shshopguitop.admin` | Toutes les sous-commandes de `/shshopguitop`. | `op` |
| `shshopguitop.admin.record` `.top` `.reload` | Une sous-commande. | `op` |

## Configuration

Fichiers par défaut, avec des valeurs d'exemple. Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHShopGuiTop/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

La section `reseau` (actif par défaut, base de données, délais d'écriture et de relecture) ne sert qu'avec SHNetwork et demande un redémarrage.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
