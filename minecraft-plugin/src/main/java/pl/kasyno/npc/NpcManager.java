package pl.kasyno.npc;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.games.GameRegistry;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Screens;
import pl.kasyno.gui.T;
import pl.kasyno.work.JobDef;
import pl.kasyno.work.Jobs;
import pl.kasyno.work.WorkService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NPC-kasjerzy: wieśniacy bez AI, niezniszczalni, z napisem nad głową. Kliknięcie prawym przyciskiem otwiera wybrane menu
 * albo grę. Typ NPC zapisuje się w danych wieśniaka (PersistentDataContainer), więc przeżywa restart serwera.
 */
public final class NpcManager implements Listener {
    private record Look(String label, Villager.Profession profession) {}

    private static final Map<String, Look> LOOKS = Map.ofEntries(
            Map.entry("menu", new Look("Kasjer", Villager.Profession.LIBRARIAN)),
            Map.entry("kasyno", new Look("Krupier", Villager.Profession.CLERIC)),
            Map.entry("automaty", new Look("Krupier: Automaty", Villager.Profession.WEAPONSMITH)),
            Map.entry("karty", new Look("Krupier: Karty", Villager.Profession.FLETCHER)),
            Map.entry("ryzyko", new Look("Krupier: Ryzyko", Villager.Profession.ARMORER)),
            Map.entry("live", new Look("Gospodarz stołów", Villager.Profession.CARTOGRAPHER)),
            Map.entry("zarobki", new Look("Pośrednik pracy", Villager.Profession.TOOLSMITH)),
            Map.entry("sklep", new Look("Sprzedawca", Villager.Profession.CARTOGRAPHER)),
            Map.entry("ekwipunek", new Look("Magazynier", Villager.Profession.LEATHERWORKER)),
            Map.entry("bank", new Look("Bankier", Villager.Profession.LIBRARIAN)),
            Map.entry("biznes", new Look("Doradca biznesowy", Villager.Profession.MASON)),
            Map.entry("skrzynie", new Look("Skrzyniarz", Villager.Profession.BUTCHER)),
            Map.entry("ranking", new Look("Sędzia rankingu", Villager.Profession.SHEPHERD)),
            Map.entry("profil", new Look("Urzędnik", Villager.Profession.NITWIT)),
            Map.entry("pokoj", new Look("Gospodarz", Villager.Profession.FISHERMAN)),
            Map.entry("pomoc", new Look("Informator", Villager.Profession.NITWIT)),
            Map.entry("wiezienie", new Look("Strażnik więzienny", Villager.Profession.ARMORER)));

    private static final List<Villager.Profession> GAME_LOOKS = List.of(Villager.Profession.CLERIC, Villager.Profession.ARMORER, Villager.Profession.WEAPONSMITH,
            Villager.Profession.FLETCHER, Villager.Profession.BUTCHER, Villager.Profession.CARTOGRAPHER);

    /** Zawody NPC od prac w terenie (wygląd wieśniaka). */
    private static final Map<String, Villager.Profession> JOB_LOOKS = Map.of(
            "lesnik", Villager.Profession.FARMER,
            "gornik", Villager.Profession.MASON,
            "rybak", Villager.Profession.FISHERMAN,
            "sprzatacz", Villager.Profession.SHEPHERD,
            "kurier", Villager.Profession.CARTOGRAPHER,
            "poszukiwacz", Villager.Profession.TOOLSMITH);

    private final KasynoPlugin plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey holoKey;
    private final NamespacedKey radiusKey;

