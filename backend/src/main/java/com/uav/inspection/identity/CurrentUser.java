package com.uav.inspection.identity;

import java.util.List;
import java.util.Objects;

/** A database-backed principal and safe response model; never contains password material. */
public record CurrentUser(String id, String orgId, String username, List<Role> roles,
                          long tokenVersion, long lockVersion) {
    public CurrentUser {
        Objects.requireNonNull(id);
        Objects.requireNonNull(orgId);
        Objects.requireNonNull(username);
        roles = List.copyOf(roles);
    }
}
