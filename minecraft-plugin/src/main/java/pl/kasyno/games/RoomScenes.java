package pl.kasyno.games;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Heist;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.T;
import pl.kasyno.gui.screens.DuelGui;
import pl.kasyno.show.ArenaCaptions;
import pl.kasyno.show.JackpotRunner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Odtwarzanie scen gier z innymi graczami: napad na bank (film złożony z klatek, zob. HeistFrames) i losowanie jackpota
 * (podświetlenie krąży po graczach i zwalnia na zwycięzcy). Napad, pojedynek i jackpot mają też wersję w prawdziwym świecie
 * (heistWorld, duelWorld, jackpotWorld: figury ze skórkami graczy), a okno służy jako zapas, gdy sceny nie ma.
 * Wynik jest już rozliczony, więc scena tylko go odgrywa: stan
 * (step) trzyma lobby, okno go rysuje, a ten kod przesuwa scenę klatka po klatce i gra dźwięki, napisy nad paskiem życia
 * i tytuły każdemu z ekipy, nawet gdy zamknął okno.
 */
final class RoomScenes {
    private RoomScenes() {}

    static final int INTRO = 40;
    static final int JACKPOT_FRAMES = JackpotRunner.FRAMES;

    private static void crew(Rooms.Lobby l, Consumer<Player> action) {
        for (UUID id : l.crew) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) action.accept(p);
        }
    }

    // ---------- napad ----------

    static void heist(KasynoPlugin plugin, Rooms rooms, Rooms.Lobby l) {
        List<HeistFrames.Frame> frames = l.frames;
        crew(l, p -> {
            Fx.title(p, "<red><bold>NAPAD NA BANK", "<gray>Ekipa: " + l.crew.size() + " os. • łup: wpisowe x" + String.format(java.util.Locale.ROOT, "%.1f", Heist.BASE_MULT).replace('.', ',') + " i więcej");
            Fx.actionBar(p, "<yellow>Czarny van rusza pod bank...");
            Fx.sound(p, Sound.ENTITY_MINECART_RIDING, 0.8f, 0.9f);
        });
        int t = INTRO;
        String[] shown = {""};
        for (int i = 0; i < frames.size(); i++) {
            final int idx = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> playFrame(l, idx, shown, p -> true), t);
            t += frames.get(i).ticks();
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> finish(rooms, l, frames.size() - 1), t + 10L);
    }

    private static void cue(Player p, HeistFrames.Cue cue, int frameIdx) {
        switch (cue) {
            case ENGINE -> Fx.sound(p, Sound.ENTITY_MINECART_RIDING, 0.6f, 0.8f + 0.04f * frameIdx);
            case STEP -> Fx.sound(p, Sound.BLOCK_STONE_STEP, 0.5f, 0.9f + 0.1f * (frameIdx % 3));
            case DOOR -> Fx.sound(p, Sound.BLOCK_IRON_DOOR_OPEN, 0.8f, 1f);
            case CAMERA -> Fx.sound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.4f, 1.7f);
            case DRILL -> Fx.sound(p, Sound.BLOCK_GRINDSTONE_USE, 0.7f, 1.2f);
            case BOOM -> Fx.sound(p, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1f);
            case BELL -> {
                Fx.sound(p, Sound.BLOCK_BELL_USE, 1f, frameIdx % 2 == 0 ? 1.4f : 1.1f);
                Fx.sound(p, Sound.ENTITY_GENERIC_EXPLODE, 0.3f, 1.4f);
            }
            case COIN -> {
                Fx.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.9f, 1.4f + 0.05f * (frameIdx % 4));
                Fx.particles(p, Particle.HAPPY_VILLAGER, 5);
            }
            case CUFF -> {
                Fx.sound(p, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 1f, 0.8f);
                Fx.sound(p, Sound.ENTITY_PLAYER_HURT, 0.7f, 0.9f);
            }
            case GUARD -> Fx.sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
            case ESCAPE -> Fx.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
            default -> {
            }
        }
    }

    private static void playFrame(Rooms.Lobby l, int idx, String[] shown, Predicate<Player> sounds) {
        if (l.sceneDone) return;
        l.step = idx;
        HeistFrames.Frame f = l.frames.get(idx);
        boolean newText = !f.text().equals(shown[0]);
        shown[0] = f.text();
        crew(l, p -> {
            if (newText) Fx.actionBar(p, (f.flash() == 1 ? "<red>" : "<yellow>") + T.esc(f.text()));
            if (sounds.test(p)) cue(p, f.cue(), idx);
            if (f.title() != null) Fx.title(p, f.title(), "<gray>Policja jest w drodze!");
            for (int who : f.newly()) {
                if (who >= 0 && who < l.crew.size() && l.crew.get(who).equals(p.getUniqueId())) {
                    Fx.title(p, "<red><bold>ZŁAPANY!", "<gray>Tracisz wpisowe " + Fmt.money(l.stake));
                    Fx.particles(p, Particle.ANGRY_VILLAGER, 6);
                }
            }
        });
    }

    /**
     * Napad w prawdziwym świecie: ekipa jako figury ze skórkami graczy podjeżdża pod bank i go okrada na oczach wszystkich, którzy
     * stoją w pobliżu. Zwraca false, gdy sceny nie ma albo nie da się jej odpalić (wtedy gra się film w oknie z mapą).
     */
    static boolean heistWorld(KasynoPlugin plugin, Rooms rooms, Rooms.Lobby l) {
        List<String> names = new ArrayList<>();
        for (UUID id : l.crew) names.add(nameOf(id));
        String[] shown = {""};
        boolean started = plugin.show().play(l.frames, l.crew, names, idx -> playFrame(l, idx, shown, p -> !plugin.show().near(p)), () -> finish(rooms, l, l.frames.size() - 1));
        if (!started) return false;
        String link = "<click:run_command:'/kasyno bank tp'><aqua><bold>[ZOBACZ NA ŻYWO]</bold></aqua></click>";
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.hasPermission("kasyno.use")) continue;
            boolean mine = l.crew.contains(p.getUniqueId());
            T.send(p, mine ? "<yellow>Twoja ekipa rusza na napad! Zobacz, jak wchodzą do banku: " + link : "<yellow>Napad na bank trwa na żywo! " + link);
            if (mine) Fx.title(p, "<red><bold>NAPAD NA BANK", "<gray>Twoja ekipa wchodzi do banku. Patrz uważnie!");
        }
        return true;
    }

    private static String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }

    // ---------- arena: pojedynek i jackpot w prawdziwym świecie ----------

    /** Zaproszenie na arenę: uczestnicy dostają link na trybunę z tytułem, reszta serwera zwykłe zaproszenie do oglądania. */
    private static void inviteToArena(Collection<UUID> participants, String forParticipants, String forOthers, String title, String sub) {
        String link = "<click:run_command:'/kasyno arena tp'><aqua><bold>[ZOBACZ NA ŻYWO]</bold></aqua></click>";
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.hasPermission("kasyno.use")) continue;
            boolean mine = participants.contains(p.getUniqueId());
            T.send(p, (mine ? forParticipants : forOthers) + " " + link);
            if (mine) Fx.title(p, title, sub);
        }
    }

    /**
     * Pojedynek w prawdziwym świecie: dwie figury ze skórkami graczy stają naprzeciw siebie na arenie, odliczanie, strzał i jeden pada.
     * Wynik jest już rozliczony, a `announce` (podsumowanie na czacie) idzie raz, po pokazie. Zwraca false, gdy areny nie ma albo
     * jest zajęta (wtedy pojedynek gra się w oknie).
     */
    static boolean duelWorld(KasynoPlugin plugin, DuelGui.Result r, Runnable announce) {
        String a = nameOf(r.a());
        String b = nameOf(r.b());
        boolean started = plugin.show().playDuel(r.a(), a, r.b(), b, r.winner(), DuelGui.waitTicks(r), ArenaCaptions.to(plugin.show(), List.of(r.a(), r.b())), announce);
        if (!started) return false;
        String vs = "<white>" + T.esc(a) + "</white> kontra <white>" + T.esc(b) + "</white> o <gold>" + Fmt.money(r.stake() * 2) + "</gold>";
        inviteToArena(List.of(r.a(), r.b()), "<yellow>Pojedynek " + vs + " rusza na arenie! Wejdź na trybunę:", "<yellow>Pojedynek " + vs + " trwa na arenie!",
                "<red><bold>POJEDYNEK", "<gray>Stajecie naprzeciw siebie na arenie");
        return true;
    }

    /**
     * Jackpot w prawdziwym świecie: uczestnicy jako figury na okręgu, podświetlenie krąży po nich i zwalnia na zwycięzcy, który dostaje
     * fajerwerki. Zwraca false, gdy areny nie ma albo jest zajęta (wtedy losowanie gra się w oknie).
     */
    static boolean jackpotWorld(KasynoPlugin plugin, Rooms rooms, Rooms.Lobby l) {
        List<String> names = new ArrayList<>();
        for (UUID id : l.crew) names.add(nameOf(id));
        boolean started = plugin.show().playJackpot(l.crew, names, l.spin, ArenaCaptions.to(plugin.show(), l.crew), k -> {
            if (!l.sceneDone) l.step = k;
        }, () -> finish(rooms, l, l.spin.length - 1));
        if (!started) return false;
        inviteToArena(l.crew, "<yellow>Losowanie jackpota rusza na arenie! Pula: <gold>" + Fmt.money(l.pot) + "</gold>. Zobacz, na kim stanie podświetlenie:",
                "<yellow>Losowanie jackpota (pula <gold>" + Fmt.money(l.pot) + "</gold>) trwa na arenie!",
                "<gold><bold>JACKPOT", "<gray>Pula: <gold>" + Fmt.money(l.pot));
        return true;
    }

    // ---------- jackpot ----------

    /** Klatki losowania: indeksy uczestników, a ostatnia wypada na zwycięzcy. */
    static int[] spinFrames(int crewSize, int winnerIdx) {
        return JackpotRunner.spinFrames(crewSize, winnerIdx);
    }

    static void jackpot(KasynoPlugin plugin, Rooms rooms, Rooms.Lobby l) {
        crew(l, p -> {
            Fx.title(p, "<gold><bold>JACKPOT", "<gray>Pula: <gold>" + Fmt.money(l.pot));
            Fx.actionBar(p, "<yellow>Losowanie... komu spadnie cała pula?");
        });
        int t = 30;
        for (int k = 0; k < l.spin.length; k++) {
            final int frame = k;
            double q = k / (double) (l.spin.length - 1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (l.sceneDone) return;
                l.step = frame;
                float pitch = 0.8f + 0.8f * (float) Math.sin(Math.PI * Math.min(1, frame / (double) l.spin.length * 1.6));
                crew(l, p -> {
                    Fx.tick(p, Math.max(0.6f, pitch));
                    Fx.actionBar(p, "<gold>▶ <white><bold>" + T.esc(nameAt(l, frame)) + "</bold> <gold>◀");
                    if (frame == l.spin.length - 1) Fx.actionBar(p, "<gold><bold>" + T.esc(winnerName(l)) + " zgarnia pulę!");
                });
            }, t);
            t += Math.round(2 + 8 * q * q);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> finish(rooms, l, l.spin.length - 1), t + 30L);
    }

    private static String nameAt(Rooms.Lobby l, int frame) {
        UUID id = l.crew.get(l.spin[frame]);
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "?" : n;
    }

    private static String winnerName(Rooms.Lobby l) {
        Player p = Bukkit.getPlayer(l.winner);
        if (p != null) return p.getName();
        String n = Bukkit.getOfflinePlayer(l.winner).getName();
        return n == null ? "?" : n;
    }

    private static void finish(Rooms rooms, Rooms.Lobby l, int lastStep) {
        if (l.sceneDone) return;
        l.step = lastStep;
        l.sceneDone = true;
        rooms.announce(l);
    }
}