    public NpcManager(KasynoPlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "npc_type");
        this.holoKey = new NamespacedKey(plugin, "npc_holo");
        this.radiusKey = new NamespacedKey(plugin, "npc_radius");
    }

    /** Nazwy typów, jakie da się zespawnić (menu, ekrany, wszystkie gry i prace w terenie). */
    public List<String> types() {
        return Screens.allKeys();
    }

    public String labelOf(String type) {
        Look l = LOOKS.get(type);
        if (l != null) return l.label();
        JobDef job = Jobs.find(type);
        if (job != null) return job.npc();
        GameRegistry.Def g = GameRegistry.find(type);
        return g == null ? type : "Krupier: " + g.name();
    }

    private Villager.Profession professionOf(String type) {
        Look l = LOOKS.get(type);
        if (l != null) return l.profession();
        Villager.Profession job = JOB_LOOKS.get(type);
        if (job != null) return job;
        return GAME_LOOKS.get(Math.floorMod(type.hashCode(), GAME_LOOKS.size()));
    }

    /** Promień pracy zapisany w NPC (0, gdy nie ustawiono: wtedy obowiązuje domyślny dla tej pracy). */
    public int radiusOf(Entity e) {
        Integer r = e.getPersistentDataContainer().get(radiusKey, PersistentDataType.INTEGER);
        return r == null ? 0 : r;
    }

    /** Ustawia promień pracy NPC (0 przywraca domyślny). */
    public void setRadius(Entity e, int radius) {
        if (radius <= 0) e.getPersistentDataContainer().remove(radiusKey);
        else e.getPersistentDataContainer().set(radiusKey, PersistentDataType.INTEGER, radius);
    }

    /** Stawia NPC w podanym miejscu, zwrócony w stronę kąta `yaw`. Zwraca null dla nieznanego typu. */
    public Villager spawn(Location loc, String type, float yaw) {
        return spawn(loc, type, yaw, 0);
    }

    /** To samo, z promieniem pracy dla NPC od prac w terenie (0 = domyślny). */
    public Villager spawn(Location loc, String type, float yaw, int radius) {
        if (!Screens.exists(type)) return null;
        World world = loc.getWorld();
        String label = labelOf(type);
        JobDef job = Jobs.find(type);
        String hint = job == null ? "Kliknij prawym przyciskiem" : "Praca: " + job.title();
        String color = plugin.getConfig().getString("npc.name-color", "<gold>");
        Location at = loc.clone();
        at.setYaw(yaw);
        at.setPitch(0);
        Villager v = world.spawn(at, Villager.class, npc -> {
            npc.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type);
            if (job != null && radius > 0) npc.getPersistentDataContainer().set(radiusKey, PersistentDataType.INTEGER, WorkService.clampRadius(radius));
            npc.setAI(false);
            npc.setInvulnerable(true);
            npc.setPersistent(true);
            // Pozostałe ustawienia są kosmetyczne: gdyby któreś nie było dostępne, NPC i tak ma działać.
            quiet(() -> npc.setSilent(true));
            quiet(() -> npc.setRemoveWhenFarAway(false));
            quiet(() -> npc.setCollidable(false));
            quiet(() -> npc.setCanPickupItems(false));
            quiet(() -> npc.setProfession(professionOf(type)));
            quiet(() -> npc.setVillagerLevel(5));
            quiet(() -> npc.customName(T.MM.deserialize(color + label)));
            quiet(() -> npc.setCustomNameVisible(false));
        });
        try {
            TextDisplay holo = world.spawn(at.clone().add(0, 2.45, 0), TextDisplay.class, t -> {
                Component text = T.MM.deserialize(color + "<bold>" + label + "</bold><newline><gray>" + T.esc(hint));
                t.text(text);
                t.setBillboard(Display.Billboard.CENTER);
                t.setShadowed(true);
                t.setPersistent(true);
                t.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, "1");
            });
            v.getPersistentDataContainer().set(holoKey, PersistentDataType.STRING, holo.getUniqueId().toString());
        } catch (RuntimeException e) {
            // Bez napisu nad głową NPC nadal działa, a nazwę widać po najechaniu (customName).
            quiet(() -> v.setCustomNameVisible(true));
        }
        return v;
    }

    private static void quiet(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // ustawienie kosmetyczne
        }
    }

    public boolean isNpc(Entity e) {
        return e instanceof Villager && e.getPersistentDataContainer().has(typeKey, PersistentDataType.STRING);
    }

    public String typeOf(Entity e) {
        return e.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
    }

    /** Wszystkie NPC w załadowanych chunkach. */
    public List<Villager> all() {
        List<Villager> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) for (Villager v : w.getEntitiesByClass(Villager.class)) if (isNpc(v)) out.add(v);
        return out;
    }

    public int count() {
        return all().size();
    }

    /** Usuwa NPC razem z napisem nad głową. */
    public void remove(Villager v) {
        String holo = v.getPersistentDataContainer().get(holoKey, PersistentDataType.STRING);
        if (holo != null) {
            try {
                Entity h = Bukkit.getEntity(UUID.fromString(holo));
                if (h != null) h.remove();
            } catch (IllegalArgumentException ignored) {
                // uszkodzony wpis
            }
        }
        v.remove();
    }

    /** Najbliższy NPC w promieniu `range` bloków od gracza (albo ten, na który patrzy). */
    public Villager nearest(Player p, double range) {
        Entity target = null;
        try {
            target = p.getTargetEntity((int) Math.ceil(range));
        } catch (RuntimeException ignored) {
            // bez promienia patrzenia szukamy po prostu najbliższego NPC
        }
        if (target != null && isNpc(target)) return (Villager) target;
        Villager best = null;
        double bestDist = range * range;
        for (Entity e : p.getNearbyEntities(range, range, range)) {
            if (!isNpc(e)) continue;
            double d = e.getLocation().distanceSquared(p.getLocation());
            if (d <= bestDist) {
                best = (Villager) e;
                bestDist = d;
            }
        }
        return best;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!isNpc(e.getRightClicked())) return;
        e.setCancelled(true); // bez okna handlu wieśniaka
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("kasyno.use")) {
            T.send(p, "<red>Nie masz uprawnień do kasyna.");
            return;
        }
        String type = typeOf(e.getRightClicked());
        JobDef job = Jobs.find(type);
        if (job != null && e.getRightClicked() instanceof Villager v) plugin.work().remember(p, job, plugin.work().siteOf(v, job));
        Fx.click(p);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!Screens.open(plugin, p, type)) T.send(p, "<red>Ten NPC jest źle skonfigurowany (typ: " + T.esc(String.valueOf(type)) + ").");
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (isNpc(e.getEntity())) e.setCancelled(true);
    }
}
