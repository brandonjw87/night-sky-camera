package androidx.test.runner.lifecycle;
import android.app.Activity;
import java.util.Collection;
public interface ActivityLifecycleMonitor {
    void addLifecycleCallback(ActivityLifecycleCallback c);
    void removeLifecycleCallback(ActivityLifecycleCallback c);
    Stage getLifecycleStageOf(Activity a);
    Collection<Activity> getActivitiesInStage(Stage s);
}
