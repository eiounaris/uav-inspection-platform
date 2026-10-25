package com.uav.inspection.device;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

/** Admitted device identity. The original digest permits reauthentication after token rotation. */
public record CurrentDevice(String id,String orgId,String code,@JsonIgnore String tokenDigest) {
    public CurrentDevice {
        Objects.requireNonNull(id); Objects.requireNonNull(orgId); Objects.requireNonNull(code);
        Objects.requireNonNull(tokenDigest);
    }

    @JsonIgnore
    @Override public String tokenDigest() { return tokenDigest; }

    @Override public String toString() {
        return "CurrentDevice[id="+id+", orgId="+orgId+", code="+code+"]";
    }
}
