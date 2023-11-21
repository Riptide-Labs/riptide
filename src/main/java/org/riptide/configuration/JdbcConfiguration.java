package org.riptide.configuration;

import org.riptide.repository.yugabyte.FlowEntitiy;
import org.riptide.repository.yugabyte.FlowJdbcRepository;
import org.riptide.repository.yugabyte.JdbcFlowRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.relational.core.mapping.event.BeforeSaveCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionManager;

import javax.sql.DataSource;
import java.util.UUID;

@Configuration
@EnableJdbcRepositories(basePackageClasses = FlowEntitiy.class)
@ConditionalOnProperty(name = "riptide.jdbc.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcConfiguration extends AbstractJdbcConfiguration {

    @Bean
    public NamedParameterJdbcOperations namedParameterJdbcOperations(final DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    public TransactionManager transactionManager(final DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    public JdbcFlowRepository flowRepository(final FlowJdbcRepository flowJdbcRepository,
                                             final FlowEntitiy.FlowEntityMapper flowEntityMapper) {
        return new JdbcFlowRepository(flowJdbcRepository, flowEntityMapper);
    }

    @Bean
    public BeforeSaveCallback<FlowEntitiy> ensureID() {
        return (entity, aggregateChange) -> {
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID()); // TODO fooker: faster random
            }

            return entity;
        };
    }
}
