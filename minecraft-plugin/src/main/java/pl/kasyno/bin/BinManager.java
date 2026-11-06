package pl.kasyno.bin;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.bin.BinModel.Xform;
import pl.kasyno.bin.BinType.Part;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Notify;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Śmietniki w świecie: modele z bloków (BlockDisplay) i niewidzialne pole kliknięcia (Interaction), które administrator stawia
 * komendą. Każdy śmietnik ma własny identyfikator i własną przerwę, a przerwa liczy się osobno dla każdego gracza i każdego
 * śmietnika, więc przeszukanie jednego nie blokuje innych, a dwóch graczy nie wchodzi sobie w drogę. Wszystko zapisuje się
 * w danych encji (PersistentDataContainer) i przeżywa restart serwera.
 */
public final class BinManager implements Listener {
    public enum Status { OK, MISS, COOLDOWN }

    /** Wynik przeszukania: łup (przy OK), ile jeszcze trwa przerwa (przy COOLDOWN) i ewentualny awans. */
    public record Result(Status status, long left, String item, int qty, Eco.LevelUp lv) {}

    public static final int MIN_COOLDOWN = 10;
    public static final int MAX_COOLDOWN = 3600;
    /** Tyle trwa otwarcie wieka po przeszukaniu (ticki). */
    static final long LID_TICKS = 30;

    private final KasynoPlugin plugin;
    private final NamespacedKey typeKey;
    private final NamespacedKey idKey;
    private final NamespacedKey cooldownKey;
    private final NamespacedKey yawKey;
    private final NamespacedKey partKey;
    private final NamespacedKey lidKey;
    private final NamespacedKey indexKey;
    private BukkitTask hint;
    private boolean warned;

    public BinManager(KasynoPlugin plugin) {
        this.plugin = plugin;
        this.typeKey = new NamespacedKey(plugin, "bin_type");
        this.idKey = new NamespacedKey(plugin, "bin_id");
        this.cooldownKey = new NamespacedKey(plugin, "bin_cd");
        this.yawKey = new NamespacedKey(plugin, "bin_yaw");
        this.partKey = new NamespacedKey(plugin, "bin_part");
        this.lidKey = new NamespacedKey(plugin, "bin_lid");
        this.indexKey = new NamespacedKey(plugin, "bin_idx");
    }

    public void enable() {
        hint = Bukkit.getScheduler().runTaskTimer(plugin, this::hint, 40L, 40L);
    }

    public void disable() {
        if (hint != null) hint.cancel();
        hint = null;
    }

