package com.axes.pmweather_aeronautics;

import java.util.LinkedHashMap;
import java.util.Map;

/** Applied-hook status and current-tick contact counts; no files or recorder in the public build. */
public final class IntegrationHealth {
    private static final Map<String, Integer> HOOKS = new LinkedHashMap<>();
    private static long contactTick = Long.MIN_VALUE, acceptedContacts, rejectedContacts;
    private IntegrationHealth() {}
    public static synchronized void applied(String name, int calls) { HOOKS.put(name, calls); }
    public static synchronized Map<String, Integer> hooks() { return Map.copyOf(HOOKS); }
    static void contact(long tick, boolean accepted) {
        if (contactTick != tick) {
            contactTick = tick; acceptedContacts = 0; rejectedContacts = 0;
        }
        if (accepted) acceptedContacts++; else rejectedContacts++;
    }
    static long acceptedContacts(long tick) { return tick == contactTick ? acceptedContacts : 0; }
    static long rejectedContacts(long tick) { return tick == contactTick ? rejectedContacts : 0; }
}
