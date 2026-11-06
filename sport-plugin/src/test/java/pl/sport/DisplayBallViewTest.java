package pl.sport;

import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.junit.jupiter.api.Test;
import pl.sport.match.BallKind;
import pl.sport.match.BallView;
import pl.sport.match.DisplayBallView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prawdziwy widok piłki (ItemDisplay i cień BlockDisplay), a nie testowa atrapa: tworzenie, ruch, obrót, usuwanie i konfiguracja. */
class DisplayBallViewTest extends SportTestBase {
    private BallView create(boolean volley) {
        return new DisplayBallView(plugin, new Location(world, 10, 65, 10), volley ? BallKind.VOLLEYBALL : BallKind.BASKETBALL, 0.13);
    }

    @Test
    void theBallIsAnItemDisplayWithAShadowAndItMovesWithTheBall() {
        BallView v = create(true);
        assertTrue(v.valid());
        assertEquals(1, world.getEntitiesByClass(ItemDisplay.class).size());
        assertEquals(1, world.getEntitiesByClass(BlockDisplay.class).size(), "cień piłki");
        ItemDisplay ball = world.getEntitiesByClass(ItemDisplay.class).iterator().next();
        assertEquals(org.bukkit.Material.SNOWBALL, ball.getItemStack().getType());
        assertFalse(ball.isPersistent(), "piłka nie jest zapisywana na dysku");

        v.move(new Location(world, 12, 67, 14), 64);
        assertEquals(12, ball.getLocation().getX(), 1e-6);
        assertEquals(67, ball.getLocation().getY(), 1e-6);
        BlockDisplay shadow = world.getEntitiesByClass(BlockDisplay.class).iterator().next();
        assertEquals(64.012, shadow.getLocation().getY(), 1e-3, "cień leży tuż nad podłogą pod piłką");
        assertEquals(14, shadow.getLocation().getZ(), 1e-6);
        v.spin(1, 0, 20); // sprite zawsze patrzy na gracza, więc nic się nie psuje

        v.remove();
        assertFalse(v.valid());
        assertEquals(0, world.getEntitiesByClass(ItemDisplay.class).size());
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size());
    }

    @Test
    void theBasketballUsesItsOwnItemAndTheShadowCanBeSwitchedOff() {
        plugin.getConfig().set("pilka.cien", false);
        BallView v = create(false);
        ItemDisplay ball = world.getEntitiesByClass(ItemDisplay.class).iterator().next();
        assertEquals(org.bukkit.Material.MAGMA_CREAM, ball.getItemStack().getType());
        assertEquals(0, world.getEntitiesByClass(BlockDisplay.class).size());
        v.remove();
    }

    @Test
    void aModelFromTheResourcePackCanBeConfigured() {
        plugin.getConfig().set("pilka.model-siatkowka", "kasyno:ball_volley");
        BallView v = create(true);
        ItemDisplay ball = world.getEntitiesByClass(ItemDisplay.class).iterator().next();
        assertTrue(ball.getItemStack().hasItemMeta());
        v.spin(0, 1, 15);
        v.remove();
    }

    @Test
    void modelsFromThePackAreTheDefaultAndCanBeSwitchedOff() {
        assertEquals("kasyno:ball_volley", plugin.sportConfig().ballModel(BallKind.VOLLEYBALL));
        assertEquals("kasyno:ball_basket", plugin.sportConfig().ballModel(BallKind.BASKETBALL));
        assertEquals("kasyno:ball_volley", plugin.getConfig().getString("pilka.model-siatkowka"), "domyślny config.yml z jara");
        assertEquals("kasyno:ball_basket", plugin.getConfig().getString("pilka.model-koszykowka"));
        // atrapa serwera nie zapamiętuje modelu przedmiotu w ItemStack, więc sprawdzamy tylko, że oba warianty tworzą piłkę bez błędów
        BallView withModel = create(false);
        assertEquals(1, world.getEntitiesByClass(ItemDisplay.class).size());
        withModel.remove();
        plugin.getConfig().set("pilka.model-koszykowka", "");
        assertEquals("kasyno:ball_basket", plugin.sportConfig().ballModel(BallKind.BASKETBALL), "puste pole (stary config.yml) znaczy model domyślny, a nie jego brak");
        plugin.getConfig().set("pilka.model-koszykowka", "brak");
        assertEquals("", plugin.sportConfig().ballModel(BallKind.BASKETBALL), "słowo „brak” wyłącza model");
        plugin.getConfig().set("pilka.model-koszykowka", "moj:model");
        assertEquals("moj:model", plugin.sportConfig().ballModel(BallKind.BASKETBALL));
        plugin.getConfig().set("pilka.model-koszykowka", "brak");
        BallView flat = create(false);
        assertEquals(1, world.getEntitiesByClass(ItemDisplay.class).size());
        flat.remove();
    }

    @Test
    void ballsDoNotSurviveAMatchThatEndsOrAServerThatStops() {
        build("siatkowka", "dv", 50, 50, -90);
        server.dispatchCommand(a, "siatkowka dolacz dv a");
        server.dispatchCommand(b, "siatkowka dolacz dv b");
        // domyślna fabryka daje prawdziwe encje (widok testowy podmieniony na czas testu)
        plugin.setBallFactory((where, kind, radius) -> new DisplayBallView(plugin, where, kind, radius));
        server.dispatchCommand(admin, "siatkowka start natychmiast dv");
        ticks(80);
        assertEquals(1, world.getEntitiesByClass(ItemDisplay.class).size(), "piłka w świecie podczas meczu");
        plugin.matches().shutdown();
        assertEquals(0, world.getEntitiesByClass(ItemDisplay.class).size(), "po wyłączeniu pluginu nie zostaje żadna piłka");
    }
}
