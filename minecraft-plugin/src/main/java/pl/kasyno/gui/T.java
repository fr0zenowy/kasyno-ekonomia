package pl.kasyno.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Teksty: MiniMessage bez domyślnej kursywy (nazwy i opisy przedmiotów w GUI) oraz wiadomości na czacie. */
public final class T {
    private T() {}

    public static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<gold>[Kasyno]</gold> ";

    /** Tekst do nazwy albo opisu przedmiotu: wyłączona kursywa. */
    public static Component c(String mm) {
        return MM.deserialize("<!i>" + mm);
    }

    public static List<Component> lines(List<String> mm) {
        List<Component> out = new ArrayList<>(mm.size());
        for (String s : mm) out.add(c(s));
        return out;
    }

    /** Ucieka znaczniki MiniMessage w tekście od użytkownika (nick, wpisana liczba). */
    public static String esc(String s) {
        return MM.escapeTags(s);
    }

    public static void send(Player p, String mm) {
        p.sendMessage(MM.deserialize(PREFIX + mm));
    }

    public static void raw(Player p, String mm) {
        p.sendMessage(MM.deserialize(mm));
    }

    /** Zawija długi opis na linie o zadanej szerokości, każda w danym kolorze (np. "<gray>"). */
    public static List<String> wrap(String text, int width, String color) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : esc(text).split(" ")) {
            if (line.length() + word.length() + 1 > width && line.length() > 0) {
                out.add(color + line);
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(color + line);
        return out;
    }

    /** Czysty tekst bez formatowania (do testów i logów). */
    public static String plain(Component c) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c);
    }
}
