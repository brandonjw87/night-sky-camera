package com.brandon.nightsky.processing;

import java.util.Locale;

/**
 * The stories on the Jupiter screen (3.2): Jupiter itself, each of the four moons, Galileo's January 1610, Ole
 * Rømer's speed of light from Io's eclipses, the three moons' 1:2:4 dance, and how to see them with binoculars. Short
 * enough to read under a dark sky, for an eleven-year-old and up.
 */
public final class JupiterStories {
    private JupiterStories() {}

    /** The stories' names, in the order the screen offers them. */
    public static final String[] TITLES = {"Jupiter", "Io", "Europa", "Ganymede", "Callisto", "Galileo, 1610", "The speed of light", "The 1:2:4 dance",
            "See them yourself"};

    /** Story {@code i} (of {@link #TITLES}); {@code v} Jupiter tonight (null: unknown), for its distance. */
    public static String story(int i, JupiterGeo.View v) {
        switch (i) {
            case 0:
                return jupiter(v);
            case 1:
            case 2:
            case 3:
            case 4:
                return moon(i - 1);
            case 5:
                return galileo();
            case 6:
                return romer(v);
            case 7:
                return dance();
            default:
                return binoculars();
        }
    }

    public static String jupiter(JupiterGeo.View v) {
        StringBuilder sb = new StringBuilder("Jupiter is the biggest planet: eleven Earths would fit across it, and it is 318 times "
                + "heavier than the Earth. It's a ball of gas with no ground to stand on, striped with clouds and storms; the Great Red "
                + "Spot is a storm wider than the Earth that has blown for at least 190 years. It spins once in under ten hours, so fast "
                + "it bulges at its middle: on your photo it's a little wider than it is tall. More than 90 moons go round it, most "
                + "of them small rocks; the four big ones are Galileo's.");
        if (v != null) {
            sb.append(String.format(Locale.US, "\n\nTonight it is %,.0f million km from you, so its light takes %.0f minutes to get here.",
                    v.distAu * Astro.AU_KM / 1e6, v.lightMinutes()));
        }
        return sb.toString();
    }

    /** Moon k's story (0 Io, 1 Europa, 2 Ganymede, 3 Callisto). */
    public static String moon(int k) {
        switch (k) {
            case 0:
                return "Io (say EYE-oh), the closest of the four, goes round Jupiter in 1 day and 18 hours. It's a little bigger than our "
                        + "Moon and the most volcanic place in the solar system: about 400 volcanoes, some throwing sulphur hundreds of "
                        + "kilometres up, painting it yellow, orange and red. Jupiter's pull, with Europa's and Ganymede's tugs, "
                        + "squeezes Io like a stress ball and heats it inside. Its volcanoes were found in 1979 by Linda Morabito, an "
                        + "engineer checking a picture from Voyager 1 who spotted a plume over its edge.";
            case 1:
                return "Europa (say yoo-ROH-pa) goes round in 3 days and 13 hours. It's a bit smaller than our Moon, and covered in "
                        + "cracked ice. Under the ice is a salty ocean with about twice as much water as all of the Earth's oceans, "
                        + "one of the best places to look for life beyond Earth. NASA's Europa Clipper, launched in October 2024, "
                        + "reaches Jupiter in 2030 and will fly past Europa dozens of times.";
            case 2:
                return "Ganymede (say GAN-ih-meed) goes round in 7 days and 4 hours. It's the biggest moon in the solar system, 5,268 "
                        + "km across: bigger than the planet Mercury. It's the only moon with a magnetic field of its own, so it has "
                        + "auroras, and it hides a salty ocean under its ice too. Europe's spacecraft JUICE, launched in 2023, reaches "
                        + "Jupiter in 2031 and in 2034 goes into orbit round Ganymede: the first spacecraft ever to orbit a moon "
                        + "other than ours.";
            default:
                return "Callisto (say ka-LIS-toe), the farthest of the four, takes 16 days and 16 hours to go round, so it's often "
                        + "the one farthest out on your photo. It's almost as big as Mercury and the most cratered world we know: its "
                        + "face has hardly changed in four billion years. It's far enough out to miss most of Jupiter's deadly "
                        + "radiation, so NASA once picked it as the place for a future human base near Jupiter.";
        }
    }

    public static String galileo() {
        return "On 7 January 1610, in Padua in Italy, Galileo Galilei pointed his home-made telescope at Jupiter and saw three little "
                + "stars in a straight line beside it. The next night they had moved. On the 13th he saw a fourth, and by the 15th he "
                + "was sure: they were going round Jupiter. Nobody had ever seen anything go round another world, and it showed not "
                + "everything circles the Earth. In March he published a little book, the Starry Messenger, and called them the "
                + "Medicean Stars after the family that ruled Florence. The names we use, Io, Europa, Ganymede and Callisto, came "
                + "from Simon Marius, a German astronomer who saw them that same winter.\n\nYour phone's telephoto sees about as "
                + "finely as Galileo's telescope did. The notebook page below puts your nights beside his.";
    }

    public static String romer(JupiterGeo.View v) {
        String now = v == null ? "" : String.format(Locale.US, " Tonight Jupiter's light takes %.0f minutes to reach you: the Jupiter on your "
                + "photo is Jupiter as it was %.0f minutes before you took it.", v.lightMinutes(), v.lightMinutes());
        return "In 1676 the Danish astronomer Ole Rømer was timing Io going into Jupiter's shadow. The timings ran late when the Earth "
                + "was far from Jupiter and early when it was near. He worked out why: light takes time to travel, and from the far "
                + "side of the Earth's orbit it has further to come. He reckoned it needs about 22 minutes to cross the orbit (it's "
                + "really about 17). It was the first time anyone showed that light has a speed (about 300,000 km a second)." + now;
    }

    public static String dance() {
        return "Io, Europa and Ganymede move in step. Every time Ganymede goes round Jupiter once, Europa goes round twice and Io four "
                + "times: 1, 2, 4. Their tugs on each other keep them in this rhythm, which Pierre-Simon Laplace explained about 1805. "
                + "Because of it, the three can never all be lined up on the same side of Jupiter. Watch them over a few nights in "
                + "the notebook and you can see the rhythm yourself.";
    }

    public static String binoculars() {
        return "You can see Galileo's moons yourself: any binoculars show them as tiny stars in a line beside Jupiter, if you hold them "
                + "still (lean against a wall or a car, or rest them on a fence). Look again an hour later and the closest, Io, has "
                + "moved. Night after night they swap sides, as on the notebook page.";
    }
}
