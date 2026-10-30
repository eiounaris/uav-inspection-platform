package com.uav.inspection.blockchain;

import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import org.hyperledger.fabric.client.CommitStatusException;
import org.hyperledger.fabric.client.Contract;
import org.hyperledger.fabric.client.EndorseException;
import org.hyperledger.fabric.client.Gateway;
import org.hyperledger.fabric.client.GatewayException;
import org.hyperledger.fabric.client.Hash;
import org.hyperledger.fabric.client.SubmitException;
import org.hyperledger.fabric.client.identity.Identities;
import org.hyperledger.fabric.client.identity.Signers;
import org.hyperledger.fabric.client.identity.X509Identity;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.concurrent.TimeUnit;

/** One reusable Gateway and signing identity per institution, enabled by explicit ledger configuration. */
public final class GatewayEvidenceLedgerClient implements EvidenceLedgerClient {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final CheckpointValidator checkpoints;
    private final ManagedChannel channel;
    private final Gateway gateway;
    private final Contract contract;

    private GatewayEvidenceLedgerClient(FabricGatewaySettings settings, X509Identity identity,
                                        ManagedChannel channel, Gateway gateway) {
        this.checkpoints = new CheckpointValidator(identity.getMspId(), identity.getCredentials(),
                settings.channel(), settings.chaincode());
        this.channel = channel;
        this.gateway = gateway;
        this.contract = gateway.getNetwork(settings.channel()).getContract(settings.chaincode(), settings.contract());
    }

    public static GatewayEvidenceLedgerClient connect(FabricGatewaySettings settings)
            throws IOException, GeneralSecurityException {
        X509Identity identity;
        try (var certificate = Files.newBufferedReader(settings.certificate(), StandardCharsets.UTF_8)) {
            identity = new X509Identity(settings.mspId(), Identities.readX509Certificate(certificate));
        }
        java.security.PrivateKey key;
        try (var privateKey = Files.newBufferedReader(settings.privateKey(), StandardCharsets.UTF_8)) {
            key = Identities.readPrivateKey(privateKey);
        }
        var credentials = TlsChannelCredentials.newBuilder()
                .trustManager(settings.tlsCertificate().toFile()).build();
        var channel = Grpc.newChannelBuilder(settings.endpoint(), credentials)
                .overrideAuthority(settings.tlsServerName()).build();
        try {
            var gateway = Gateway.newInstance().identity(identity).signer(Signers.newPrivateKeySigner(key))
                    .hash(Hash.SHA256).connection(channel)
                    .evaluateOptions(options -> options.withDeadlineAfter(5, TimeUnit.SECONDS))
                    .endorseOptions(options -> options.withDeadlineAfter(15, TimeUnit.SECONDS))
                    .submitOptions(options -> options.withDeadlineAfter(10, TimeUnit.SECONDS))
                    .commitStatusOptions(options -> options.withDeadlineAfter(30, TimeUnit.SECONDS))
                    .connect();
            return new GatewayEvidenceLedgerClient(settings, identity, channel, gateway);
        } catch (RuntimeException exception) {
            channel.shutdownNow();
            throw exception;
        }
    }

    @Override
    public PreparedRegistration prepareRegistration(String evidenceId, int version, String fileHash)
            throws EndorseException {
        var transaction = contract.newProposal("RegisterEvidence")
                .addArguments(evidenceId, Integer.toString(version), fileHash)
                .build().endorse();
        return new PreparedRegistration(transaction.getTransactionId(), transaction.getBytes());
    }

    @Override
    public CommitCheckpoint submitPrepared(PreparedRegistration prepared) throws SubmitException {
        // Gateway itself does not check that restored bytes belong to the configured identity.
        checkpoints.transaction(prepared);
        var transaction = gateway.newTransaction(prepared.transactionBytes());
        var submitted = transaction.submitAsync();
        return new CommitCheckpoint(submitted.getTransactionId(), submitted.getBytes());
    }

    @Override
    public CommitOutcome queryCommit(CommitCheckpoint checkpoint) throws CommitStatusException {
        checkpoints.commit(checkpoint);
        var status = gateway.newCommit(checkpoint.commitBytes()).getStatus();
        if (!checkpoint.transactionId().equals(status.getTransactionId())) {
            throw new IllegalStateException("Unexpected commit transaction identifier");
        }
        return new CommitOutcome(status.getTransactionId(), status.isSuccessful(),
                status.getCode().getNumber(), status.getBlockNumber());
    }

    @Override
    public EvidenceQuery queryEvidence(String evidenceId, int version) throws GatewayException {
        byte[] result = contract.evaluateTransaction("QueryEvidence", evidenceId, Integer.toString(version));
        return JSON.readValue(result, EvidenceQuery.class);
    }

    @Override
    public void close() {
        try {
            gateway.close();
        } finally {
            channel.shutdownNow();
            try {
                channel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
