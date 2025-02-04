package org.riptide.configuration;

import org.riptide.config.PostgresConfig;
import org.riptide.repository.FlowRepository;
import org.riptide.repository.postgres.PostgresRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "riptide.postgres.enabled", havingValue = "true")
public class PostgresConfiguration {
    @Bean
    FlowRepository postgresRepository(final PostgresConfig config,
                                      @Qualifier("postgresDataSource") DataSource dataSource) {
        if (!config.isPersistFlows() && !config.isPersistBuckets()) {
            throw new IllegalStateException("Postgres persistence is enabled, but neither flows nor buckets are persisted. Enable either or both");
        }
        return new PostgresRepository(config, dataSource);
    }

    @Bean("postgresDataSource")
    DataSource postgresDataSource(final PostgresConfig config) {
        final var ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(config.getJdbcUrl());
        if (config.getUsername() != null) {
            ds.setUser(config.getUsername());
        }
        if (config.getPassword() != null) {
            ds.setPassword(config.getPassword());
        }
        return ds;
    }

}