package androidx.test.internal.runner.lifecycle;
import android.app.Application;
import androidx.test.runner.lifecycle.*;
public class ApplicationLifecycleMonitorImpl implements ApplicationLifecycleMonitor {
    public ApplicationLifecycleMonitorImpl() {}
    public void signalLifecycleChange(Application a, ApplicationStage s) {}
}
