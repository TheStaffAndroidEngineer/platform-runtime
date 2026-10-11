package staff.android.runtime;
import staff.android.runtime.ICatalogCallback;
interface ICatalogService {
    String check(String productId);
    void request(String productId, ICatalogCallback callback);
    int processId();
    int entryThreadId();
    int callThreadId();
    void pauseForLab(boolean paused);
    int waitingForLab();
    void crashForLab();
}
