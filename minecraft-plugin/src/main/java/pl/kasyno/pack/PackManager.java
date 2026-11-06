package pl.kasyno.pack;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Paczka zasobów kasyna (własne tła okien i ikony). Plugin ma ją w środku, może sam ją udostępnić graczom (wbudowany serwer HTTP)
 * albo wskazać zewnętrzny link, śledzi, którzy gracze ją załadowali, i tylko im pokazuje okna w nowym wyglądzie. Reszta widzi
 * zwykłe okna, więc odmowa pobrania niczego nie psuje.
 */
public final class PackManager implements Listener {
    /** Układ paczki: tła okien (id → znak czcionki) i lista dostępnych ikon. Plik generuje tools/gen-pack.js. */
    public static final class Index {
        public String font = "kasyno:gui";
        public String shift = "";
        public Map<String, String> backgrounds = Map.of();
        public Set<String> icons = Set.of();
        public String namespace = "kasyno";
    }

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("kasyno-ekonomia-ui-pack".getBytes(StandardCharsets.UTF_8));
    private static final String ZIP_NAME = "KasynoUI.zip";

    private final KasynoPlugin plugin;
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private Index index = new Index();
    private byte[] zip;
    private byte[] sha1;
    private String url;
    private HttpServer http;
    private final java.util.concurrent.atomic.AtomicLong skinned = new java.util.concurrent.atomic.AtomicLong();

