package com.uav.inspection.blockchain;

import java.nio.file.Path;
import java.util.Map;
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

    public static FabricGatewaySettings fromEnvironment(Map<String, String> environment, String organization) {
        if (!organization.equals("ORGA") && !organization.equals("ORGB")) {
            throw new IllegalArgumentException("Unknown business organization");
        }
        String prefix = "FABRIC_" + organization + "_";
        return new FabricGatewaySettings(required(environment, prefix + "MSP_ID"),
                required(environment, prefix + "GATEWAY_ENDPOINT"),
                required(environment, prefix + "TLS_SERVER_NAME"),
                Path.of(required(environment, prefix + "CERT_PATH")),
                Path.of(required(environment, prefix + "KEY_PATH")),
                Path.of(required(environment, prefix + "TLS_CERT_PATH")),
                environment.getOrDefault("FABRIC_CHANNEL", "mychannel"),
                environment.getOrDefault("FABRIC_CHAINCODE", "evidence"),
                environment.getOrDefault("FABRIC_CONTRACT", "EvidenceContract"));
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing Fabric setting: " + key);
        }
        return value;
    }
}
