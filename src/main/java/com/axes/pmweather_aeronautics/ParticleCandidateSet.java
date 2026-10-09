package com.axes.pmweather_aeronautics;

/** Fixed-probe open-addressed set helpers for the client particle refresh queue. */
final class ParticleCandidateSet {
    static final int MAX_HASH_PROBES = 64;

    private ParticleCandidateSet() {
    }

    /** Returns a matching live slot, or -1 when absent or beyond the bounded probe window. */
    static int findSlot(long key, long[] keys, byte[] states, int mask) {
        int slot = hash(key, mask);
        for (int checked = 0; checked < MAX_HASH_PROBES; checked++) {
            byte state = states[slot];
            if (state == 0) return -1;
            if (state == 1 && keys[slot] == key) return slot;
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    /**
     * Returns an available slot, or -1 for a duplicate or a saturated probe window.
     * A caller must defer when this returns -1; it must not clear or rebuild the table here.
     */
    static int insertSlot(long key, long[] keys, byte[] states, int mask) {
        int slot = hash(key, mask);
        int firstTombstone = -1;
        for (int checked = 0; checked < MAX_HASH_PROBES; checked++) {
            byte state = states[slot];
            if (state == 1 && keys[slot] == key) return -1;
            if (state == 2 && firstTombstone < 0) firstTombstone = slot;
            if (state == 0) return firstTombstone >= 0 ? firstTombstone : slot;
            slot = (slot + 1) & mask;
        }
        return firstTombstone;
    }

    private static int hash(long key, int mask) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return (int) key & mask;
    }
}
