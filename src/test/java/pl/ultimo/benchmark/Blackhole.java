package pl.ultimo.benchmark;

/**
 * Microbenchmark utility preventing the JIT compiler from eliminating
 * dead code or constant-folding pure computations.
 */
public final class Blackhole {
    public volatile Object lastValue;
    public volatile long consumedCount;

    public void consume(Object obj) {
        this.lastValue = obj;
        this.consumedCount++;
    }

    public void consume(long val) {
        this.consumedCount += val;
    }

    public void consume(byte[] bytes) {
        this.lastValue = bytes;
        this.consumedCount += bytes.length;
    }
}
