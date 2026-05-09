package com.fraud.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${fraud.kafka.topic.transactions-raw}") private String transactionsRaw;
    @Value("${fraud.kafka.topic.fraud-scored}")      private String fraudScored;
    @Value("${fraud.kafka.topic.drift-signals}")     private String driftSignals;

    @Bean public NewTopic transactionsRawTopic() {
        return TopicBuilder.name(transactionsRaw).partitions(12).replicas(1).build();
    }
    @Bean public NewTopic fraudScoredTopic() {
        return TopicBuilder.name(fraudScored).partitions(6).replicas(1).build();
    }
    @Bean public NewTopic driftSignalsTopic() {
        return TopicBuilder.name(driftSignals).partitions(1).replicas(1).build();
    }
}
