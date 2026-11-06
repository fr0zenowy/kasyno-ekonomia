package pl.kasyno.gui.screens;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import pl.kasyno.KasynoPlugin;
import pl.kasyno.core.Catalog;
import pl.kasyno.core.Empire;
import pl.kasyno.core.Fmt;
import pl.kasyno.core.PlayerData;
import pl.kasyno.gui.Fx;
import pl.kasyno.gui.Gui;
import pl.kasyno.gui.ItemBuilder;
import pl.kasyno.gui.T;

import java.util.List;

/** Biznesy (dochód pasywny, ulepszenia do poziomu 5) i farma (cztery pola, uprawy dojrzewają w czasie). */
public final class EmpireGui extends Gui {
    private static final Material[] BIZ_ICONS = {Material.PAPER, Material.PUMPKIN_PIE, Material.BARREL, Material.WATER_BUCKET, Material.RED_BED, Material.REDSTONE_LAMP, Material.GOLD_BLOCK, Material.NETHER_STAR};
    static final Material[] CROP_ICONS = {Material.CARROT, Material.POTATO, Material.WHEAT, Material.APPLE, Material.PUMPKIN, Material.GLOW_BERRIES, Material.NETHER_WART};

    public EmpireGui(KasynoPlugin plugin, Player player) {
        super(plugin, player);
    }

    @Override
    protected String art() {
        return "biznes";
    }

    @Override
    protected Component title() {
        return T.c("<green><bold>Biznes i farma");
    }

    @Override
    protected void render() {
        Catalog cat = plugin.catalog();
        Empire empire = plugin.empire();
        PlayerData d = eco().pd(id);
        long now = eco().now();
        fill(ItemBuilder.filler());
        border(ItemBuilder.filler(Material.BLACK_STAINED_GLASS_PANE));

        Empire.Pending pending = empire.pending(id, now);
        set(4, ItemBuilder.of(Material.CHEST_MINECART).icon("moneta").name("<gold><bold>Dochód: " + Fmt.money(empire.hourlyTotal(id)) + "/h")
                .lore("<gray>Do odebrania: <gold>" + Fmt.money(pending.total()), "<gray>Magazyn: <white>" + pending.cap() + " h <gray>(po tym czasie nie kumuluje się)", "",
                        pending.total() > 0 ? "<yellow>Kliknij, aby odebrać zarobki" : "<dark_gray>Nic do odebrania").glow(pending.total() > 0).build(), t -> collect());

        for (int i = 0; i < cat.businesses.size() && i < 8; i++) {
            Catalog.Business b = cat.businesses.get(i);
            PlayerData.Biz row = d.businesses.get(b.id());
            final String bid = b.id();
            if (row == null) {
                set(10 + i, ItemBuilder.of(BIZ_ICONS[i]).name("<gray><bold>" + b.name())
                        .lore("<gray>Dochód: <white>" + Fmt.num(b.hourly()) + " $/h", "<gray>Zwrot po ok. <white>" + Math.round((double) b.price() / b.hourly()) + " h", "<gray>Cena: <gold>" + Fmt.money(b.price()), "",
                                eco().wallet(id) >= b.price() ? "<yellow>Kliknij, aby kupić" : "<red>Za mało monet").build(), t -> buy(bid));
            } else {
                boolean max = row.level >= cat.businessMaxLevel;
                String next = max ? "<gold>Maksymalny poziom" : "<yellow>Kliknij, aby ulepszyć: <gold>" + Fmt.money(empire.upgradeCost(b, row.level));
                set(10 + i, ItemBuilder.of(BIZ_ICONS[i]).name("<green><bold>" + b.name() + " <white>poz. " + row.level + "/" + cat.businessMaxLevel).glow(max)
                        .lore("<gray>Dochód: <gold>" + Fmt.money((long) Math.floor(empire.hourlyAt(b, row.level) * empire.incomeMult(id))) + "/h", "<gray>Zebrano już: <gold>" + Fmt.money(
                                pending.parts().stream().filter(p -> p.biz().id().equals(bid)).mapToLong(Empire.Part::amount).sum()), "", next).amount(row.level).build(), max ? null : t -> buy(bid));
            }
        }

        // farma
        set(27, ItemBuilder.of(Material.GRASS_BLOCK).icon("farm").name("<green><bold>Farma").lore("<gray>Cztery pola. Kliknij puste pole, żeby", "<gray>zasadzić, albo plon, żeby zebrać.").build());
        List<Empire.SlotState> plots = empire.status(id, now);
        for (int i = 0; i < plots.size(); i++) {
            Empire.SlotState s = plots.get(i);
            final int slot = s.slot();
            int cell = 29 + i * 2;
            switch (s.state()) {
                case "empty" -> set(cell, ItemBuilder.of(Material.BROWN_TERRACOTTA).name("<gray>Pole " + slot + ": puste").lore("<yellow>Kliknij, aby zasadzić").build(), t -> go(new CropGui(plugin, player, slot)));
                case "growing" -> set(cell, ItemBuilder.of(cropIcon(s.crop())).name("<yellow>Pole " + slot + ": " + s.crop().name())
                        .lore("<gray>Dojrzeje za <white>" + Fmt.duration(s.left()), "<gray>Plon: <gold>" + Fmt.money(s.crop().yield())).build());
                default -> set(cell, ItemBuilder.of(cropIcon(s.crop())).name("<green><bold>Pole " + slot + ": " + s.crop().name() + " gotowe!").glow()
                        .lore("<gray>Plon: <gold>" + Fmt.money(s.crop().yield()), "", "<yellow>Kliknij, aby zebrać").build(), t -> harvest());
            }
        }
        long ripe = plots.stream().filter(p -> p.state().equals("ready")).count();
        set(40, ItemBuilder.of(Material.IRON_HOE).name("<green><bold>Zbierz plony").glow(ripe > 0).lore(ripe > 0 ? "<yellow>Gotowych pól: " + ripe : "<dark_gray>Nic jeszcze nie dojrzało").build(), t -> harvest());
        set(42, ItemBuilder.of(Material.WHEAT_SEEDS).name("<green><bold>Zasadź na wszystkich wolnych polach").lore("<gray>Wybierzesz uprawę, a bot zasadzi ją", "<gray>na tylu polach, na ile starczy monet.").build(), t -> go(new CropGui(plugin, player, null)));
        backTo(49, () -> go(new MainMenu(plugin, player)));
    }

