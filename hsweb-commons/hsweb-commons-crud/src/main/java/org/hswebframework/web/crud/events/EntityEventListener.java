package org.hswebframework.web.crud.events;


import org.apache.commons.collections.CollectionUtils;
import org.hswebframework.ezorm.core.param.QueryParam;
import org.hswebframework.ezorm.rdb.events.*;
import org.hswebframework.ezorm.rdb.mapping.*;
import org.hswebframework.ezorm.rdb.mapping.events.MappingContextKeys;
import org.hswebframework.ezorm.rdb.mapping.events.MappingEventTypes;
import org.hswebframework.ezorm.rdb.mapping.events.ReactiveResultHolder;
import org.hswebframework.ezorm.rdb.metadata.RDBColumnMetadata;
import org.hswebframework.ezorm.rdb.metadata.TableOrViewMetadata;
import org.hswebframework.web.api.crud.entity.Entity;
import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.crud.annotation.EnableEntityEvent;
import org.hswebframework.web.event.AsyncEvent;
import org.hswebframework.web.event.GenericsPayloadApplicationEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import reactor.core.publisher.Mono;
import reactor.function.Function3;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

@SuppressWarnings("all")
public class EntityEventListener implements EventListener {

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Override
    public String getId() {
        return "entity-listener";
    }

    @Override
    public String getName() {
        return "实体变更事件监听器";
    }

    @Override
    public void onEvent(EventType type, EventContext context) {

        if (context.get(MappingContextKeys.error).isPresent()) {
            return;
        }
        EntityColumnMapping mapping = context.get(MappingContextKeys.columnMapping).orElse(null);
        if (mapping == null ||
                !Entity.class.isAssignableFrom(mapping.getEntityType()) ||
                mapping.getEntityType().getAnnotation(EnableEntityEvent.class) == null) {
            return;
        }
        if (type == MappingEventTypes.select_before) {
            handleQueryBefore(mapping, context);
        }
        if (type == MappingEventTypes.insert_before) {
            boolean single = context.get(MappingContextKeys.type).map("single"::equals).orElse(false);
            if (single) {
                handleSingleOperation(mapping.getEntityType(), context, EntityBeforeCreateEvent::new, EntityCreatedEvent::new);
            } else {
                handleBatchOperation(mapping.getEntityType(), context, EntityBeforeCreateEvent::new, EntityCreatedEvent::new);
            }
        }
        if (type == MappingEventTypes.save_before) {
            boolean single = context.get(MappingContextKeys.type).map("single"::equals).orElse(false);
            if (single) {
                handleSingleOperation(mapping.getEntityType(), context, EntityBeforeSaveEvent::new, EntitySavedEvent::new);
            } else {
                handleBatchOperation(mapping.getEntityType(), context, EntityBeforeSaveEvent::new, EntitySavedEvent::new);
            }
        }
        if (type == MappingEventTypes.update_before) {
            handleUpdateBefore(context);
        }

        if (type == MappingEventTypes.delete_before) {
            handleDeleteBefore(context);
        }
    }

    protected void handleQueryBefore(EntityColumnMapping mapping, EventContext context) {
        context.get(MappingContextKeys.reactiveResultHolder)
               .ifPresent(holder -> {
                   context.get(MappingContextKeys.queryOaram)
                          .ifPresent(queryParam -> {
                              EntityBeforeQueryEvent event = new EntityBeforeQueryEvent<>(queryParam, mapping.getEntityType());
                              eventPublisher.publishEvent(new GenericsPayloadApplicationEvent<>(this, event, mapping.getEntityType()));
                              holder
                                      .before(
                                              event.getAsync()
                                      );
                          });
               });
    }

    protected List<Object> createAfterData(List<Object> olds,
                                           EventContext context) {
        List<Object> newValues = new ArrayList<>(olds.size());
        EntityColumnMapping mapping = context
                .get(MappingContextKeys.columnMapping)
                .orElseThrow(UnsupportedOperationException::new);
        TableOrViewMetadata table = context.get(ContextKeys.table).orElseThrow(UnsupportedOperationException::new);
        RDBColumnMetadata idColumn = table
                .getColumns()
                .stream()
                .filter(RDBColumnMetadata::isPrimaryKey)
                .findFirst()
                .orElse(null);
        if (idColumn == null) {
            return Collections.emptyList();
        }
        for (Object old : olds) {
            Object newValue = context
                    .get(MappingContextKeys.instance)
                    .filter(Entity.class::isInstance)
                    .map(Entity.class::cast)
                    .orElseGet(() -> {
                        return context.get(MappingContextKeys.updateColumnInstance)
                                      .map(map -> {
                                          return FastBeanCopier.copy(map, FastBeanCopier.copy(old, mapping.getEntityType()));
                                      })
                                      .map(Entity.class::cast)
                                      .orElse(null);
                    });
            if (newValue != null) {
                FastBeanCopier.copy(old, newValue, FastBeanCopier.include(idColumn.getAlias()));
            }
            newValues.add(newValue);
        }
        return newValues;
    }

