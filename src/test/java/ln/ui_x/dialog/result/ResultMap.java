package ln.ui_x.dialog.result;
import java.util.HashMap;
public final class ResultMap<K> {
    private final HashMap<K,Object> values = new HashMap<>();
    public ResultMap(boolean canceled) { }
    public ResultMap<K> set(K key, Object value) { values.put(key,value); return this; }
    public Object get(K key) { return values.get(key); }
}
