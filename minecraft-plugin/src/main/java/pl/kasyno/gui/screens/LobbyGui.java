package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Fmt;
import pl.kasyno.games.Lottery;
import pl.kasyno.games.Rooms;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

/** Pokój graczy: jackpot, napad na bank, pojedynki i loteria. Okno odświeża się co sekundę. */
public final class LobbyGui extends Gui {
    public LobbyGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "pokoj";
    }

    @Override
    protected Component title() {
        return T.c("<red><bold>Pokój graczy");
    }

    @Override
    public void open() {
        super.open();
        repeat(20, 20, this::redraw);
    }

    private String status(Rooms.Kind kind) {
        Rooms.Lobby l = plugin.rooms().lobby(kind);
        if (l == null) return "<dark_gray>Nikt nie gra. Otwórz pierwszy!";
        if (l.open()) return "<green>Trwa: " + l.players.size() + " graczy, stawka " + Fmt.money(l.stake) + ", start za " + Fmt.duration(Math.max(0, l.endsAt - System.currentTimeMillis()));
        return "<yellow>Właśnie rozstrzygnięte";
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        Rooms.Lobby jp = plugin.rooms().lobby(Rooms.Kind.JACKPOT);
        Rooms.Lobby hs = plugin.rooms().lobby(Rooms.Kind.HEIST);
        set(11, ItemBuilder.of(Material.GOLD_BLOCK).icon("jackpot").name("<gold><bold>Jackpot").glow(jp != null && jp.open())
                .lore("<gray>Wszyscy dorzucają stawkę,", "<gray>jedna osoba bierze całą pulę.", "", status(Rooms.Kind.JACKPOT), "", "<yellow>Kliknij, aby wejść").build(), t -> go(new RoomGui(plugin, player, Rooms.Kind.JACKPOT)));
        set(13, ItemBuilder.of(Material.TNT).icon("napad").name("<red><bold>Napad na bank").glow(hs != null && hs.open())
                .lore("<gray>Ekipa dzieli łup. Wpisowe " + Fmt.money(Rooms.HEIST_STAKE) + ".", "", status(Rooms.Kind.HEIST), "", "<yellow>Kliknij, aby wejść").build(), t -> go(new RoomGui(plugin, player, Rooms.Kind.HEIST)));
        set(15, ItemBuilder.of(Material.IRON_SWORD).icon("pojedynek").name("<aqua><bold>Pojedynek").lore("<gray>Wyzwij gracza na 50/50 o pieniądze", "<gray>(prowizja 3%).", "", "<yellow>Kliknij, aby wybrać przeciwnika").build(),
                t -> go(new PlayerPicker(plugin, player, "Kogo wyzwać?", "gracz_pojedynek", p -> true, this::askDuelStake, () -> go(new LobbyGui(plugin, player)))));
        Lottery.Pool pool = plugin.lottery().pool();
        set(29, ItemBuilder.of(Material.PAPER).icon("loteria").name("<gold><bold>Loteria").glow(eco().pd(id).lotteryTickets > 0)
                .lore("<gray>Pula: <gold>" + Fmt.money(pool.pot()), "<gray>Twoje bilety: <white>" + eco().pd(id).lotteryTickets, "<gray>Losowanie za: <white>" + Fmt.duration(Math.max(0, plugin.lottery().nextDraw() - System.currentTimeMillis())), "", "<yellow>Kliknij, aby kupić bilety").build(),
                t -> go(new LotteryGui(plugin, player)));

        Rooms.Duel duel = plugin.rooms().duelFor(id);
        if (duel != null) {
            String from = eco().pd(duel.challenger).name;
            set(31, ItemBuilder.of(Material.PLAYER_HEAD).head(Bukkit.getOfflinePlayer(duel.challenger)).name("<yellow><bold>Wyzwanie od " + T.esc(from)).glow()
                    .lore("<gray>Stawka: <gold>" + Fmt.money(duel.stake) + " <gray>od każdego", "<gray>Wygasa za: <white>" + Fmt.duration(Math.max(0, duel.expiresAt - System.currentTimeMillis()))).build());
            set(30, ItemBuilder.of(Material.LIME_DYE).icon("check").name("<green><bold>Przyjmij").build(), t -> answer(true));
            set(32, ItemBuilder.of(Material.RED_DYE).icon("cross").name("<red><bold>Odmów").build(), t -> answer(false));
        } else {
            set(31, ItemBuilder.of(Material.GRAY_DYE).name("<gray>Brak wyzwań").lore("<gray>Gdy ktoś Cię wyzwie, zobaczysz to tutaj.").build());
        }
        backTo(49, () -> go(new CasinoGui(plugin, player)));
    }

    private void answer(boolean accept) {
        String err = accept ? plugin.rooms().accept(player) : plugin.rooms().decline(player);
        if (err != null) {
            Fx.deny(player);
            T.send(player, "<red>" + err);
        }
        redraw();
    }

    private void askDuelStake(Player target) {
        plugin.prompts().ask(player, "<white>Jaka stawka pojedynku z <yellow>" + T.esc(target.getName()) + "</yellow>? <gray>(np. 500, 2k)", text -> {
            long s = Fmt.parseAmount(text, eco().wallet(id));
            if (s < 1) T.send(player, "<red>Nie rozumiem stawki.");
            else {
                String err = plugin.rooms().challenge(player, target, s);
                if (err != null) T.send(player, "<red>" + err);
                else T.send(player, "<green>Wyzwanie wysłane do <yellow>" + T.esc(target.getName()) + "</yellow>. Ma minutę na odpowiedź.");
            }
            new LobbyGui(plugin, player).open();
        }, () -> new LobbyGui(plugin, player).open());
    }
}