    public PackManager(KasynoPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- start i stop ----------

    public void enable() {
        try (InputStream in = plugin.getResource("pack/pack-index.json")) {
            if (in != null) {
                index = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Index.class);
                if (index.backgrounds == null) index.backgrounds = Map.of();
                if (index.icons == null) index.icons = Set.of();
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Nie udało się wczytać indeksu paczki zasobów: " + e);
        }
        try (InputStream in = plugin.getResource("pack/" + ZIP_NAME)) {
            if (in == null) {
                plugin.getLogger().warning("W pluginie nie ma paczki zasobów, okna zostaną zwykłe.");
                return;
            }
            zip = in.readAllBytes();
            sha1 = MessageDigest.getInstance("SHA-1").digest(zip);
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            plugin.getLogger().warning("Nie udało się wczytać paczki zasobów: " + e);
            return;
        }
        // kopia obok konfiguracji: można ją wgrać na własny hosting
        try {
            Path out = plugin.getDataFolder().toPath().resolve(ZIP_NAME);
            Files.createDirectories(out.getParent());
            Files.write(out, zip);
        } catch (IOException e) {
            plugin.getLogger().warning("Nie udało się zapisać " + ZIP_NAME + ": " + e);
        }
        if (!plugin.getConfig().getBoolean("resource-pack.enabled", true)) return;

        String configured = plugin.getConfig().getString("resource-pack.url", "").trim();
        if (!configured.isEmpty()) {
            url = configured;
            plugin.getLogger().info("Paczka zasobów: link z konfiguracji " + url);
        } else if (plugin.getConfig().getBoolean("resource-pack.host.enabled", true) && System.getProperty("kasyno.test") == null) {
            startHost();
        } else if (configured.isEmpty()) {
            plugin.getLogger().info("Paczka zasobów nie jest udostępniana (ustaw resource-pack.url albo resource-pack.host.enabled).");
        }
    }

    private void startHost() {
        int port = plugin.getConfig().getInt("resource-pack.host.port", 8123);
        String address = plugin.getConfig().getString("resource-pack.host.address", "").trim();
        if (address.isEmpty()) {
            String ip = Bukkit.getIp();
            address = ip == null || ip.isBlank() ? "127.0.0.1" : ip;
            plugin.getLogger().warning("resource-pack.host.address jest puste, używam " + address + ". Gracze spoza tej maszyny pobiorą paczkę dopiero po wpisaniu publicznego adresu serwera.");
        }
        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
            byte[] body = zip;
            http.createContext("/" + ZIP_NAME, ex -> {
                try (ex) {
                    ex.getResponseHeaders().set("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, body.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(body);
                    }
                }
            });
            http.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "KasynoUI-pack-host");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            url = "http://" + address + ":" + port + "/" + ZIP_NAME;
            plugin.getLogger().info("Paczka zasobów jest udostępniana pod " + url + " (port " + port + " TCP musi być otwarty dla graczy).");
        } catch (IOException e) {
            http = null;
            plugin.getLogger().warning("Nie udało się uruchomić serwera paczki na porcie " + port + ": " + e.getMessage()
                    + ". Ustaw inny port (resource-pack.host.port) albo wgraj " + ZIP_NAME + " na własny hosting i wpisz link w resource-pack.url.");
        }
    }

    public void disable() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        loaded.clear();
    }

    // ---------- stan i opis ----------

    public boolean available() {
        return zip != null;
    }

    public boolean serving() {
        return url != null;
    }

    public String url() {
        return url;
    }

    public String sha1Hex() {
        if (sha1 == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : sha1) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public boolean hasPack(Player p) {
        return loaded.contains(p.getUniqueId());
    }

    /** Czy ten gracz widzi okna w nowym wyglądzie (ma paczkę i nie wyłączono tego w konfiguracji). */
    public boolean custom(Player p) {
        String cfg = plugin.getConfig().getString("custom-gui", "auto");
        if (cfg.equalsIgnoreCase("off") || !available() || index.backgrounds.isEmpty()) return false;
        if (cfg.equalsIgnoreCase("on")) return true; // wymuszone dla wszystkich (np. paczka wgrana przez server.properties)
        String mine = plugin.eco().pd(p.getUniqueId()).guiMode;
        if ("off".equals(mine)) return false;
        if ("on".equals(mine)) return true; // gracz sam zadeklarował, że ma paczkę (np. wgrał ją ręcznie do folderu resourcepacks)
        return hasPack(p);
    }

    /** Ustawia wygląd okien dla gracza: "on", "off" albo null (automatycznie). */
    public void setMode(Player p, String mode) {
        plugin.eco().pd(p.getUniqueId()).guiMode = mode;
        plugin.store().touch(p.getUniqueId());
    }

    public String mode(Player p) {
        String m = plugin.eco().pd(p.getUniqueId()).guiMode;
        return m == null ? "auto" : m;
    }

    /** Oznacza gracza jako mającego paczkę (do testów i ręcznej obsługi). */
    public void markLoaded(Player p, boolean on) {
        if (on) loaded.add(p.getUniqueId());
        else loaded.remove(p.getUniqueId());
    }

    /** Ile ikon podmieniono na własne modele od startu (do diagnostyki i testów). */
    public long skinned() {
        return skinned.get();
    }

    public Index index() {
        return index;
    }

    // ---------- tytuł okna i ikony ----------

    /**
     * Tytuł okna z tłem z paczki: znak przesuwający tekst o 8 pikseli w lewo (tytuł zaczyna się 8 px od krawędzi), potem znak tła.
     * Jeśli paczka nie ma tła o tej nazwie, zostaje zwykły tytuł.
     */
    public Component title(String art, Component plain) {
        String glyph = art == null ? null : index.backgrounds.get(art);
        if (glyph == null) glyph = index.backgrounds.get("default");
        if (glyph == null) return plain;
        Key font = Key.key(index.font);
        return Component.text()
                .append(Component.text(index.shift).font(font).color(NamedTextColor.WHITE))
                .append(Component.text(glyph).font(font).color(NamedTextColor.WHITE))
                .build();
    }

    /** Podmienia model przedmiotu na własny z paczki, jeśli ikona ma znacznik i paczka ją zawiera. */
    public ItemStack skin(ItemStack item) {
        if (item == null || ItemBuilder.ICON_KEY == null) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String icon = meta.getPersistentDataContainer().get(ItemBuilder.ICON_KEY, PersistentDataType.STRING);
        if (icon == null || !index.icons.contains(icon)) return item;
        NamespacedKey model = NamespacedKey.fromString(index.namespace + ":" + icon);
        if (model == null) return item;
        ItemStack copy = item.clone();
        ItemMeta m = copy.getItemMeta();
        m.setItemModel(model);
        copy.setItemMeta(m);
        skinned.incrementAndGet();
        return copy;
    }

    // ---------- wysyłanie i zdarzenia ----------

    /** Wysyła graczowi paczkę (przy wejściu albo na życzenie). Zwraca false, gdy nie ma czego wysłać. */
    public boolean send(Player p) {
        if (!available() || url == null) return false;
        String prompt = plugin.getConfig().getString("resource-pack.prompt", "Paczka kasyna: własne okna i ikony.");
        boolean required = plugin.getConfig().getBoolean("resource-pack.required", false);
        String configuredSha = plugin.getConfig().getString("resource-pack.sha1", "").trim();
        byte[] hash = sha1;
        if (!configuredSha.isEmpty()) {
            try {
                hash = java.util.HexFormat.of().parseHex(configuredSha);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("resource-pack.sha1 nie jest poprawnym zapisem szesnastkowym, używam sumy z dołączonej paczki.");
            }
        }
        p.setResourcePack(PACK_ID, url, hash, T.MM.deserialize(prompt), required);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!available() || url == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) send(p);
        }, 20L);
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        if (!PACK_ID.equals(e.getID())) return;
        plugin.getLogger().info("Paczka zasobów, gracz " + e.getPlayer().getName() + ": " + e.getStatus());
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> loaded.add(e.getPlayer().getUniqueId());
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> loaded.remove(e.getPlayer().getUniqueId());
            default -> {
                // ACCEPTED, DOWNLOADED: czekamy na wynik ładowania
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        loaded.remove(e.getPlayer().getUniqueId());
    }

    /** Gracze z paczką (do podsumowania w komendzie administratora). */
    public Set<UUID> loadedPlayers() {
        return new HashSet<>(loaded);
    }
}