    private static void quiet(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // ustawienie kosmetyczne
        }
    }

    // ---------- dane śmietnika ----------

    public boolean isBin(Entity e) {
        return e instanceof Interaction && e.getPersistentDataContainer().has(idKey, PersistentDataType.STRING);
    }

    public String idOf(Entity e) {
        return e.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public BinType typeOf(Entity e) {
        String id = e.getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        return id == null ? null : BinType.find(id);
    }

    /** Przerwa tego konkretnego śmietnika w sekundach (każdy ma własną). */
    public int cooldownOf(Entity e) {
        Integer c = e.getPersistentDataContainer().get(cooldownKey, PersistentDataType.INTEGER);
        BinType t = typeOf(e);
        return c != null ? c : t == null ? 0 : t.cooldownSec();
    }

    public int yawOf(Entity e) {
        Integer y = e.getPersistentDataContainer().get(yawKey, PersistentDataType.INTEGER);
        return y == null ? 0 : y;
    }

    public void setCooldown(Interaction in, int seconds) {
        in.getPersistentDataContainer().set(cooldownKey, PersistentDataType.INTEGER, Math.max(MIN_COOLDOWN, Math.min(MAX_COOLDOWN, seconds)));
    }

    /** Klucz przerwy gracza dla jednego śmietnika. */
    public static String cooldownKeyOf(String binId) {
        return "bin:" + binId;
    }

    /** Wszystkie śmietniki w załadowanych chunkach. */
    public List<Interaction> all() {
        List<Interaction> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) for (Interaction in : w.getEntitiesByClass(Interaction.class)) if (isBin(in)) out.add(in);
        return out;
    }

    public int count() {
        return all().size();
    }

    /** Śmietnik, na który patrzy gracz, a gdy żaden nie jest na celowniku, najbliższy w promieniu `range` bloków. */
    public Interaction nearest(Player p, double range) {
        Entity target = null;
        try {
            target = p.getTargetEntity((int) Math.ceil(range));
        } catch (RuntimeException ignored) {
            // bez promienia patrzenia szukamy po prostu najbliższego
        }
        if (target != null && isBin(target)) return (Interaction) target;
        Interaction best = null;
        double bestDist = range * range;
        for (Interaction in : all()) {
            if (!in.getWorld().equals(p.getWorld())) continue;
            double d = in.getLocation().distanceSquared(p.getLocation());
            if (d <= bestDist) {
                best = in;
                bestDist = d;
            }
        }
        return best;
    }

    // ---------- stawianie i usuwanie ----------

    private String newId() {
        List<String> taken = all().stream().map(this::idOf).toList();
        for (int i = 0; i < 100; i++) {
            String id = String.format("%06x", plugin.eco().rng.nextInt(1 << 24));
            if (!taken.contains(id)) return id;
        }
        return String.format("%06x", UUID.randomUUID().hashCode() & 0xffffff);
    }

    /** Przerwa nowego śmietnika: bazowa dla rodzaju z rozrzutem -20% do +25%, żeby każdy stojący w mieście był trochę inny. */
    public int randomCooldown(BinType t) {
        double factor = 0.8 + plugin.eco().rng.nextDouble() * 0.45;
        return (int) Math.max(MIN_COOLDOWN, Math.round(t.cooldownSec() * factor / 5.0) * 5);
    }

    private BlockData blockData(String block) {
        try {
            return Bukkit.createBlockData(block);
        } catch (RuntimeException e) {
            Material m = Material.matchMaterial(BinModel.materialName(block));
            if (m != null) {
                try {
                    return m.createBlockData();
                } catch (RuntimeException ignored) {
                    // spróbujemy pominąć bryłę
                }
            }
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Śmietnik: nieznany blok modelu " + block + " (" + e + ").");
            }
            return null;
        }
    }

    private static Transformation transformation(Xform x) {
        return new Transformation(x.translation(), x.rotation(), x.scale(), new Quaternionf());
    }

    /**
     * Stawia śmietnik: model, pole kliknięcia i napis. Środek podstawy to środek bloku, w którym stoi `loc`, a obrót zaokrągla się
     * do 90 stopni. `cooldownSec` zero oznacza losową przerwę wokół domyślnej dla rodzaju.
     */
    public Interaction place(Location loc, BinType type, double yawDeg, int cooldownSec) {
        World w = loc.getWorld();
        Location base = new Location(w, Math.floor(loc.getX()) + 0.5, loc.getY(), Math.floor(loc.getZ()) + 0.5);
        int yaw = BinModel.snapYaw(yawDeg);
        int cd = cooldownSec > 0 ? Math.max(MIN_COOLDOWN, Math.min(MAX_COOLDOWN, cooldownSec)) : randomCooldown(type);
        String id = newId();
        for (int i = 0; i < type.parts().size(); i++) {
            Part part = type.parts().get(i);
            BlockData data = blockData(part.block());
            if (data == null) continue;
            int index = i;
            w.spawn(base, BlockDisplay.class, d -> {
                d.setBlock(data);
                d.setPersistent(true);
                quiet(() -> d.setTransformation(transformation(BinModel.xform(part, yaw, 0))));
                quiet(() -> d.setViewRange(1.0f));
                d.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, id);
                d.getPersistentDataContainer().set(indexKey, PersistentDataType.INTEGER, index);
                if (part.lid()) d.getPersistentDataContainer().set(lidKey, PersistentDataType.STRING, "1");
            });
        }
        try {
            w.spawn(base.clone().add(0, type.hitH() + 0.35, 0), TextDisplay.class, t -> {
                Component text = T.MM.deserialize("<white><bold>" + T.esc(type.name()) + "</bold><newline><gray>Kliknij, aby przeszukać");
                t.text(text);
                quiet(() -> t.setBillboard(Display.Billboard.CENTER));
                quiet(() -> t.setShadowed(true));
                quiet(() -> t.setViewRange(0.4f));
                t.setPersistent(true);
                t.getPersistentDataContainer().set(partKey, PersistentDataType.STRING, id);
            });
        } catch (RuntimeException e) {
            // bez napisu śmietnik nadal działa
        }
        return w.spawn(base, Interaction.class, in -> {
            in.setPersistent(true);
            quiet(() -> in.setInteractionWidth(type.hitW()));
            quiet(() -> in.setInteractionHeight(type.hitH()));
            quiet(() -> in.setResponsive(true));
            in.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
            in.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id);
            in.getPersistentDataContainer().set(cooldownKey, PersistentDataType.INTEGER, cd);
            in.getPersistentDataContainer().set(yawKey, PersistentDataType.INTEGER, yaw);
        });
    }

    /** Wszystkie bryły i napisy należące do śmietnika o danym identyfikatorze (w całym świecie). */
    private List<Display> partsOf(World w, String id) {
        List<Display> out = new ArrayList<>();
        for (Display d : w.getEntitiesByClass(Display.class)) {
            if (id.equals(d.getPersistentDataContainer().get(partKey, PersistentDataType.STRING))) out.add(d);
        }
        return out;
    }

    /** Usuwa śmietnik razem z modelem i napisem. */
    public void remove(Interaction in) {
        String id = idOf(in);
        if (id != null) for (Display d : partsOf(in.getWorld(), id)) d.remove();
        in.remove();
    }

    /** Usuwa wszystkie śmietniki w załadowanych chunkach i osierocone bryły po nich. Zwraca liczbę usuniętych śmietników. */
    public int removeAll() {
        int n = 0;
        for (Interaction in : all()) {
            remove(in);
            n++;
        }
        for (World w : Bukkit.getWorlds()) {
            for (Display d : w.getEntitiesByClass(Display.class)) {
                if (d.getPersistentDataContainer().has(partKey, PersistentDataType.STRING)) d.remove();
            }
        }
        return n;
    }

    // ---------- przeszukiwanie ----------

    /**
     * Przeszukanie bez efektów w świecie: sprawdza przerwę gracza dla tego śmietnika, zakłada ją (także gdy nic nie wyjdzie) i losuje łup.
     * Przerwa jest osobna dla każdej pary gracz-śmietnik i skraca ją eliksir skracający cooldowny, jak przy innych zarobkach.
     */
    public Result search(UUID player, String binId, BinType t, int cooldownSec) {
        Eco eco = plugin.eco();
        String key = cooldownKeyOf(binId);
        long left = eco.cooldownLeft(player, key);
        if (left > 0) return new Result(Status.COOLDOWN, left, null, 0, null);
        eco.setCooldown(player, key, eco.scaledCooldown(player, cooldownSec * 1000L));
        Catalog.Gather g = plugin.catalog().gather.get("smietnik");
        BinLoot.Drop drop = BinLoot.roll(g, t, eco.luckMultiplier(player), eco.lootMultiplier(player), eco.rng);
        if (drop == null) return new Result(Status.MISS, 0, null, 0, null);
        eco.addItem(player, drop.item(), drop.qty());
        return new Result(Status.OK, 0, drop.item(), drop.qty(), eco.addXp(player, eco.randInt(4, 9)));
    }

    /** Kliknięcie gracza w śmietnik: przeszukanie, wieko, dźwięki i wiadomość. */
    public Result click(Player p, Interaction in) {
        BinType t = typeOf(in);
        String id = idOf(in);
        if (t == null || id == null) return null;
        Result r = search(p.getUniqueId(), id, t, cooldownOf(in));
        switch (r.status()) {
            case COOLDOWN -> {
                Fx.deny(p);
                Fx.actionBar(p, "<red>" + T.esc(t.name()) + " jest już przeszukany. <gray>Wróć za <white>" + Fmt.duration(r.left()));
            }
            case MISS -> {
                openLid(in, t);
                Fx.sound(p, Sound.BLOCK_BARREL_OPEN, 0.7f, 1.3f);
                Fx.lose(p);
                T.send(p, "<gray>Same śmieci, nic wartościowego. <dark_gray>(" + T.esc(t.name()) + ")");
                Fx.actionBar(p, "<gray>Następne przeszukanie za <white>" + Fmt.duration(plugin.eco().cooldownLeft(p.getUniqueId(), cooldownKeyOf(id))));
            }
            case OK -> {
                openLid(in, t);
                Fx.sound(p, Sound.BLOCK_BARREL_OPEN, 0.7f, 1.0f);
                Fx.coin(p);
                Catalog.Item it = plugin.catalog().item(r.item());
                String name = it == null ? r.item() : it.name();
                T.send(p, "<green>Przeszukujesz: <white>" + T.esc(t.name()) + "<green>. Znajdujesz <white>" + r.qty() + "x " + T.esc(name) + "</white>"
                        + (it == null || it.sellValue() < 1 ? "" : " <gray>(wartość " + Fmt.money(it.sellValue() * r.qty()) + ")"));
                if (it != null && it.type().equals("crate")) T.send(p, "<yellow>To skrzynia! Otworzysz ją w menu skrzyń.");
                Fx.actionBar(p, "<green>+" + r.qty() + "x " + T.esc(name) + " <gray>• następne za <white>" + Fmt.duration(plugin.eco().cooldownLeft(p.getUniqueId(), cooldownKeyOf(id))));
                Notify.levelUp(p, r.lv());
            }
        }
        return r;
    }

    /** Unosi wieka na chwilę (na wszystkich widoczne, to ozdoba) i zamyka je z powrotem. */
    private void openLid(Interaction in, BinType t) {
        String id = idOf(in);
        int yaw = yawOf(in);
        for (Display d : partsOf(in.getWorld(), id)) {
            if (!d.getPersistentDataContainer().has(lidKey, PersistentDataType.STRING)) continue;
            Integer index = d.getPersistentDataContainer().get(indexKey, PersistentDataType.INTEGER);
            if (index == null || index < 0 || index >= t.parts().size()) continue;
            Part part = t.parts().get(index);
            quiet(() -> {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(5);
                d.setTransformation(transformation(BinModel.xform(part, yaw, BinModel.OPEN_DEGREES)));
            });
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!d.isValid()) return;
                quiet(() -> {
                    d.setInterpolationDelay(0);
                    d.setInterpolationDuration(5);
                    d.setTransformation(transformation(BinModel.xform(part, yaw, 0)));
                });
            }, LID_TICKS);
        }
    }

    /** Co dwie sekundy: nad śmietnikiem, który ten gracz może przeszukać, lecą zielone iskry (widzi je tylko on, z bliska). */
    private void hint() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                for (Entity e : p.getNearbyEntities(14, 8, 14)) {
                    if (!(e instanceof Interaction in) || !isBin(in)) continue;
                    BinType t = typeOf(in);
                    String id = idOf(in);
                    if (t == null || id == null || plugin.eco().cooldownLeft(p.getUniqueId(), cooldownKeyOf(id)) > 0) continue;
                    p.spawnParticle(Particle.HAPPY_VILLAGER, in.getLocation().add(0, t.hitH() + 0.15, 0), 3, 0.2, 0.1, 0.2, 0);
                }
            } catch (RuntimeException ignored) {
                // podpowiedź jest tylko dodatkiem
            }
        }
    }

    // ---------- kliknięcia ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!isBin(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        use(e.getPlayer(), (Interaction) e.getRightClicked());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (!isBin(e.getEntity())) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) use(p, (Interaction) e.getEntity());
    }

    private void use(Player p, Interaction in) {
        if (!p.hasPermission("kasyno.use")) {
            T.send(p, "<red>Nie masz uprawnień do kasyna.");
            return;
        }
        click(p, in);
    }
}
