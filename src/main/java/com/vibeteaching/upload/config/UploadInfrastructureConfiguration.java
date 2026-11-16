package com.vibeteaching.upload.config;

import com.zaxxer.hikari.HikariDataSource;
import io.minio.MinioClient;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@EnableConfigurationProperties(UploadProperties.class)
public class UploadInfrastructureConfiguration {
    @Bean
    public Clock uploadClock() { return Clock.systemUTC(); }

    @Configuration
    @ConditionalOnProperty(name = "app.upload.enabled", havingValue = "true")
    @EnableConfigurationProperties({DataSourceProperties.class, RedisProperties.class, ObjectStorageProperties.class})
    static class EnabledInfrastructure {
        @Bean(destroyMethod = "close")
        HikariDataSource uploadDataSource(DataSourceProperties properties) {
            HikariDataSource source = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
            source.setMaximumPoolSize(5);
            source.setConnectionTimeout(5000);
            return source;
        }

        @Bean(initMethod = "migrate")
        Flyway uploadFlyway(DataSource source) {
            return Flyway.configure().dataSource(source).locations("classpath:db/migration").load();
        }

        @Bean
        JdbcTemplate uploadJdbcTemplate(DataSource source) { return new JdbcTemplate(source); }

        @Bean
        PlatformTransactionManager uploadTransactionManager(DataSource source) {
            return new DataSourceTransactionManager(source);
        }

        @Bean
        LettuceConnectionFactory uploadRedisConnectionFactory(RedisProperties properties) {
            RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(properties.getHost(), properties.getPort());
            server.setDatabase(properties.getDatabase());
            if (properties.getUsername() != null) server.setUsername(properties.getUsername());
            if (properties.getPassword() != null) server.setPassword(RedisPassword.of(properties.getPassword()));
            LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                    .commandTimeout(properties.getTimeout() == null ? Duration.ofSeconds(3) : properties.getTimeout())
                    .shutdownTimeout(Duration.ZERO);
            if (properties.getSsl().isEnabled()) client.useSsl();
            return new LettuceConnectionFactory(server, client.build());
        }

        @Bean
        StringRedisTemplate uploadRedisTemplate(LettuceConnectionFactory factory) {
            return new StringRedisTemplate(factory);
        }

        @Bean(destroyMethod = "close")
        MinioClient uploadMinioClient(ObjectStorageProperties properties) {
            if (properties.endpoint() == null || properties.bucket() == null
                    || properties.accessKey() == null || properties.secretKey() == null) {
                throw new IllegalArgumentException("启用上传前请在本地 dev 配置填写对象存储连接信息");
            }
            return MinioClient.builder().endpoint(properties.endpoint())
                    .credentials(properties.accessKey(), properties.secretKey()).build();
        }
    }
}
