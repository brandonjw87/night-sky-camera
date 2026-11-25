package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every place people and their robots have reached the Moon (3.0, the Moonshot), for the labels on Brandon's own
 * photo and the stories behind them. Coordinates are selenographic, north and east positive, in the Mean Earth /
 * polar axis frame: the six Apollo landers from LRO's photos of them (NSSDC's table, from Wagner et al., Icarus 283,
 * 2017), the others as the "List of artificial objects on the Moon" gives them (from the missions and LRO), IM-1
 * from LRO's photo of it.
 */
public final class MoonSites {
    private MoonSites() {}

    public enum Kind {
        /** People walked here. */
        CREW,
        /** A robot landed (and maybe drove). */
        LANDER,
        /** Meant to hit (a probe photographing to the end, or the first to arrive). */
        IMPACT,
        /** A landing that went wrong. */
        CRASH
    }

    public static final class Site {
        public final String key, name, label, who, date, place;
        public final double lat, lon;
        public final Kind kind;
        /** Who went (crewed landings), "" otherwise. */
        public final String crew;
        /** What happened there, for an 11-year-old and his dad. */
        public final String story;
        /** A laser reflector is there: observatories still bounce lasers off it to measure the Moon's distance. */
        public final boolean reflector;
        public final int year;

        Site(String key, String name, String label, String who, String date, String place, double lat, double lon, Kind kind,
             boolean reflector, String crew, String story) {
            this.key = key;
            this.name = name;
            this.label = label;
            this.who = who;
            this.date = date;
            this.place = place;
            this.lat = lat;
            this.lon = lon;
            this.kind = kind;
            this.reflector = reflector;
            this.crew = crew;
            this.story = story;
            this.year = Integer.parseInt(date.substring(date.length() - 4));
        }

        /** Never seen from the Earth: past the limb even at the widest libration (about 8 degrees). */
        public boolean farSide() {
            return Math.abs(lon) > 98;
        }

        public boolean apollo() {
            return key.startsWith("apollo");
        }
    }

    private static final List<Site> LIST = new ArrayList<Site>();

    private static void add(String key, String name, String label, String who, String date, String place, double lat, double lon,
                            Kind kind, boolean reflector, String crew, String story) {
        LIST.add(new Site(key, name, label, who, date, place, lat, lon, kind, reflector, crew, story));
    }

