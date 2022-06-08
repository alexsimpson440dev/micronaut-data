/*
 * Copyright 2017-2022 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.data.mongodb.operations;

import com.mongodb.client.model.Collation;
import com.mongodb.client.model.DeleteOptions;
import com.mongodb.client.model.InsertManyOptions;
import com.mongodb.client.model.InsertOneOptions;
import com.mongodb.client.model.ReplaceOptions;
import io.micronaut.aop.MethodInvocationContext;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.util.StringUtils;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.model.runtime.AttributeConverterRegistry;
import io.micronaut.data.model.runtime.PreparedQuery;
import io.micronaut.data.model.runtime.RuntimeEntityRegistry;
import io.micronaut.data.model.runtime.RuntimePersistentEntity;
import io.micronaut.data.model.runtime.RuntimePersistentProperty;
import io.micronaut.data.model.runtime.StoredQuery;
import io.micronaut.data.mongodb.annotation.MongoRepository;
import io.micronaut.data.mongodb.operations.options.MongoAggregationOptions;
import io.micronaut.data.mongodb.operations.options.MongoFindOptions;
import io.micronaut.data.mongodb.operations.options.MongoOptionsUtils;
import io.micronaut.data.operations.HintsCapableRepository;
import io.micronaut.data.repository.GenericRepository;
import io.micronaut.data.runtime.config.DataSettings;
import io.micronaut.data.runtime.convert.DataConversionService;
import io.micronaut.data.runtime.date.DateTimeProvider;
import io.micronaut.data.runtime.operations.internal.AbstractRepositoryOperations;
import io.micronaut.data.runtime.query.MethodContextAwareStoredQueryDecorator;
import io.micronaut.data.runtime.query.PreparedQueryDecorator;
import io.micronaut.data.runtime.query.internal.QueryResultStoredQuery;
import io.micronaut.http.codec.MediaTypeCodec;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.bson.BsonDocument;
import org.bson.BsonDocumentWrapper;
import org.bson.BsonNull;
import org.bson.BsonValue;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Shared implementation of Mongo sync and reactive repositories.
 *
 * @param <Dtb> The database type
 * @author Denis Stepanov
 * @since 3.3
 */
