package pl.sport.match;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import pl.sport.core.Team;

import java.util.UUID;

/** Gracz w meczu: drużyna, zapamiętany stan sprzed meczu i znaczniki czasu do ograniczania uderzeń. */
public final class Participant {
    private final UUID id;
    private final String name;
    private Team team;
    private final PlayerSnapshot snapshot;
    /** Do tego czasu (nanosekundy) teleport gracza jest nasz, więc nie traktujemy go jak ucieczki z boiska. */
    public long ignoreTeleportUntil;
    /** Dokąd właśnie teleportowaliśmy gracza (do rozpoznania naszych teleportów). */
    private org.bukkit.Location expectedTeleport;

    /** Zapamiętuje własny teleport gracza w to miejsce, ważny przez 1,5 s. */
    public void expectTeleport(org.bukkit.Location to) {
        this.expectedTeleport = to.clone();
        this.ignoreTeleportUntil = System.nanoTime() + 1_500_000_000L;
    }

    /** Czy teleport do `to` to nasz własny (a nie ucieczka z boiska wymuszona komendą albo innym pluginem). */
    public boolean isOurTeleport(org.bukkit.Location to) {
        if (expectedTeleport == null || System.nanoTime() > ignoreTeleportUntil || to == null) return false;
        if (to.getWorld() != expectedTeleport.getWorld()) return false;
        return to.distanceSquared(expectedTeleport) < 4.0;
    }
    /** Czas ostatniego uderzenia piłki przez gracza (nanosekundy). */
    public long lastHit = -1_000_000_000_000L;
    /** Czas ostatniej próby przechwytu (nanosekundy), do ograniczania spamu. */
    public long lastSteal = -1_000_000_000_000L;
    /** Do tego czasu (nanosekundy) gracz nie może podnieść piłki (po własnym rzucie albo po utracie piłki). */
    public long pickupLockUntil;

    /** Ruch gracza liczony z różnic położenia między tickami (prędkość klienta nie jest dostępna na serwerze), w układzie lokalnym boiska. */
    double y;
    double px;
    double pz;
    double vy;
    double vx;
    double vz;
    boolean air;
    boolean motionInit;

    public double vy() {
        return vy;
    }

    public double vx() {
        return vx;
    }

    public double vz() {
        return vz;
    }

    /** Czy gracz jest w powietrzu (skacze). */
    public boolean air() {
        return air;
    }

    /** Wysokość stóp nad podłogą boiska (m). */
    public double footY() {
        return y;
    }

    public Participant(Player p, Team team, PlayerSnapshot snapshot) {
        this.id = p.getUniqueId();
        this.name = p.getName();
        this.team = team;
        this.snapshot = snapshot;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Team team() {
        return team;
    }

    public void team(Team t) {
        this.team = t;
    }

    public PlayerSnapshot snapshot() {
        return snapshot;
    }

    public Player player() {
        return Bukkit.getPlayer(id);
    }
}
