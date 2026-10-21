FROM eclipse-temurin:21.0.12_8-jre-jammy@sha256:61d6c7b34d36aee3f45d043101259f97f3c6d428dc2a6f75513789983c5e254f
WORKDIR /app
COPY --chown=10001:10001 target/evidence-chaincode-0.1.0.jar /app/chaincode.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" OTEL_SDK_DISABLED=true
ENTRYPOINT ["java", "-jar", "/app/chaincode.jar"]
