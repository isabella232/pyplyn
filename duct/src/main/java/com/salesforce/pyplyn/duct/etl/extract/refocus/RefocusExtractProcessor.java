/*
 *  Copyright (c) 2016-2017, Salesforce.com, Inc.
 *  All rights reserved.
 *  Licensed under the BSD 3-Clause license.
 *  For full license text, see the LICENSE.txt file in repo root
 *    or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.pyplyn.duct.etl.extract.refocus;

import com.codahale.metrics.Timer;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.salesforce.pyplyn.cache.Cache;
import com.salesforce.pyplyn.cache.CacheFactory;
import com.salesforce.pyplyn.cache.ConcurrentCacheMap;
import com.salesforce.pyplyn.client.AuthenticatedEndpointProvider;
import com.salesforce.pyplyn.client.ClientFactory;
import com.salesforce.pyplyn.client.UnauthorizedException;
import com.salesforce.pyplyn.duct.app.ShutdownHook;
import com.salesforce.pyplyn.duct.providers.client.RemoteClientFactory;
import com.salesforce.pyplyn.model.TransformationResult;
import com.salesforce.pyplyn.model.builder.TransformationResultBuilder;
import com.salesforce.pyplyn.processor.AbstractMeteredExtractProcessor;
import com.salesforce.refocus.RefocusClient;
import com.salesforce.refocus.model.Sample;
import com.salesforce.refocus.model.builder.SampleBuilder;

import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.salesforce.pyplyn.util.FormatUtils.*;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Queries data from Refocus
 * <p/>Annotated as Singleton as there should only be one instance of this class in operation.
 *
 * @author Mihai Bojin &lt;mbojin@salesforce.com&gt;
 * @since 3.0
 */
@Singleton
public class RefocusExtractProcessor extends AbstractMeteredExtractProcessor<Refocus> implements AuthenticatedEndpointProvider<RefocusClient> {
    private static final String RESPONSE_TIMEOUT = "Timeout";
    private final RemoteClientFactory<RefocusClient> refocusClientFactory;
    private final CacheFactory cacheFactory;
    private final ConcurrentHashMap<RefocusClient, ConcurrentCacheMap<Sample>> clientToCacheMap = new ConcurrentHashMap<>();
    private final ShutdownHook shutdownHook;


    @Inject
    public RefocusExtractProcessor(RemoteClientFactory<RefocusClient> refocusClientFactory, CacheFactory cacheFactory, ShutdownHook shutdownHook) {
        this.refocusClientFactory = refocusClientFactory;
        this.cacheFactory = cacheFactory;
        this.shutdownHook = shutdownHook;
    }