    static Material cropIcon(Catalog.Crop c) {
        String[] ids = {"marchew", "ziemniak", "kukurydza", "pomidor", "dynia", "winogrona", "zen_szen"};
        for (int i = 0; i < ids.length; i++) if (c != null && ids[i].equals(c.id())) return CROP_ICONS[i];
        return Material.WHEAT;
    }

    private void collect() {
        Empire.Pending p = plugin.empire().collect(id, eco().now());
        if (p.total() <= 0) {
            Fx.deny(player);
            T.send(player, p.parts().isEmpty() ? "<red>Nie masz jeszcze żadnego biznesu." : "<gray>Biznesy dopiero zaczęły zarabiać, wróć za chwilę.");
        } else {
            Fx.coin(player);
            T.send(player, "<green>Odebrano zarobki z biznesów: <gold>" + Fmt.money(p.total()));
        }
        redraw();
    }

    private void buy(String bizId) {
        Empire.Buy r = plugin.empire().buyOrUpgrade(id, bizId, eco().now());
        if (!r.ok()) {
            Fx.deny(player);
            T.send(player, switch (r.reason()) {
                case "max" -> "<gold>" + r.biz().name() + " ma już maksymalny poziom.";
                case "poor" -> "<red>" + r.biz().name() + " kosztuje <gold>" + Fmt.money(r.cost()) + "</gold>, a masz <gold>" + Fmt.money(eco().wallet(id)) + "</gold>.";
                default -> "<red>Nie ma takiego biznesu.";
            });
        } else {
            Fx.win(player);
            T.send(player, (r.bought() ? "<green>Nowy biznes: <white>" : "<green>Ulepszono: <white>") + r.biz().name() + "</white> <gray>(poziom " + r.level() + ") za <gold>" + Fmt.money(r.cost()) + "</gold>."
                    + (r.collected() > 0 ? " <gray>Zaległy dochód: <gold>" + Fmt.money(r.collected()) : ""));
        }
        redraw();
    }

    private void harvest() {
        Empire.Harvest h = plugin.empire().harvest(id, eco().now());
        if (h.count() == 0) {
            Fx.deny(player);
            T.send(player, "<gray>Nic jeszcze nie dojrzało. Sprawdź, ile zostało, w menu farmy.");
        } else {
            Fx.coin(player);
            T.send(player, "<green>Zebrano plony z <white>" + h.count() + "</white> pól. Plon wart <gold>" + Fmt.money(h.gain()));
        }
        redraw();
    }
}