    protected Mono<Void> sendUpdateEvent(List<Object> before,
                                         List<Object> after,
                                         Class<Object> type,
                                         Function3<List<Object>, List<Object>, Class<Object>, AsyncEvent> mapper) {

        AsyncEvent event = mapper.apply(before, after, type);
        eventPublisher.publishEvent(new GenericsPayloadApplicationEvent<>(this, event, type));
        return event.getAsync();
    }

    protected Mono<Void> sendDeleteEvent(List<Object> olds,
                                         Class<Object> type,
                                         BiFunction<List<Object>, Class<Object>, AsyncEvent> eventBuilder) {

        AsyncEvent deletedEvent = eventBuilder.apply(olds, type);
        eventPublisher.publishEvent(new GenericsPayloadApplicationEvent<>(this, deletedEvent, type));
        return deletedEvent.getAsync();
    }

    protected void handleUpdateBefore(DSLUpdate<?, ?> update, EventContext context) {
        Object repo = context.get(MappingContextKeys.repository).orElse(null);
        EntityColumnMapping mapping = context
                .get(MappingContextKeys.columnMapping)
                .orElseThrow(UnsupportedOperationException::new);

        if (repo instanceof ReactiveRepository) {

            context.get(MappingContextKeys.reactiveResultHolder)
                   .ifPresent(holder -> {
                       AtomicReference<Tuple2<List<Object>, List<Object>>> updated = new AtomicReference<>();

                       holder.after(v -> {
                           return Mono.defer(() -> {
                               Tuple2<List<Object>, List<Object>> _tmp = updated.getAndSet(null);
                               if (_tmp != null) {
                                   return sendUpdateEvent(_tmp.getT1(), _tmp.getT2(), (Class) mapping.getEntityType(), EntityModifyEvent::new);
                               }
                               return Mono.empty();
                           });
                       });

                       holder.before(
                               ((ReactiveRepository<Object, ?>) repo)
                                       .createQuery()
                                       .setParam(update.toQueryParam())
                                       .fetch()
                                       .collectList()
                                       .flatMap((list) -> {
                                           List<Object> after = createAfterData(list, context);
                                           updated.set(Tuples.of(list, after));
                                           return sendUpdateEvent(list,
                                                                  after,
                                                                  (Class) mapping.getEntityType(),
                                                                  EntityBeforeModifyEvent::new);

                                       })
                                       .then()
                       );
                   });
        } else if (repo instanceof SyncRepository) {
            QueryParam param = update.toQueryParam();
            SyncRepository<Object, ?> syncRepository = ((SyncRepository<Object, ?>) repo);
            List<Object> list = syncRepository.createQuery()
                                              .setParam(param)
                                              .fetch();
            sendUpdateEvent(list,
                            createAfterData(list, context),
                            (Class<Object>) mapping.getEntityType(),
                            EntityBeforeModifyEvent::new).block();
        }
    }

    protected void handleUpdateBefore(EventContext context) {
        context.<DSLUpdate<?, ?>>get(ContextKeys.source())
               .ifPresent(dslUpdate -> {
                   handleUpdateBefore(dslUpdate, context);
               });

    }

