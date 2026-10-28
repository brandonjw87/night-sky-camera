package androidx.test.internal.runner.lifecycle;
import android.app.Activity;
import androidx.test.runner.lifecycle.*;
import java.util.*;
public class ActivityLifecycleMonitorImpl implements ActivityLifecycleMonitor {
    private final Map<Activity, Stage> stages = new WeakHashMap<Activity, Stage>();
    private final List<ActivityLifecycleCallback> cbs = new ArrayList<ActivityLifecycleCallback>();
    public ActivityLifecycleMonitorImpl() {}
    public synchronized void signalLifecycleChange(Stage s, Activity a) {
        stages.put(a, s);
        for (ActivityLifecycleCallback c : new ArrayList<ActivityLifecycleCallback>(cbs)) c.onActivityLifecycleChanged(a, s);
    }
    public synchronized void addLifecycleCallback(ActivityLifecycleCallback c) { cbs.add(c); }
    public synchronized void removeLifecycleCallback(ActivityLifecycleCallback c) { cbs.remove(c); }
    public synchronized Stage getLifecycleStageOf(Activity a) { return stages.get(a); }
    public synchronized Collection<Activity> getActivitiesInStage(Stage s) {
        List<Activity> out = new ArrayList<Activity>();
        for (Map.Entry<Activity, Stage> e : stages.entrySet()) if (e.getValue() == s) out.add(e.getKey());
        return out;
    }
}
