package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Moon's famous places for the Moon map (3.1): its seas, bays, lakes and marshes, its mountain ranges, valleys and
 * cliffs, and every crater big enough for the Pixel's 5x telephoto to show (about 55 km and up, a stacked pixel being
 * about 8 km there), plus the small bright ones that shine out anyway (Aristarchus, Kepler, Proclus). Each has where it
 * is, how big, and its story, for an 11-year-old and his dad.
 * <p>
 * Positions and sizes are the IAU's (the Gazetteer of Planetary Nomenclature), as Wikipedia's lists of lunar maria,
 * features and craters give them: selenographic latitude and longitude in degrees, north and east positive (east
 * towards Mare Crisium), the size in km across (a valley's or cliff's length). The seas were named in 1651 by the
 * Jesuit astronomer Giovanni Riccioli after the weather and moods; the craters mostly after astronomers.
 */
public final class MoonFeatures {
    private MoonFeatures() {}

    public enum Kind {
        /** A dark lava plain: a mare, an oceanus, a sinus (bay), a lacus (lake) or a palus (marsh). */
        SEA,
        CRATER,
        /** A mountain range or a single mountain. */
        MOUNTAINS,
        /** A valley, a cliff or a rille (a long crack or channel). */
        VALLEY
    }

    public static final class Feature {
        /** A short key, the name on the map, and its Latin name (the same as the name for craters). */
        public final String key, name, latin;
        public final double lat, lon, km;
        public final Kind kind;
        /** 1: one of the famous ones, named first; 2: next; 3: only if there is room. */
        public final int rank;
        public final String story;

        Feature(String key, Kind kind, int rank, String name, String latin, double lat, double lon, double km, String story) {
            this.key = key;
            this.kind = kind;
            this.rank = rank;
            this.name = name;
            this.latin = latin;
            this.lat = lat;
            this.lon = lon;
            this.km = km;
            this.story = story;
        }

        /** "Sea of Tranquility (Mare Tranquillitatis), 873 km across" / "Crater, 96 km across". */
        public String what() {
            String size = km >= 10 ? String.format(java.util.Locale.US, "%,.0f km", km) : "";
            switch (kind) {
                case SEA:
                    return name + " (" + latin + "), " + size + " across";
                case MOUNTAINS:
                    return name + (latin.equals(name) ? "" : " (" + latin + ")") + ", " + size + (latin.startsWith("Mons ") ? " across" : " long");
                case VALLEY:
                    return name + (latin.equals(name) ? "" : " (" + latin + ")") + ", " + size + " long";
                default:
                    return name + ", a crater " + size + " across";
            }
        }
    }

    private static final List<Feature> LIST = new ArrayList<Feature>();

    private static void sea(String key, int rank, String name, String latin, double lat, double lon, double km, String story) {
        LIST.add(new Feature(key, Kind.SEA, rank, name, latin, lat, lon, km, story));
    }

    private static void crater(String name, int rank, double lat, double lon, double km, String story) {
        LIST.add(new Feature(name.toLowerCase(java.util.Locale.US).replace(' ', '_').replace("ö", "o"), Kind.CRATER, rank, name, name, lat, lon, km, story));
    }

    private static void mountains(String key, int rank, String name, String latin, double lat, double lon, double km, String story) {
        LIST.add(new Feature(key, Kind.MOUNTAINS, rank, name, latin, lat, lon, km, story));
    }

    private static void valley(String key, int rank, String name, String latin, double lat, double lon, double km, String story) {
        LIST.add(new Feature(key, Kind.VALLEY, rank, name, latin, lat, lon, km, story));
    }

