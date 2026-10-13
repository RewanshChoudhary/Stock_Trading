package edu.oslab.threadpool;

public record SliceResult(boolean isComplete) {
    public static SliceResult done() {
        return new SliceResult(true);
    }

    public static SliceResult yielded() {
        return new SliceResult(false);
    }
}
