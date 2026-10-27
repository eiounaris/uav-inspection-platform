package com.uav.inspection.evidence.storage;

import java.time.Instant;
import java.util.List;

/** Internal inventory metadata only. Keys are never public views or message fields. */
public interface ObjectInventory {
    Page list(String prefix, String continuation, int limit);
    Entry head(String key);

    record Entry(String key, Instant lastModified, long size) {}
    record Page(List<Entry> entries, String continuation) {
        public Page { entries = List.copyOf(entries); }
    }
}