    /**
     * Processes a list of Refocus expressions and returns their results
     */
    public List<List<TransformationResult>> process(List<Refocus> data) {
        return data.stream()
                // group by Refocus endpoint
                .collect(Collectors.groupingBy(Refocus::endpoint))

                // process each endpoint in parallel
                .entrySet().parallelStream()

                // process each (endpointId, expressions) pair
                .map(endpointExpressions -> {
                    final String endpointId = endpointExpressions.getKey();

                    // retrieve Refocus client and cache for the specified endpoint
                    final RefocusClient client = initializeEndpointOrLogFailure(endpointId, this);
                    final Cache<Sample> endpointCache = getOrInitializeCacheFor(clientToCacheMap, client, cacheFactory);

                    // stop here if we cannot find an endpoint
                    if (isNull(client)) {
                        failed();
                        return null;
                    }

                    // go through all expressions to load for the current endpoint
                    return endpointExpressions.getValue().stream()
                            .map(refocus -> {
                                // attempt to load from cache
                                boolean shouldCache = false;
                                boolean isDefault = false;
                                Sample sample = endpointCache.isCached(refocus.cacheKey());

                                // if not found in cache, load from cache
                                if (isNull(sample)) {
                                    try {
                                        // short circuit if app was shutdown
                                        if (shutdownHook.isShutdown()) {
                                            return null;
                                        }

                                        // load Sample from Refocus endpoint
                                        try (Timer.Context context = systemStatus.timer(meterName(), "get-sample." + endpointId).time()) {
                                            sample = client.getSample(refocus.name(), null);
                                        }

                                        // if a null response was returned, but we have a default value, create a sample from it
                                        if (isNull(sample) && nonNull(refocus.defaultValue())) {
                                            String now = Instant.now().atZone(ZoneOffset.UTC).toString();
                                            sample = new SampleBuilder()
                                                    .withName(refocus.name())
                                                    .withValue(formatNumber(refocus.defaultValue()))
                                                    .withUpdatedAt(now)
                                                    .build();
                                            logger.info("Default data provided for {}={}, endpoint {}", sample.name(), sample.value(), endpointId);
                                            isDefault = true;
                                        }

                                        // if a null response was returned from endpoint and we didn't have a default value, mark no-data and stop
                                        if (isNull(sample)) {
                                            logger.error("No data for sample {}, endpoint {}; null response", refocus.name(), endpointId);
                                            noData();

                                            return null;
                                        }

                                        // if a default value was specified, and we have a timed out sample, replace it with the default value
                                        if (nonNull(refocus.defaultValue()) && isTimedOut(sample)) {
                                            sample = new SampleBuilder(sample).withValue(formatNumber(refocus.defaultValue())).build();
                                            logger.info("Default data provided for {}={}, endpoint {}", sample.name(), sample.value(), endpointId);
                                            isDefault = true;
                                        }

                                        // since we loaded a new sample from the endpoint,
                                        if (!isDefault && !isTimedOut(sample)) {
                                            // cache it if not timed out, if so configured
                                            shouldCache = refocus.cacheMillis() > 0;
                                        }


                                    } catch (UnauthorizedException e) {
                                        logger.error("Could not complete request for endpoint {}; failed metric={}; due to {}", endpointId, refocus.name(), e.getMessage());
                                        failed();
                                    }
                                }

                                // at this point we either have a valid cached sample or we loaded a new one from the endpoint
                                TransformationResult result = createResult(sample, endpointId);

                                // if a transform result could not be created (due to various reasons) mark as failure and stop here
                                if (isNull(result)) {
                                    failed();
                                    return null;
                                }

                                // if this was a default value, append metadata message
                                if (isDefault) {
                                    String defaultValueMessage =
                                            generateDefaultValueMessage(refocus.name(), refocus.defaultValue());
                                    result = new TransformationResultBuilder(result)
                                            .metadata((metadata) -> metadata
                                                    .addMessage(defaultValueMessage))
                                            .build();
                                }

                                // cache result if required;
                                if (shouldCache) {
                                    endpointCache.cache(sample, refocus.cacheMillis());
                                }

                                succeeded();
                                logger.info("Loaded data for {}, endpoint {}", refocus.name(), endpointId);

                                return result;

                            })
                            // filter any errored results
                            .filter(Objects::nonNull)

                            // add another layer (wrap every result in a Collection, to generate a matrix
                            //   containing expression results on each row and a single result as columns
                            .map(Collections::singletonList)
                            .collect(Collectors.toList());
                })

                // flatten the cube generated by the initial grouping by Refocus::endpoint and then collect
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toList());
    }


    /**
     * Creates an extract result
     *
     * @return null if either time or value could not be passed, or the passed sample was null
     */
    TransformationResult createResult(Sample sample, String endpointId) {
        try {
            // try to parse time and value and return a TransformResultStage object
            ZonedDateTime parsedTime = parseUTCTime(sample.updatedAt());
            Number parsedNumber = parseNumber(sample.value());
            return new TransformationResult(parsedTime, sample.name(), parsedNumber, parsedNumber);

        } catch (DateTimeParseException e) {
            logger.warn("No data for {}, endpoint {}; invalid time: {}", sample.name(), endpointId, e.getMessage());
            noData();
            return null;

        } catch (ParseException e) {
            // if value could not be parsed, check if it's timed out
            if (isTimedOut(sample)) {
                logger.warn("No data for {}, endpoint {}; timed out", sample.name(), endpointId);

            } else {
                logger.warn("No data for {}, endpoint {}; invalid value: {}", sample.name(), endpointId, e.getMessage());
            }
            noData();
            return null;
        }
    }

    /**
     * Returns true, if the sample is timed out
     */
    private boolean isTimedOut(Sample sample) {
        return RESPONSE_TIMEOUT.equals(sample.value());
    }

    /**
     * Datasource type this processor can extract from
     */
    @Override
    public Class<Refocus> filteredType() {
        return Refocus.class;
    }

    /**
     * Meter name used to track this implementation's system status
     */
    @Override
    protected String meterName() {
        return "Refocus";
    }

    @Override
    public ClientFactory<RefocusClient> factory() {
        return refocusClientFactory;
    }
}
