package pl.kasyno.work;

import pl.kasyno.show.Stage.P;
import pl.kasyno.work.JobDef.Kind;
import pl.kasyno.work.JobDef.NodeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** Rozstawia cele pracy po okolicy: losuje miejsca w kole wokół NPC, pilnuje odstępów i dobiera rodzaje z wagami (rzadkie × szczęście). */
public final class JobPlanner {
    private JobPlanner() {}

    /** Jeden cel: rodzaj (indeks w JobDef.types) i miejsce. */
    public record Plan(int type, P pos) {}

    /** Ile miejsc planujemy z góry: trasa kuriera ma tyle punktów, ile paczek, reszta tyle, ile celów naraz. */
    public static int count(JobDef job) {
        return job.kind() == Kind.ROUTE ? job.quota() : job.nodes();
    }

    private static double minRadius(JobDef job, double radius) {
        double want = switch (job.kind()) {
            case ROUTE -> 15;
            case HUNT -> 25;
            default -> 3;
        };
        return Math.min(want, radius * 0.5);
    }

    private static double spacing(JobDef job, double radius) {
        double want = switch (job.kind()) {
            case ROUTE -> 18;
            case COLLECT -> 2.5;
            case WATER -> 3;
            case HUNT -> 4;
        };
        return Math.min(want, radius * 0.3);
    }

    /** Jedno miejsce w kole (poza `avoid` bliżej niż odstęp); empty, gdy po wielu próbach nie ma sensownego. */
    public static Optional<P> spot(JobDef job, Terrain terrain, P center, double radius, RandomGenerator rng, List<P> avoid) {
        double minR = minRadius(job, radius);
        double gap = spacing(job, radius);
        for (int attempt = 0; attempt < 120; attempt++) {
            double r = Math.sqrt(minR * minR + rng.nextDouble() * (radius * radius - minR * minR));
            double a = rng.nextDouble() * Math.PI * 2;
            double x = center.x() + Math.cos(a) * r;
            double z = center.z() + Math.sin(a) * r;
            P p = job.kind() == Kind.WATER ? terrain.water(x, z) : terrain.ground(x, z);
            if (p == null) continue;
            boolean crowded = false;
            for (P o : avoid) {
                if (o.flat(p) < gap) {
                    crowded = true;
                    break;
                }
            }
            if (!crowded) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** Rodzaj celu z wagami; rzadkie rodzaje mają wagę pomnożoną przez szczęście (narzędzie, eliksiry). */
    public static int pickType(JobDef job, double luck, RandomGenerator rng) {
        double total = 0;
        for (NodeType t : job.types()) total += t.rare() ? t.weight() * luck : t.weight();
        double r = rng.nextDouble() * total;
        for (int i = 0; i < job.types().size(); i++) {
            NodeType t = job.types().get(i);
            r -= t.rare() ? t.weight() * luck : t.weight();
            if (r < 0) return i;
        }
        return job.types().size() - 1;
    }

    /**
     * Plan rundy: miejsca i rodzaje wszystkich celów. Pusta lista oznacza, że w okolicy nie ma odpowiedniego terenu (np. wody dla
     * rybaka). Gdy uda się znaleźć co najmniej połowę miejsc, runda startuje z tym, co jest.
     */
    public static List<Plan> plan(JobDef job, Terrain terrain, P center, double radius, RandomGenerator rng, double luck) {
        int want = count(job);
        List<P> spots = new ArrayList<>();
        for (int i = 0; i < want; i++) {
            Optional<P> p = spot(job, terrain, center, radius, rng, spots);
            if (p.isEmpty()) break;
            spots.add(p.get());
        }
        if (spots.size() < Math.max(1, (want + 1) / 2)) return List.of();
        List<Plan> out = new ArrayList<>();
        for (P p : spots) out.add(new Plan(pickType(job, luck, rng), p));
        return out;
    }
}
