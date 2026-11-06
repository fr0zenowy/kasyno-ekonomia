package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Heist;
import pl.kasyno.games.HeistFrames;
import pl.kasyno.games.Rooms;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Lobby jackpota albo napadu: uczestnicy jako głowy, odliczanie, dołączanie. Po losowaniu okno odgrywa scenę: w jackpocie
 * podświetlenie krąży po graczach i zwalnia na zwycięzcy, a w napadzie ekipa przechodzi etapy (wejście, kamery, skarbiec, łup,
 * ucieczka), a po drodze ktoś wpada albo trafia się dodatkowy łup.
 */
public final class RoomGui extends Gui {
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
    /** Pasek łupu w dolnym rzędzie: sześć poziomów, mnożnik rośnie od x1,2 do ok. x2,8. */
    private static final int[] LOOT_METER = {47, 48, 49, 50, 51, 52};
    private static final long[] PRESETS = {100, 500, 1_000, 5_000, 10_000};

    private final Rooms.Kind kind;
    private int seenStep = -2;
    private boolean seenDone;
    private int ticks;

    public RoomGui(KasynoPlugin plugin, Player player, Rooms.Kind kind) {
        super(plugin, player);
        this.kind = kind;
    }

    public Rooms.Kind kind() {
        return kind;
    }

    @Override
    protected String art() {
        return kind == Rooms.Kind.JACKPOT ? "jackpot" : "napad";
    }

    @Override
    protected Component title() {
        return T.c(kind == Rooms.Kind.JACKPOT ? "<gold><bold>Jackpot" : "<red><bold>Napad na bank");
    }

    @Override
    public void open() {
        super.open();
        repeat(1, 1, this::tick);
    }

    /**
     * Co tick sprawdza, czy scena poszła do przodu (wtedy od razu rysuje nową klatkę), a w lobby odświeża odliczanie co pół sekundy.
     */
    private void tick() {
        ticks++;
        Rooms.Lobby l = plugin.rooms().lobby(kind);
        if (l != null && l.resolved && !l.cancelled) {
            if (l.step != seenStep || l.sceneDone != seenDone) {
                seenStep = l.step;
                seenDone = l.sceneDone;
                redraw();
            }
            return;
        }
        if (ticks % 10 == 0) redraw();
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
        Rooms.Lobby l = plugin.rooms().lobby(kind);
        boolean jackpot = kind == Rooms.Kind.JACKPOT;

        if (l == null) {
            set(4, ItemBuilder.of(jackpot ? Material.GOLD_BLOCK : Material.TNT).icon(jackpot ? "jackpot" : "napad").name(jackpot ? "<gold><bold>Jackpot" : "<red><bold>Napad na bank")
                    .lore(jackpot ? List.of("<gray>Wszyscy dorzucają tę samą stawkę,", "<gray>a jedna osoba zgarnia całą pulę (prowizja 5%).", "<gray>Lobby trwa 45 s, min. 2 osoby.")
                            : List.of("<gray>Wpisowe " + Fmt.money(Rooms.HEIST_STAKE) + ", ekipa do 10 osób, min. 2.", "<gray>Pięć etapów: wejście, kamery, skarbiec, łup, ucieczka.", "<gray>Po drodze ktoś może wpaść, a łup dzielą ci, którzy uciekną.")).build());
            if (jackpot) {
                for (int i = 0; i < PRESETS.length; i++) {
                    final long s = PRESETS[i];
                    set(20 + i, ItemBuilder.of(Material.GOLD_NUGGET).amount(i + 1).name("<yellow><bold>Otwórz za " + Fmt.money(s)).lore("<gray>Wszyscy dorzucą tyle samo.", "<yellow>Kliknij").build(), t -> open(s));
                }
                set(31, ItemBuilder.of(Material.NAME_TAG).name("<white>Własna stawka").lore("<yellow>Kliknij i wpisz kwotę").build(), t -> askStake());
            } else {
                set(22, ItemBuilder.of(Material.TNT).icon("napad").name("<red><bold>Zorganizuj napad").glow().lore("<gray>Wpisowe: <gold>" + Fmt.money(Rooms.HEIST_STAKE), "<gray>Cooldown: " + Fmt.duration(eco().cooldownOf("heist")), "<yellow>Kliknij").build(), t -> open(Rooms.HEIST_STAKE));
            }
            backTo(49, () -> go(new LobbyGui(plugin, player)));
            return;
        }

        List<UUID> crew = new ArrayList<>(l.resolved ? l.crew : l.players);
        long left = Math.max(0, l.endsAt - System.currentTimeMillis());
        if (l.open()) {
            for (int i = 0; i < crew.size() && i < SLOTS.length; i++) {
                set(SLOTS[i], ItemBuilder.of(Material.PLAYER_HEAD).head(Bukkit.getOfflinePlayer(crew.get(i))).name("<yellow>" + T.esc(eco().pd(crew.get(i)).name))
                        .lore("<gray>Stawka: <gold>" + Fmt.money(l.stake)).build());
            }
            int n = crew.size();
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Stawka: <gold>" + Fmt.money(l.stake) + " <gray>od każdego");
            lore.add("<gray>Uczestników: <white>" + n);
            lore.add("<gray>Pula: <gold>" + Fmt.money(l.stake * n));
            if (jackpot) lore.add("<gray>Szansa każdego: <white>" + String.format(Locale.ROOT, "%.1f", 100.0 / n) + "%");
            else lore.add("<gray>Łup: wpisowe ekipy x1,2 do x2,6 (zależnie od etapów)");
            lore.add("<gray>Start za: <white>" + Fmt.duration(left) + (n < 2 ? " <red>(potrzeba min. 2 osób)" : ""));
            set(4, ItemBuilder.of(jackpot ? Material.GOLD_BLOCK : Material.TNT).icon(jackpot ? "jackpot" : "napad").name((jackpot ? "<gold>" : "<red>") + "<bold>" + (jackpot ? "Jackpot" : "Napad") + " trwa").glow().lore(lore).build());
            if (!l.players.contains(id)) {
                set(49, ItemBuilder.of(Material.EMERALD).icon("play").name("<green><bold>Dołącz (" + Fmt.money(l.stake) + ")").glow().lore("<yellow>Kliknij").build(), t -> join());
            } else set(49, ItemBuilder.of(Material.LIME_DYE).icon("check").name("<green>Jesteś w grze").lore("<gray>Czekaj na losowanie.").build());
            backTo(45, () -> go(new LobbyGui(plugin, player)));
            return;
        }
        if (l.cancelled) {
            set(4, ItemBuilder.of(Material.BARRIER).icon("cross").name("<red><bold>Gra odwołana").lore("<gray>Nikt nie dołączył. Stawki wróciły.").build());
            backTo(49, () -> go(new LobbyGui(plugin, player)));
            return;
        }
        if (jackpot) renderJackpot(l, crew);
        else renderHeist(l, crew);
        backTo(49, () -> go(new LobbyGui(plugin, player)));
    }

