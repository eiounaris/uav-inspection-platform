package com.uav.inspection.blockchain;

import java.nio.file.Path;
import java.util.Objects;

/** Explicit connection settings; the default web application does not load identities. */
public record FabricGatewaySettings(String mspId, String endpoint, String tlsServerName,
                                    Path certificate, Path privateKey, Path tlsCertificate,
                                    String channel, String chaincode, String contract) {
    public FabricGatewaySettings {
        for (String value : new String[]{mspId, endpoint, tlsServerName, channel, chaincode, contract}) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Fabric connection settings must not be blank");
            }
        }
        Objects.requireNonNull(certificate);
        Objects.requireNonNull(privateKey);
        Objects.requireNonNull(tlsCertificate);
    }
}
