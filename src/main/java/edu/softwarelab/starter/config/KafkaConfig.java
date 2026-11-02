package edu.softwarelab.starter.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import edu.softwarelab.starter.messaging.MessageEnvelope;

/**
 * Kafka configuration for the S3 Payment service.
 *
 * <p>Defines the {@link ProducerFactory} / {@link ConsumerFactory} / Kafka
 * template / listener-container factory for {@link MessageEnvelope} values.
 * Both serializer and deserializer use a dedicated {@link ObjectMapper} with
 * {@link JavaTimeModule} registered and {@code WRITE_DATES_AS_TIMESTAMPS}
 * disabled — this guarantees that {@link java.time.OffsetDateTime} fields
 * round-trip as ISO-8601 strings (e.g. {@code 2026-11-01T08:00:00Z}).
 *
 * <p>Consumers commit offsets manually ({@code AckMode#MANUAL}) so
 * that a crash mid-processing does not lose a result. The consumer group id
 * is {@code s3-payment-service} for both S4 and S5 result topics.
 *
 * <p>Trusting packages: the only concrete type that the JsonDeserializer needs
 * to instantiate is {@link MessageEnvelope} itself; the {@code data} field is
 * {@link Object} and deserializes to a {@code LinkedHashMap} which the
 * consumers convert to the typed DTO via {@code ObjectMapper#convertValue}.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    public static final String GROUP_ID = "s3-payment-service";

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /* -----------------------------------------------------------------
     * Dedicated ObjectMapper for Kafka serializer/deserializer.
     * ----------------------------------------------------------------- */

    private ObjectMapper kafkaObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    /* -----------------------------------------------------------------
     * REST-API note: Spring Boot 4.x ships with Jackson 3.x, in which the
     * WRITE_DATES_AS_TIMESTAMPS feature no longer exists — OffsetDateTime
     * is serialized as ISO-8601 by default. The JavaTime support is built
     * into jackson-databind 3.x (tools.jackson.databind.ext.javatime) and
     * is auto-registered. Therefore no REST-side ObjectMapper customization
     * is needed for ISO-8601 OffsetDateTime serialization.
     * ----------------------------------------------------------------- */

    /* -----------------------------------------------------------------
     * Producer
     * ----------------------------------------------------------------- */

    @Bean
    public ProducerFactory<String, MessageEnvelope> producerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        configs.put(ProducerConfig.ACKS_CONFIG, "all");
        configs.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configs.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        configs.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

        JsonSerializer<MessageEnvelope> valueSerializer =
                new JsonSerializer<>(kafkaObjectMapper());
        StringSerializer keySerializer = new StringSerializer();

        log.info("Kafka producer factory configured bootstrap={}", bootstrapServers);
        return new DefaultKafkaProducerFactory<>(configs, keySerializer, valueSerializer);
    }

    @Bean
    public KafkaTemplate<String, MessageEnvelope> kafkaTemplate(
            ProducerFactory<String, MessageEnvelope> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /* -----------------------------------------------------------------
     * Consumer
     * ----------------------------------------------------------------- */

    @Bean
    public ConsumerFactory<String, MessageEnvelope> consumerFactory() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID);
        // The JsonDeserializer instance below is configured through setters.
        // Do not also configure spring.json.* properties in this map: Spring Kafka
        // rejects mixing setter-based and property-based deserializer configuration.

        JsonDeserializer<MessageEnvelope> valueDeserializer =
                new JsonDeserializer<>(MessageEnvelope.class, kafkaObjectMapper());
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.setRemoveTypeHeaders(true);
        // Belt-and-suspenders trust since setUseTypeHeaders(false) already
        // prevents Jackson polymorphic deserialization.
        valueDeserializer.addTrustedPackages(
                "edu.softwarelab.starter.messaging",
                "edu.softwarelab.starter.messaging.dto"
        );

        StringDeserializer keyDeserializer = new StringDeserializer();

        log.info("Kafka consumer factory configured bootstrap={} groupId={}",
                bootstrapServers, GROUP_ID);
        return new DefaultKafkaConsumerFactory<>(configs, keyDeserializer, valueDeserializer);
    }

    @Bean(name = "kafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, MessageEnvelope>
    kafkaListenerContainerFactory(ConsumerFactory<String, MessageEnvelope> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, MessageEnvelope> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        // MANUAL queues the acknowledgement for the container to commit after the
        // listener invocation returns. MANUAL_IMMEDIATE could commit the Kafka
        // offset inside the @Transactional listener, before the DB transaction
        // has committed, which risks losing the result after a crash.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        // Single-threaded consumer per listener is sufficient for this service.
        factory.setConcurrency(1);
        return factory;
    }

    /* -----------------------------------------------------------------
     * KafkaAdmin — used by HealthController for the readiness probe.
     * In Spring Boot 4.x with spring-kafka 4.x, this bean is NOT
     * auto-configured by default, so we declare it explicitly.
     * ----------------------------------------------------------------- */
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers);
        KafkaAdmin admin = new KafkaAdmin(configs);
        log.info("Kafka admin configured bootstrap={}", bootstrapServers);
        return admin;
    }
}
