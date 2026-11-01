package edu.softwarelab.starter.messaging;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Canonical Kafka message envelope used by every S3 ↔ S4 / S3 ↔ S5 message.
 *
 * <p>Java field names are camelCase; JSON keys are snake_case per the AsyncAPI
 * contracts (e.g. {@code message_id}, {@code schema_version},
 * {@code transaction_id}, {@code correlation_id}, {@code causation_id},
 * {@code occurred_at}, {@code message_type}, {@code operation_id}).
 *
 * <p>The Kafka record key for every message is the UTF-8 string form of
 * {@link #transactionId} (handled by the producers).
 *
 * <p>Outcome/error semantics (per AsyncAPI):
 * <ul>
 *   <li>Commands (S3 → S4 / S3 → S5): {@code outcome} and {@code error} are null.</li>
 *   <li>Results with outcome=APPLIED: {@code data} required, {@code error} null.</li>
 *   <li>Results with outcome=REJECTED: {@code error} required, {@code data} null.</li>
 *   <li>Results with outcome=UNKNOWN: both {@code data} and {@code error} null.</li>
 * </ul>
 *
 * <p>{@link JsonInclude.Include#NON_NULL} keeps the JSON payload aligned with
 * the contract: optional fields are omitted entirely when absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageEnvelope {

    @JsonProperty("message_id")
    private UUID messageId;

    @JsonProperty("schema_version")
    private String schemaVersion;

    @JsonProperty("transaction_id")
    private UUID transactionId;

    @JsonProperty("correlation_id")
    private UUID correlationId;

    @JsonProperty("causation_id")
    private UUID causationId;

    @JsonProperty("occurred_at")
    private OffsetDateTime occurredAt;

    @JsonProperty("producer")
    private String producer;

    @JsonProperty("message_type")
    private String messageType;

    @JsonProperty("operation_id")
    private UUID operationId;

    @JsonProperty("data")
    private Object data;

    @JsonProperty("outcome")
    private String outcome;

    @JsonProperty("error")
    private Map<String, Object> error;

    /** No-arg constructor for Jackson deserialization. */
    public MessageEnvelope() {
    }

    private MessageEnvelope(Builder b) {
        this.messageId = b.messageId;
        this.schemaVersion = b.schemaVersion;
        this.transactionId = b.transactionId;
        this.correlationId = b.correlationId;
        this.causationId = b.causationId;
        this.occurredAt = b.occurredAt;
        this.producer = b.producer;
        this.messageType = b.messageType;
        this.operationId = b.operationId;
        this.data = b.data;
        this.outcome = b.outcome;
        this.error = b.error;
    }

    /* Getters — Jackson uses them for serialization. ----------------- */

    public UUID getMessageId() { return messageId; }
    public String getSchemaVersion() { return schemaVersion; }
    public UUID getTransactionId() { return transactionId; }
    public UUID getCorrelationId() { return correlationId; }
    public UUID getCausationId() { return causationId; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public String getProducer() { return producer; }
    public String getMessageType() { return messageType; }
    public UUID getOperationId() { return operationId; }
    public Object getData() { return data; }
    public String getOutcome() { return outcome; }
    public Map<String, Object> getError() { return error; }

    /* Setters — Jackson uses them for deserialization. --------------- */

    public void setMessageId(UUID messageId) { this.messageId = messageId; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }
    public void setCorrelationId(UUID correlationId) { this.correlationId = correlationId; }
    public void setCausationId(UUID causationId) { this.causationId = causationId; }
    public void setOccurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; }
    public void setProducer(String producer) { this.producer = producer; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public void setOperationId(UUID operationId) { this.operationId = operationId; }
    public void setData(Object data) { this.data = data; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public void setError(Map<String, Object> error) { this.error = error; }

    @Override
    public String toString() {
        return "MessageEnvelope{messageType='" + messageType + '\'' +
                ", messageId=" + messageId +
                ", transactionId=" + transactionId +
                ", operationId=" + operationId +
                ", outcome=" + outcome +
                '}';
    }

    /* Builder -------------------------------------------------------- */

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID messageId;
        private String schemaVersion;
        private UUID transactionId;
        private UUID correlationId;
        private UUID causationId;
        private OffsetDateTime occurredAt;
        private String producer;
        private String messageType;
        private UUID operationId;
        private Object data;
        private String outcome;
        private Map<String, Object> error;

        private Builder() {
        }

        public Builder messageId(UUID messageId) { this.messageId = messageId; return this; }
        public Builder schemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; return this; }
        public Builder transactionId(UUID transactionId) { this.transactionId = transactionId; return this; }
        public Builder correlationId(UUID correlationId) { this.correlationId = correlationId; return this; }
        public Builder causationId(UUID causationId) { this.causationId = causationId; return this; }
        public Builder occurredAt(OffsetDateTime occurredAt) { this.occurredAt = occurredAt; return this; }
        public Builder producer(String producer) { this.producer = producer; return this; }
        public Builder messageType(String messageType) { this.messageType = messageType; return this; }
        public Builder operationId(UUID operationId) { this.operationId = operationId; return this; }
        public Builder data(Object data) { this.data = data; return this; }
        public Builder outcome(String outcome) { this.outcome = outcome; return this; }
        public Builder error(Map<String, Object> error) { this.error = error; return this; }

        public MessageEnvelope build() {
            return new MessageEnvelope(this);
        }
    }
}
