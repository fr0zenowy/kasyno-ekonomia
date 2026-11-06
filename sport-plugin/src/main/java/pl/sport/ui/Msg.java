package pl.sport.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;

/** Wiadomości do graczy: MiniMessage, tytuły i dźwięki. */
public final class Msg {
    private Msg() {}

    public static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<gold>[Sport]</gold> <gray>";

    public static Component c(String mm) {
        return MM.deserialize(mm);
    }

    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Ucieka znaczniki MiniMessage w tekście od użytkownika (nazwa boiska, nick). */
    public static String esc(String s) {
        return MM.escapeTags(s);
    }

    public static void send(CommandSender to, String mm) {
        to.sendMessage(c(PREFIX + mm));
    }

    public static void error(CommandSender to, String mm) {
        to.sendMessage(c(PREFIX + "<red>" + mm));
    }

    public static void actionBar(Player p, String mm) {
        p.sendActionBar(c(mm));
    }

    public static void title(Player p, String title, String subtitle, double fadeIn, double stay, double fadeOut) {
        p.showTitle(Title.title(c(title), c(subtitle), Title.Times.times(Duration.ofMillis((long) (fadeIn * 1000)), Duration.ofMillis((long) (stay * 1000)), Duration.ofMillis((long) (fadeOut * 1000)))));
    }

    public static void sound(Player p, Sound s, float volume, float pitch) {
        p.playSound(p.getLocation(), s, volume, pitch);
    }
}
