package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Eco;
import pl.kasyno.core.Fmt;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.Notify;
import pl.kasyno.gui.T;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pytanie z czterema odpowiedziami (trivia i matma). Dobra odpowiedź to nagroda, zła albo brak odpowiedzi to nic. */
public final class QuizGui extends Gui {
    private final String titleText;
    private final String art;
    private final String question;
    private final List<String> options;
    private final int correct;
    private final long rewardMin;
    private final long rewardMax;
    private final long timeMs;
    private final long startedAt;
    private boolean answered;
    private int picked = -1;
    private String resultLine;
    /** Egzamin w więzieniu: dobra odpowiedź zalicza zadanie zamiast dawać pieniądze. */
    private boolean exam;

    private QuizGui(KasynoPlugin plugin, Player player, String titleText, String art, String question, List<String> options, int correct, long rewardMin, long rewardMax, long timeMs) {
        super(plugin, player);
        this.titleText = titleText;
        this.art = art;
        this.question = question;
        this.options = options;
        this.correct = correct;
        this.rewardMin = rewardMin;
        this.rewardMax = rewardMax;
        this.timeMs = timeMs;
        this.startedAt = System.currentTimeMillis();
    }

    public static QuizGui trivia(KasynoPlugin plugin, Player p) {
        Catalog.Trivia t = plugin.eco().pick(plugin.catalog().trivia);
        List<String> opts = new ArrayList<>(t.w());
        opts.add(t.a());
        Collections.shuffle(opts, new java.util.Random(plugin.eco().rng.nextLong()));
        return new QuizGui(plugin, p, "Trivia", "trivia", t.q(), opts, opts.indexOf(t.a()), 200, 450, 20_000);
    }

    public static QuizGui math(KasynoPlugin plugin, Player p) {
        Eco eco = plugin.eco();
        String op = eco.pick(List.of("+", "-", "×"));
        int a;
        int b;
        int answer;
        if (op.equals("+")) {
            a = eco.randInt(12, 99);
            b = eco.randInt(12, 99);
            answer = a + b;
        } else if (op.equals("-")) {
            a = eco.randInt(30, 150);
            b = eco.randInt(5, a - 1);
            answer = a - b;
        } else {
            a = eco.randInt(4, 15);
            b = eco.randInt(4, 15);
            answer = a * b;
        }
        Set<Integer> wrong = new HashSet<>();
        while (wrong.size() < 3) {
            int cand = answer + eco.pick(List.of(-1, 1)) * eco.pick(List.of(1, 2, 3, 5, 10, eco.randInt(1, 12)));
            if (cand != answer && cand >= 0) wrong.add(cand);
        }
        List<String> opts = new ArrayList<>();
        opts.add(String.valueOf(answer));
        wrong.forEach(w -> opts.add(String.valueOf(w)));
        Collections.shuffle(opts, new java.util.Random(eco.rng.nextLong()));
        return new QuizGui(plugin, p, "Matma", "matma", a + " " + op + " " + b + " = ?", opts, opts.indexOf(String.valueOf(answer)), 120, 300, 12_000);
    }

    /** Pytanie z wiedzy albo z matmy, którego zaliczenie liczy się jako zadanie więzienne (bez nagrody w pieniądzach). */
    public static QuizGui exam(KasynoPlugin plugin, Player p) {
        QuizGui q = plugin.eco().chance(0.5) ? trivia(plugin, p) : math(plugin, p);
        q.exam = true;
        return q;
    }

    @Override
    protected boolean allowedInPrison() {
        return exam;
    }

    @Override
    protected String art() {
        return art;
    }

    @Override
    protected Component title() {
        return T.c("<aqua><bold>" + (exam ? "Egzamin • " : "") + titleText);
    }

    @Override
    protected void render() {
        fill(ItemBuilder.filler());
        List<String> q = T.wrap(question, 34, "<white>");
        set(13, ItemBuilder.of(Material.BOOK).name("<yellow><bold>Pytanie").lore(q).build());
        int[] slots = {28, 30, 32, 34};
        String[] letters = {"A", "B", "C", "D"};
        for (int i = 0; i < options.size() && i < 4; i++) {
            final int idx = i;
            Material m = !answered ? Material.PAPER : i == correct ? Material.LIME_DYE : i == picked ? Material.RED_DYE : Material.GRAY_DYE;
            set(slots[i], ItemBuilder.of(m).name((!answered ? "<white>" : i == correct ? "<green>" : i == picked ? "<red>" : "<gray>") + letters[i] + ") " + T.esc(options.get(i)))
                    .lore(answered ? "" : "<yellow>Kliknij, aby odpowiedzieć").build(), answered ? null : t -> answer(idx));
        }
        long left = Math.max(0, timeMs - (System.currentTimeMillis() - startedAt));
        set(22, ItemBuilder.of(Material.CLOCK).name(answered ? "<white>" + (resultLine == null ? "Koniec" : resultLine) : "<yellow>Zostało: " + Fmt.duration(left)).build());
        if (answered) backTo(49, () -> go(exam ? new pl.kasyno.prison.PrisonGui(plugin, player) : new EarnGui(plugin, player)));
        else closeButton(49);
    }

    @Override
    public void open() {
        super.open();
        repeat(20, 20, () -> {
            if (answered) return;
            if (System.currentTimeMillis() - startedAt >= timeMs) finish(-1);
            else redraw();
        });
    }

    private void answer(int idx) {
        if (answered) return;
        finish(idx);
    }

    private void finish(int idx) {
        answered = true;
        picked = idx;
        if (exam) {
            if (idx == correct) {
                resultLine = "<green>Zdane!";
                Fx.win(player);
                T.send(player, "<green>Dobra odpowiedź!");
                plugin.prison().completeTask(id);
            } else {
                resultLine = idx < 0 ? "<red>Czas minął!" : "<red>Źle! Poprawna: " + T.esc(options.get(correct));
                Fx.lose(player);
                T.send(player, "<red>Nie zdałeś. Poprawna odpowiedź: <white>" + T.esc(options.get(correct)));
            }
            redraw();
            return;
        }
        if (idx == correct) {
            Eco.Earned e = eco().applyEarn(id, eco().randLong(rewardMin, rewardMax));
            eco().add(id, e.amount());
            Eco.LevelUp lv = eco().addXp(id, eco().randInt(8, 16));
            resultLine = "<green>Dobrze! +" + Fmt.money(e.amount());
            Fx.win(player);
            T.send(player, "<green>Dobra odpowiedź! Zarabiasz <gold>" + Fmt.money(e.amount()) + "</gold>.");
            Notify.notes(player, e.notes());
            Notify.levelUp(player, lv);
        } else {
            resultLine = idx < 0 ? "<red>Czas minął!" : "<red>Źle! Poprawna: " + T.esc(options.get(correct));
            Fx.lose(player);
            T.send(player, idx < 0 ? "<red>Czas minął. Poprawna odpowiedź: <white>" + T.esc(options.get(correct)) : "<red>Źle! Poprawna odpowiedź: <white>" + T.esc(options.get(correct)));
        }
        redraw();
    }
}
