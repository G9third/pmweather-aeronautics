package com.axes.pmweather_aeronautics;

/** Dependency-free regression for the particle queue's fixed 64-probe candidate set. */
public final class ParticleCandidateSetRegression {
    private static final int CAPACITY = 128;
    private static final int MASK = CAPACITY - 1;

    private ParticleCandidateSetRegression() {}

    public static void main(String[] args) {
        long[] keys = new long[CAPACITY];
        byte[] states = new byte[CAPACITY];
        long target = 0x1234_5678_9ABCDEFL;
        int home = hash(target);

        // A key at the last permitted probe remains findable and duplicate inserts are rejected.
        for (int probe = 0; probe < ParticleCandidateSet.MAX_HASH_PROBES - 1; probe++) {
            int slot = (home + probe) & MASK;
            states[slot] = 1;
            keys[slot] = target + probe + 1;
        }
        int lastProbe = (home + ParticleCandidateSet.MAX_HASH_PROBES - 1) & MASK;
        states[lastProbe] = 1;
        keys[lastProbe] = target;
        require(ParticleCandidateSet.findSlot(target, keys, states, MASK) == lastProbe,
            "find must inspect exactly the 64th slot");
        require(ParticleCandidateSet.insertSlot(target, keys, states, MASK) == -1,
            "duplicate insert must be rejected within the probe bound");

        // A collision chain beyond the window is treated as unavailable, never scanned farther.
        states = new byte[CAPACITY];
        keys = new long[CAPACITY];
        for (int probe = 0; probe < ParticleCandidateSet.MAX_HASH_PROBES; probe++) {
            int slot = (home + probe) & MASK;
            states[slot] = 1;
            keys[slot] = target + probe + 1;
        }
        int beyondWindow = (home + ParticleCandidateSet.MAX_HASH_PROBES) & MASK;
        states[beyondWindow] = 1;
        keys[beyondWindow] = target;
        require(ParticleCandidateSet.findSlot(target, keys, states, MASK) == -1,
            "find must not inspect beyond the 64-slot window");
        require(ParticleCandidateSet.insertSlot(target, keys, states, MASK) == -1,
            "saturated collision chain must defer instead of scanning farther");

        // Tombstones are reusable, but a duplicate behind one is still detected.
        states = new byte[CAPACITY];
        keys = new long[CAPACITY];
        states[home] = 2;
        int duplicateSlot = (home + 1) & MASK;
        states[duplicateSlot] = 1;
        keys[duplicateSlot] = target;
        require(ParticleCandidateSet.findSlot(target, keys, states, MASK) == duplicateSlot,
            "find must pass a tombstone to reach the matching live key");
        require(ParticleCandidateSet.insertSlot(target, keys, states, MASK) == -1,
            "insert must not reuse a tombstone in front of an existing key");
        long newKey = target + 1;
        while (hash(newKey) != home) newKey++;
        require(ParticleCandidateSet.insertSlot(newKey, keys, states, MASK) == home,
            "insert should reuse the first tombstone in its probe window");

        System.out.println("Particle candidate-set regression passed: 64-probe cap, collision deferral, and tombstones.");
    }

    private static int hash(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return (int) key & MASK;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
