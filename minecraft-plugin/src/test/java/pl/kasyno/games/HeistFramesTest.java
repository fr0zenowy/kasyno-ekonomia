package pl.kasyno.games;

import org.junit.jupiter.api.Test;
import pl.kasyno.core.Heist;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Klatki animacji napadu są spójne ze scenariuszem: ekipa, wpadki, mnożnik i kolejność nigdy się nie rozjeżdżają. */
class HeistFramesTest {
    @Test
    void framesFollowTheScriptForEveryCrewAndOutcome() {
        Random rng = new Random(21);
        int downEarly = 0;
        int escaped = 0;
        for (int run = 0; run < 3_000; run++) {
            int n = 2 + rng.nextInt(9);
            List<UUID> crew = new ArrayList<>();
            for (int i = 0; i < n; i++) crew.add(UUID.randomUUID());
            Heist.Result res = Heist.run(crew, u -> "gracz", rng);
            List<HeistFrames.Frame> frames = HeistFrames.build(res, crew);

            assertFalse(frames.isEmpty());
            int total = HeistFrames.totalTicks(frames);
            assertTrue(total >= 60 && total <= 700, "czas filmu: " + total + " ticków");
            int lastCaught = 0;
            double lastMult = 0;
            for (HeistFrames.Frame f : frames) {
                assertTrue(f.ticks() > 0);
                assertTrue(f.token() >= -1 && f.token() <= 44, "token poza oknem: " + f.token());
                assertTrue(f.alive() >= 0 && f.alive() <= n);
                assertEquals(n - Integer.bitCount(f.caught()), f.alive(), "ocaleli = ekipa minus złapani");
                assertEquals(0, f.caught() & ~((1 << n) - 1), "maska złapanych tylko w obrębie ekipy");
                assertEquals(lastCaught, f.caught() & lastCaught, "złapany nie może „odżyć”");
                lastCaught = f.caught();
                assertTrue(f.mult() >= lastMult - 1e-9, "mnożnik nie spada");
                lastMult = f.mult();
                for (int slot : f.props().keySet()) assertTrue(slot >= 1 && slot <= 44 && slot % 9 != 0 && slot % 9 != 8, "rekwizyt poza mapą: " + slot);
                if (f.token() >= 0) assertTrue(f.token() % 9 >= 1 && f.token() % 9 <= 7, "token na portretach: " + f.token());
                assertFalse(f.text() == null || f.text().isBlank());
                for (int i : f.newly()) assertTrue(i >= 0 && i < n);
            }
            HeistFrames.Frame last = frames.get(frames.size() - 1);
            assertEquals(res.survivors().size(), last.alive(), "ostatnia klatka zgadza się z wynikiem");
            assertEquals(res.caught().size(), Integer.bitCount(last.caught()));
            for (UUID u : res.caught()) assertTrue((last.caught() & (1 << crew.indexOf(u))) != 0);
            // nowo złapani w klatkach to dokładnie ci ze scenariusza, każdy raz
            int newlyTotal = 0;
            for (HeistFrames.Frame f : frames) newlyTotal += f.newly().length;
            assertEquals(res.caught().size(), newlyTotal);
            if (res.success()) {
                escaped++;
                assertEquals(HeistFrames.Cue.ESCAPE, last.cue());
                assertEquals(res.multiplier(), last.mult(), 1e-9);
            } else downEarly++;
            // wstęp: van podjeżdża klatka po klatce
            assertEquals(HeistFrames.Cue.ENGINE, frames.get(0).cue());
            assertTrue(frames.get(0).props().containsValue(HeistFrames.Prop.VAN));
        }
        assertTrue(escaped > 500 && downEarly > 100, "uciekli " + escaped + ", wpadli " + downEarly);
    }
}