    // ---------- scena jackpota ----------

    private void renderJackpot(Rooms.Lobby l, List<UUID> crew) {
        int hot = l.step >= 0 && l.spin != null && l.step < l.spin.length ? l.spin[l.step] : -1;
        for (int i = 0; i < crew.size() && i < SLOTS.length; i++) {
            UUID p = crew.get(i);
            boolean win = l.sceneDone && p.equals(l.winner);
            boolean on = i == hot && !l.sceneDone;
            String name = T.esc(eco().pd(p).name);
            if (on) set(SLOTS[i], ItemBuilder.of(Material.GOLD_BLOCK).icon("jackpot").name("<gold><bold>▶ " + name + " ◀").glow().lore("<gray>Stawka: <gold>" + Fmt.money(l.stake)).build());
            else set(SLOTS[i], ItemBuilder.of(Material.PLAYER_HEAD).head(Bukkit.getOfflinePlayer(p)).name((win ? "<gold><bold>★ " : "<yellow>") + name).glow(win).lore("<gray>Stawka: <gold>" + Fmt.money(l.stake)).build());
        }
        if (!l.sceneDone) {
            String who = hot >= 0 ? T.esc(eco().pd(crew.get(hot)).name) : "?";
            set(4, ItemBuilder.of(Material.CLOCK).name("<yellow><bold>Losowanie...").lore("<gray>Pula: <gold>" + Fmt.money(l.pot), hot >= 0 ? "<gray>Teraz: <white>" + who : "<gray>Kręcimy...").build());
        } else {
            String wn = T.esc(eco().pd(l.winner).name);
            set(4, ItemBuilder.of(Material.NETHER_STAR).icon("star").name("<gold><bold>Zwycięzca: " + wn).glow()
                    .lore("<gray>Wygrana: <gold>" + Fmt.money(l.payout), "<gray>Pula: <white>" + Fmt.money(l.pot) + " <gray>(prowizja 5%)", l.winner.equals(id) ? "<green>To Ty!" : "").build());
        }
    }

    // ---------- scena napadu: animowana mapa banku ----------

    private String names(List<UUID> ids) {
        return String.join(", ", ids.stream().map(u -> T.esc(eco().pd(u).name)).toList());
    }

    private static ItemStack floor(Material pane) {
        return ItemBuilder.of(pane).name(" ").hidden().icon("blank").build();
    }

    private static ItemStack flash(Material pane) {
        return ItemBuilder.of(pane).name(" ").hidden().build();
    }

