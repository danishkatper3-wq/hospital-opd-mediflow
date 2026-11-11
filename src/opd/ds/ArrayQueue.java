package opd.ds;

/** Fixed-size circular buffer that keeps items in the order they arrived. */
public class ArrayQueue<T> {

    /** Called once for every item during rotate(). Return true to keep the item. */
    public interface Visitor<T> { boolean visit(T item); }

    private final Object[] data;
    private final int cap;
    private int frontIdx = 0, rearIdx = -1, count = 0;

    public ArrayQueue(int capacity) {
        this.cap = capacity;
        this.data = new Object[capacity];
    }

    public boolean isEmpty() { return count == 0; }
    public boolean isFull()  { return count == cap; }
    public int size()        { return count; }
    public int capacity()    { return cap; }

    /** Adds an item at the end. Returns false when full. */
    public boolean enqueue(T item) {
        if (isFull()) return false;
        rearIdx = (rearIdx + 1) % cap;
        data[rearIdx] = item;
        count++;
        return true;
    }

    /** Removes the oldest item. Returns null when empty. */
    @SuppressWarnings("unchecked")
    public T dequeue() {
        if (isEmpty()) return null;
        T item = (T) data[frontIdx];
        data[frontIdx] = null;
        frontIdx = (frontIdx + 1) % cap;
        count--;
        return item;
    }

    /** Returns the oldest item without removing it. */
    @SuppressWarnings("unchecked")
    public T front() {
        return isEmpty() ? null : (T) data[frontIdx];
    }

    /**
     * Visits every item once, in order. The visitor returns true to keep the item
     * or false to drop it. The order of the kept items does not change.
     */
    public void rotate(Visitor<T> visitor) {
        int n = count;
        for (int i = 0; i < n; i++) {
            T item = dequeue();
            if (visitor.visit(item)) enqueue(item);
        }
    }

    public int frontIndex() { return isEmpty() ? -1 : frontIdx; }
    public int rearIndex()  { return isEmpty() ? -1 : rearIdx; }
    @SuppressWarnings("unchecked")
    public T slot(int i)    { return (T) data[i]; }
}
