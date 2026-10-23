package com.uav.inspection.config;

import com.uav.inspection.blockchain.FabricGatewaySettings;
import com.uav.inspection.blockchain.GatewayEvidenceLedgerClient;
import com.uav.inspection.blockchain.InstitutionLedgerClients;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
@Profile("ledger")
public class LedgerConfiguration {
    @Bean
    static BeanFactoryPostProcessor ledgerProfileGuard(Environment environment) {
        return factory -> {
            if (!environment.acceptsProfiles(Profiles.of("database & storage & messaging"))) {
                throw new IllegalStateException("Ledger requires database, storage and messaging profiles");
            }
        };
    }

    @Bean(destroyMethod = "close")
    InstitutionLedgerClients institutionLedgerClients(Environment environment) {
        GatewayEvidenceLedgerClient owner = null;
        GatewayEvidenceLedgerClient operator = null;
        try {
            var ownerSettings = settings(environment, "orga");
            var operatorSettings = settings(environment, "orgb");
            owner = GatewayEvidenceLedgerClient.connect(ownerSettings);
            operator = GatewayEvidenceLedgerClient.connect(operatorSettings);
            return new InstitutionLedgerClients(owner, operator, ownerSettings.mspId(), operatorSettings.mspId());
        } catch (Exception failure) {
            if (operator != null) operator.close();
            if (owner != null) owner.close();
            // Certificate paths, private key parser messages and RPC metadata are not startup diagnostics.
            throw new IllegalStateException("Cannot initialize Fabric identities; check explicit ledger configuration");
        }
    }

    private static FabricGatewaySettings settings(Environment environment, String organization) {
        String prefix = organization + ".";
        return new FabricGatewaySettings(required(environment, prefix + "msp-id"),
                required(environment, prefix + "endpoint"), required(environment, prefix + "tls-server-name"),
                Path.of(required(environment, prefix + "certificate")),
                Path.of(required(environment, prefix + "private-key")),
                Path.of(required(environment, prefix + "tls-certificate")),
                required(environment, "channel"), required(environment, "chaincode"), required(environment, "contract"));
    }

    private static String required(Environment environment, String key) {
        String value = environment.getRequiredProperty("inspection.ledger." + key);
        if (value.isBlank()) throw new IllegalArgumentException("Missing ledger setting");
        return value;
    }
}