    static {
        // ---- the seas: old lava that flooded giant craters 3 to 4 billion years ago (no water: early astronomers took
        // the dark patches for oceans)
        sea("procellarum", 1, "Ocean of Storms", "Oceanus Procellarum", 18.4, -57.4, 2568,
                "The biggest of the Moon's dark plains, about 2,500 km across. Luna 9 made the first soft landing on another world "
                        + "here in 1966, and Apollo 12 landed here in 1969. The dark \"seas\" are old lava, not water: astronomers "
                        + "who first saw them through telescopes thought they were oceans.");
        sea("imbrium", 1, "Sea of Rains", "Mare Imbrium", 32.8, -15.6, 1123,
                "A giant crater 1,100 km across, blasted out by an asteroid about 3.9 billion years ago and later flooded with "
                        + "lava. The mountains around it (the Apennines, the Alps, the Carpathians) are its rim. The rovers "
                        + "Lunokhod 1 and China's Yutu drove here.");
        sea("serenitatis", 1, "Sea of Serenity", "Mare Serenitatis", 28.0, 17.5, 707,
                "Apollo 17, the last crewed landing, set down by its eastern shore, and the Soviet rover Lunokhod 2 drove along "
                        + "its edge. Its lava is browner than the Sea of Tranquility's next door: try Mineral colours.");
        sea("tranquillitatis", 1, "Sea of Tranquility", "Mare Tranquillitatis", 8.5, 31.4, 873,
                "Where Apollo 11 landed on 20 July 1969: \"Houston, Tranquility Base here. The Eagle has landed.\" Its lava "
                        + "is rich in titanium, which makes it a little bluer than the other seas (try Mineral colours).");
        sea("crisium", 1, "Sea of Crises", "Mare Crisium", 17.0, 59.1, 418,
                "A round sea all on its own near the Moon's eastern edge. From Earth it looks taller than wide, but it is really "
                        + "wider: we see it at a slant. Luna 24 brought soil home from here in 1976, and Blue Ghost landed here in "
                        + "2025.");
        sea("fecunditatis", 1, "Sea of Fertility", "Mare Fecunditatis", -7.8, 51.3, 909,
                "Luna 16 landed here in 1970: the first robot to dig up Moon soil and fly it back to Earth all by itself.");
        sea("nectaris", 2, "Sea of Nectar", "Mare Nectaris", -15.2, 35.5, 333,
                "A small round sea with a chain of three big craters on its western shore: Theophilus, Cyrillus and Catharina. "
                        + "The asteroid that dug it out about 3.9 billion years ago gives its name to a whole age of the Moon's "
                        + "history, the Nectarian period.");
        sea("nubium", 1, "Sea of Clouds", "Mare Nubium", -21.3, -16.6, 715,
                "A wide sea south of the Moon's middle. Look on its eastern side for the Straight Wall, a cliff that looks as "
                        + "if it was drawn with a ruler.");
        sea("humorum", 2, "Sea of Moisture", "Mare Humorum", -24.4, -38.6, 389,
                "A round sea with the crater Gassendi on its northern shore. Lava pushed Gassendi's floor up from below and "
                        + "cracked it into long grooves.");
        sea("frigoris", 1, "Sea of Cold", "Mare Frigoris", 56.0, 1.4, 1596,
                "A long thin sea stretching across the Moon's far north, above the dark crater Plato and the Alps.");
        sea("vaporum", 2, "Sea of Vapours", "Mare Vaporum", 13.3, 3.6, 245,
                "A small sea near the middle of the Moon, with the Apennine Mountains along its northern side.");
        sea("insularum", 2, "Sea of Islands", "Mare Insularum", 7.5, -30.9, 513,
                "The sea between the bright rayed craters Copernicus and Kepler, with islands of older, higher ground poking "
                        + "up through its lava.");
        sea("cognitum", 3, "Sea that has become Known", "Mare Cognitum", -10.0, -23.1, 376,
                "Named in 1964, after Ranger 7 sent the first close-up photos of the Moon from here just before it hit. "
                        + "Apollo 12 and Apollo 14 landed just to its north.");
        sea("spumans", 3, "Foaming Sea", "Mare Spumans", 1.1, 65.1, 139,
                "A small sea near the Moon's eastern edge, beside the Sea of Fertility.");
        sea("undarum", 3, "Sea of Waves", "Mare Undarum", 6.8, 68.4, 243,
                "Patches of dark lava near the Moon's eastern edge.");
        sea("smythii", 3, "Smyth's Sea", "Mare Smythii", 1.3, 87.5, 373,
                "Right on the Moon's eastern edge: it shows only when the Moon's slow wobble (its libration) turns it towards "
                        + "us. Named after William Henry Smyth, an astronomer and admiral.");
        sea("marginis", 3, "Sea of the Edge", "Mare Marginis", 13.3, 86.1, 420,
                "On the very edge of the Moon: its wobble shows it some nights and hides it on others.");
        sea("australe", 3, "Southern Sea", "Mare Australe", -38.9, 93.0, 603,
                "Dark patches round the south-east edge, mostly on the side we never see.");
        sea("humboldtianum", 3, "Humboldt's Sea", "Mare Humboldtianum", 56.8, 81.5, 273,
                "A sea on the north-east edge, named after the explorer Alexander von Humboldt.");
        sea("orientale", 3, "Eastern Sea", "Mare Orientale", -19.4, -92.8, 327,
                "On the Moon's western edge, but called the Eastern Sea: in 1961 astronomers swapped which side of the Moon "
                        + "they call east. Around it is a giant bullseye of mountain rings 930 km across, mostly round the "
                        + "edge, out of sight.");
        sea("anguis", 3, "Serpent Sea", "Mare Anguis", 22.6, 67.7, 150,
                "A thin snaking patch of lava north-east of the Sea of Crises.");
        sea("iridum", 1, "Bay of Rainbows", "Sinus Iridum", 44.1, -31.5, 236,
                "A bay of lava ringed by the Jura Mountains. About ten days after New Moon the Sun rises over it: the mountain "
                        + "tops catch the light first and make a \"golden handle\" sticking out into the dark.");
        sea("medii", 2, "Central Bay", "Sinus Medii", 2.4, 1.7, 335,
                "The middle of the Moon as we see it: the spot that always faces the Earth. Surveyor 6 made the first hop on "
                        + "the Moon here in 1967.");
        sea("roris", 3, "Bay of Dew", "Sinus Roris", 54.0, -56.6, 202,
                "Where the Ocean of Storms reaches into the north.");
        sea("aestuum", 3, "Seething Bay", "Sinus Aestuum", 10.9, -8.8, 290,
                "A bay of dark lava east of Copernicus, with some of the darkest volcanic ash on the Moon.");
        sea("amoris", 2, "Bay of Love", "Sinus Amoris", 18.1, 39.1, 130,
                "One of the Moon's funniest names: Riccioli named the seas in 1651 after the weather and moods, so there's a "
                        + "Bay of Love, a Lake of Dreams, a Marsh of Sleep and a Lake of Death.");
        sea("asperitatis", 3, "Bay of Roughness", "Sinus Asperitatis", -3.8, 27.4, 206,
                "Where the Sea of Tranquility flows south towards the Sea of Nectar.");
        sea("concordiae", 3, "Bay of Harmony", "Sinus Concordiae", 10.8, 43.2, 142,
                "A bay on the eastern side of the Sea of Tranquility.");
        sea("honoris", 3, "Bay of Honour", "Sinus Honoris", 11.7, 18.1, 109,
                "A small bay on the western side of the Sea of Tranquility.");
        sea("putredinis", 2, "Marsh of Decay", "Palus Putredinis", 26.5, 0.4, 161,
                "A lava plain at the foot of the Apennine Mountains. Apollo 15 landed at its eastern edge, by the winding "
                        + "Hadley Rille.");
        sea("somni", 2, "Marsh of Sleep", "Palus Somni", 14.1, 45.0, 143,
                "A plain between the Sea of Tranquility and the Sea of Crises.");
        sea("epidemiarum", 3, "Marsh of Epidemics", "Palus Epidemiarum", -32.0, -28.2, 286,
                "A small lava plain south-west of the Sea of Clouds.");
        sea("somniorum", 2, "Lake of Dreams", "Lacus Somniorum", 38.0, 29.2, 384,
                "A lava plain north-east of the Sea of Serenity.");
        sea("mortis", 2, "Lake of Death", "Lacus Mortis", 45.0, 27.2, 151,
                "A dark lava plain with the crater B\u00fcrg in the middle and long cracks across it. Not as spooky as it sounds.");
        sea("felicitatis", 3, "Lake of Happiness", "Lacus Felicitatis", 19.0, 5.0, 90,
                "A little patch of lava north of the Sea of Vapours.");
        sea("veris", 3, "Lake of Spring", "Lacus Veris", -16.5, -86.1, 396,
                "Lava between the mountain rings of the Eastern Sea, on the Moon's western edge.");

        // ---- mountains, valleys and cliffs
        mountains("apenninus", 1, "Apennine Mountains", "Montes Apenninus", 19.87, -0.03, 401,
                "The Moon's grandest mountains, part of the rim of the giant crater that became the Sea of Rains. Mons Huygens, "
                        + "about 5.5 km high, is among them. Apollo 15 landed at their foot.");
        mountains("alpes", 1, "The Alps", "Montes Alpes", 48.36, -0.58, 281,
                "Mountains on the Sea of Rains' northern rim, cut in two by the Alpine Valley, a straight trench 166 km long.");
        mountains("caucasus", 2, "Caucasus Mountains", "Montes Caucasus", 37.52, 9.93, 445,
                "Peaks up to about 6 km high between the Sea of Rains and the Sea of Serenity.");
        mountains("carpatus", 2, "Carpathian Mountains", "Montes Carpatus", 14.57, -23.62, 361,
                "The southern rim of the Sea of Rains, just north of Copernicus.");
        mountains("jura", 2, "Jura Mountains", "Montes Jura", 47.49, -36.11, 422,
                "The arc of mountains round the Bay of Rainbows. When the Sun rises on them they shine like a golden handle.");
        mountains("haemus", 3, "Haemus Mountains", "Montes Haemus", 17.11, 12.03, 560,
                "The southern rim of the Sea of Serenity.");
        mountains("pyrenaeus", 3, "Pyrenees", "Montes Pyrenaeus", -14.05, 41.51, 164,
                "Mountains along the eastern shore of the Sea of Nectar.");
        mountains("riphaeus", 3, "Riphaeus Mountains", "Montes Riphaeus", -7.48, -27.60, 189,
                "The peaks of an old crater's wall poking up through the lava, near where Apollo 12 landed.");
        mountains("teneriffe", 3, "Teneriffe Mountains", "Montes Teneriffe", 47.89, -13.19, 182,
                "Lonely peaks in the northern Sea of Rains, named after the island of Tenerife.");
        mountains("piton", 3, "Mons Piton", "Mons Piton", 40.72, -0.92, 25,
                "A lone mountain 2.3 km high standing in the Sea of Rains. When the Sun is low it casts a long pointed shadow.");
        mountains("pico", 3, "Mons Pico", "Mons Pico", 45.82, -8.87, 25,
                "A lone peak about 2.4 km high in the Sea of Rains, south of Plato.");
        valley("alpes_vallis", 1, "Alpine Valley", "Vallis Alpes", 48.5, 3.2, 166,
                "A straight valley 166 km long that cuts right through the Alps, as if a giant had run a finger through them. "
                        + "A thin channel of old lava winds along its floor.");
        valley("schroteri", 2, "Schröter's Valley", "Vallis Schröteri", 26.2, -50.8, 168,
                "The Moon's biggest winding valley, carved by flowing lava, next to the bright crater Aristarchus. It starts in "
                        + "a crater called the Cobra's Head.");
        valley("recta", 1, "Straight Wall", "Rupes Recta", -22.1, -7.8, 110,
                "A cliff about 110 km long and 300 m high, so straight it looks drawn with a ruler. Around first quarter it "
                        + "faces away from the Sun and shows as a dark line; around last quarter it catches the Sun and shines as "
                        + "a bright one.");
        valley("hyginus", 3, "Hyginus Rille", "Rima Hyginus", 7.4, 7.8, 219,
                "A long crack with a small crater in the middle where it bends, near the centre of the Moon.");
        valley("ariadaeus", 3, "Ariadaeus Rille", "Rima Ariadaeus", 6.4, 14.0, 250,
                "A long straight valley where the ground dropped between two cracks in the crust.");

        // ---- the craters, named mostly after astronomers, mathematicians and explorers
        crater("Tycho", 1, -43.30, -11.22, 85,
                "Young for the Moon, about 108 million years old: dinosaurs were alive when it formed. Its bright rays splash "
                        + "across a quarter of the Moon and shine brightest at full Moon. Named after Tycho Brahe, who measured "
                        + "the planets so carefully that Kepler could work out how they orbit.");
        crater("Copernicus", 1, 9.62, -20.08, 96,
                "The \"Monarch of the Moon\": almost 4 km deep, with mountains in the middle, terraced walls and bright rays. "
                        + "About 800 million years old. Named after Nicolaus Copernicus, who said the Earth goes round the Sun.");
        crater("Kepler", 1, 8.1, -38.0, 31,
                "Small but bright, with rays. Named after Johannes Kepler, who found the laws of the planets' orbits, and who "
                        + "wrote one of the first science fiction stories: a trip to the Moon.");
        crater("Aristarchus", 1, 23.7, -47.4, 40,
                "The brightest spot on the Moon, so bright it can even be seen on the dark part lit only by the Earth. Named "
                        + "after Aristarchus of Samos, who said 2,200 years ago that the Earth goes round the Sun.");
        crater("Plato", 1, 51.62, -9.38, 101,
                "Its floor of old lava is so smooth and dark that it looks like a lake in the mountains. Named after the Greek "
                        + "philosopher.");
        crater("Clavius", 1, -58.62, -14.73, 231,
                "One of the biggest craters we can see, with a curved line of smaller craters across its floor getting smaller "
                        + "one after another. The Moon base in the film 2001: A Space Odyssey was here, and in 2020 the SOFIA "
                        + "flying telescope found water in its sunlit ground.");
        crater("Grimaldi", 1, -5.2, -68.6, 172,
                "One of the darkest patches on the Moon: a lava-filled crater near the western edge. Named after Francesco "
                        + "Grimaldi, who drew the famous Moon map of 1651 with Riccioli.");
        crater("Langrenus", 1, -8.86, 61.04, 132,
                "Bright walls with mountains in the middle, near the eastern edge. Named after Michael van Langren, who drew one "
                        + "of the first maps of the Moon in 1645.");
        crater("Petavius", 1, -25.39, 60.78, 184,
                "A big crater with mountains in the middle and a long crack running from them to its wall.");
        crater("Theophilus", 1, -11.45, 26.28, 99,
                "About 4 km deep, with a cluster of mountains in the middle. The youngest of a chain of three: it broke into "
                        + "Cyrillus's wall, and Catharina to the south is the oldest.");
        crater("Cyrillus", 2, -13.29, 24.07, 98,
                "The middle crater of the chain Theophilus, Cyrillus, Catharina: older than Theophilus, which cut into its wall.");
        crater("Catharina", 2, -17.98, 23.55, 99,
                "The oldest of the chain Theophilus, Cyrillus, Catharina, its walls worn by later hits.");
        crater("Ptolemaeus", 1, -9.16, -1.84, 154,
                "A huge flat-floored crater near the middle of the Moon, first in a chain of three going south with Alphonsus "
                        + "and Arzachel. Named after Ptolemy, whose astronomy book was used for 1,400 years.");
        crater("Alphonsus", 1, -13.4, -2.8, 110,
                "Ranger 9 dived into it in 1965, with its pictures shown live on American TV. Its floor has small dark spots of "
                        + "volcanic ash.");
        crater("Arzachel", 2, -18.2, -1.9, 97,
                "The southern end of the chain Ptolemaeus, Alphonsus, Arzachel, with sharp terraced walls and a peak in the "
                        + "middle.");
        crater("Archimedes", 1, 29.7, -4.0, 83,
                "A crater with a smooth lava floor in the Sea of Rains. The first thing from Earth ever to reach the Moon, "
                        + "Luna 2, crashed near it in 1959. Named after the Greek inventor.");
        crater("Eratosthenes", 1, 14.47, -11.32, 59,
                "At the end of the Apennine Mountains. Named after the Greek who measured the size of the whole Earth "
                        + "2,200 years ago, with shadows and some clever geometry.");
        crater("Posidonius", 1, 31.88, 29.99, 95,
                "On the shore of the Sea of Serenity, its floor crossed by winding cracks.");
        crater("Proclus", 1, 16.09, 46.89, 27,
                "Small but very bright, with rays that fan out to one side only: the rock that made it came in at a low angle.");
        crater("Albategnius", 2, -11.2, 4.1, 114,
                "An old crater near the middle of the Moon, named after the Arab astronomer al-Battani.");
        crater("Gassendi", 1, -17.6, -40.1, 110,
                "On the shore of the Sea of Moisture. Lava pushed its floor up from below and cracked it into long grooves.");
        crater("Schickard", 2, -44.4, -54.6, 227,
                "A giant near the south-western edge, squashed into an oval because we see it at a slant. Named after Wilhelm "
                        + "Schickard, who built a calculating machine in 1623.");
        crater("Endymion", 2, 53.61, 56.48, 122,
                "A dark-floored crater near the north-eastern edge, named after the shepherd in a Greek myth loved by Selene, "
                        + "the Moon goddess.");
        crater("Atlas", 2, 46.7, 44.4, 87,
                "Named after the giant who held up the sky. Its neighbour Hercules is named after the hero who held the sky "
                        + "for him once.");
        crater("Hercules", 2, 46.7, 39.1, 69,
                "Named after the hero who once held up the sky for the giant Atlas: the crater Atlas is right next door.");
        crater("Bailly", 2, -66.82, -68.9, 301,
                "The biggest crater on the side of the Moon we see, 300 km across, but it's on the south-western edge so we see "
                        + "it at a steep slant.");
        crater("Maginus", 2, -50.03, -5.98, 156,
                "An old worn crater south of Tycho. At full Moon, with no shadows, it almost disappears.");
        crater("Longomontanus", 2, -49.55, -21.88, 146,
                "An old crater in the crowded southern highlands, named after Tycho Brahe's assistant.");
        crater("Walther", 2, -33.25, 0.62, 134, "An old walled plain in the southern highlands, with a peak off-centre.");
        crater("Stöfler", 2, -41.1, 6.0, 126, "A crater with a flat floor in the southern highlands, its eastern wall broken by Faraday.");
        crater("Maurolycus", 2, -41.77, 13.92, 115, "A big crater in the southern highlands with craters piled on its walls.");
        crater("Janssen", 2, -44.9, 41.5, 199,
                "An ancient crater in the south-east, so old that later craters have broken its walls. A long crack runs "
                        + "across its floor.");
        crater("Hipparchus", 1, -5.1, 5.2, 138,
                "An old worn crater near the middle of the Moon. Named after Hipparchus, who made the first catalogue of the "
                        + "stars over 2,100 years ago.");
        crater("Pythagoras", 2, 63.68, -62.98, 145,
                "A big crater near the north-western edge, named after the Greek mathematician of the right-angled triangle.");
        crater("Cleomedes", 2, 27.6, 55.5, 131, "A big crater just north of the Sea of Crises.");
        crater("Furnerius", 3, -36.0, 60.54, 135, "A big old crater near the south-eastern edge.");
        crater("Fracastorius", 2, -21.36, 33.07, 121,
                "A crater whose northern wall was melted away by the lava of the Sea of Nectar, leaving a bay.");
        crater("Pitatus", 2, -29.88, -13.53, 101, "A crater on the southern shore of the Sea of Clouds, half flooded by its lava.");
        crater("Wargentin", 2, -49.53, -60.44, 85,
                "A crater filled to the brim with lava, like a pie, near the south-western edge: the Moon's only big one like it.");
        crater("Riccioli", 3, -3.3, -74.6, 139,
                "Near the western edge. Named after Giovanni Riccioli, who in 1651 named the craters after astronomers and the "
                        + "seas after the weather and moods.");
        crater("Hevelius", 3, 2.2, -67.6, 115,
                "Near the western edge. Named after Johannes Hevelius, who drew one of the first detailed Moon maps in 1647.");
        crater("Schiller", 3, -51.9, -40.0, 180, "A long crater shaped like a footprint, near the south-western edge.");
        crater("Moretus", 3, -70.63, -5.95, 114, "A big crater near the south pole, with a tall mountain in the middle.");
        crater("Aristoteles", 2, 50.2, 17.4, 87,
                "Named after the Greek philosopher Aristotle. With Eudoxus it makes a pair north of the Caucasus Mountains.");
        crater("Eudoxus", 2, 44.27, 16.23, 70, "Named after the Greek astronomer Eudoxus: the southern one of a pair with Aristoteles.");
        crater("Cassini", 2, 40.25, 4.64, 57,
                "A lava-flooded crater with two smaller craters inside. Named after Giovanni Cassini, who found four of "
                        + "Saturn's moons and the gap in its rings.");
        crater("Aristillus", 2, 33.9, 1.2, 55, "A crater with mountains in the middle, in the Sea of Rains next to Archimedes.");
        crater("Autolycus", 3, 30.7, 1.5, 39, "A small crater in the Sea of Rains between Aristillus and Archimedes.");
        crater("Bullialdus", 2, -20.75, -22.26, 61,
                "A textbook crater in the Sea of Clouds: terraced walls and mountains in the middle.");
        crater("Abulfeda", 3, -13.8, 13.9, 62, "A crater in the central highlands, named after a Syrian geographer.");
        crater("Piccolomini", 2, -29.7, 32.2, 88, "A crater at the end of a long curved cliff, the Altai Scarp, south-west of the Sea of Nectar.");
        crater("Macrobius", 3, 21.26, 45.97, 63, "A crater north-west of the Sea of Crises.");
        crater("Taruntius", 3, 5.5, 46.54, 57, "A crater on the northern edge of the Sea of Fertility.");
        crater("Vendelinus", 2, -16.46, 61.55, 141, "An old worn crater near the eastern edge, between Langrenus and Petavius.");
        crater("Le Monnier", 2, 26.66, 30.5, 68,
                "A bay on the eastern shore of the Sea of Serenity, where the Soviet rover Lunokhod 2 landed in 1973 and drove "
                        + "39 km.");
        crater("Julius Caesar", 2, 9.0, 15.4, 90,
                "A worn crater named after the Roman general, who also gave us the calendar with leap years.");
        crater("Fra Mauro", 1, -6.06, -16.97, 97,
                "An old worn crater. Apollo 14 landed in the hills just north of it in 1971: Apollo 13 was meant to, until an "
                        + "oxygen tank exploded on the way.");
        crater("Letronne", 3, -10.5, -42.49, 118, "A crater on the shore of the Ocean of Storms: lava flooded its northern half.");
        crater("Mersenius", 3, -21.49, -49.34, 84, "A crater on the western shore of the Sea of Moisture.");
        crater("Purbach", 3, -25.51, -2.03, 115, "An old crater in the southern highlands, north of Regiomontanus.");
        crater("Regiomontanus", 3, -28.3, -1.0, 108, "An old crater in the southern highlands, its walls broken by Purbach's.");
        crater("Werner", 3, -28.03, 3.29, 71, "A crater with sharp walls in the southern highlands.");
        crater("Deslandres", 3, -32.55, -5.57, 227,
                "An ancient crater so worn it's hard to see. There's a crater called Hell inside it, named after the "
                        + "astronomer Maximilian Hell.");
        crater("Capuanus", 3, -34.09, -26.73, 60, "A lava-flooded crater on the southern shore of the Marsh of Epidemics.");
        crater("Byrgius", 3, -24.73, -65.38, 84, "A crater near the western edge.");
        crater("Messala", 3, 39.31, 60.06, 122, "An old crater north of the Sea of Crises.");
        crater("Geminus", 3, 34.5, 56.7, 85, "A crater with terraced walls north of the Sea of Crises.");
        crater("Humboldt", 3, -27.2, 80.9, 207, "A giant crater on the eastern edge, its floor cracked in many places.");
        crater("Gauss", 3, 35.7, 79.0, 177, "A big crater on the north-eastern edge, named after the mathematician Carl Friedrich Gauss.");
        crater("Struve", 3, 22.4, -77.1, 170, "A flooded old crater on the western edge.");
        crater("Olbers", 3, 7.3, -76.14, 73, "A crater near the western edge, named after the astronomer who asked why the night sky is dark.");
        crater("Snellius", 3, -29.3, 55.7, 83, "An old crater near the south-eastern edge.");
        crater("Stevinus", 3, -32.5, 54.2, 74, "A crater near the south-eastern edge; its small neighbour Stevinus A has bright rays.");
        crater("Sacrobosco", 3, -23.7, 16.7, 98, "An old crater in the highlands west of the Sea of Nectar.");
        crater("Rabbi Levi", 3, -34.7, 23.6, 81, "An old crater in the southern highlands.");
        crater("Rheita", 3, -37.1, 47.2, 70, "A crater at the end of the Rheita Valley, a chain of craters 500 km long.");
        crater("Fabricius", 3, -42.75, 41.84, 79, "A crater inside the giant old crater Janssen.");
        crater("Metius", 3, -40.42, 43.37, 84, "A crater beside Fabricius in the south-eastern highlands.");
        crater("Zagut", 3, -31.94, 21.89, 79, "An old crater in the southern highlands.");
        crater("Steinheil", 3, -48.6, 46.5, 67, "A crater in the far south-east, joined to its twin Watt.");
        crater("Anaxagoras", 3, 73.4, -10.1, 51,
                "A young bright crater near the north pole, with rays. Named after the Greek who worked out that the Moon "
                        + "shines by the Sun's light.");
        crater("Philolaus", 3, 72.22, -32.88, 71, "A crater near the north pole, named after a Greek who said the Earth moves.");
    }

    /** Every feature, the seas first. */
    public static final List<Feature> ALL = Collections.unmodifiableList(LIST);

    public static Feature get(String key) {
        for (Feature f : LIST) if (f.key.equals(key)) return f;
        return null;
    }

    /**
     * The place {@code km} along a bearing (degrees from the Moon's north through east) from a place on the Moon, as
     * {lat, lon} in degrees: for a crater's rim on the map.
     */
    public static double[] along(double latDeg, double lonDeg, double bearingDeg, double km) {
        double a = km / MoonGeo.RADIUS_KM, la = Math.toRadians(latDeg), t = Math.toRadians(bearingDeg);
        double lat2 = Math.asin(Math.sin(la) * Math.cos(a) + Math.cos(la) * Math.sin(a) * Math.cos(t));
        double lon2 = Math.toRadians(lonDeg) + Math.atan2(Math.sin(t) * Math.sin(a) * Math.cos(la), Math.cos(a) - Math.sin(la) * Math.sin(lat2));
        return new double[]{Math.toDegrees(lat2), MoonGeo.norm180(Math.toDegrees(lon2))};
    }
}
