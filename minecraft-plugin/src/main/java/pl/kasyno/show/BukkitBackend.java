package pl.kasyno.show;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import pl.kasyno.games.HeistFrames;
import pl.kasyno.show.Stage.P;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Efekty pokazów w prawdziwym świecie Paper 1.21.11 (napad, pojedynek, jackpot): postacie to manekiny (encje z modelem gracza), którym
 * ustawiamy skórkę prawdziwego gracza. Każda operacja jest osłonięta: gdy czegoś nie da się zrobić (np. inna wersja serwera), pokaz idzie
 * dalej bez tego elementu. Wszystko, co powstaje w świecie, ma znacznik TAG, więc purge() sprząta po awarii.
 */
public final class BukkitBackend implements ShowRunner.Backend, ArenaBackend {
    /** Znacznik w danych encji: po awarii serwera albo przy starcie pluginu zostawione figury da się znaleźć i usunąć. */
    public static final NamespacedKey TAG = NamespacedKey.fromString("kasyno:show");

    private final Stage stage;
    private final World world;
    private final Logger log;
    private boolean warned;

    public BukkitBackend(Stage stage, World world, Logger log) {
        this.stage = stage;
        this.world = world;
        this.log = log;
    }

    private org.bukkit.Location loc(P p, float yaw) {
        return new org.bukkit.Location(world, p.x(), p.y(), p.z(), yaw, 0);
    }

    /**
     * Miejsce dla figury: wysokość dopasowana do podłoża (schody, dywan, podest, płyty), bo punkty sceny mają jedną wysokość na odcinek, a ekipa
     * idzie gęsiego. Gdy świat nie potrafi podać kształtu kolizji, zostaje wysokość punktu.
     */
    private org.bukkit.Location stand(P p, float yaw) {
        double y = p.y();
        try {
            y = Ground.snap(p.y(), by -> surfaceTop(p.x(), p.z(), by));
        } catch (RuntimeException ignored) {
            // bez kształtów kolizji figura stoi na wysokości punktu sceny
        }
        return new org.bukkit.Location(world, p.x(), y, p.z(), yaw, 0);
    }

