package pl.kasyno.gui;

import org.bukkit.Material;
import pl.kasyno.core.Catalog;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Ikony przedmiotów ekonomii (materiały Minecrafta). Nazwy czytane przez matchMaterial, więc brak materiału nie psuje pluginu. */
public final class Icons {
    private Icons() {}

    private static final Map<String, String> BY_ID = new HashMap<>();
    /** Przedmioty wyższych poziomów świecą. */
    private static final Set<String> GLOWING = Set.of("super_wedka", "wedka_mistrza", "wedka_legendarna", "zloty_kilof", "diamentowy_kilof",
            "kilof_kosmiczny", "karabin", "luneta", "snajperka", "wielki_koszyk", "skrzypce", "fortepian", "mega_eliksir", "mega_podrecznik",
            "mega_magnes", "super_zegarek", "polisa_platynowa", "tarcza_tytanowa", "tarcza_duza", "polisa_duza", "vip_platynowa", "vip_diamentowa",
            "smok", "jednorozec", "skrzynia_legendarna", "skrzynia_diamentowa", "trofeum", "luks_planeta", "luks_ksiezyc", "luks_satelita",
            "konto_premium", "zloty_grzyb", "meteoryt", "rekin", "zubr", "trufla");

    private static void put(String material, String... ids) {
        for (String id : ids) BY_ID.put(id, material);
    }

    static {
        put("FISHING_ROD", "wedka", "super_wedka", "wedka_mistrza", "wedka_legendarna");
        put("IRON_PICKAXE", "kilof");
        put("GOLDEN_PICKAXE", "zloty_kilof");
        put("DIAMOND_PICKAXE", "diamentowy_kilof");
        put("NETHERITE_PICKAXE", "kilof_kosmiczny");
        put("CROSSBOW", "strzelba");
        put("BOW", "karabin");
        put("SPYGLASS", "luneta");
        put("TRIDENT", "snajperka");
        put("BUNDLE", "koszyk");
        put("ORANGE_BUNDLE", "wielki_koszyk");
        put("NOTE_BLOCK", "gitara", "skrzypce");
        put("JUKEBOX", "fortepian");
        put("TRIPWIRE_HOOK", "klodka");
        put("CARVED_PUMPKIN", "maska");
        put("BELL", "alarm");
        put("IRON_AXE", "lom");
        put("COMPARATOR", "laptop", "stary_telefon");
        put("LEATHER_CHESTPLATE", "garnitur");
        put("MINECART", "samochod", "luks_samochod");
        put("CHAINMAIL_LEGGINGS", "pas_narzedziowy");
        put("TOTEM_OF_UNDYING", "talizman");
        put("ENDER_CHEST", "sejf", "skrzynia_hazardzisty");
        put("WOLF_SPAWN_EGG", "pies");
        put("CAT_SPAWN_EGG", "kot");
        put("PARROT_SPAWN_EGG", "papuga");
        put("BAT_SPAWN_EGG", "sowa");
        put("HORSE_SPAWN_EGG", "jednorozec");
        put("DRAGON_HEAD", "smok");
        put("COPPER_INGOT", "vip_brazowy");
        put("IRON_INGOT", "vip_srebrny");
        put("GOLD_INGOT", "vip_zloty", "zloto");
        put("AMETHYST_SHARD", "vip_platynowa", "luks_pierscien");
        put("NETHER_STAR", "vip_diamentowa");
        put("PAPER", "dyplom", "polisa_mala", "polisa_srednia", "polisa_duza", "polisa_platynowa");
        put("ENCHANTED_BOOK", "doktorat");
        put("GOLD_NUGGET", "konto_oszczednosciowe", "zlota_obraczka");
        put("GOLD_BLOCK", "konto_premium", "trofeum");
        put("VILLAGER_SPAWN_EGG", "menedzer");
        put("WRITABLE_BOOK", "ksiegowy");
        put("WATER_BUCKET", "podlewaczka");
        put("POTION", "eliksir_zarobkow", "mega_eliksir", "eliksir_szczescia");
        put("BOOK", "podrecznik", "mega_podrecznik");
        put("COMPASS", "magnes_lupu", "mega_magnes");
        put("CLOCK", "zegarek", "super_zegarek", "luks_zegarek", "zepsuty_zegarek");
        put("FERN", "koniczyna");
        put("SHIELD", "tarcza_mala", "tarcza_srednia", "tarcza_duza", "tarcza_tytanowa");
        put("CHEST", "skrzynka");
        put("TRAPPED_CHEST", "skrzynia");
        put("BARREL", "skrzynia_zbieracza");
        put("SHULKER_BOX", "skrzynia_diamentowa");
        put("RESPAWN_ANCHOR", "skrzynia_legendarna");
        put("ENDER_EYE", "luks_okulary");
        put("LEATHER_HELMET", "luks_cylinder");
        put("OAK_BOAT", "luks_jacht");
        put("SAND", "luks_wyspa");
        put("STONE_BRICKS", "luks_zamek");
        put("ELYTRA", "luks_odrzutowiec");
        put("LIGHTNING_ROD", "luks_satelita");
        put("END_STONE", "luks_ksiezyc");
        put("HEART_OF_THE_SEA", "luks_planeta");
        put("LEATHER_BOOTS", "but");
        put("COD", "sledz");
        put("SALMON", "karp");
        put("TROPICAL_FISH", "szczupak");
        put("PUFFERFISH", "sum");
        put("COOKED_COD", "tunczyk");
        put("NAUTILUS_SHELL", "rekin");
        put("COBBLESTONE", "kamien");
        put("COAL", "wegiel");
        put("RAW_IRON", "zelazo");
        put("DIAMOND", "diament");
        put("EMERALD", "rubin");
        put("NETHERITE_SCRAP", "meteoryt");
        put("RABBIT", "krolik");
        put("CHICKEN", "kaczka");
        put("BEEF", "jelen");
        put("PORKCHOP", "dzik");
        put("MUTTON", "niedzwiedz");
        put("COOKED_BEEF", "zubr");
        put("SPRUCE_SAPLING", "szyszka");
        put("SWEET_BERRIES", "jagody");
        put("BROWN_MUSHROOM", "kurki");
        put("RED_MUSHROOM", "borowik");
        put("CRIMSON_FUNGUS", "trufla");
        put("GOLDEN_CARROT", "zloty_grzyb");
        put("IRON_NUGGET", "puszka");
        put("GLASS_BOTTLE", "butelka");
    }

    private static Material byType(String type) {
        return switch (type) {
            case "tool" -> Material.IRON_PICKAXE;
            case "boost" -> Material.POTION;
            case "protection" -> Material.PAPER;
            case "crate" -> Material.CHEST;
            case "luxury" -> Material.DIAMOND;
            case "trophy" -> Material.GOLD_BLOCK;
            case "loot" -> Material.IRON_NUGGET;
            default -> Material.NAME_TAG;
        };
    }

    public static Material of(Catalog.Item item) {
        String name = BY_ID.get(item.id());
        Material m = name == null ? null : Material.matchMaterial(name);
        return m != null ? m : byType(item.type());
    }

    public static boolean glows(String id) {
        return GLOWING.contains(id);
    }

    public static Material mat(String name, Material fallback) {
        Material m = Material.matchMaterial(name);
        return m != null ? m : fallback;
    }
}
