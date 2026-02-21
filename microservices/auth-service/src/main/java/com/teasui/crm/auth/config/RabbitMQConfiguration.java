package com.teasui.crm.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.teasui.crm.common.messaging.RabbitMQConfig;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfiguration {

    @Bean
    public TopicExchange authExchange() {
        return new TopicExchange(RabbitMQConfig.AUTH_EXCHANGE, true, false);
    }

    @Bean
    public Queue authEventQueue() {
        return QueueBuilder.durable(RabbitMQConfig.AUTH_EVENT_QUEUE)
                .withArgument("x-dead-letter-exchange", RabbitMQConfig.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Binding authEventBinding(Queue authEventQueue, TopicExchange authExchange) {
        return BindingBuilder.bind(authEventQueue)
                .to(authExchange)
                .with("auth.#");
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(mapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        return template;
    }
}
