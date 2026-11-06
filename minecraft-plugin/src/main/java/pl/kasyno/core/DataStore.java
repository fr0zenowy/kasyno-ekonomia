package pl.kasyno.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Zapis danych w plikach JSON (jeden plik na gracza + global.json). Dane trzymamy w pamięci, a na dysk trafiają
 * zmienione rekordy co jakiś czas i przy wyłączaniu. Zapis jest atomowy (plik tymczasowy + przeniesienie).
 */
public final class DataStore {
    /** Dane wspólne dla wszystkich graczy: loteria i historia crasha. */
    public static final class Global {
        public long lotteryNext;
        public String lotteryLastName;
        public long lotteryLastAmount;
        public List<Double> crashHistory = new ArrayList<>();
    }

    private final Path playersDir;
    private final Path globalFile;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Logger log;
    private final long startBalance;
    private final Map<UUID, PlayerData> cache = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private Global global = new Global();
    private boolean globalDirty;

    public DataStore(Path folder, Logger log, long startBalance) {
        this.playersDir = folder.resolve("players");
        this.globalFile = folder.resolve("global.json");
        this.log = log;
        this.startBalance = startBalance;
        try {
            Files.createDirectories(playersDir);
        } catch (IOException e) {
            throw new IllegalStateException("Nie można utworzyć folderu danych: " + playersDir, e);
        }
    }

    /** Wczytuje wszystkie zapisane rekordy (potrzebne do rankingu). */
    public void loadAll() {
        try (Stream<Path> files = Files.list(playersDir)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".json"))::iterator) {
                String name = p.getFileName().toString();
                try {
                    UUID id = UUID.fromString(name.substring(0, name.length() - 5));
                    PlayerData d = gson.fromJson(Files.readString(p, StandardCharsets.UTF_8), PlayerData.class);
                    if (d != null) cache.put(id, d);
                } catch (Exception e) {
                    log.warning("Pomijam uszkodzony plik gracza " + name + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warning("Nie można odczytać folderu graczy: " + e.getMessage());
        }
        try {
            if (Files.exists(globalFile)) {
                Global g = gson.fromJson(Files.readString(globalFile, StandardCharsets.UTF_8), Global.class);
                if (g != null) global = g;
            }
        } catch (Exception e) {
            log.warning("Nie można odczytać global.json: " + e.getMessage());
        }
    }

    /** Rekord gracza; nowy gracz dostaje kwotę startową. */
    public PlayerData get(UUID id) {
        return cache.computeIfAbsent(id, k -> {
            PlayerData d = new PlayerData();
            d.wallet = startBalance;
            d.created = System.currentTimeMillis();
            dirty.add(k);
            return d;
        });
    }

    public boolean exists(UUID id) {
        return cache.containsKey(id);
    }

    public Map<UUID, PlayerData> all() {
        return cache;
    }

    public Global global() {
        return global;
    }

    public void touch(UUID id) {
        dirty.add(id);
    }

    public void touchGlobal() {
        globalDirty = true;
    }

    /** Zapisuje zmienione rekordy. Serializacja idzie w wątku głównym, a pliki może zapisać dowolny wątek. */
    public List<Runnable> snapshotDirty() {
        List<Runnable> jobs = new ArrayList<>();
        for (UUID id : dirty) {
            PlayerData d = cache.get(id);
            if (d == null) continue;
            String json = gson.toJson(d);
            Path target = playersDir.resolve(id + ".json");
            jobs.add(() -> write(target, json));
        }
        dirty.clear();
        if (globalDirty) {
            String json = gson.toJson(global);
            jobs.add(() -> write(globalFile, json));
            globalDirty = false;
        }
        return jobs;
    }

    /** Zapis synchroniczny wszystkiego, co zmienione (wyłączanie pluginu, testy). */
    public void flush() {
        snapshotDirty().forEach(Runnable::run);
    }

    private void write(Path target, String json) {
        try {
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.severe("Nie udało się zapisać " + target.getFileName() + ": " + e.getMessage());
        }
    }
}
