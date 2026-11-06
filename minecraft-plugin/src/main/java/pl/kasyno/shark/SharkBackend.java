package pl.kasyno.shark;

import org.bukkit.World;

/** Stawianie rekina w świecie. Wersja prawdziwa składa go z BlockDisplay; testy podstawiają atrapę, która tylko zapisuje klatki. */
public interface SharkBackend {
    /** Stawia rekina w pozie z klatki i zwraca uchwyt do dalszych poleceń. */
    Object spawn(World world, SharkChoreo.Frame frame);

    /** Przesuwa i układa rekina według klatki. */
    void update(Object handle, SharkChoreo.Frame frame);

    /** Usuwa rekina. */
    void remove(Object handle);

    /** Usuwa wszystkie zabłąkane rekiny w świecie (np. po przeładowaniu pluginu). Zwraca, ile bryłek usunięto. */
    int purge();
}