    protected void handleDeleteBefore(EventContext context) {
        EntityColumnMapping mapping = context
                .get(MappingContextKeys.columnMapping)
                .orElseThrow(UnsupportedOperationException::new);
        context.<DSLDelete>get(ContextKeys.source())
               .ifPresent(dslUpdate -> {
                   Object repo = context.get(MappingContextKeys.repository).orElse(null);
                   if (repo instanceof ReactiveRepository) {
                       context.get(MappingContextKeys.reactiveResultHolder)
                              .ifPresent(holder -> {
                                  AtomicReference<List<Object>> deleted = new AtomicReference<>();
                                  holder.after(v -> {
                                      return Mono
                                              .defer(() -> {
                                                  List<Object> _tmp = deleted.getAndSet(null);
                                                  if (CollectionUtils.isNotEmpty(_tmp)) {
                                                      return sendDeleteEvent(_tmp, (Class) mapping.getEntityType(), EntityDeletedEvent::new);
                                                  }
                                                  return Mono.empty();
                                              });
                                  });
                                  holder.before(((ReactiveRepository<Object, ?>) repo)
                                                        .createQuery()
                                                        .setParam(dslUpdate.toQueryParam())
                                                        .fetch()
                                                        .collectList()
                                                        .filter(CollectionUtils::isNotEmpty)
                                                        .flatMap(list -> {
                                                            deleted.set(list);
                                                            return this
                                                                    .sendDeleteEvent(list, (Class) mapping.getEntityType(), EntityBeforeDeleteEvent::new);
                                                        })
                                  );
                              });
                   } else if (repo instanceof SyncRepository) {
                       QueryParam param = dslUpdate.toQueryParam();
                       SyncRepository<Object, ?> syncRepository = ((SyncRepository<Object, ?>) repo);
                       List<Object> list = syncRepository.createQuery()
                                                         .setParam(param)
                                                         .fetch();
                       this.sendDeleteEvent(list, (Class) mapping.getEntityType(), EntityBeforeDeleteEvent::new)
                           .block();
                   }
               });
    }

    protected void handleUpdateAfter(EventContext context) {

    }

    protected void handleBatchOperation(Class clazz, EventContext context,
                                        BiFunction<List<?>, Class, AsyncEvent> before,
                                        BiFunction<List<?>, Class, AsyncEvent> after) {

        context.get(MappingContextKeys.instance)
               .filter(List.class::isInstance)
               .map(List.class::cast)
               .ifPresent(lst -> {
                   AsyncEvent afterEvent = after.apply(lst, clazz);
                   AsyncEvent beforeEvent = before.apply(lst, clazz);
                   Object repo = context.get(MappingContextKeys.repository).orElse(null);
                   if (repo instanceof ReactiveRepository) {
                       Optional<ReactiveResultHolder> resultHolder = context.get(MappingContextKeys.reactiveResultHolder);
                       if (resultHolder.isPresent()) {
                           eventPublisher.publishEvent(new GenericsPayloadApplicationEvent<>(this, beforeEvent, clazz));
                           ReactiveResultHolder holder = resultHolder.get();
                           if (null != beforeEvent) {
                               holder.before(beforeEvent.publish(eventPublisher));
                           }
                           holder.after(v -> {
                               eventPublisher.publishEvent(new GenericsPayloadApplicationEvent<>(this, afterEvent, clazz));
                               return afterEvent.getAsync();
                           });
                           return;
                       }
                   }
                   eventPublisher.publishEvent(new GenericsPayloadApplicationEvent<>(this, afterEvent, clazz));
                   //block非响应式的支持
                   afterEvent.getAsync().block();
               });
    }

    protected void handleSingleOperation(Class clazz,
                                         EventContext context,
                                         BiFunction<List<?>, Class, AsyncEvent> before,
                                         BiFunction<List<?>, Class, AsyncEvent> after) {
        context.get(MappingContextKeys.instance)
               .filter(Entity.class::isInstance)
               .map(Entity.class::cast)
               .ifPresent(entity -> {
                   AsyncEvent afterEvent = after.apply(Collections.singletonList(entity), clazz);
                   AsyncEvent beforeEvent = before.apply(Collections.singletonList(entity), clazz);
                   Object repo = context.get(MappingContextKeys.repository).orElse(null);
                   if (repo instanceof ReactiveRepository) {
                       Optional<ReactiveResultHolder> resultHolder = context.get(MappingContextKeys.reactiveResultHolder);
                       if (resultHolder.isPresent()) {
                           eventPublisher.publishEvent(new GenericsPayloadApplicationEvent<>(this, beforeEvent, clazz));
                           ReactiveResultHolder holder = resultHolder.get();
                           if (null != beforeEvent) {
                               holder.before(beforeEvent.publish(eventPublisher));
                           }
                           holder.after(v -> {
                               eventPublisher.publishEvent(new GenericsPayloadApplicationEvent<>(this, afterEvent, clazz));
                               return afterEvent.getAsync();
                           });
                           return;
                       }
                   }
                   eventPublisher.publishEvent(new GenericsPayloadApplicationEvent<>(this, afterEvent, clazz));
                   //block非响应式的支持
                   afterEvent.getAsync().block();
               });
    }
}
