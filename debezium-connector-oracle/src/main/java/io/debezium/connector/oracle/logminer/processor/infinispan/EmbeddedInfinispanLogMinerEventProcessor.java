/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.oracle.logminer.processor.infinispan;

import static io.debezium.connector.oracle.OracleConnectorConfig.LOG_MINING_BUFFER_INFINISPAN_CACHE_EVENTS;
import static io.debezium.connector.oracle.OracleConnectorConfig.LOG_MINING_BUFFER_INFINISPAN_CACHE_PROCESSED_TRANSACTIONS;
import static io.debezium.connector.oracle.OracleConnectorConfig.LOG_MINING_BUFFER_INFINISPAN_CACHE_SCHEMA_CHANGES;
import static io.debezium.connector.oracle.OracleConnectorConfig.LOG_MINING_BUFFER_INFINISPAN_CACHE_TRANSACTIONS;

import java.util.Map;
import java.util.Objects;

import org.infinispan.commons.api.BasicCache;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.config.Field;
import io.debezium.connector.oracle.OracleConnection;
import io.debezium.connector.oracle.OracleConnectorConfig;
import io.debezium.connector.oracle.OracleDatabaseSchema;
import io.debezium.connector.oracle.OracleOffsetContext;
import io.debezium.connector.oracle.OraclePartition;
import io.debezium.connector.oracle.logminer.LogMinerStreamingChangeEventSourceMetrics;
import io.debezium.connector.oracle.logminer.processor.CacheProvider;
import io.debezium.connector.oracle.logminer.processor.LogMinerCache;
import io.debezium.connector.oracle.logminer.processor.LogMinerTransactionCache;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.relational.TableId;

/**
 * A concrete implementation of {@link AbstractInfinispanLogMinerEventProcessor} that uses Infinispan in
 * embedded mode to store transaction and mined event data in caches.
 *
 * The cache configurations are supplied via connector configurations and are expected to be valid XML
 * that represent parseable local cache setups for Infinispan.
 *
 * @author Chris Cranford
 */