    private ItemStack propItem(HeistFrames.Prop p) {
        return switch (p) {
            case VAN -> ItemBuilder.of(Material.MINECART).icon("h_car").name("<white><bold>Van ekipy").lore("<gray>Czeka z włączonym silnikiem.").build();
            case DOOR_CLOSED -> ItemBuilder.of(Material.OAK_DOOR).icon("h_door").name("<gray>Drzwi banku").lore("<gray>Zamknięte.").build();
            case DOOR_OPEN -> ItemBuilder.of(Material.OAK_DOOR).icon("h_door").name("<green>Drzwi banku").glow().lore("<gray>Otwarte.").build();
            case CAMERA -> ItemBuilder.of(Material.ENDER_EYE).icon("h_camera").name("<red>Kamera ochrony").lore("<gray>Obraca się i patrzy.").build();
            case BEAM -> flash(Material.YELLOW_STAINED_GLASS_PANE);
            case GUARD -> ItemBuilder.of(Material.IRON_HELMET).icon("h_guard").name("<blue><bold>Ochroniarz").lore("<gray>Rusza w stronę ekipy!").glow().build();
            case POLICE -> ItemBuilder.of(Material.BELL).icon("h_siren").name("<red><bold>Policja").lore("<gray>Zajeżdża drogę!").glow().build();
            case VAULT_CLOSED -> ItemBuilder.of(Material.IRON_DOOR).icon("h_vault").name("<gray>Skarbiec").lore("<gray>Drzwi z pancernej stali.").build();
            case VAULT_DRILL -> ItemBuilder.of(Material.IRON_PICKAXE).icon("m_zarobki").name("<gold><bold>Wiertło w pracy").glow().build();
            case VAULT_OPEN -> ItemBuilder.of(Material.CHEST).icon("m_skrzynie").name("<green><bold>Skarbiec otwarty").glow().build();
            case GOLD -> ItemBuilder.of(Material.GOLD_INGOT).icon("h_loot").name("<gold>Złoto").lore("<gray>Czeka na zgarnięcie.").build();
            case SPARK -> ItemBuilder.of(Material.FIREWORK_STAR).name("<yellow>✦").glow().build();
            case CUFFS -> ItemBuilder.of(Material.IRON_BARS).icon("h_cuffs").name("<red><bold>Kajdanki").glow().build();
            case SIREN -> ItemBuilder.of(Material.BELL).icon("h_siren").name("<red><bold>ALARM!").glow().build();
        };
    }