@Internal
abstract class AbstractMongoRepositoryOperations<Dtb> extends AbstractRepositoryOperations
        implements HintsCapableRepository, PreparedQueryDecorator, MethodContextAwareStoredQueryDecorator {

    protected static final Logger QUERY_LOG = DataSettings.QUERY_LOG;
    protected static final BsonDocument EMPTY = new BsonDocument();
    protected final Map<Class, String> repoDatabaseConfig;

    /**
     * Default constructor.
     *
     * @param server                     The server
     * @param beanContext                The bean context
     * @param codecs                     The media type codecs
     * @param dateTimeProvider           The date time provider
     * @param runtimeEntityRegistry      The entity registry
     * @param conversionService          The conversion service
     * @param attributeConverterRegistry The attribute converter registry
     */
    protected AbstractMongoRepositoryOperations(String server,
                                                BeanContext beanContext,
                                                List<MediaTypeCodec> codecs,
                                                DateTimeProvider<Object> dateTimeProvider,
                                                RuntimeEntityRegistry runtimeEntityRegistry,
                                                DataConversionService<?> conversionService,
                                                AttributeConverterRegistry attributeConverterRegistry) {
        super(codecs, dateTimeProvider, runtimeEntityRegistry, conversionService, attributeConverterRegistry);
        Collection<BeanDefinition<GenericRepository>> beanDefinitions = beanContext
                .getBeanDefinitions(GenericRepository.class, Qualifiers.byStereotype(MongoRepository.class));
        HashMap<Class, String> repoDatabaseConfig = new HashMap<>();
        for (BeanDefinition<GenericRepository> beanDefinition : beanDefinitions) {
            String targetSrv = beanDefinition.stringValue(Repository.class).orElse(null);
            if (targetSrv == null || targetSrv.isEmpty() || targetSrv.equalsIgnoreCase(server)) {
                String database = beanDefinition.stringValue(MongoRepository.class, "databaseName").orElse(null);
                if (StringUtils.isNotEmpty(database)) {
                    repoDatabaseConfig.put(beanDefinition.getBeanType(), database);
                }
            }
        }
        this.repoDatabaseConfig = Collections.unmodifiableMap(repoDatabaseConfig);
    }

    protected final ReplaceOptions getReplaceOptions(AnnotationMetadata annotationMetadata) {
        return MongoOptionsUtils.buildReplaceOptions(annotationMetadata).orElseGet(ReplaceOptions::new);
    }

    protected final InsertOneOptions getInsertOneOptions(AnnotationMetadata annotationMetadata) {
        return MongoOptionsUtils.buildInsertOneOptions(annotationMetadata).orElseGet(InsertOneOptions::new);
    }

    protected final InsertManyOptions getInsertManyOptions(AnnotationMetadata annotationMetadata) {
        return MongoOptionsUtils.buildInsertManyOptions(annotationMetadata).orElseGet(InsertManyOptions::new);
    }

    protected final DeleteOptions getDeleteOptions(AnnotationMetadata annotationMetadata) {
        return MongoOptionsUtils.buildDeleteOptions(annotationMetadata, true).orElseGet(DeleteOptions::new);
    }

    protected abstract Dtb getDatabase(RuntimePersistentEntity<?> persistentEntity, Class<?> repository);

    protected abstract CodecRegistry getCodecRegistry(Dtb database);

    protected <E, R> MongoStoredQuery<E, R, Dtb> getMongoStoredQuery(StoredQuery<E, R> storedQuery) {
        if (storedQuery instanceof MongoStoredQuery) {
            return (MongoStoredQuery<E, R, Dtb>) storedQuery;
        }
        throw new IllegalStateException("Expected for stored query to be of type: MongoStoredQuery");
    }

    protected <E, R> MongoPreparedQuery<E, R, Dtb> getMongoPreparedQuery(PreparedQuery<E, R> preparedQuery) {
        if (preparedQuery instanceof MongoPreparedQuery) {
            return (MongoPreparedQuery<E, R, Dtb>) preparedQuery;
        }
        throw new IllegalStateException("Expected for prepared query to be of type: MongoPreparedQuery");
    }

    @Override
    public <E, R> PreparedQuery<E, R> decorate(PreparedQuery<E, R> preparedQuery) {
        return new DefaultMongoPreparedQuery<>(preparedQuery);
    }

    @Override
    public <E, R> StoredQuery<E, R> decorate(MethodInvocationContext<?, ?> context, StoredQuery<E, R> storedQuery) {
        RuntimePersistentEntity<E> persistentEntity = runtimeEntityRegistry.getEntity(storedQuery.getRootEntity());
        Class<?> repositoryType = context.getTarget().getClass();
        Dtb database = getDatabase(persistentEntity, repositoryType);
        CodecRegistry codecRegistry = getCodecRegistry(database);
        if (storedQuery instanceof QueryResultStoredQuery) {
            QueryResultStoredQuery<?, ?> resultStoredQuery = (QueryResultStoredQuery) storedQuery;
            String update = resultStoredQuery.getQueryResult().getUpdate();
            if (update != null) {
                return new DefaultMongoStoredQuery<>(storedQuery, codecRegistry, attributeConverterRegistry,
                        runtimeEntityRegistry, conversionService, persistentEntity, database, resultStoredQuery.getOperationType(), update);
            }
        }
        return new DefaultMongoStoredQuery<>(storedQuery, codecRegistry, attributeConverterRegistry,
                runtimeEntityRegistry, conversionService, persistentEntity, database);
    }

    protected <R> R convertResult(CodecRegistry codecRegistry,
                                  Class<R> resultType,
                                  BsonDocument result,
                                  boolean isDtoProjection) {
        if (resultType == BsonDocument.class) {
            return (R) result;
        }
        BsonValue value;
        if (result == null) {
            value = BsonNull.VALUE;
        } else if (result.size() == 1) {
            value = result.values().iterator().next();
        } else if (result.size() == 2) {
            Optional<Map.Entry<String, BsonValue>> id = result.entrySet().stream().filter(f -> !f.getKey().equals("_id")).findFirst();
            if (id.isPresent()) {
                value = id.get().getValue();
            } else {
                value = result.values().iterator().next();
            }
        } else if (isDtoProjection) {
            Object dtoResult = MongoUtils.toValue(result.asDocument(), resultType, codecRegistry);
            if (resultType.isInstance(dtoResult)) {
                return (R) dtoResult;
            }
            return conversionService.convertRequired(dtoResult, resultType);
        } else {
            throw new IllegalStateException("Unrecognized result: " + result);
        }
        return conversionService.convertRequired(MongoUtils.toValue(value), resultType);
    }

    protected BsonDocument association(CodecRegistry codecRegistry,
                                       Object value, RuntimePersistentEntity<Object> persistentEntity,
                                       Object child, RuntimePersistentEntity<Object> childPersistentEntity) {
        BsonDocument document = new BsonDocument();
        document.put(persistentEntity.getPersistedName(), MongoUtils.entityIdValue(conversionService, persistentEntity, value, codecRegistry));
        document.put(childPersistentEntity.getPersistedName(), MongoUtils.entityIdValue(conversionService, childPersistentEntity, child, codecRegistry));
        return document;
    }

    protected final <T> Bson createFilterIdAndVersion(RuntimePersistentEntity<T> persistentEntity, T entity, CodecRegistry codecRegistry) {
        BsonDocument bsonDocument = BsonDocumentWrapper.asBsonDocument(entity, codecRegistry);
        BsonDocument filter = new BsonDocument();
        filter.put(MongoUtils.ID, bsonDocument.get(MongoUtils.ID));
        RuntimePersistentProperty<T> version = persistentEntity.getVersion();
        if (version != null) {
            filter.put(version.getPersistedName(), bsonDocument.get(version.getPersistedName()));
        }
        return filter;
    }

    protected void logFind(MongoFind find) {
        StringBuilder sb = new StringBuilder("Executing Mongo 'find'");
        MongoFindOptions options = find.getOptions();
        if (options != null) {
            sb.append(" with");
            Bson filter = options.getFilter();
            sb.append(" filter: ").append(filter == null ? "{}" : filter.toBsonDocument().toJson());
            Bson sort = options.getSort();
            if (sort != null) {
                sb.append(" sort: ").append(sort.toBsonDocument().toJson());
            }
            Bson projection = options.getProjection();
            if (projection != null) {
                sb.append(" projection: ").append(projection.toBsonDocument().toJson());
            }
            Collation collation = options.getCollation();
            if (collation != null) {
                sb.append(" collation: ").append(collation);
            }
        }
        QUERY_LOG.debug(sb.toString());
    }

    protected void logAggregate(MongoAggregation aggregation) {
        MongoAggregationOptions options = aggregation.getOptions();
        StringBuilder sb = new StringBuilder("Executing Mongo 'aggregate'");
        if (options != null) {
            sb.append(" with");
            sb.append(" pipeline: ").append(aggregation.getPipeline().stream().map(e -> e.toBsonDocument().toJson()).collect(Collectors.toList()));
            Collation collation = options.getCollation();
            if (collation != null) {
                sb.append(" collation: ").append(collation);
            }
        }
        QUERY_LOG.debug(sb.toString());
    }

}