package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.PlayerData;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.Screens;
import pl.kasyno.gui.T;

/** Główne menu: wejście do kasyna, zarobków, sklepu, banku, ekwipunku, biznesów, skrzyń, rankingu i pokoju graczy. */
public final class MainMenu extends Gui {
    public MainMenu(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "menu";
    }

    @Override
    protected Component title() {
        return T.c("<gradient:#fbbf24:#f97316><bold>Kasyno i Ekonomia</bold></gradient>");
    }

    @Override
    protected void render() {
        PlayerData d = eco().pd(id);
        long now = eco().now();
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        fill(ItemBuilder.filler());

        long need = Eco.xpNeeded(d.level);
        set(4, ItemBuilder.of(Material.PLAYER_HEAD).head(player).name("<yellow><bold>" + T.esc(player.getName()))
                .lore("<gray>Portfel: <gold>" + Fmt.money(d.wallet), "<gray>Bank: <gold>" + Fmt.money(d.bank),
                        "<gray>Poziom: <white>" + d.level + " <dark_gray>(" + Fmt.shortNum(d.xp) + "/" + Fmt.shortNum(need) + " XP)",
                        "<gray>Ranking: <white>#" + eco().rankOf(id), "", "<yellow>Kliknij, aby zobaczyć profil").build(),
                t -> go(new ProfileGui(plugin, player, id)));

        boolean ready = now >= d.lastDaily + Eco.DAILY_MS || now >= d.lastWeekly + Eco.WEEK_MS || eco().cooldownLeft(id, "godzinna") == 0;
        set(10, ItemBuilder.of(Material.GOLD_INGOT).icon("kasyno").name("<gold><bold>Kasyno").glow()
                .lore("<gray>Cztery sale: automaty, karty, ryzyko", "<gray>i stoły na żywo z innymi graczami.", "", "<yellow>Kliknij, aby wejść").build(), t -> go(new CasinoGui(plugin, player)));
        set(12, ItemBuilder.of(Material.IRON_PICKAXE).icon("m_zarobki").name("<green><bold>Zarobki").glow(ready)
                .lore("<gray>Nagrody, praca, zbieranie, quizy.", ready ? "<green>Są nagrody do odebrania!" : "<dark_gray>Brak nagród do odebrania", "", "<yellow>Kliknij, aby wejść").build(), t -> go(new EarnGui(plugin, player)));
        set(14, ItemBuilder.of(Material.EMERALD).icon("m_sklep").name("<aqua><bold>Sklep")
                .lore("<gray>" + plugin.catalog().shopOrder().size() + " przedmiotów: narzędzia, zwierzaki,", "<gray>eliksiry, ochrona, luksus.", "", "<yellow>Kliknij, aby wejść").build(), t -> go(new ShopGui(plugin, player)));
        set(16, ItemBuilder.of(Material.GOLD_BLOCK).icon("m_bank").name("<yellow><bold>Bank")
                .lore("<gray>W banku: <gold>" + Fmt.money(d.bank) + " <gray>/ <gold>" + Fmt.money(eco().bank(id).total()), "<gray>Ulepszenia zwiększają limit.", "", "<yellow>Kliknij, aby wejść").build(), t -> go(new BankGui(plugin, player)));

        set(28, ItemBuilder.of(Material.ENDER_CHEST).icon("m_ekwipunek").name("<light_purple><bold>Ekwipunek")
                .lore("<gray>Twoje przedmioty, eliksiry i łupy.", "<gray>Tu używasz polis, tarcz i eliksirów.", "", "<yellow>Kliknij, aby wejść").build(), t -> go(new InventoryGui(plugin, player)));
        long pending = plugin.empire().pending(id, now).total();
        long ripe = plugin.empire().status(id, now).stream().filter(s -> s.state().equals("ready")).count();
        set(30, ItemBuilder.of(Material.WHEAT).icon("m_biznes").name("<green><bold>Biznes i farma").glow(pending > 0 || ripe > 0)
                .lore("<gray>Dochód pasywny: <gold>" + Fmt.money(plugin.empire().hourlyTotal(id)) + "<gray>/h", pending > 0 ? "<green>Do odebrania: <gold>" + Fmt.money(pending) : "<dark_gray>Nic do odebrania", ripe > 0 ? "<green>Plony gotowe: " + ripe : "", "", "<yellow>Kliknij, aby wejść").build(), t -> go(new EmpireGui(plugin, player)));
        set(32, ItemBuilder.of(Material.CHEST).icon("m_skrzynie").name("<gold><bold>Skrzynie")
                .lore("<gray>Otwieraj skrzynie z animacją.", "<gray>Masz ich: <white>" + chestCount(), "", "<yellow>Kliknij, aby wejść").build(), t -> go(new CratesGui(plugin, player)));
        set(34, ItemBuilder.of(Material.PLAYER_HEAD).icon("hall_pokoj").name("<red><bold>Pokój graczy").glow(plugin.rooms().hasLive())
                .lore("<gray>Jackpot, pojedynek, napad i loteria.", plugin.rooms().hasLive() ? "<green>Trwa gra, dołącz!" : "<dark_gray>Teraz cicho", "", "<yellow>Kliknij, aby wejść").build(), t -> go(new LobbyGui(plugin, player)));

        set(45, ItemBuilder.of(Material.NETHER_STAR).icon("m_ranking").name("<aqua>Ranking").lore("<gray>Najbogatsi, poziomy i hazardziści.").build(), t -> go(new RankingGui(plugin, player)));
        set(49, ItemBuilder.close(), t -> player.closeInventory());
        set(53, ItemBuilder.of(Material.BOOK).icon("m_pomoc").name("<white>Pomoc").lore("<gray>Jak działa kasyno, komendy i NPC.").build(), t -> go(new HelpGui(plugin, player)));
    }

    private int chestCount() {
        int n = 0;
        for (String c : plugin.catalog().crates.keySet()) n += eco().qty(id, c);
        return n;
    }

    /** Skrót do otwierania z komend. */
    public static void show(KasynoPlugin plugin, Player p) {
        Screens.open(plugin, p, "menu");
    }
}
