package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

/** Krótka instrukcja: jak działa kasyno, ochrona w grach, komendy i NPC. */
public final class HelpGui extends Gui {
    public HelpGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "pomoc";
    }

    @Override
    protected Component title() {
        return T.c("<white><bold>Pomoc");
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        set(10, ItemBuilder.of(Material.GOLD_INGOT).name("<gold><bold>Jak to działa")
                .lore("<gray>Zarabiasz monety (nagrody, praca,", "<gray>zbieranie, biznesy, farma) i wydajesz je", "<gray>w sklepie albo grasz w kasynie.",
                        "<gray>Każda gra ma zwrot poniżej 100%.", "<gray>Wszystko robisz klikaniem w menu.").build());
        set(12, ItemBuilder.of(Material.SHIELD).name("<aqua><bold>Ochrona w grach")
                .lore("<gray>Polisa zwraca stawkę po całkowitej", "<gray>przegranej. Tarcza anuluje pierwszy zły", "<gray>ruch w minach, kurczaku, wieży, hi-lo i",
                        "<gray>blackjacku. Uzbrajasz je w ekwipunku", "<gray>(kliknij przedmiot). Chronią stawki do", "<gray>swojego limitu.").build());
        set(14, ItemBuilder.of(Material.WHEAT).name("<green><bold>Biznes i farma")
                .lore("<gray>Biznesy zarabiają same, także gdy", "<gray>Cię nie ma (magazyn 8 godzin). Farma:", "<gray>sadzisz, po czasie zbierasz więcej,",
                        "<gray>niż włożyłeś w nasiona.").build());
        set(16, ItemBuilder.of(Material.VILLAGER_SPAWN_EGG).name("<yellow><bold>NPC")
                .lore("<gray>Wieśniacy-kasjerzy otwierają menu", "<gray>jednym kliknięciem. Admin stawia ich", "<gray>komendą <white>/kasyno npc spawn <typ>").build());
        set(28, ItemBuilder.of(Material.COMMAND_BLOCK).name("<light_purple><bold>Komendy")
                .lore("<white>/kasyno <gray>- menu główne", "<white>/saldo /profil /top /bank /sklep", "<white>/ekwipunek /daily /weekly /godzinna /praca",
                        "<white>/ryby /kopalnia /polowanie /las ...", "<white>/moneta /sloty /crash /kurczak ...", "<gray>Każda komenda otwiera swoje GUI.").build());
        set(30, ItemBuilder.of(Material.CLOCK).name("<aqua><bold>Cooldowny").lore("<gray>Nagrody i zarobki mają cooldowny.", "<gray>Eliksiry przyspieszające je skracają.").build());
        set(32, ItemBuilder.of(Material.NETHER_STAR).name("<gold><bold>Mnożniki")
                .lore("<gray>Karty VIP, zwierzaki i eliksiry", "<gray>zwiększają zarobki (nie wygrane w", "<gray>hazardzie). Działa najlepszy eliksir.").build());
        set(34, ItemBuilder.of(Material.PLAYER_HEAD).name("<red><bold>Pokój graczy").lore("<gray>Jackpot, pojedynek, napad i loteria", "<gray>to gry z innymi graczami.").build());
        backTo(49, () -> go(new MainMenu(plugin, player)));
    }
}
