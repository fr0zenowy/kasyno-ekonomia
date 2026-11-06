package pl.kasyno;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import pl.kasyno.games.GameRegistry;
import pl.kasyno.gui.CardIcons;
import pl.kasyno.gui.GuiManager;
import pl.kasyno.gui.Screens;
import pl.kasyno.pack.PackManager;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Paczka zasobów: spójność plików, zgodność z kodem (ikony, tła) i przełączanie wyglądu okien zależnie od gracza. */
class PackTest {
    static {
        System.setProperty("kasyno.test", "1");
    }

    ServerMock server;
    KasynoPlugin plugin;
    PlayerMock withPack;
    PlayerMock plain;

    @BeforeEach
    void setUp() {
        GuiManager.clickGapMs = 0;
        server = MockBukkit.mock();
        plugin = MockBukkit.load(KasynoPlugin.class);
        withPack = server.addPlayer();
        plain = server.addPlayer();
        for (PlayerMock p : new PlayerMock[]{withPack, plain}) {
            PlayerListener.prepare(plugin, p);
            plugin.eco().pd(p.getUniqueId()).wallet = 10_000;
        }
        plugin.packs().markLoaded(withPack, true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Map<String, byte[]> zipEntries() throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (InputStream in = PackTest.class.getResourceAsStream("/pack/KasynoUI.zip")) {
            assertNotNull(in, "paczka jest w zasobach pluginu");
            try (ZipInputStream zin = new ZipInputStream(in)) {
                for (ZipEntry e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) out.put(e.getName(), zin.readAllBytes());
            }
        }
        return out;
    }

    private static PackManager.Index index() throws IOException {
        try (InputStream in = PackTest.class.getResourceAsStream("/pack/pack-index.json")) {
            assertNotNull(in);
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), PackManager.Index.class);
        }
    }

    private static String iconOf(ItemStack item) {
        return item == null || !item.hasItemMeta() ? null : item.getItemMeta().getPersistentDataContainer().get(pl.kasyno.gui.ItemBuilder.ICON_KEY, org.bukkit.persistence.PersistentDataType.STRING);
    }

    // ---------- pliki paczki ----------

    @Test
    void zipHasEveryFileTheFontAndModelsPointTo() throws IOException {
        Map<String, byte[]> z = zipEntries();
        assertTrue(z.containsKey("pack.mcmeta"));
        JsonObject meta = new Gson().fromJson(new String(z.get("pack.mcmeta"), StandardCharsets.UTF_8), JsonObject.class).getAsJsonObject("pack");
        assertEquals(75, meta.get("min_format").getAsInt(), "format paczki dla 1.21.11");
        assertEquals(75, meta.get("max_format").getAsInt());
        assertTrue(z.containsKey("assets/minecraft/textures/gui/container/generic_54.png"), "podstawa okna");

        PackManager.Index idx = index();
        JsonObject font = new Gson().fromJson(new String(z.get("assets/kasyno/font/gui.json"), StandardCharsets.UTF_8), JsonObject.class);
        JsonArray providers = font.getAsJsonArray("providers");
        Set<String> glyphs = new HashSet<>();
        for (var el : providers) {
            JsonObject p = el.getAsJsonObject();
            if (!p.get("type").getAsString().equals("bitmap")) continue;
            String file = p.get("file").getAsString(); // kasyno:font/bg_x.png
            String path = "assets/kasyno/textures/" + file.substring(file.indexOf(':') + 1);
            assertTrue(z.containsKey(path), "brak pliku " + path);
            var img = ImageIO.read(new ByteArrayInputStream(z.get(path)));
            assertTrue(img.getWidth() <= 256 && img.getHeight() <= 256, "glif większy niż 256x256: " + path);
            assertEquals(176, img.getWidth(), path);
            assertEquals(p.get("height").getAsInt(), img.getHeight(), "height musi się równać wysokości grafiki: " + path);
            assertTrue(p.get("ascent").getAsInt() <= p.get("height").getAsInt(), "ascent nie może przekraczać height");
            String ch = p.getAsJsonArray("chars").get(0).getAsString();
            assertEquals(1, ch.length());
            assertTrue(glyphs.add(ch), "znak użyty dwa razy");
            // okienka na przedmioty: slot (0,0) ma przezroczystą dziurę 16x16 na dokładnych współrzędnych okna
            int extra = img.getHeight() - 222;
            assertEquals(0, img.getRGB(8, extra + 18) >>> 24, "dziura na pierwszy slot: " + path);
            assertEquals(0, img.getRGB(8 + 15, extra + 18 + 15) >>> 24);
            assertTrue((img.getRGB(7, extra + 18) >>> 24) > 0, "ramka slotu ma być nieprzezroczysta: " + path);
        }
        assertEquals(new HashSet<>(idx.backgrounds.values()), glyphs, "indeks i czcionka mają te same znaki");

        for (String icon : idx.icons) {
            assertTrue(z.containsKey("assets/kasyno/textures/item/" + icon + ".png"), icon);
            assertTrue(z.containsKey("assets/kasyno/models/item/" + icon + ".json"), icon);
            assertTrue(z.containsKey("assets/kasyno/items/" + icon + ".json"), icon);
            var img = ImageIO.read(new ByteArrayInputStream(z.get("assets/kasyno/textures/item/" + icon + ".png")));
            assertEquals(16, img.getWidth());
            assertEquals(16, img.getHeight());
        }
        for (String name : z.keySet()) assertFalse(name.contains("\\"), "nazwy w ZIP muszą mieć ukośniki w przód: " + name);
    }

    /** Każda ikona, o którą prosi kod (.icon("..."), withIcon), istnieje w paczce, a każdy ekran ma swoje tło. */
    @Test
    void everyIconAndArtUsedByCodeExistsInPack() throws IOException {
        PackManager.Index idx = index();
        Pattern icon = Pattern.compile("\\.icon\\(\\s*\"([a-z0-9_]+)\"\\s*\\)|withIcon\\([^,]+,\\s*\"([a-z0-9_]+)\"");
        Set<String> used = new HashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = icon.matcher(Files.readString(f));
                while (m.find()) used.add(m.group(1) != null ? m.group(1) : m.group(2));
            }
        }
        for (GameRegistry.Def g : GameRegistry.all()) used.add(g.id());
        for (GameRegistry.Hall h : GameRegistry.HALLS) used.add("hall_" + h.id());
        used.addAll(Set.of("play", "cashout", "info", "h_door", "h_camera", "h_vault", "h_loot", "h_car", "h_siren", "h_cuffs"));
        assertTrue(used.size() > 30, "wykryto " + used.size() + " ikon");
        for (String u : used) assertTrue(idx.icons.contains(u), "brak ikony w paczce: " + u);
    }

    @Test
    void everyScreenHasItsOwnBackground() throws IOException {
        PackManager.Index idx = index();
        String defaultGlyph = idx.backgrounds.get("default");
        assertNotNull(defaultGlyph);
        for (String key : Screens.keys()) {
            plugin.eco().pd(withPack.getUniqueId()).wallet = 1_000_000;
            assertTrue(Screens.open(plugin, withPack, key), key);
            server.getScheduler().performOneTick();
            String title = PlainTextComponentSerializer.plainText().serialize(withPack.getOpenInventory().title());
            assertTrue(title.contains(idx.shift), key + ": tytuł zaczyna się od przesunięcia");
            assertFalse(title.contains(defaultGlyph), "ekran " + key + " nie ma własnego tła (używa domyślnego)");
            assertTrue(idx.backgrounds.values().stream().anyMatch(title::contains), key);
            withPack.closeInventory();
            server.getScheduler().performOneTick();
        }
    }

    // ---------- talia kart ----------

    /** Wszystkie 52 karty i rewers są w paczce jako osobne, różne grafiki 16x16 z pełnym zestawem plików modelu. */
    @Test
    void deckHasEveryCardAndTheBack() throws IOException {
        PackManager.Index idx = index();
        Map<String, byte[]> z = zipEntries();
        List<String> expected = new ArrayList<>(List.of(CardIcons.BACK));
        for (int suit = 0; suit < 4; suit++) for (int rank = 1; rank <= 13; rank++) expected.add(CardIcons.of(rank, suit));
        assertEquals(53, expected.size());
        assertEquals(53, new HashSet<>(expected).size(), "każda karta ma własną nazwę ikony");
        assertEquals(53, idx.icons.stream().filter(i -> i.startsWith("card_")).count(), "w indeksie nie ma obcych kart");

        Set<String> looks = new HashSet<>();
        for (String icon : expected) {
            assertNotNull(icon);
            assertTrue(idx.icons.contains(icon), "brak karty w indeksie: " + icon);
            byte[] tex = z.get("assets/kasyno/textures/item/" + icon + ".png");
            assertNotNull(tex, "brak tekstury: " + icon);
            var img = ImageIO.read(new ByteArrayInputStream(tex));
            assertEquals(16, img.getWidth(), icon);
            assertEquals(16, img.getHeight(), icon);
            assertEquals(0, img.getRGB(0, 0) >>> 24, icon + ": karta ma przezroczysty margines");
            assertTrue((img.getRGB(7, 8) >>> 24) == 255, icon + ": środek karty jest pełny");
            assertTrue(looks.add(Arrays.toString(img.getRGB(0, 0, 16, 16, null, 0, 16))), icon + ": wygląda tak samo jak inna karta");

            JsonObject model = new Gson().fromJson(new String(z.get("assets/kasyno/models/item/" + icon + ".json"), StandardCharsets.UTF_8), JsonObject.class);
            assertEquals("minecraft:item/generated", model.get("parent").getAsString());
            assertEquals("kasyno:item/" + icon, model.getAsJsonObject("textures").get("layer0").getAsString());
            // w oknie płaska ikona, poza oknem (ręka, ziemia, ramka, głowa) model 3D
            JsonObject def = new Gson().fromJson(new String(z.get("assets/kasyno/items/" + icon + ".json"), StandardCharsets.UTF_8), JsonObject.class).getAsJsonObject("model");
            assertEquals("minecraft:select", def.get("type").getAsString());
            assertEquals("minecraft:display_context", def.get("property").getAsString());
            JsonObject gui = def.getAsJsonArray("cases").get(0).getAsJsonObject();
            assertEquals("gui", gui.get("when").getAsString());
            assertEquals("kasyno:item/" + icon, gui.getAsJsonObject("model").get("model").getAsString(), "w oknie płaska ikona");
            assertEquals("kasyno:card/" + icon.substring("card_".length()), def.getAsJsonObject("fallback").get("model").getAsString(), "poza oknem model 3D");
        }

        assertEquals(CardIcons.of(1, 2), CardIcons.of(14, 2), "as w hi-lo (14) to ta sama karta co as w blackjacku (1)");
        assertEquals("card_h_k", CardIcons.of(13, 1));
        assertEquals("card_s_10", CardIcons.of(10, 0));
        assertNull(CardIcons.of(0, 0));
        assertNull(CardIcons.of(15, 0));
        assertNull(CardIcons.of(5, 4));
        assertNull(CardIcons.of(5, -1));
    }

    /** Model 3D każdej karty: cienki prostopadłościan z awersem, rewersem i brzegiem, z teksturami 50x70 o tych samych proporcjach co karta. */
    @Test
    void cardsHaveThreeDimensionalModels() throws IOException {
        Map<String, byte[]> z = zipEntries();
        Gson gson = new Gson();
        Set<String> looks = new HashSet<>();
        int models = 0;
        for (String icon : index().icons) {
            if (!icon.startsWith("card_")) continue;
            String key = icon.substring("card_".length());
            JsonObject model = gson.fromJson(new String(z.get("assets/kasyno/models/card/" + key + ".json"), StandardCharsets.UTF_8), JsonObject.class);
            models++;

            JsonObject tex = model.getAsJsonObject("textures");
            for (String name : List.of("face", "back", "edge", "particle")) {
                String ref = tex.get(name).getAsString();
                assertTrue(ref.startsWith("kasyno:item/card/"), icon + ": tekstura " + name + " = " + ref);
                assertTrue(z.containsKey("assets/kasyno/textures/" + ref.substring("kasyno:".length()) + ".png"), icon + ": brak tekstury " + ref);
            }
            assertEquals("kasyno:item/card/" + key, tex.get("face").getAsString(), "awers to tekstura tej karty");
            assertEquals("kasyno:item/card/back", tex.get("back").getAsString());

            JsonObject el = model.getAsJsonArray("elements").get(0).getAsJsonObject();
            double[] from = new double[3];
            double[] to = new double[3];
            for (int i = 0; i < 3; i++) {
                from[i] = el.getAsJsonArray("from").get(i).getAsDouble();
                to[i] = el.getAsJsonArray("to").get(i).getAsDouble();
                assertTrue(from[i] >= -16 && to[i] <= 32 && to[i] > from[i], icon + ": bryła poza dopuszczalnym zakresem modelu");
            }
            assertTrue(to[2] - from[2] <= 1, icon + ": karta ma być cienka");
            JsonObject faces = el.getAsJsonObject("faces");
            assertEquals("#face", faces.getAsJsonObject("south").get("texture").getAsString(), "awers od południa, jak strona ze zwykłych przedmiotów");
            assertEquals("#back", faces.getAsJsonObject("north").get("texture").getAsString());
            for (String side : List.of("east", "west", "up", "down")) assertEquals("#edge", faces.getAsJsonObject(side).get("texture").getAsString(), side);
            for (String ctx : List.of("ground", "head", "thirdperson_righthand", "firstperson_righthand", "fixed")) assertTrue(model.getAsJsonObject("display").has(ctx), icon + ": brak display." + ctx);

            var face = ImageIO.read(new ByteArrayInputStream(z.get("assets/kasyno/textures/item/card/" + key + ".png")));
            assertEquals(50, face.getWidth(), key);
            assertEquals(70, face.getHeight(), key);
            assertEquals((to[0] - from[0]) / (to[1] - from[1]), (double) face.getWidth() / face.getHeight(), 0.01, icon + ": proporcje bryły i tekstury");
            for (int y = 0; y < face.getHeight(); y++) for (int x = 0; x < face.getWidth(); x++) assertEquals(255, face.getRGB(x, y) >>> 24, key + ": karta jest nieprzezroczysta");
            assertTrue(looks.add(Arrays.toString(face.getRGB(0, 0, 50, 70, null, 0, 50))), key + ": wygląda tak samo jak inna karta");
        }
        assertEquals(53, models, "52 karty i rewers");
        assertEquals(53, z.keySet().stream().filter(n -> n.startsWith("assets/kasyno/models/card/")).count(), "bez zbędnych modeli");
        assertEquals(54, z.keySet().stream().filter(n -> n.startsWith("assets/kasyno/textures/item/card/")).count(), "53 grafiki i brzeg, bez zbędnych");
    }

    /** Modele piłek dla pluginu Sport: przedmiot, model z elementów, tekstura z mapą kuli, i zgodność UV z rozmiarem tekstury. */
    @Test
    void ballModelsAreWellFormed() throws IOException {
        Map<String, byte[]> z = zipEntries();
        Gson gson = new Gson();
        for (String ball : List.of("basket", "volley")) {
            JsonObject def = gson.fromJson(new String(z.get("assets/kasyno/items/ball_" + ball + ".json"), StandardCharsets.UTF_8), JsonObject.class).getAsJsonObject("model");
            assertEquals("minecraft:model", def.get("type").getAsString());
            assertEquals("kasyno:ball/" + ball, def.get("model").getAsString());

            JsonObject model = gson.fromJson(new String(z.get("assets/kasyno/models/ball/" + ball + ".json"), StandardCharsets.UTF_8), JsonObject.class);
            String tex = model.getAsJsonObject("textures").get("ball").getAsString();
            assertEquals("kasyno:item/ball/" + ball, tex);
            assertEquals(tex, model.getAsJsonObject("textures").get("particle").getAsString());
            var img = ImageIO.read(new ByteArrayInputStream(z.get("assets/kasyno/textures/item/ball/" + ball + ".png")));
            int tw = img.getWidth();
            int th = img.getHeight();
            assertFalse(model.has("texture_size"), "UV w modelu to zawsze skala 0-16 (gra ignoruje texture_size)");
            assertTrue(tw % 16 == 0 && th % 16 == 0, ball + ": rozmiar tekstury podzielny przez 16 (atlas): " + tw + "x" + th);
            for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++) assertEquals(255, img.getRGB(x, y) >>> 24, ball + ": tekstura nieprzezroczysta");

            JsonArray elements = model.getAsJsonArray("elements");
            assertTrue(elements.size() >= 12 && elements.size() <= 64, ball + ": liczba warstw " + elements.size());
            double minY = 99;
            double maxY = -99;
            double minX = 99;
            double maxX = -99;
            for (var e : elements) {
                JsonObject el = e.getAsJsonObject();
                double[] from = new double[3];
                double[] to = new double[3];
                for (int i = 0; i < 3; i++) {
                    from[i] = el.getAsJsonArray("from").get(i).getAsDouble();
                    to[i] = el.getAsJsonArray("to").get(i).getAsDouble();
                    assertTrue(from[i] >= -16 && to[i] <= 32 && to[i] > from[i], ball + ": element poza zakresem modelu");
                }
                minY = Math.min(minY, from[1]);
                maxY = Math.max(maxY, to[1]);
                double turned = 0;
                if (el.has("rotation")) {
                    JsonObject rot = el.getAsJsonObject("rotation");
                    double angle = rot.get("angle").getAsDouble();
                    turned = Math.toRadians(angle);
                    assertTrue(Math.abs(angle) <= 45 && angle % 22.5 == 0, ball + ": kąt obrotu elementu musi być wielokrotnością 22,5 w zakresie -45..45: " + angle);
                    assertEquals("y", rot.get("axis").getAsString());
                }
                // rzeczywista połowa szerokości warstwy po obrocie (kwadrat obrócony o 45 stopni sięga dalej niż jego nieobrócone boki)
                double reach = (to[0] - from[0]) / 2 * (Math.abs(Math.cos(turned)) + Math.abs(Math.sin(turned)));
                minX = Math.min(minX, 8 - reach);
                maxX = Math.max(maxX, 8 + reach);
                JsonObject faces = el.getAsJsonObject("faces");
                for (String dir : faces.keySet()) {
                    assertTrue(List.of("north", "south", "east", "west", "up", "down").contains(dir), dir);
                    JsonArray uv = faces.getAsJsonObject(dir).getAsJsonArray("uv");
                    double u0 = uv.get(0).getAsDouble();
                    double v0 = uv.get(1).getAsDouble();
                    double u1 = uv.get(2).getAsDouble();
                    double v1 = uv.get(3).getAsDouble();
                    assertTrue(u0 >= 0 && v0 >= 0 && u1 <= 16 && v1 <= 16, ball + ": UV poza skalą 0-16 " + uv);
                    assertTrue(u1 > u0 && v1 > v0, ball + ": UV odwrócone " + uv);
                    assertEquals("#ball", faces.getAsJsonObject(dir).get("texture").getAsString());
                }
                assertTrue(faces.has("north") && faces.has("south") && faces.has("east") && faces.has("west"), ball + ": warstwa ma cztery ściany boczne");
                assertTrue(faces.has("up") ^ faces.has("down"), ball + ": odsłonięty pierścień tylko z jednej strony warstwy");
            }
            assertEquals(0, minY, 0.01, ball + ": kula od dołu bloku");
            assertEquals(16, maxY, 0.01, ball + ": kula do góry bloku");
            assertTrue(maxX - minX > 14 && maxX - minX < 18, ball + ": szerokość kuli ok. 16: " + (maxX - minX));
            for (String ctx : List.of("gui", "ground", "head", "thirdperson_righthand", "firstperson_righthand", "fixed")) assertTrue(model.getAsJsonObject("display").has(ctx), ball + ": brak display." + ctx);
            JsonObject fixed = model.getAsJsonObject("display").getAsJsonObject("fixed");
            for (var sc : fixed.getAsJsonArray("scale")) assertEquals(1.0, sc.getAsDouble(), 1e-9, ball + ": skala fixed musi być 1, bo plugin skaluje piłkę sam");
        }
        // wygląd: koszykówka jest pomarańczowa z ciemnymi szwami, siatkówka ma biel, żółć i błękit
        var basket = ImageIO.read(new ByteArrayInputStream(z.get("assets/kasyno/textures/item/ball/basket.png")));
        int orange = 0;
        int dark = 0;
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 80; x++) {
                int c = basket.getRGB(x, y);
                int r = (c >> 16) & 255;
                int g = (c >> 8) & 255;
                int b = c & 255;
                if (r > 170 && g > 70 && g < 150 && b < 70) orange++;
                if (r < 90 && g < 70 && b < 60) dark++;
            }
        }
        assertTrue(orange > 80 * 32 * 0.6, "koszykówka głównie pomarańczowa: " + orange);
        assertTrue(dark > 40, "koszykówka ma ciemne szwy: " + dark);
        var volley = ImageIO.read(new ByteArrayInputStream(z.get("assets/kasyno/textures/item/ball/volley.png")));
        int white = 0;
        int yellow = 0;
        int blue = 0;
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 80; x++) {
                int c = volley.getRGB(x, y);
                int r = (c >> 16) & 255;
                int g = (c >> 8) & 255;
                int b = c & 255;
                if (r > 220 && g > 215 && b > 190) white++;
                if (r > 220 && g > 170 && b < 90) yellow++;
                if (b > 150 && r < 90) blue++;
            }
        }
        int total = 80 * 32;
        assertTrue(white > total * 0.15 && yellow > total * 0.15 && blue > total * 0.15, "siatkówka ma trzy kolory: " + white + "/" + yellow + "/" + blue);
        assertEquals(2, z.keySet().stream().filter(n -> n.startsWith("assets/kasyno/models/ball/")).count(), "bez zbędnych modeli piłek");
        assertEquals(2, z.keySet().stream().filter(n -> n.startsWith("assets/kasyno/textures/item/ball/")).count());
    }

    /**
     * Do atlasu tekstur trafiają tylko katalogi block/ i item/, więc tekstury modeli 3D (karty, piłki) poza nimi pokazałyby się jako
     * fioletowo-czarna „brakująca tekstura”. Sprawdzamy każdy model w paczce.
     */
    @Test
    void everyTextureOfA3dModelLivesInAnAtlasDirectory() throws IOException {
        Map<String, byte[]> z = zipEntries();
        Gson gson = new Gson();
        int models = 0;
        for (String name : z.keySet()) {
            if (!name.startsWith("assets/kasyno/models/") || !name.endsWith(".json")) continue;
            JsonObject model = gson.fromJson(new String(z.get(name), StandardCharsets.UTF_8), JsonObject.class);
            if (!model.has("textures")) continue;
            models++;
            for (var e : model.getAsJsonObject("textures").entrySet()) {
                String ref = e.getValue().getAsString();
                if (ref.startsWith("#")) continue;
                assertTrue(ref.startsWith("kasyno:item/") || ref.startsWith("kasyno:block/") || ref.startsWith("minecraft:"), name + ": tekstura " + ref + " leży poza atlasem");
                if (ref.startsWith("kasyno:")) assertTrue(z.containsKey("assets/kasyno/textures/" + ref.substring("kasyno:".length()) + ".png"), name + ": brak pliku tekstury " + ref);
            }
        }
        assertTrue(models >= 55, "zbadano modele kart, piłek i ikon: " + models);
    }

    private static final Pattern CARD_NAME =Pattern.compile("^(A|[2-9]|10|J|Q|K) ([♠♥♦♣])$");

    /** Ikona, jakiej wymaga karta o nazwie z okna (np. „10 ♥”), liczona niezależnie od CardIcons. */
    private static String iconForCardName(String name) {
        Matcher m = CARD_NAME.matcher(name);
        assertTrue(m.matches(), "to nie jest nazwa karty: " + name);
        String suit = switch (m.group(2)) {
            case "♠" -> "s";
            case "♥" -> "h";
            case "♦" -> "d";
            default -> "c";
        };
        return "card_" + suit + "_" + m.group(1).toLowerCase();
    }

    private static String plainName(ItemStack item) {
        return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
    }

    /** Sprawdza karty w podanych slotach: odkryte mają ikonę swojej figury i koloru, zakryte rewers. Zwraca liczbę kart i czy był rewers. */
    private static int[] checkCards(PlayerMock p, int... slots) {
        int cards = 0;
        int backs = 0;
        for (int slot : slots) {
            ItemStack it = p.getOpenInventory().getTopInventory().getItem(slot);
            if (it == null || !it.hasItemMeta() || it.getItemMeta().displayName() == null) continue;
            String name = plainName(it);
            if (name.equals("Zakryta karta")) {
                assertEquals("card_back", iconOf(it), "zakryta karta w slocie " + slot);
                backs++;
            } else if (CARD_NAME.matcher(name).matches()) {
                assertEquals(iconForCardName(name), iconOf(it), "karta „" + name + "” w slocie " + slot);
                cards++;
            }
        }
        return new int[]{cards, backs};
    }

    private void startGame(PlayerMock p, String game) {
        plugin.eco().pd(p.getUniqueId()).wallet = 10_000;
        plugin.eco().pd(p.getUniqueId()).activeStake = 0;
        assertTrue(Screens.open(plugin, p, game));
        server.getScheduler().performOneTick();
        p.simulateInventoryClick(49); // rozdaj / zacznij grę
        server.getScheduler().performOneTick();
    }

    private void endGame(PlayerMock p) {
        p.closeInventory();
        server.getScheduler().performTicks(100);
    }

    /** Blackjack pokazuje graczowi z paczką karty z talii (zakrytą kartę krupiera jako rewers), a graczowi bez paczki zwykły papier. */
    @Test
    void blackjackShowsDeckCards() {
        int[] dealer = {10, 11, 12, 13, 14, 15, 16};
        int[] hand = {28, 29, 30, 31, 32, 33, 34};
        boolean sawBack = false;
        for (int round = 0; round < 25; round++) {
            startGame(withPack, "blackjack");
            int[] mine = checkCards(withPack, hand);
            int[] theirs = checkCards(withPack, dealer);
            assertTrue(mine[0] >= 2, "ręka gracza: " + mine[0]);
            assertTrue(theirs[0] + theirs[1] >= 2, "ręka krupiera: " + (theirs[0] + theirs[1]));
            sawBack |= theirs[1] > 0;
            endGame(withPack);
        }
        assertTrue(sawBack, "przy zakrytej karcie krupiera widać rewers");

        long before = plugin.packs().skinned();
        for (int round = 0; round < 5; round++) {
            startGame(plain, "blackjack");
            for (int slot : hand) {
                ItemStack it = plain.getOpenInventory().getTopInventory().getItem(slot);
                if (it == null || it.getType().isAir() || !it.hasItemMeta() || it.getItemMeta().displayName() == null) continue;
                if (CARD_NAME.matcher(plainName(it)).matches()) assertEquals(org.bukkit.Material.PAPER, it.getType(), "bez paczki karta to zwykły papier");
            }
            endGame(plain);
        }
        assertEquals(before, plugin.packs().skinned(), "gracz bez paczki nie dostaje podmienionych ikon kart");
    }

    /** Hi-Lo: zakryta karta przed grą, potem odkryta karta i historia z ikonami zgodnymi z nazwami. */
    @Test
    void hiloShowsDeckCards() {
        for (int round = 0; round < 10; round++) {
            plugin.eco().pd(withPack.getUniqueId()).wallet = 10_000;
            plugin.eco().pd(withPack.getUniqueId()).activeStake = 0;
            assertTrue(Screens.open(plugin, withPack, "hilo"));
            server.getScheduler().performOneTick();
            assertEquals("card_back", iconOf(withPack.getOpenInventory().getTopInventory().getItem(22)), "przed grą karta jest zakryta");
            withPack.simulateInventoryClick(49);
            server.getScheduler().performOneTick();
            assertEquals(1, checkCards(withPack, 22)[0], "po starcie widać pierwszą kartę");
            withPack.simulateInventoryClick(round % 2 == 0 ? 30 : 32); // wyżej albo niżej
            server.getScheduler().performTicks(40);
            int[] shown = checkCards(withPack, 22, 10, 11, 12, 13, 14, 15, 16);
            assertTrue(shown[0] >= 1, "po zgadywaniu karty nadal mają ikony");
            endGame(withPack);
        }

        long before = plugin.packs().skinned();
        startGame(plain, "hilo");
        ItemStack card = plain.getOpenInventory().getTopInventory().getItem(22);
        assertEquals(org.bukkit.Material.PAPER, card.getType(), "bez paczki karta to zwykły papier");
        endGame(plain);
        assertEquals(before, plugin.packs().skinned());
    }

    // ---------- wygląd zależny od gracza ----------

    @Test
    void windowsLookCustomOnlyForPlayersWithPack() throws IOException {
        PackManager.Index idx = index();
        assertTrue(Screens.open(plugin, withPack, "menu"));
        server.getScheduler().performOneTick();

        String t1 = PlainTextComponentSerializer.plainText().serialize(withPack.getOpenInventory().title());
        assertEquals(idx.shift + idx.backgrounds.get("menu"), t1);

        // atrapa serwera nie przechowuje modelu przedmiotu, więc liczymy podmiany: okno gracza z paczką podmienia ikony, okno drugiego nie
        assertEquals("m_bank", iconOf(withPack.getOpenInventory().getTopInventory().getItem(16)));
        assertEquals("blank", iconOf(withPack.getOpenInventory().getTopInventory().getItem(0)), "wypełniacz jest niewidoczny");
        assertTrue(plugin.packs().skinned() >= 10, "podmienione ikony: " + plugin.packs().skinned());
        long afterFirst = plugin.packs().skinned();
        assertTrue(Screens.open(plugin, plain, "menu"));
        server.getScheduler().performOneTick();
        assertEquals(afterFirst, plugin.packs().skinned(), "gracz bez paczki nie dostaje podmienionych ikon");
    }

    /** Okna spoza listy ekranów (sceny, napad, pojedynek, loteria) też mają własne tło i zawsze 54 sloty. */
    @Test
    void sceneAndRoomWindowsHaveTheirOwnArt() throws IOException {
        PackManager.Index idx = index();
        plugin.eco().pd(withPack.getUniqueId()).wallet = 50_000;
        server.dispatchCommand(withPack, "zarobki");
        server.getScheduler().performOneTick();
        withPack.simulateInventoryClick(16); // przestępstwo: scena
        server.getScheduler().performTicks(2);
        assertEquals("SceneGui", plugin.guis().current(withPack).getClass().getSimpleName());
        assertEquals(idx.shift + idx.backgrounds.get("scena_przestepstwo"), PlainTextComponentSerializer.plainText().serialize(withPack.getOpenInventory().title()));
        assertEquals(54, withPack.getOpenInventory().getTopInventory().getSize());
        withPack.closeInventory();

        for (var kind : pl.kasyno.games.Rooms.Kind.values()) {
            new pl.kasyno.gui.screens.RoomGui(plugin, withPack, kind).open();
            server.getScheduler().performOneTick();
            String art = kind == pl.kasyno.games.Rooms.Kind.JACKPOT ? "jackpot" : "napad";
            assertEquals(idx.shift + idx.backgrounds.get(art), PlainTextComponentSerializer.plainText().serialize(withPack.getOpenInventory().title()));
            assertEquals(54, withPack.getOpenInventory().getTopInventory().getSize());
            withPack.closeInventory();
        }
    }

    /** Nazwa tła (np. "scena_rob") z tytułu okna, które gracz ma teraz otwarte. */
    private String artOfOpenWindow(PlayerMock p, PackManager.Index idx) {
        String title = PlainTextComponentSerializer.plainText().serialize(p.getOpenInventory().title());
        assertTrue(title.startsWith(idx.shift), "tytuł zaczyna się od przesunięcia: " + title);
        String glyph = title.substring(idx.shift.length());
        return idx.backgrounds.entrySet().stream().filter(e -> e.getValue().equals(glyph)).map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new AssertionError("tytuł okna nie jest tłem z paczki: " + title));
    }

    private void expectOwnArt(Map<String, String> seen, PackManager.Index idx, String what) {
        String art = artOfOpenWindow(withPack, idx);
        assertNotEquals("default", art, what + " nie ma własnego tła");
        String other = seen.put(art, what);
        assertNull(other, what + " ma to samo tło (" + art + ") co " + other);
        withPack.closeInventory();
        server.getScheduler().performTicks(2);
    }

    /**
     * Żadne dwa różne okna nie są podpisane tak samo: ekrany i gry, każda scena akcji zarobkowej, każdy wybór gracza (przelew,
     * okradanie, pojedynek), trivia i matma, zawód, loteria i sale. Okna otwieramy prawdziwymi kliknięciami, jak robi to gracz.
     */
    @Test
    void everyKindOfWindowHasItsOwnTitle() throws IOException {
        PackManager.Index idx = index();
        long wallet = 1_000_000;
        plugin.eco().pd(withPack.getUniqueId()).wallet = wallet;
        plugin.eco().addItem(withPack.getUniqueId(), "wedka", 1);
        plugin.eco().addItem(withPack.getUniqueId(), "kilof", 1);
        plugin.eco().addItem(withPack.getUniqueId(), "strzelba", 1);
        Map<String, String> seen = new HashMap<>();

        for (String key : Screens.keys()) {
            assertTrue(Screens.open(plugin, withPack, key), key);
            server.getScheduler().performOneTick();
            expectOwnArt(seen, idx, "ekran " + key);
        }

        // okna otwierane z ekranu zarobków: sceny, okradanie, trivia, matma, zawód
        Object[][] earn = {{16, "scena przestępstwa"}, {13, "scena pracy"}, {14, "scena zbierania drobnych"}, {15, "scena grajka"}, {19, "scena łowienia"},
                {20, "scena kopalni"}, {21, "scena polowania"}, {22, "scena grzybobrania"}, {23, "scena śmietników"}, {30, "wybór ofiary"}, {28, "trivia"}, {29, "matma"}, {33, "zawód"}};
        for (Object[] e : earn) {
            assertTrue(Screens.open(plugin, withPack, "zarobki"));
            server.getScheduler().performOneTick();
            withPack.simulateInventoryClick((Integer) e[0]);
            server.getScheduler().performTicks(2);
            assertNotEquals("EarnGui", plugin.guis().current(withPack).getClass().getSimpleName(), "kliknięcie " + e[1] + " otwiera nowe okno");
            expectOwnArt(seen, idx, (String) e[1]);
        }

        assertTrue(Screens.open(plugin, withPack, "bank"));
        server.getScheduler().performOneTick();
        withPack.simulateInventoryClick(40);
        server.getScheduler().performTicks(2);
        expectOwnArt(seen, idx, "wybór odbiorcy przelewu");

        assertTrue(Screens.open(plugin, withPack, "pokoj"));
        server.getScheduler().performOneTick();
        withPack.simulateInventoryClick(15);
        server.getScheduler().performTicks(2);
        expectOwnArt(seen, idx, "wybór przeciwnika w pojedynku");

        for (var kind : pl.kasyno.games.Rooms.Kind.values()) {
            new pl.kasyno.gui.screens.RoomGui(plugin, withPack, kind).open();
            server.getScheduler().performOneTick();
            expectOwnArt(seen, idx, "sala " + kind);
        }
        new pl.kasyno.gui.screens.LotteryGui(plugin, withPack).open();
        server.getScheduler().performOneTick();
        expectOwnArt(seen, idx, "loteria");

        assertTrue(seen.size() > 50, "sprawdzono " + seen.size() + " rodzajów okien");
        for (String art : seen.keySet()) assertTrue(idx.backgrounds.containsKey(art), art);
    }

    @Test
    void customGuiCanBeSwitchedOffInConfig() {
        plugin.getConfig().set("custom-gui", "off");
        assertFalse(plugin.packs().custom(withPack));
        assertTrue(Screens.open(plugin, withPack, "menu"));
        server.getScheduler().performOneTick();
        long before = plugin.packs().skinned();
        server.getScheduler().performOneTick();
        assertEquals(before, plugin.packs().skinned(), "przy wyłączonym wyglądzie nic się nie podmienia");
        plugin.getConfig().set("custom-gui", "auto");
        assertTrue(plugin.packs().custom(withPack));
    }

    /** Gracz, który wgrał paczkę ręcznie (serwer o tym nie wie), włącza nowy wygląd komendą. */
    @Test
    void playerCanSwitchLookManually() throws IOException {
        PackManager.Index idx = index();
        assertFalse(plugin.packs().custom(plain), "serwer nie wie o paczce gracza");
        server.dispatchCommand(plain, "kasyno pack on");
        assertTrue(plugin.packs().custom(plain));
        assertTrue(Screens.open(plugin, plain, "bank"));
        server.getScheduler().performOneTick();
        String title = PlainTextComponentSerializer.plainText().serialize(plain.getOpenInventory().title());
        assertEquals(idx.shift + idx.backgrounds.get("bank"), title);
        plain.closeInventory();

        server.dispatchCommand(plain, "kasyno pack off");
        assertFalse(plugin.packs().custom(plain));
        server.dispatchCommand(withPack, "kasyno pack off");
        assertFalse(plugin.packs().custom(withPack), "tryb gracza „off” wygrywa nad paczką załadowaną z serwera");
        server.dispatchCommand(withPack, "kasyno pack auto");
        assertTrue(plugin.packs().custom(withPack));
        server.dispatchCommand(plain, "kasyno pack");
        assertEquals("off", plugin.packs().mode(plain));

        // tryb serwera „on” wymusza nowy wygląd wszystkim (np. paczka z server.properties), „off” wyłącza go wszystkim
        server.dispatchCommand(plain, "kasyno pack auto");
        plugin.getConfig().set("custom-gui", "on");
        assertTrue(plugin.packs().custom(plain));
        plugin.getConfig().set("custom-gui", "off");
        assertFalse(plugin.packs().custom(withPack));
        server.dispatchCommand(plain, "kasyno pack on");
        assertFalse(plugin.packs().custom(plain), "serwerowe „off” wygrywa z wyborem gracza");
    }

    @Test
    void packStatusIsNotKeptAfterQuit() {
        assertTrue(plugin.packs().hasPack(withPack));
        withPack.disconnect();
        assertFalse(plugin.packs().hasPack(withPack));
    }

    @Test
    void playersWithPackStillClickThroughScreens() {
        assertTrue(Screens.open(plugin, withPack, "menu"));
        server.getScheduler().performOneTick();
        withPack.simulateInventoryClick(10); // Kasyno
        server.getScheduler().performOneTick();
        assertEquals("CasinoGui", plugin.guis().current(withPack).getClass().getSimpleName());
        withPack.simulateInventoryClick(19); // pierwsza sala
        server.getScheduler().performOneTick();
        assertEquals("HallGui", plugin.guis().current(withPack).getClass().getSimpleName());
        withPack.simulateInventoryClick(19);
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();
        assertNotEquals("HallGui", plugin.guis().current(withPack).getClass().getSimpleName());
        assertEquals("play", iconOf(withPack.getOpenInventory().getTopInventory().getItem(49)), "przycisk gry ma własną ikonę");
        assertEquals("bet_min", iconOf(withPack.getOpenInventory().getTopInventory().getItem(46)));
        assertEquals("back", iconOf(withPack.getOpenInventory().getTopInventory().getItem(45)));
    }
}
