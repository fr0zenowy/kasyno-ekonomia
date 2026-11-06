package pl.kasyno.gui;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;

import java.util.List;

/** Wspólne komunikaty na czacie: awans, cooldown, notatki z bonusów. */
public final class Notify {
    private Notify() {}

    public static void levelUp(Player p, Eco.LevelUp lv) {
        if (lv == null || !lv.leveled()) return;
        T.send(p, "<green><bold>Awans na poziom " + lv.level() + "!</bold> <gray>Bonus: <gold>" + Fmt.money(lv.bonus())
                + (lv.crates() > 0 ? " <gray>+ skrzynia skarbów" + (lv.crates() > 1 ? " x" + lv.crates() : "") : ""));
        Fx.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    public static void cooldown(Player p, long left) {
        T.send(p, "<red>Jeszcze " + Fmt.duration(left) + " cooldownu.");
        Fx.deny(p);
    }

    public static void notes(Player p, List<String> notes) {
        for (String n : notes) T.raw(p, "<dark_gray>  » <gray>" + T.esc(n));
    }

    public static void protection(Player p, Catalog.Protection pr, Catalog cat, String what) {
        T.send(p, "<aqua>" + cat.item(pr.id()).name() + " " + what);
    }
}
