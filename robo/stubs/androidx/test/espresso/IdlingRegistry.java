package androidx.test.espresso;
import android.os.Looper;
import java.util.*;
public final class IdlingRegistry {
    private static final IdlingRegistry I = new IdlingRegistry();
    public static IdlingRegistry getInstance() { return I; }
    public Collection<Looper> getLoopers() { return new ArrayList<Looper>(); }
    public Collection<IdlingResource> getResources() { return new ArrayList<IdlingResource>(); }
}
