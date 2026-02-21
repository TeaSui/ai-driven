package com.teasui.crm.workflow.config;

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
    public TopicExchange workflowExchange() {
        return new TopicExchange(RabbitMQConfig.WORKFLOW_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(RabbitMQConfig.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Queue workflowQueue() {
        return QueueBuilder.durable(RabbitMQConfig.WORKFLOW_QUEUE)
                .withArgument("x-dead-letter-exchange", RabbitMQConfig.DEAD_LETTER_EXCHANGE)
                .build();
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(RabbitMQConfig.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public Binding workflowBinding(Queue workflowQueue, TopicExchange workflowExchange) {
        return BindingBuilder.bind(workflowQueue)
                .to(workflowExchange)
                .with("workflow.trigger");
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

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
}