public class EmbeddedInfinispanLogMinerEventProcessor extends AbstractInfinispanLogMinerEventProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddedInfinispanLogMinerEventProcessor.class);

    private final EmbeddedCacheManager cacheManager;
    private final boolean dropBufferOnStop;

    private final LogMinerTransactionCache<InfinispanTransaction> transactionCache;
    private final LogMinerCache<String, String> processedTransactionsCache;
    private final LogMinerCache<String, String> schemaChangesCache;

    public EmbeddedInfinispanLogMinerEventProcessor(ChangeEventSourceContext context,
                                                    OracleConnectorConfig connectorConfig,
                                                    OracleConnection jdbcConnection,
                                                    EventDispatcher<OraclePartition, TableId> dispatcher,
                                                    OraclePartition partition,
                                                    OracleOffsetContext offsetContext,
                                                    OracleDatabaseSchema schema,
                                                    LogMinerStreamingChangeEventSourceMetrics metrics) {
        super(context, connectorConfig, jdbcConnection, dispatcher, partition, offsetContext, schema, metrics);

        LOGGER.info("Using Infinispan in embedded mode.");
        this.cacheManager = new DefaultCacheManager(
                parseAndGetGlobalConfiguration(connectorConfig));
        this.dropBufferOnStop = connectorConfig.isLogMiningBufferDropOnStop();

        this.transactionCache = new InfinispanLogMinerTransactionCache(
                createCache(TRANSACTIONS_CACHE_NAME, connectorConfig, LOG_MINING_BUFFER_INFINISPAN_CACHE_TRANSACTIONS),
                createCache(EVENTS_CACHE_NAME, connectorConfig, LOG_MINING_BUFFER_INFINISPAN_CACHE_EVENTS));
        this.processedTransactionsCache = new InfinispanLogMinerCache<>(
                createCache(PROCESSED_TRANSACTIONS_CACHE_NAME, connectorConfig, LOG_MINING_BUFFER_INFINISPAN_CACHE_PROCESSED_TRANSACTIONS));
        this.schemaChangesCache = new InfinispanLogMinerCache<>(
                createCache(SCHEMA_CHANGES_CACHE_NAME, connectorConfig, LOG_MINING_BUFFER_INFINISPAN_CACHE_SCHEMA_CHANGES));

        displayCacheStatistics();
    }

    @Override
    public void close() throws Exception {
        if (dropBufferOnStop) {
            LOGGER.info("Clearing infinispan caches");
            transactionCache.clear();
            schemaChangesCache.clear();
            processedTransactionsCache.clear();

            // this block should only be used by tests, should we wrap this in case admin rights aren't given?
            cacheManager.administration().removeCache(CacheProvider.TRANSACTIONS_CACHE_NAME);
            cacheManager.administration().removeCache(CacheProvider.PROCESSED_TRANSACTIONS_CACHE_NAME);
            cacheManager.administration().removeCache(CacheProvider.SCHEMA_CHANGES_CACHE_NAME);
            cacheManager.administration().removeCache(CacheProvider.EVENTS_CACHE_NAME);
        }
        LOGGER.info("Shutting down infinispan embedded caches");
        cacheManager.close();
    }

    @Override
    public LogMinerTransactionCache<InfinispanTransaction> getTransactionCache() {
        return transactionCache;
    }

    @Override
    public LogMinerCache<String, String> getSchemaChangesCache() {
        return schemaChangesCache;
    }

    @Override
    public LogMinerCache<String, String> getProcessedTransactionsCache() {
        return processedTransactionsCache;
    }

    private <K, V> BasicCache<K, V> createCache(String cacheName, OracleConnectorConfig connectorConfig, Field field) {
        Objects.requireNonNull(cacheName);

        final String cacheConfiguration = connectorConfig.getConfig().getString(field);
        Objects.requireNonNull(cacheConfiguration);

        // define the cache, parsing the supplied XML configuration
        cacheManager.defineConfiguration(cacheName, parseAndGetConfiguration(cacheName, cacheConfiguration));
        return cacheManager.getCache(cacheName);
    }

    private Configuration parseAndGetConfiguration(String cacheName, String configuration) {
        // We should only be parsing a single cache configuration; however since we have no control over the
        // contents of the provided XML, we validate there is at least 1 and only 1 valid configuration that
        // is provided, and we use the configuration as is.
        //
        // The purpose of these checks is to allow the user to supply a cache configuration with any valid
        // Infinispan name, and we automatically map that configuration to the cache names used by Debezium.
        // So for example, a user could supply a cache config named "processed_transactions" and we would
        // allow that being mapped to our internal cache name of "processed-transactions". Using iterators
        // avoids a NullPointerException in the event the user supplies a name that doesn't match to our
        // internal names exactly.
        final ConfigurationBuilderHolder builderHolder = new ParserRegistry().parse(configuration);
        final Map<String, ConfigurationBuilder> builders = builderHolder.getNamedConfigurationBuilders();
        if (builders.size() > 1) {
            throw new DebeziumException("Infinispan cache configuration for '" + cacheName +
                    "' contains multiple cache configurations and should only contain one.");
        }
        else if (builders.isEmpty()) {
            throw new DebeziumException("Infinispan cache configuration for '" + cacheName +
                    "' contained no valid cache configuration. Please check your connector configuration");
        }
        else {
            return builders.values().iterator().next().build();
        }
    }

    private GlobalConfiguration parseAndGetGlobalConfiguration(OracleConnectorConfig connectorConfig) {
        final String globalCacheConfiguration = connectorConfig.getLogMiningInifispanGlobalConfiguration();
        if (globalCacheConfiguration == null) {
            // if no configuration provided, use the default
            return new GlobalConfigurationBuilder().build();
        }
        final ConfigurationBuilderHolder builderHolder = new ParserRegistry().parse(globalCacheConfiguration);
        return builderHolder.getGlobalConfigurationBuilder().build();
    }
}
