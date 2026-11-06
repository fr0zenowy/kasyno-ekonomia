package pl.kasyno.work;

import org.bukkit.entity.Player;
import pl.kasyno.show.Stage.P;
import pl.kasyno.work.JobDef.NodeType;

import java.util.UUID;

/** Wszystko, co praca robi w świecie: stawia świecące cele widoczne tylko dla jednego gracza, rusza je, usuwa i pokazuje efekty. */
public interface NodeBackend {
    /** Postawiony cel: nieprzejrzysty uchwyt i identyfikator, który przychodzi w zdarzeniu kliknięcia. */
    record Spawned(Object handle, UUID clickId) {}

    /** Stawia cel dla `owner`. Gdy `visible` jest false, gracz zobaczy go dopiero po reveal (skarb widać z bliska). Null, gdy się nie udało. */
    Spawned spawn(Player owner, NodeType type, P at, boolean visible);

    void reveal(Player owner, Object handle);

    void move(Object handle, P at);

    /** Obrót ikony celu (krok animacji). */
    void spin(Object handle, int step);

    void remove(Object handle);

    /** Iskry nad celem, widoczne tylko dla właściciela. */
    void sparkle(Player owner, P at, boolean rare);

    /** Unoszący się napis (np. „+45 $”), widoczny tylko dla właściciela; sam znika po chwili. */
    void popup(Player owner, String miniMessage, P at);

    /** Sprząta wszystkie cele zostawione po awarii albo przerwanej pracy. */
    default void purge() {}
}
