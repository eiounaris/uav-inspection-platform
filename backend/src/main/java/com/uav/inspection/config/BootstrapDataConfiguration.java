package com.uav.inspection.config;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Explicit local-only account initialization; existing records are validated, never reset. */
@Configuration(proxyBeanMethods = false)
@Profile("bootstrap")
public class BootstrapDataConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(BootstrapDataConfiguration.class);
    private static final String OWNER = "00000000-0000-4000-8000-000000000001";
    private static final String OPERATOR = "00000000-0000-4000-8000-000000000002";
    private static final List<Account> ACCOUNTS = List.of(
            new Account(1, OWNER, "owner-admin", List.of("ORG_ADMIN", "OWNER_TASK_ADMIN")),
            new Account(2, OWNER, "owner-reviewer", List.of("OWNER_REVIEWER")),
            new Account(3, OWNER, "owner-approver", List.of("OWNER_DATA_APPROVER")),
            new Account(4, OPERATOR, "operator-admin", List.of("ORG_ADMIN", "OPERATOR_TASK_ADMIN")),
            new Account(5, OPERATOR, "operator-user", List.of("OPERATOR_USER")));

    @Bean
    static BeanFactoryPostProcessor bootstrapProfileGuard(Environment environment) {
        return beanFactory -> {
            if (!environment.acceptsProfiles(Profiles.of("local & database & !prod"))) {
                throw new IllegalStateException("Bootstrap initialization requires local,database,bootstrap and forbids prod");
            }
        };
    }

    @Bean
    ApplicationRunner initializeBootstrapData(Environment environment, IdentityMapper identities,
                                              PlatformTransactionManager transactions) {
        return arguments -> {
            Path path = Path.of(environment.getRequiredProperty("inspection.bootstrap.credentials")).toAbsolutePath();
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
                        throw new IllegalStateException("Existing bootstrap account differs from its original credentials");
                    }
                }
            });
            LOG.info("Bootstrap data ready: 5 accounts; original credentials retained in {}", path);
        };
    }

    private static Secrets readSecrets(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !Files.getPosixFilePermissions(path)
                .equals(PosixFilePermissions.fromString("rw-------"))) {
            throw new IllegalStateException("Bootstrap credentials must be an existing local file with mode 0600");
        }
        Secrets secrets;
        try {
            secrets = JsonMapper.builder().build().readValue(Files.readAllBytes(path), Secrets.class);
            Set<String> usernames = Set.copyOf(ACCOUNTS.stream().map(Account::username).toList());
            if (secrets.formatVersion() != 1 || !secrets.passwords().keySet().equals(usernames)
                    || secrets.passwords().values().stream().anyMatch(value ->
                        value == null || !value.matches("[A-Za-z0-9_-]{24,64}"))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            // JSON parse failures may quote input, so do not attach their original messages or causes.
            throw new IllegalStateException("Invalid bootstrap credentials; preserve the original credential file");
        }
        return secrets;
    }

    private record Account(int number, String organization, String username, List<String> roles) {}
    private record Secrets(int formatVersion, Map<String, String> passwords) {}
}
