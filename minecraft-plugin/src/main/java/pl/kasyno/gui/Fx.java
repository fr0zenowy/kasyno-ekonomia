package pl.kasyno.gui;

import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

/** Dźwięki i efekty (cząsteczki, fajerwerki) dla gier. */
public final class Fx {
    private Fx() {}

    public static void sound(Player p, Sound s, float volume, float pitch) {
        p.playSound(p.getLocation(), s, volume, pitch);
    }

    /** Napis nad paskiem życia (MiniMessage): sceny działają też wtedy, gdy gracz zamknął okno. */
    public static void actionBar(Player p, String mm) {
        try {
            p.sendActionBar(T.MM.deserialize(mm));
        } catch (RuntimeException ignored) {
            // efekt jest tylko dodatkiem
        }
    }

    /** Duży napis na środku ekranu (MiniMessage) z krótkim pojawianiem się i znikaniem. */
    public static void title(Player p, String titleMm, String subMm) {
        try {
            p.showTitle(net.kyori.adventure.title.Title.title(T.MM.deserialize(titleMm), T.MM.deserialize(subMm),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(150), java.time.Duration.ofMillis(1800), java.time.Duration.ofMillis(400))));
        } catch (RuntimeException ignored) {
            // efekt jest tylko dodatkiem
        }
    }

    public static void particles(Player p, Particle particle, int count) {
        try {
            p.spawnParticle(particle, p.getLocation().add(0, 1.2, 0), count, 0.5, 0.6, 0.5, 0.05);
        } catch (RuntimeException ignored) {
            // efekt jest tylko dodatkiem
        }
    }

    public static void click(Player p) {
        sound(p, Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    public static void tick(Player p, float pitch) {
        sound(p, Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, pitch);
    }

    public static void coin(Player p) {
        sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
    }

    public static void deny(Player p) {
        sound(p, Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
    }

    public static void ding(Player p, float pitch) {
        sound(p, Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, pitch);
    }

    public static void win(Player p) {
        sound(p, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        p.spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1.2, 0), 18, 0.5, 0.5, 0.5, 0.1);
    }

    public static void lose(Player p) {
        sound(p, Sound.ENTITY_VILLAGER_NO, 0.8f, 0.8f);
        p.spawnParticle(Particle.ANGRY_VILLAGER, p.getLocation().add(0, 1.8, 0), 5, 0.3, 0.2, 0.3, 0);
    }

    /** Duża wygrana (mnożnik >= 10): fajerwerki, totem i fanfary. */
    public static void jackpot(Player p) {
        sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 60, 0.6, 0.9, 0.6, 0.4);
        Location loc = p.getLocation();
        try {
            loc.getWorld().spawn(loc, Firework.class, fw -> {
                FireworkMeta meta = fw.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.YELLOW, Color.ORANGE).withFade(Color.WHITE).flicker(true).build());
                meta.setPower(0);
                fw.setFireworkMeta(meta);
                fw.setSilent(false);
            }).detonate();
        } catch (RuntimeException ignored) {
            // efekt jest tylko dodatkiem
        }
    }

    /** Efekt wyniku gry zależnie od mnożnika wypłaty. */
    public static void outcome(Player p, long net, double mult) {
        if (net > 0) {
            if (mult >= 10) jackpot(p);
            else win(p);
        } else if (net < 0) lose(p);
        else sound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 1f);
    }
}
