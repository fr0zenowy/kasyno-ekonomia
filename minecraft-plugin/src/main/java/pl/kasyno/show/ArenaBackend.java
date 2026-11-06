package pl.kasyno.show;

import pl.kasyno.show.Stage.P;

import java.util.UUID;

/**
 * Wszystko, co pokazy areny (pojedynek, jackpot) robią w świecie. Uchwyt postaci jest nieprzejrzysty (null = nie udało się jej
 * zrobić; pokaz idzie dalej). Prawdziwą implementacją jest BukkitBackend, w testach podstawiamy atrapę.
 */
public interface ArenaBackend {
    /** Co figura trzyma w ręku: nic, rewolwer (pojedynek) albo złoto (jackpot). */
    enum Hold { NONE, GUN, GOLD }

    /** Poza figury: stoi, przycupnęła (napięcie przed strzałem) albo leży (przegrany). */
    enum Pose { STAND, SNEAK, LIE }

    /** Dźwięki sceny. */
    enum Sfx { STEP, TENSION, COUNT, SHOT, HIT, FALL, TICK, FANFARE, WIN }

    /** Stawia figurę ze skórką gracza `skin`. */
    Object spawn(UUID skin, String name, P at, float yaw, Hold hold);

    void teleport(Object handle, P at, float yaw);

    void pose(Object handle, Pose pose);

    void swing(Object handle);

    /** Podświetlenie figury (obrys widoczny przez ściany). */
    void glow(Object handle, boolean on);

    void remove(Object handle);

    /** Dźwięk sceny w danym miejscu; `pitch` zmienia wysokość (odliczanie rośnie, losowanie zwalnia). */
    void sound(Sfx sfx, P at, float pitch);

    /** Efekt cząsteczek: "smoke", "dust", "shot", "hit", "pick", "gold", "firework". */
    void fx(String kind, P at);
}
