package com.uav.inspection.config;

import com.uav.inspection.device.persistence.DroneMapper;
import com.uav.inspection.identity.persistence.IdentityMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit local-only initialization; existing records are validated, never reset. */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoDataConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(DemoDataConfiguration.class);
    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String OPERATOR = "00000000-0000-4000-8000-000000000002";
    private static final String DRONE_CODE = "UAV-DEMO-001";
    private static final String DRONE_ID = "20000000-0000-4000-8000-000000000001";
    private static final List<Account> ACCOUNTS = List.of(
            new Account(1, OWNER, "owner-admin", List.of("ORG_ADMIN", "OWNER_TASK_ADMIN")),
            new Account(2, OWNER, "owner-reviewer", List.of("OWNER_REVIEWER")),
            new Account(3, OWNER, "owner-approver", List.of("OWNER_DATA_APPROVER")),
            new Account(4, OPERATOR, "operator-admin", List.of("ORG_ADMIN", "OPERATOR_TASK_ADMIN")),
            new Account(5, OPERATOR, "operator-user", List.of("OPERATOR_USER")),
            new Account(6, OPERATOR, "operator-outsider", List.of("OPERATOR_USER")));

    @Bean
    static BeanFactoryPostProcessor demoProfileGuard(Environment environment) {
        return beanFactory -> {
            if (!environment.acceptsProfiles(Profiles.of("local & database & !prod"))) {
                throw new IllegalStateException("Demo initialization requires local,database,demo and forbids prod");
            }
        };
    }

    @Bean
    ApplicationRunner initializeDemoData(Environment environment, IdentityMapper identities, DroneMapper drones,
                                         PlatformTransactionManager transactions) {
        return arguments -> {
            Path path = Path.of(environment.getRequiredProperty("inspection.demo.credentials")).toAbsolutePath();
            Secrets secrets = readSecrets(path);
            var passwords = new BCryptPasswordEncoder(12);
            var json = JsonMapper.builder().build();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                for (Account account : ACCOUNTS) {
                    String id = "10000000-0000-4000-8000-%012d".formatted(account.number());
                    String password = secrets.passwords().get(account.username());
                    var existing = identities.findUserByUsername(account.username());
                    if (existing == null) {
                        identities.insertUser(id, account.organization(), account.username(),
                                passwords.encode(password), json.writeValueAsString(account.roles()));
                    } else if (!existing.id().equals(id) || !existing.orgId().equals(account.organization())
                            || !passwords.matches(password, existing.passwordHash())) {
                        throw new IllegalStateException("Existing demo account differs from its original credentials");
                    }
                }
                String tokenHash = sha256(secrets.droneTokens().get(DRONE_CODE));
                var existing = drones.findByCode(DRONE_CODE);
                if (existing == null) {
                    drones.insertDrone(DRONE_ID, OPERATOR, DRONE_CODE, tokenHash);
                } else if (!DRONE_ID.equals(existing.id()) || !OPERATOR.equals(existing.operatorOrgId())
                        || !tokenHash.equals(existing.tokenHash())) {
                    throw new IllegalStateException("Existing demo device differs from its original credentials");
                }
            });
            LOG.info("Demo data ready: 6 accounts and 1 device; original credentials retained in {}", path);
        };
    }

    private static Secrets readSecrets(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !Files.getPosixFilePermissions(path)
                .equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IllegalStateException("Demo credentials must be an existing local file with mode 0600");
        }
        Secrets secrets;
        try {
            secrets = JsonMapper.builder().build().readValue(Files.readAllBytes(path), Secrets.class);
            Set<String> usernames = Set.copyOf(ACCOUNTS.stream().map(Account::username).toList());
            if (secrets.formatVersion() != 1 || !secrets.passwords().keySet().equals(usernames)
                    || !secrets.droneTokens().keySet().equals(Set.of(DRONE_CODE))
                    || secrets.passwords().values().stream().anyMatch(value ->
                        value == null || !value.matches("[A-Za-z0-9_-]{24,64}"))
                    || !secrets.droneTokens().get(DRONE_CODE).matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            // JSON parse failures may quote input, so do not attach their original messages or causes.
            throw new IllegalStateException("Invalid demo credentials; preserve the original credential file");
        }
        return secrets;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Account(int number, String organization, String username, List<String> roles) {}
    private record Secrets(int formatVersion, Map<String, String> passwords, Map<String, String> droneTokens) {}
}