    static {
        // the six Apollo landings (the lunar modules' descent stages, from LRO)
        add("apollo11", "Apollo 11", "Apollo 11", "NASA", "20 July 1969", "Sea of Tranquility", 0.67416, 23.47314, Kind.CREW, true,
                "Neil Armstrong and Buzz Aldrin landed in Eagle; Michael Collins circled overhead in Columbia",
                "The first people on the Moon. Neil Armstrong stepped down at 10:56 p.m. Eastern time and said \"That's one small step "
                        + "for man, one giant leap for mankind.\" They walked for two and a half hours and left a laser mirror that "
                        + "observatories on Earth still bounce lasers off today.");
        add("apollo12", "Apollo 12", "Apollo 12", "NASA", "19 November 1969", "Ocean of Storms", -3.0128, -23.4219, Kind.CREW, false,
                "Pete Conrad and Alan Bean landed in Intrepid; Dick Gordon circled overhead in Yankee Clipper",
                "Lightning struck their rocket twice on the way up, and they flew on. They landed so precisely that they walked over "
                        + "to Surveyor 3, a robot that had landed two and a half years before, and brought pieces of it home.");
        add("apollo14", "Apollo 14", "Apollo 14", "NASA", "5 February 1971", "Fra Mauro", -3.64589, -17.47194, Kind.CREW, true,
                "Alan Shepard and Edgar Mitchell landed in Antares; Stuart Roosa circled overhead in Kitty Hawk",
                "Alan Shepard, America's first astronaut in 1961, hit two golf balls with a club head he had brought along. Stuart "
                        + "Roosa carried tree seeds around the Moon that were planted back home as \"Moon trees\".");
        add("apollo15", "Apollo 15", "Apollo 15", "NASA", "30 July 1971", "Hadley-Apennine", 26.13239, 3.63330, Kind.CREW, true,
                "David Scott and James Irwin landed in Falcon; Al Worden circled overhead in Endeavour",
                "The first car on the Moon: the Lunar Roving Vehicle. Dave Scott dropped a hammer and a falcon feather together and "
                        + "they landed at the same moment, as Galileo said they would with no air in the way. They found the "
                        + "\"Genesis Rock\", about 4 billion years old.");
        add("apollo16", "Apollo 16", "Apollo 16", "NASA", "21 April 1972", "Descartes Highlands", -8.9734, 15.5011, Kind.CREW, false,
                "John Young and Charlie Duke landed in Orion; Ken Mattingly circled overhead in Casper",
                "The only landing in the Moon's bright highlands. Charlie Duke left a photo of his family on the ground, and they set "
                        + "up the first telescope on the Moon, an ultraviolet camera that photographed the Earth.");
        add("apollo17", "Apollo 17", "Apollo 17", "NASA", "11 December 1972", "Taurus-Littrow", 20.1911, 30.7723, Kind.CREW, false,
                "Gene Cernan and Harrison Schmitt landed in Challenger; Ron Evans circled overhead in America",
                "The last people to walk on the Moon, so far. Jack Schmitt, a geologist, found orange soil made of tiny glass beads "
                        + "from an ancient volcanic fountain. They stayed three days, the longest anyone has.");
        // the Soviet Union
        add("luna2", "Luna 2", "Luna 2", "Soviet Union", "13 September 1959", "Near Archimedes", 29.1, 0.0, Kind.IMPACT, false, "",
                "The first thing from Earth ever to reach the Moon. It hit it on purpose, carrying metal badges with the Soviet coat "
                        + "of arms, a year and a half before the first person went to space.");
        add("luna9", "Luna 9", "Luna 9", "Soviet Union", "3 February 1966", "Ocean of Storms", 7.08, -64.37, Kind.LANDER, false, "",
                "The first soft landing on another world, and the first photos from the surface. It showed the ground was firm "
                        + "enough to land on, not deep dust that would swallow a spacecraft, as some had feared.");
        add("luna13", "Luna 13", "Luna 13", "Soviet Union", "24 December 1966", "Ocean of Storms", 18.87, -62.05, Kind.LANDER, false, "",
                "The second Soviet landing. It poked the ground with a spring-loaded rod to measure how firm the soil was, and took "
                        + "panoramas on Christmas Eve 1966.");
        add("luna16", "Luna 16", "Luna 16", "Soviet Union", "20 September 1970", "Sea of Fertility", -0.68, 56.3, Kind.LANDER, false, "",
                "The first robot to dig up Moon soil and fly it back to Earth by itself: 101 grams, scooped with a drill.");
        add("luna17", "Lunokhod 1", "Lunokhod 1", "Soviet Union", "17 November 1970", "Sea of Rains", 38.28, -35.0, Kind.LANDER, true, "",
                "The first rover on another world, driven by remote control from Crimea for ten months and 10.5 km. Its laser mirror "
                        + "was lost for 40 years, until NASA's Lunar Reconnaissance Orbiter spotted it in 2010.");
        add("luna20", "Luna 20", "Luna 20", "Soviet Union", "21 February 1972", "Apollonius highlands", 3.57, 56.5, Kind.LANDER, false, "",
                "Drilled 55 grams of rock from the highlands next to the Sea of Fertility and flew them home.");
        add("luna21", "Lunokhod 2", "Lunokhod 2", "Soviet Union", "15 January 1973", "Le Monnier crater", 25.85, 30.45, Kind.LANDER, true, "",
                "Its rover drove 39 km, a record that stood for over 40 years until NASA's Opportunity rover beat it on Mars in 2014. "
                        + "It carries a laser mirror too.");
        add("luna24", "Luna 24", "Luna 24", "Soviet Union", "18 August 1976", "Sea of Crises", 12.75, 62.20, Kind.LANDER, false, "",
                "The last Soviet Moon mission brought home 170 grams of soil. After it, nothing landed on the Moon for 37 years.");
        // NASA's robots
        add("ranger7", "Ranger 7", "Ranger 7", "NASA", "31 July 1964", "Sea that has become Known", -10.63, -20.60, Kind.IMPACT, false, "",
                "The first close-up photos of the Moon: over 4,000 in its last 17 minutes, before it hit a sea that was then named "
                        + "Mare Cognitum, \"the Sea that has become Known\".");
        add("ranger8", "Ranger 8", "Ranger 8", "NASA", "20 February 1965", "Sea of Tranquility", 2.638, 24.787, Kind.IMPACT, false, "",
                "Photographed the Sea of Tranquility all the way down, scouting the kind of ground Apollo 11 would land on four years "
                        + "later.");
        add("ranger9", "Ranger 9", "Ranger 9", "NASA", "24 March 1965", "Alphonsus crater", -12.828, -2.387, Kind.IMPACT, false, "",
                "Its pictures were shown live on American television as it dived into the crater Alphonsus: \"Live from the Moon\".");
        add("surveyor1", "Surveyor 1", "Surveyor 1", "NASA", "2 June 1966", "Ocean of Storms", -2.474, -43.339, Kind.LANDER, false, "",
                "America's first soft landing, on its first try. It sent back more than 11,000 pictures.");
        add("surveyor3", "Surveyor 3", "Surveyor 3", "NASA", "20 April 1967", "Ocean of Storms", -3.015, -23.418, Kind.LANDER, false, "",
                "Dug the first trench in the Moon with a little scoop. Two and a half years later Apollo 12 landed next door and the "
                        + "astronauts brought its camera back to Earth.");
        add("surveyor5", "Surveyor 5", "Surveyor 5", "NASA", "11 September 1967", "Sea of Tranquility", 1.461, 23.195, Kind.LANDER, false, "",
                "Tested the soil's chemistry for the first time, 25 km from where Apollo 11 would land.");
        add("surveyor6", "Surveyor 6", "Surveyor 6", "NASA", "10 November 1967", "Sinus Medii", 0.4743, -1.4275, Kind.LANDER, false, "",
                "The first lift-off from the Moon: it fired its engines, hopped about 3 metres, and landed again.");
        add("surveyor7", "Surveyor 7", "Surveyor 7", "NASA", "10 January 1968", "Near Tycho", -40.98, -11.514, Kind.LANDER, false, "",
                "The last Surveyor, in the rough highlands by the bright crater Tycho. Its camera saw laser beams fired at it from "
                        + "Earth.");
        // the new Moon race
        add("change3", "Chang'e 3", "Chang'e 3", "China", "14 December 2013", "Sea of Rains", 44.12, -19.51, Kind.LANDER, false, "",
                "China's first landing, with the Yutu (Jade Rabbit) rover: the first soft landing anyone had made on the Moon in 37 years.");
        add("change5", "Chang'e 5", "Chang'e 5", "China", "1 December 2020", "Mons Rumker", 43.0576, -51.9161, Kind.LANDER, false, "",
                "Brought home 1.7 kg of the youngest rocks ever collected on the Moon, from lava that flowed about 2 billion years ago.");
        add("change4", "Chang'e 4", "Chang'e 4", "China", "3 January 2019", "Von Karman crater, far side", -45.4561, 177.5885, Kind.LANDER,
                false, "", "The first landing on the far side, the half we never see from Earth. Its rover Yutu-2 kept exploring for years.");
        add("change6", "Chang'e 6", "Chang'e 6", "China", "1 June 2024", "Apollo basin, far side", -41.6385, -153.9852, Kind.LANDER, false, "",
                "Brought back the first rocks from the far side: about 2 kg.");
        add("chandrayaan3", "Chandrayaan-3", "Chandrayaan-3", "India", "23 August 2023", "Near the south pole", -69.373, 32.319, Kind.LANDER,
                false, "", "India's Vikram lander set down farther south than anyone before it, and its rover Pragyan found sulphur in "
                        + "the soil. India became the fourth country to land on the Moon.");
        add("slim", "SLIM", "SLIM", "Japan", "19 January 2024", "Shioli crater", -13.316, 25.251, Kind.LANDER, false, "",
                "Japan's \"Moon Sniper\" landed within 100 m of its target, but upside down on its nose! It still took pictures and "
                        + "survived three freezing lunar nights.");
        add("im1", "IM-1 Odysseus", "Odysseus", "Intuitive Machines, USA", "22 February 2024", "Malapert A crater", -80.13, 1.44, Kind.LANDER,
                false, "", "The first company to land on the Moon, and America's first landing since 1972. It tipped onto its side but "
                        + "kept working for a week.");
        add("blueghost1", "Blue Ghost 1", "Blue Ghost", "Firefly Aerospace, USA", "2 March 2025", "Sea of Crises", 18.5623, 61.8103,
                Kind.LANDER, false, "", "Landed upright and worked for two weeks. On 14 March 2025 it filmed an eclipse of the Sun "
                        + "from the Moon: the Earth passing in front of the Sun.");
        add("im2", "IM-2 Athena", "Athena", "Intuitive Machines, USA", "6 March 2025", "Mons Mouton, near the south pole", -84.7906, 29.1957,
                Kind.LANDER, false, "", "Landed the closest to the south pole anyone has, then tipped over in a crater.");
        // the ones that didn't make it
        add("beresheet", "Beresheet", "Beresheet", "Israel", "11 April 2019", "Sea of Serenity", 32.5956, 19.3496, Kind.CRASH, false, "",
                "A small private Israeli lander. Its engine cut out just before landing; it was carrying a library on tiny discs, and "
                        + "some tardigrades.");
        add("vikram2", "Chandrayaan-2", "Vikram", "India", "6 September 2019", "Southern highlands", -70.881, 22.784, Kind.CRASH, false, "",
                "India's first try at landing. It lost control in the last kilometres; four years later Chandrayaan-3 made it.");
        add("hakuto1", "Hakuto-R 1", "Hakuto-R 1", "ispace, Japan", "25 April 2023", "Atlas crater", 47.581, 44.094, Kind.CRASH, false, "",
                "A Japanese company's lander. Its computer thought it was much lower than it really was, and it ran out of fuel "
                        + "before it reached the ground.");
        add("luna25", "Luna 25", "Luna 25", "Russia", "19 August 2023", "Southern highlands", -57.865, 61.36, Kind.CRASH, false, "",
                "Russia's first Moon mission in 47 years. An engine burn went wrong and it crashed.");
        add("hakuto2", "Hakuto-R 2", "Resilience", "ispace, Japan", "5 June 2025", "Sea of Cold", 60.4445, -4.588, Kind.CRASH, false, "",
                "The company's second try. Its laser altimeter was late seeing the ground and it crashed.");
    }

    /** Every site, the Apollo landings first. */
    public static final List<Site> ALL = Collections.unmodifiableList(LIST);

    public static Site get(String key) {
        for (Site s : LIST) if (s.key.equals(key)) return s;
        return null;
    }

    /** Apollo 13: the landing that never was, for the list of Apollo missions. */
    public static final String APOLLO_13 = "Apollo 13 (April 1970) never landed: an oxygen tank exploded on the way, and Jim Lovell, "
            + "Jack Swigert and Fred Haise looped around the Moon and made it home. \"Houston, we've had a problem.\"";

    /** President Kennedy at Rice University, 12 September 1962. */
    public static final String RICE = "\"We choose to go to the Moon in this decade and do the other things, not because they are "
            + "easy, but because they are hard.\"";
}
