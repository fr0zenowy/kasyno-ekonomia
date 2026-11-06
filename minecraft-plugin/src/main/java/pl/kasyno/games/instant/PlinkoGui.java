package pl.kasyno.games.instant;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.Games;
import pl.kasyno.games.GameGui;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.LinkedHashMap;
import java.util.Map;

/** Plinko: kulka spada przez 12 rzędów kołków do koszyka z mnożnikiem (do x1000). Plansza jest skompresowana do czterech rzędów. */
public final class PlinkoGui extends GameGui {
    private static final Map<String, String> RISKS = new LinkedHashMap<>();

    static {
        RISKS.put("niskie", "Niskie");
        RISKS.put("srednie", "Średnie");
        RISKS.put("wysokie", "Wysokie");
        RISKS.put("szalone", "Szalone");
    }

    private String risk;
    private int ballRow = -1;
    private int ballCol;
    private int landed = -1;
    private int landedBucket;
    private ItemStack last;

    public PlinkoGui(KasynoPlugin plugin, Player player) {
        super(plugin, player, "plinko");
        risk = choice("risk", "srednie");
    }

    @Override
    protected Component title() {
        return T.c("<dark_aqua><bold>Plinko");
    }

    private double[] mults() {
        return plugin.catalog().games.plinko().mults().get(risk);
    }

    private static int colOf(int bucket) {
        return (int) Math.round(bucket * 8 / 12.0);
    }

    private static int bucketOf(int col) {
        return (int) Math.round(col * 12 / 8.0);
    }

    private static String color(double m) {
        return m >= 100 ? "<light_purple>" : m >= 10 ? "<gold>" : m >= 2 ? "<green>" : m >= 1 ? "<yellow>" : "<red>";
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        // kołki
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 9; c++) {
                if ((r + c) % 2 == 1) set(r * 9 + c, ItemBuilder.of(Material.IRON_NUGGET).name(" ").hidden().build());
                else set(r * 9 + c, ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));
            }
        }
        // koszyki
        double[] m = mults();
        for (int c = 0; c < 9; c++) {
            boolean here = landed == c;
            double v = m[here ? landedBucket : bucketOf(c)];
            Material pane = v >= 100 ? Material.MAGENTA_STAINED_GLASS_PANE : v >= 10 ? Material.ORANGE_STAINED_GLASS_PANE : v >= 2 ? Material.LIME_STAINED_GLASS_PANE : v >= 1 ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
            set(36 + c, ItemBuilder.of(here ? Material.SLIME_BALL : pane).name(color(v) + "<bold>" + Fmt.mult(v)).glow(here).lore(here ? "<white>Tu wpadła kulka!" : "<gray>Koszyk").build());
        }
        if (ballRow >= 0 && ballRow < 4) set(ballRow * 9 + ballCol, ItemBuilder.of(Material.SLIME_BALL).name("<green><bold>●").glow().build());
        if (last != null) set(4, last);

        int[] slots = {46, 47, 48};
        // wybór ryzyka w polach pod planszą nie mieści się w pasku stawki, więc idzie do rzędu kołków po bokach
        int i = 0;
        for (Map.Entry<String, String> e : RISKS.entrySet()) {
            boolean on = e.getKey().equals(risk);
            double max = plugin.catalog().games.plinko().mults().get(e.getKey())[0];
            set(9 * (i + 0) + 0, ItemBuilder.of(Material.PAPER).name((on ? "<green><bold>" : "<white>") + e.getValue()).glow(on).lore("<gray>Maks. <gold>" + Fmt.mult(max), on ? "<green>Wybrane" : "<yellow>Kliknij").build(), t -> pick(e.getKey()));
            i++;
        }
        betBar(ItemBuilder.of(Material.EMERALD).name("<green><bold>Puść kulkę!").glow(!busy).lore("<gray>Ryzyko: <white>" + RISKS.get(risk), "<gray>Maks. wypłata: <gold>" + Fmt.mult(m[0])).build(), t -> play(),
                ItemBuilder.of(Material.BOOK).name("<white>Zasady").lore("<gray>Kulka spada przez 12 rzędów.", "<gray>Wyższe ryzyko = większe maksima.", "<gray>Zwrot 94-96%.").build());
        if (slots.length == 0) set(0, null);
    }

    private void pick(String r) {
        if (busy) return;
        risk = r;
        saveChoice("risk", r);
        landed = -1;
        ballRow = -1;
        Fx.click(player);
        redraw();
    }

    private void play() {
        if (busy || !takeStake()) return;
        busy = true;
        last = null;
        landed = -1;
        boolean[] path = new boolean[12];
        for (int i = 0; i < 12; i++) path[i] = eco().rng.nextBoolean();
        int bucket = Games.plinkoBucket(path);
        double mult = mults()[bucket];
        long stakeNow = stake;
        Eco.Settle s = eco().settle(id, stakeNow, (long) Math.floor(stakeNow * mult));
        String text = "Kulka wpadła do koszyka " + Fmt.mult(mult) + ".";
        deferAnnounce(() -> announce(s, stakeNow, text));
        int[] rights = new int[1];
        sequence(even(12, 3), k -> {
            if (path[k]) rights[0]++;
            int step = k + 1;
            ballRow = Math.min(3, k / 3);
            ballCol = Math.max(0, Math.min(8, (int) Math.round(4 + (2 * rights[0] - step) * 4 / 12.0)));
            redraw();
            Fx.tick(player, 0.8f + 0.06f * k);
        }, () -> {
            ballRow = -1;
            landed = colOf(bucket);
            landedBucket = bucket;
            last = resultItem(s, stakeNow, "<white>" + text);
            endRound();
        });
    }
}
