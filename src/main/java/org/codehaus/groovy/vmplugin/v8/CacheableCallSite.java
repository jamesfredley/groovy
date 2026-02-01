/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.codehaus.groovy.vmplugin.v8;

import org.apache.groovy.util.SystemUtil;
import org.codehaus.groovy.runtime.memoize.MemoizeCache;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.ref.SoftReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a cacheable call site, which can reduce the cost of resolving methods
 *
 * @since 3.0.0
 */
public class CacheableCallSite extends MutableCallSite {
    private static final int CACHE_SIZE = SystemUtil.getIntegerSafe("groovy.indy.callsite.cache.size", 4);
    private static final float LOAD_FACTOR = 0.75f;
    private static final int INITIAL_CAPACITY = (int) Math.ceil(CACHE_SIZE / LOAD_FACTOR) + 1;
    private volatile SoftReference<MethodHandleWrapper> latestHitMethodHandleWrapperSoftReference = null;
    private final AtomicLong fallbackCount = new AtomicLong();
    private MethodHandle defaultTarget;
    private MethodHandle fallbackTarget;
    private final MethodHandles.Lookup lookup;
    private final Class<?> ownerClass;
    private final Map<String, SoftReference<MethodHandleWrapper>> lruCache =
            new LinkedHashMap<String, SoftReference<MethodHandleWrapper>>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
                private static final long serialVersionUID = 7785958879964294463L;

                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    public CacheableCallSite(MethodType type) {
        this(type, null);
    }

    public CacheableCallSite(MethodType type, MethodHandles.Lookup lookup) {
        super(type);
        this.lookup = lookup;
        this.ownerClass = (lookup != null) ? lookup.lookupClass() : null;
    }

    /**
     * Get the owner class of this call site (the class whose bytecode contains the invokedynamic instruction).
     * Returns null if lookup was not provided (old constructor used).
     */
    public Class<?> getOwnerClass() {
        return ownerClass;
    }
    
    /**
     * Get the lookup context for this call site. This lookup should be used for unreflect
     * operations to ensure proper access control - the unreflected method handle will respect
     * the caller's access permissions and Java module boundaries.
     * 
     * @return the lookup context, or null if not provided
     * @see java.lang.invoke.MethodHandles.Lookup#unreflect(java.lang.reflect.Method)
     */
    public MethodHandles.Lookup getLookup() {
        return lookup;
    }

    public MethodHandleWrapper getAndPut(String className, MemoizeCache.ValueProvider<? super String, ? extends MethodHandleWrapper> valueProvider) {
        MethodHandleWrapper result = null;
        SoftReference<MethodHandleWrapper> resultSoftReference;
        synchronized (lruCache) {
            resultSoftReference = lruCache.get(className);
            if (null != resultSoftReference) {
                result = resultSoftReference.get();
                if (null == result) removeAllStaleEntriesOfLruCache();
            }

            if (null == result) {
                result = valueProvider.provide(className);
                resultSoftReference = new SoftReference<>(result);
                lruCache.put(className, resultSoftReference);
            }
        }
        final SoftReference<MethodHandleWrapper> mhwsr = latestHitMethodHandleWrapperSoftReference;
        final MethodHandleWrapper methodHandleWrapper = null == mhwsr ? null : mhwsr.get();

        if (methodHandleWrapper == result) {
            result.incrementLatestHitCount();
        } else {
            result.resetLatestHitCount();
            if (null != methodHandleWrapper) methodHandleWrapper.resetLatestHitCount();
            latestHitMethodHandleWrapperSoftReference = resultSoftReference;
        }

        return result;
    }

    public MethodHandleWrapper put(String name, MethodHandleWrapper mhw) {
        synchronized (lruCache) {
            final SoftReference<MethodHandleWrapper> methodHandleWrapperSoftReference;
            methodHandleWrapperSoftReference = lruCache.put(name, new SoftReference<>(mhw));
            if (null == methodHandleWrapperSoftReference) return null;
            final MethodHandleWrapper methodHandleWrapper = methodHandleWrapperSoftReference.get();
            if (null == methodHandleWrapper) removeAllStaleEntriesOfLruCache();
            return methodHandleWrapper;
        }
    }

    private void removeAllStaleEntriesOfLruCache() {
        lruCache.values().removeIf(v -> null == v.get());
    }

    public long incrementFallbackCount() {
        return fallbackCount.incrementAndGet();
    }

    public void resetFallbackCount() {
        fallbackCount.set(0);
    }

    public MethodHandle getDefaultTarget() {
        return defaultTarget;
    }

    public void setDefaultTarget(MethodHandle defaultTarget) {
        this.defaultTarget = defaultTarget;
    }

    public MethodHandle getFallbackTarget() {
        return fallbackTarget;
    }

    public void setFallbackTarget(MethodHandle fallbackTarget) {
        this.fallbackTarget = fallbackTarget;
    }
    
    /**
     * Clear the cache entirely. Called when metaclass changes to ensure
     * stale method handles are discarded.
     */
    public void clearCache() {
        // Clear the latest hit reference
        latestHitMethodHandleWrapperSoftReference = null;
        
        // Clear the LRU cache
        synchronized (lruCache) {
            lruCache.clear();
        }
        
        // Reset fallback count
        fallbackCount.set(0);
    }
    
    /**
     * Clear cache entries only for the specified class. Called when that
     * class's metaclass changes, allowing cached handles for other classes
     * to remain valid.
     * 
     * @param className the name of the class whose cache entries should be cleared
     * @return true if an entry was removed, false otherwise
     */
    public boolean clearCacheForClass(String className) {
        // Clear latest hit if it might be for this class
        // (we clear it to be safe since we can't easily check the class)
        latestHitMethodHandleWrapperSoftReference = null;
        
        // Remove only this class from cache
        synchronized (lruCache) {
            return lruCache.remove(className) != null;
        }
    }
}