    private void renderHeist(Rooms.Lobby l, List<UUID> crew) {
        Heist.Result hr = l.heist;
        List<HeistFrames.Frame> frames = l.frames;
        int idx = Math.min(l.step, frames.size() - 1);
        HeistFrames.Frame f = idx >= 0 ? frames.get(idx) : null;
        boolean done = l.sceneDone;

        // podłoga mapy: ulica na dole, skarbiec na górze, reszta to hol (przy alarmie cała mapa miga)
        for (int r = 0; r < 5; r++) {
            for (int c = 1; c <= 7; c++) {
                Material pane = r == 4 ? Material.BLACK_STAINED_GLASS_PANE : r == 0 && c >= 3 && c <= 5 ? Material.ORANGE_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
                if (f != null && f.flash() == 1) set(r * 9 + c, flash(Material.RED_STAINED_GLASS_PANE));
                else if (f != null && f.flash() == 2) set(r * 9 + c, flash(Material.YELLOW_STAINED_GLASS_PANE));
                else set(r * 9 + c, floor(pane));
            }
        }

        // rekwizyty i ekipa
        if (f == null) {
            set(HeistFrames.DOOR, propItem(HeistFrames.Prop.DOOR_CLOSED));
            set(HeistFrames.VAULT, propItem(HeistFrames.Prop.VAULT_CLOSED));
            set(HeistFrames.CAM_LEFT, propItem(HeistFrames.Prop.CAMERA));
            set(HeistFrames.CAM_RIGHT, propItem(HeistFrames.Prop.CAMERA));
        } else {
            f.props().forEach((slot, prop) -> set(slot, propItem(prop)));
            if (f.token() >= 0) {
                set(f.token(), ItemBuilder.of(Material.PLAYER_HEAD).icon("players").amount(f.alive()).name("<yellow><bold>Ekipa: " + f.alive() + (f.alive() == 1 ? " osoba" : " os."))
                        .lore("<gray>Idą razem, cicho i szybko.").glow().build());
            }
        }

        // portrety ekipy w skrajnych kolumnach: złapani dostają kajdanki
        for (int i = 0; i < crew.size() && i < HeistFrames.ROSTER.length; i++) {
            UUID p = crew.get(i);
            String name = T.esc(eco().pd(p).name);
            boolean caught = f != null && (f.caught() & (1 << i)) != 0;
            if (caught) {
                Heist.Beat where = hr.beats().stream().filter(b -> b.caught().contains(p)).findFirst().orElse(null);
                set(HeistFrames.ROSTER[i], ItemBuilder.of(Material.IRON_BARS).icon("h_cuffs").name("<red><bold>" + name + " <red>(złapany)")
                        .lore(where == null ? "" : "<gray>Etap: <white>" + Heist.STAGES.get(where.stage()).name(), "<gray>Traci wpisowe " + Fmt.money(l.stake)).build());
            } else {
                boolean safe = done && hr.survivors().contains(p);
                set(HeistFrames.ROSTER[i], ItemBuilder.of(Material.PLAYER_HEAD).head(Bukkit.getOfflinePlayer(p)).name((safe ? "<green><bold>" : "<yellow>") + name).glow(safe || !done)
                        .lore(safe ? "<green>Uciekł z łupem" : "<gray>W akcji").build());
            }
        }

        // dolny rząd: pasek łupu, narracja i wynik
        double mult = f == null ? Heist.BASE_MULT : f.mult();
        boolean down = f != null && f.alive() == 0;
        int level = (int) Math.max(1, Math.min(LOOT_METER.length, Math.round((mult - 1.0) / 1.6 * LOOT_METER.length)));
        String multLine = "<gray>Mnożnik łupu: <gold>x" + String.format(Locale.ROOT, "%.2f", mult).replace('.', ',');
        for (int i = 0; i < LOOT_METER.length; i++) {
            set(LOOT_METER[i], i < level
                    ? ItemBuilder.of(down ? Material.RED_CONCRETE : Material.GOLD_BLOCK).icon("h_loot").name(down ? "<red>Łup przepadł" : "<gold><bold>Łup").lore(multLine, "<gray>Pula ekipy: <gold>" + Fmt.money(l.pot)).build()
                    : ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).name("<dark_gray>Pusto").hidden().icon("blank").build());
        }
        String text = f == null ? "Ekipa rusza na akcję..." : f.text();
        set(46, ItemBuilder.of(Material.PAPER).icon("info").name("<yellow><bold>Co się dzieje").glow(f != null && f.flash() == 1).lore(T.wrap(text, 30, f != null && f.flash() == 1 ? "<red>" : "<white>")).build());
        if (done) set(53, resultItem(l));
    }

    private ItemStack resultItem(Rooms.Lobby l) {
        Heist.Result hr = l.heist;
        List<String> lore = new ArrayList<>();
        if (l.success) {
            lore.add("<gray>Łup: <gold>" + Fmt.money(l.payout) + " <gray>(x" + String.format(Locale.ROOT, "%.2f", hr.multiplier()).replace('.', ',') + " wpisowego)");
            lore.add("<gray>Udział każdego z " + hr.survivors().size() + ": <gold>" + Fmt.money(l.share));
            lore.add("<gray>Uciekli: <white>" + names(hr.survivors()));
        } else lore.add("<gray>Ekipa straciła wpisowe: <gold>" + Fmt.money(l.stake) + " <gray>od każdego.");
        if (!hr.caught().isEmpty()) lore.add("<gray>Złapani: <red>" + names(hr.caught()));
        lore.add(hr.survivors().contains(id) ? "<green>Uciekłeś z łupem!" : "<red>Wpadłeś i tracisz wpisowe.");
        return ItemBuilder.of(l.success ? Material.EMERALD_BLOCK : Material.REDSTONE_BLOCK).icon(l.success ? "check" : "cross").name(l.success ? "<green><bold>Napad udany!" : "<red><bold>Napad nieudany!").glow(l.success).lore(lore).build();
    }

    private void open(long stake) {
        String err = plugin.rooms().start(player, kind, stake);
        if (err != null) {
            Fx.deny(player);
            T.send(player, "<red>" + err);
        } else Fx.coin(player);
        redraw();
    }

    private void askStake() {
        plugin.prompts().ask(player, "<white>Jaka stawka jackpota? <gray>(np. 500, 2k)", text -> {
            long s = Fmt.parseAmount(text, eco().wallet(id));
            if (s < 1) T.send(player, "<red>Nie rozumiem stawki.");
            else {
                String err = plugin.rooms().start(player, kind, s);
                if (err != null) T.send(player, "<red>" + err);
            }
            new RoomGui(plugin, player, kind).open();
        }, () -> new RoomGui(plugin, player, kind).open());
    }

    private void join() {
        String err = plugin.rooms().join(player, kind);
        if (err != null) {
            Fx.deny(player);
            T.send(player, "<red>" + err);
        } else Fx.coin(player);
        redraw();
    }
}
