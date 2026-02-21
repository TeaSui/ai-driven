package com.teasui.crm.notification.config;

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
    public TopicExchange notificationExchange() {
        return new TopicExchange(RabbitMQConfig.NOTIFICATION_EXCHANGE, true, false);
    }

    @Bean
    public TopicExchange workflowExchange() {
        return new TopicExchange(RabbitMQConfig.WORKFLOW_EXCHANGE, true, false);
    }

    @Bean
    public Queue notificationEmailQueue() {
        return QueueBuilder.durable(RabbitMQConfig.NOTIFICATION_EMAIL_QUEUE)
                .withArgument("x-dead-letter-exchange", RabbitMQConfig.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Queue notificationInAppQueue() {
        return QueueBuilder.durable(RabbitMQConfig.NOTIFICATION_IN_APP_QUEUE)
                .withArgument("x-dead-letter-exchange", RabbitMQConfig.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Queue workflowEventQueue() {
        return QueueBuilder.durable(RabbitMQConfig.WORKFLOW_QUEUE + ".notification")
                .withArgument("x-dead-letter-exchange", RabbitMQConfig.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Binding emailBinding(Queue notificationEmailQueue, TopicExchange notificationExchange) {
        return BindingBuilder.bind(notificationEmailQueue)
                .to(notificationExchange)
                .with(RabbitMQConfig.NOTIFICATION_EMAIL_KEY);
    }

    @Bean
    public Binding inAppBinding(Queue notificationInAppQueue, TopicExchange notificationExchange) {
        return BindingBuilder.bind(notificationInAppQueue)
                .to(notificationExchange)
                .with(RabbitMQConfig.NOTIFICATION_IN_APP_KEY);
    }

    @Bean
    public Binding workflowEventBinding(Queue workflowEventQueue, TopicExchange workflowExchange) {
        return BindingBuilder.bind(workflowEventQueue)
                .to(workflowExchange)
                .with("workflow.execution.#");
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
