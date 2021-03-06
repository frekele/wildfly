/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.wildfly.clustering.web.infinispan.session.fine;

import java.util.AbstractMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.infinispan.Cache;
import org.infinispan.context.Flag;
import org.wildfly.clustering.ee.infinispan.CacheEntryMutator;
import org.wildfly.clustering.ee.infinispan.CacheProperties;
import org.wildfly.clustering.ee.infinispan.Mutator;
import org.wildfly.clustering.marshalling.jboss.InvalidSerializedFormException;
import org.wildfly.clustering.marshalling.jboss.MarshalledValue;
import org.wildfly.clustering.marshalling.jboss.Marshaller;
import org.wildfly.clustering.marshalling.jboss.MarshallingContext;
import org.wildfly.clustering.web.infinispan.logging.InfinispanWebLogger;
import org.wildfly.clustering.web.infinispan.session.SessionAttributes;
import org.wildfly.clustering.web.infinispan.session.SessionAttributesFactory;
import org.wildfly.clustering.web.session.ImmutableSessionAttributes;

/**
 * {@link SessionAttributesFactory} for fine granularity sessions.
 * A given session's attributes are mapped to N+1 co-located cache entries, where N is the number of session attributes.
 * A separate cache entry stores the activate attribute names for the session.
 * @author Paul Ferraro
 */
public class FineSessionAttributesFactory implements SessionAttributesFactory<Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>>> {

    private final Cache<SessionAttributeNamesKey, Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>>> namesCache;
    private final Cache<SessionAttributeKey, MarshalledValue<Object, MarshallingContext>> attributeCache;
    private final Marshaller<Object, MarshalledValue<Object, MarshallingContext>, MarshallingContext> marshaller;
    private final CacheProperties properties;

    public FineSessionAttributesFactory(Cache<SessionAttributeNamesKey, Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>>> namesCache, Cache<SessionAttributeKey, MarshalledValue<Object, MarshallingContext>> attributeCache, Marshaller<Object, MarshalledValue<Object, MarshallingContext>, MarshallingContext> marshaller, CacheProperties properties) {
        this.namesCache = namesCache;
        this.attributeCache = attributeCache;
        this.marshaller = marshaller;
        this.properties = properties;
    }

    @Override
    public Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>> createValue(String id, Void context) {
        Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>> entry = this.getAttributeNames(id, key -> this.namesCache.getAdvancedCache().withFlags(Flag.FORCE_SYNCHRONOUS).computeIfAbsent(key, k -> new AbstractMap.SimpleImmutableEntry<>(new AtomicInteger(), new ConcurrentHashMap<>())));
        return (entry != null) ? entry : this.createValue(id, context);
    }

    @Override
    public Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>> findValue(String id) {
        return this.getAttributeNames(id, key -> this.namesCache.get(key));
    }

    private Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>> getAttributeNames(String id, Function<SessionAttributeNamesKey, Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>>> provider) {
        SessionAttributeNamesKey key = new SessionAttributeNamesKey(id);
        Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>> entry = provider.apply(key);
        if (entry != null) {
            ConcurrentMap<String, Integer> names = entry.getValue();
            Map<SessionAttributeKey, MarshalledValue<Object, MarshallingContext>> attributes = this.attributeCache.getAdvancedCache().getAll(names.values().stream().map(attributeId -> new SessionAttributeKey(id, attributeId)).collect(Collectors.toSet()));
            Predicate<Map.Entry<String, MarshalledValue<Object, MarshallingContext>>> invalidAttribute = attribute -> {
                try {
                    this.marshaller.read(attribute.getValue());
                    return false;
                } catch (InvalidSerializedFormException e) {
                    InfinispanWebLogger.ROOT_LOGGER.failedToActivateSessionAttribute(e, id, attribute.getKey());
                    return true;
                }
            };
            if (names.entrySet().stream().map(name -> new AbstractMap.SimpleImmutableEntry<>(name.getKey(), attributes.get(new SessionAttributeKey(id, name.getValue())))).anyMatch(invalidAttribute)) {
                // If any attributes are invalid - remove them all
                this.remove(id);
                return null;
            }
        }
        return entry;
    }

    @Override
    public boolean remove(String id) {
        Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>> entry = this.namesCache.getAdvancedCache().withFlags(Flag.FORCE_SYNCHRONOUS).remove(new SessionAttributeNamesKey(id));
        if (entry == null) return false;
        entry.getValue().values().forEach(attributeId -> this.attributeCache.getAdvancedCache().withFlags(Flag.IGNORE_RETURN_VALUES).remove(new SessionAttributeKey(id, attributeId)));
        return true;
    }

    @Override
    public void evict(String id) {
        SessionAttributeNamesKey key = new SessionAttributeNamesKey(id);
        Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>> entry = this.namesCache.getAdvancedCache().withFlags(Flag.SKIP_CACHE_LOAD).get(key);
        if (entry != null) {
            entry.getValue().entrySet().stream().forEach(attribute -> {
                try {
                    this.attributeCache.evict(new SessionAttributeKey(id, attribute.getValue()));
                } catch (Throwable e) {
                    InfinispanWebLogger.ROOT_LOGGER.failedToPassivateSessionAttribute(e, id, attribute.getKey());
                }
            });
            this.namesCache.getAdvancedCache().withFlags(Flag.FAIL_SILENTLY).evict(key);
        }
    }

    @Override
    public SessionAttributes createSessionAttributes(String id, Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>> entry) {
        SessionAttributeNamesKey key = new SessionAttributeNamesKey(id);
        Mutator mutator = this.properties.isTransactional() && this.namesCache.getAdvancedCache().getCacheEntry(key).isCreated() ? Mutator.PASSIVE : new CacheEntryMutator<>(this.namesCache, key, entry);
        return new FineSessionAttributes<>(id, entry.getKey(), entry.getValue(), mutator, this.attributeCache, this.marshaller, this.properties);
    }

    @Override
    public ImmutableSessionAttributes createImmutableSessionAttributes(String id, Map.Entry<AtomicInteger, ConcurrentMap<String, Integer>> entry) {
        return new FineImmutableSessionAttributes<>(id, entry.getValue(), this.attributeCache, this.marshaller);
    }
}
