package com.github.walletengine.publisher;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.DefaultJacksonJavaTypeMapper;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Declares the wallet event topology; auto-created on startup by Boot's RabbitAdmin.
 *
 * <p>The mandatory flag required for {@code CorrelationData.getReturned()} comes from
 * {@code spring.rabbitmq.template.mandatory=true} (with publisher returns enabled).
 */
@Configuration
public class WalletAmqpConfiguration {

    public static final String EXCHANGE = "wallet.events";
    public static final String QUEUE = "wallet.updated";
    public static final String ROUTING_KEY = "wallet.updated";

    @Bean
    Exchange walletEventsExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    Queue walletUpdatedQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    Binding walletUpdatedBinding(Queue walletUpdatedQueue, Exchange walletEventsExchange) {
        return BindingBuilder.bind(walletUpdatedQueue).to(walletEventsExchange).with(ROUTING_KEY).noargs();
    }

    @Bean
    MessageConverter walletEventMessageConverter(JsonMapper jsonMapper) {
        // The default type mapper only trusts java.util/java.lang, which also
        // breaks deserializing our own events (see WalletOutboxPublisherTest).
        var typeMapper = new DefaultJacksonJavaTypeMapper();
        typeMapper.setTrustedPackages("com.github.walletengine.publisher");

        var converter = new JacksonJsonMessageConverter(jsonMapper);
        converter.setClassMapper(typeMapper);

        return converter;
    }
}