    /** Górna krawędź kolizji w rzędzie bloków `by` dokładnie pod punktem (x, z) albo NaN (otwarte drzwi i ciągi bez kolizji nie podnoszą figury). */
    private double surfaceTop(double x, double z, int by) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        Block b = world.getBlockAt(bx, by, bz);
        if (b.getType().isAir()) return Double.NaN;
        double fx = x - bx;
        double fz = z - bz;
        double top = Double.NaN;
        for (org.bukkit.util.BoundingBox box : b.getCollisionShape().getBoundingBoxes()) {
            if (fx < box.getMinX() - 1e-6 || fx > box.getMaxX() + 1e-6 || fz < box.getMinZ() - 1e-6 || fz > box.getMaxZ() + 1e-6) continue;
            top = Double.isNaN(top) ? by + box.getMaxY() : Math.max(top, by + box.getMaxY());
        }
        return top;
    }

    private void warn(RuntimeException e) {
        if (warned) return;
        warned = true;
        log.warning("Pokaz: nie udało się wykonać efektu (" + e + "). Pokaz idzie dalej bez niego.");
    }

    // ---------- postacie ----------

    /** Ustawienie kosmetyczne: gdyby któreś nie było dostępne, figura i tak ma stanąć na scenie. */
    private void quiet(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            warn(e);
        }
    }

    @Override
    public Object spawnCrew(UUID skin, String name, P at, float yaw) {
        return figure(skin, name, at, yaw, Material.IRON_PICKAXE);
    }

    @Override
    public Object spawn(UUID skin, String name, P at, float yaw, Hold hold) {
        return figure(skin, name, at, yaw, switch (hold) {
            case GUN -> Material.CROSSBOW;
            case GOLD -> Material.GOLD_INGOT;
            case NONE -> null;
        });
    }

    /** Figura ze skórką gracza `skin`, z przedmiotem w ręku (albo bez). */
    private Object figure(UUID skin, String name, P at, float yaw, Material hand) {
        try {
            return world.spawn(stand(at, yaw), Mannequin.class, e -> {
                quiet(() -> {
                    // skórka prawdziwego gracza; na serwerze offline-mode profil nie ma tekstur, więc prosimy o skórkę po nicku
                    Player real = org.bukkit.Bukkit.getPlayer(skin);
                    if (real == null) e.setProfile(ResolvableProfile.resolvableProfile().uuid(skin).build());
                    else {
                        com.destroystokyo.paper.profile.PlayerProfile pp = real.getPlayerProfile();
                        boolean textured = pp.getProperties().stream().anyMatch(p -> p.getName().equals("textures"));
                        e.setProfile(textured ? ResolvableProfile.resolvableProfile(pp) : ResolvableProfile.resolvableProfile().name(real.getName()).build());
                    }
                });
                dress(e, name, NamedTextColor.YELLOW);
                if (hand == null) return;
                quiet(() -> {
                    EntityEquipment eq = e.getEquipment();
                    if (eq != null) eq.setItemInMainHand(new ItemStack(hand));
                });
            });
        } catch (RuntimeException e) {
            warn(e);
            return null;
        }
    }

    @Override
    public Object spawnGuard(String name, boolean police, P at, float yaw) {
        try {
            return world.spawn(stand(at, yaw), Mannequin.class, e -> {
                dress(e, police ? "Policja" : "Ochroniarz", police ? NamedTextColor.AQUA : NamedTextColor.BLUE);
                quiet(() -> {
                    EntityEquipment eq = e.getEquipment();
                    if (eq == null) return;
                    Color c = police ? Color.fromRGB(0x1f4fa8) : Color.fromRGB(0x2b2f3a);
                    eq.setHelmet(armor(Material.LEATHER_HELMET, c));
                    eq.setChestplate(armor(Material.LEATHER_CHESTPLATE, c));
                    eq.setLeggings(armor(Material.LEATHER_LEGGINGS, c));
                    eq.setBoots(armor(Material.LEATHER_BOOTS, c));
                    eq.setItemInMainHand(new ItemStack(police ? Material.STICK : Material.BLAZE_ROD));
                });
            });
        } catch (RuntimeException e) {
            warn(e);
            return null;
        }
    }

    private static ItemStack armor(Material m, Color c) {
        ItemStack it = new ItemStack(m);
        LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
        meta.setColor(c);
        it.setItemMeta(meta);
        return it;
    }

    private void dress(Mannequin e, String name, NamedTextColor color) {
        quiet(() -> e.setDescription(null));
        quiet(() -> {
            e.customName(Component.text(name, color));
            e.setCustomNameVisible(true);
        });
        quiet(() -> e.setImmovable(true));
        quiet(() -> e.setInvulnerable(true));
        quiet(() -> e.setGravity(false));
        quiet(() -> e.setSilent(true));
        quiet(() -> e.setPersistent(false));
        quiet(() -> e.setRemoveWhenFarAway(false));
        quiet(() -> {
            if (TAG != null) e.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, "1");
        });
    }

    @Override
    public void teleport(Object handle, P at, float yaw) {
        if (handle instanceof Entity e && e.isValid()) {
            try {
                e.teleport(stand(at, yaw));
            } catch (RuntimeException ex) {
                warn(ex);
            }
        }
    }

    @Override
    public void sneak(Object handle, boolean on) {
        if (handle instanceof Mannequin m) {
            try {
                m.setPose(on ? org.bukkit.entity.Pose.SNEAKING : org.bukkit.entity.Pose.STANDING, false);
            } catch (RuntimeException ex) {
                warn(ex);
            }
        }
    }

    @Override
    public void pose(Object handle, ArenaBackend.Pose pose) {
        if (!(handle instanceof Mannequin m)) return;
        try {
            switch (pose) {
                case STAND -> m.setPose(org.bukkit.entity.Pose.STANDING, false);
                case SNEAK -> m.setPose(org.bukkit.entity.Pose.SNEAKING, true);
                case LIE -> {
                    // leży na ziemi jak śpiący: pozę trzymamy na sztywno, a figurę unosimy o grubość ciała, żeby nie tonęła w podłodze
                    m.setPose(org.bukkit.entity.Pose.SLEEPING, true);
                    m.teleport(m.getLocation().add(0, 0.25, 0));
                }
            }
        } catch (RuntimeException ex) {
            warn(ex);
        }
    }

    @Override
    public void glow(Object handle, boolean on) {
        if (handle instanceof Entity e) {
            try {
                e.setGlowing(on);
            } catch (RuntimeException ex) {
                warn(ex);
            }
        }
    }

    @Override
    public void swing(Object handle) {
        if (handle instanceof Mannequin m) {
            try {
                m.swingMainHand();
            } catch (RuntimeException ex) {
                warn(ex);
            }
        }
    }

    @Override
    public void remove(Object handle) {
        if (handle instanceof Entity e) {
            try {
                e.remove();
            } catch (RuntimeException ex) {
                warn(ex);
            }
        }
    }

    // ---------- dźwięk i cząsteczki ----------

    @Override
    public void sound(HeistFrames.Cue cue, P at) {
        Sound s;
        float v = 1.2f;
        float pitch = 1f;
        switch (cue) {
            case ENGINE -> {
                s = Sound.ENTITY_MINECART_RIDING;
                pitch = 0.8f;
            }
            case STEP -> {
                s = Sound.BLOCK_STONE_STEP;
                v = 0.5f;
            }
            case DOOR -> s = Sound.BLOCK_IRON_DOOR_OPEN;
            case CAMERA -> {
                s = Sound.BLOCK_NOTE_BLOCK_CHIME;
                pitch = 1.7f;
                v = 0.6f;
            }
            case DRILL -> s = Sound.BLOCK_GRINDSTONE_USE;
            case BOOM -> s = Sound.ENTITY_GENERIC_EXPLODE;
            case BELL -> {
                s = Sound.BLOCK_BELL_USE;
                v = 2f;
            }
            case COIN -> {
                s = Sound.ENTITY_EXPERIENCE_ORB_PICKUP;
                pitch = 1.5f;
            }
            case CUFF -> s = Sound.BLOCK_IRON_TRAPDOOR_CLOSE;
            case GUARD -> {
                s = Sound.BLOCK_NOTE_BLOCK_BASS;
                pitch = 0.6f;
            }
            case ESCAPE -> s = Sound.UI_TOAST_CHALLENGE_COMPLETE;
            default -> {
                return;
            }
        }
        try {
            world.playSound(loc(at, 0), s, SoundCategory.MASTER, v, pitch);
        } catch (RuntimeException ex) {
            warn(ex);
        }
    }

    @Override
    public void sound(Sfx sfx, P at, float pitch) {
        try {
            org.bukkit.Location l = loc(at, 0);
            switch (sfx) {
                case STEP -> world.playSound(l, Sound.BLOCK_SAND_STEP, SoundCategory.MASTER, 0.5f, pitch);
                case TENSION -> world.playSound(l, Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.MASTER, 1.2f, pitch);
                case COUNT -> world.playSound(l, Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.MASTER, 1.4f, pitch);
                case SHOT -> {
                    world.playSound(l, Sound.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.MASTER, 2f, 0.7f);
                    world.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.MASTER, 1f, 1.6f);
                }
                case HIT -> world.playSound(l, Sound.ENTITY_PLAYER_HURT, SoundCategory.MASTER, 1f, 0.9f);
                case FALL -> world.playSound(l, Sound.ENTITY_PLAYER_DEATH, SoundCategory.MASTER, 1f, 1f);
                case TICK -> world.playSound(l, Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.MASTER, 1.2f, pitch);
                case FANFARE -> world.playSound(l, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 2f, pitch);
                case WIN -> world.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 1.2f, pitch);
            }
        } catch (RuntimeException ex) {
            warn(ex);
        }
    }

    /** Fajerwerk, który wybucha od razu i znika; gdy się nie da (inna wersja serwera), zostają same cząsteczki. */
    private void firework(org.bukkit.Location l) {
        Firework fw = null;
        try {
            fw = world.spawn(l, Firework.class, f -> {
                FireworkMeta meta = f.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.YELLOW, Color.ORANGE).withFade(Color.WHITE).flicker(true).trail(true).build());
                meta.setPower(0);
                f.setFireworkMeta(meta);
                if (TAG != null) f.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, "1");
            });
            fw.detonate();
        } catch (RuntimeException ex) {
            warn(ex);
            if (fw != null) {
                try {
                    fw.remove();
                } catch (RuntimeException ignored) {
                    // i tak zniknie przy sprzątaniu po znaczniku
                }
            }
            world.spawnParticle(Particle.FIREWORK, l, 30, 0.6, 0.6, 0.6, 0.15);
        }
    }

    @Override
    public void fx(String kind, P at) {
        try {
            org.bukkit.Location l = loc(at, 0);
            switch (kind) {
                case "dust" -> world.spawnParticle(Particle.CLOUD, l.clone().add(0, 0.1, 0), 8, 0.4, 0.05, 0.4, 0.01);
                case "shot" -> {
                    world.spawnParticle(Particle.FLAME, l.clone().add(0, 1.2, 0), 8, 0.1, 0.1, 0.1, 0.05);
                    world.spawnParticle(Particle.LARGE_SMOKE, l.clone().add(0, 1.2, 0), 6, 0.15, 0.1, 0.15, 0.01);
                }
                case "hit" -> {
                    world.spawnParticle(Particle.DUST, l.clone().add(0, 1.1, 0), 24, 0.25, 0.4, 0.25, 0, new Particle.DustOptions(Color.RED, 1.3f));
                    world.spawnParticle(Particle.CRIT, l.clone().add(0, 1.1, 0), 10, 0.3, 0.4, 0.3, 0.2);
                }
                case "pick" -> {
                    // złoty słup nad podświetloną głową
                    for (double y = 0; y <= 2.6; y += 0.3) world.spawnParticle(Particle.DUST, l.clone().add(0, y, 0), 1, 0.05, 0, 0.05, 0, new Particle.DustOptions(Color.fromRGB(0xFFC800), 1.2f));
                    world.spawnParticle(Particle.END_ROD, l.clone().add(0, 2.7, 0), 3, 0.1, 0.1, 0.1, 0.02);
                }
                case "firework" -> firework(l);
                case "beam" -> {
                    // dwie czerwone wiązki kamer przez cały hol
                    for (double x = -5.5; x <= 5.5; x += 0.5) {
                        world.spawnParticle(Particle.DUST, l.clone().add(x, 1.0, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.RED, 1.1f));
                        world.spawnParticle(Particle.DUST, l.clone().add(x, 1.7, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.RED, 1.1f));
                    }
                }
                case "sparks" -> {
                    world.spawnParticle(Particle.ELECTRIC_SPARK, l, 10, 0.25, 0.25, 0.25, 0.05);
                    world.spawnParticle(Particle.CRIT, l, 6, 0.2, 0.2, 0.2, 0.1);
                }
                case "gold" -> world.spawnParticle(Particle.TOTEM_OF_UNDYING, l.add(0, 1, 0), 14, 0.4, 0.6, 0.4, 0.3);
                case "cuff" -> {
                    world.spawnParticle(Particle.CRIT, l.clone().add(0, 1, 0), 18, 0.4, 0.5, 0.4, 0.2);
                    world.spawnParticle(Particle.ANGRY_VILLAGER, l.clone().add(0, 2, 0), 3, 0.3, 0.2, 0.3, 0);
                }
                case "smoke" -> world.spawnParticle(Particle.CLOUD, l.add(0, 0.8, 0), 18, 0.3, 0.5, 0.3, 0.03);
                case "alarm" -> {
                    world.spawnParticle(Particle.DUST, l.clone().add(0, 2, 0), 30, 4, 1, 4, 0, new Particle.DustOptions(Color.RED, 1.6f));
                    world.playSound(l, Sound.BLOCK_BELL_USE, SoundCategory.MASTER, 2f, 1.3f);
                }
                default -> {
                }
            }
        } catch (RuntimeException ex) {
            warn(ex);
        }
    }

    // ---------- drzwi i lampy ----------

    private Block block(String point) {
        P p = stage.get(point);
        return p == null ? null : world.getBlockAt((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
    }

    @Override
    public void door(String which, boolean open) {
        Block lower = block(which.equals("vault") ? "vaultdoor" : "bankdoor");
        if (lower == null) return;
        try {
            for (Block b : new Block[]{lower, lower.getRelative(0, 1, 0)}) {
                BlockData data = b.getBlockData();
                if (data instanceof Door d) {
                    d.setOpen(open);
                    b.setBlockData(d, false);
                }
            }
            world.playSound(lower.getLocation().add(0.5, 0.5, 0.5), open ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_IRON_DOOR_CLOSE, SoundCategory.MASTER, 1f, 1f);
        } catch (RuntimeException ex) {
            warn(ex);
        }
    }

    @Override
    public void lamps(boolean lit) {
        for (int i = 1; i <= 4; i++) {
            Block b = block("lamp" + i);
            if (b == null) continue;
            try {
                if (b.getBlockData() instanceof Lightable l) {
                    l.setLit(lit);
                    b.setBlockData(l, false);
                }
            } catch (RuntimeException ex) {
                warn(ex);
            }
        }
    }
}
