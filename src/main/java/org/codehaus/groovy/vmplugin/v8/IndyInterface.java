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

import groovy.lang.GroovySystem;
import groovy.lang.MetaClassRegistryChangeEvent;
import org.apache.groovy.util.SystemUtil;
import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.reflection.ClassInfo;
import org.codehaus.groovy.runtime.NullObject;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.SwitchPoint;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Bytecode level interface for bootstrap methods used by invokedynamic.
 * This class provides a logging ability by using the boolean system property
 * groovy.indy.logging. Other than that this class contains the
 * interfacing methods with bytecode for invokedynamic as well as some helper
 * methods and classes.
 */
public class IndyInterface {
    private static final long INDY_OPTIMIZE_THRESHOLD = SystemUtil.getLongSafe("groovy.indy.optimize.threshold", 10_000L);
    private static final long INDY_FALLBACK_THRESHOLD = SystemUtil.getLongSafe("groovy.indy.fallback.threshold", 10_000L);
    /**
     * Initial capacity for the call site registry used to track all active call sites
     * for cache invalidation when metaclass changes occur. The default of 1024 balances
     * memory usage against resize overhead. Tune via {@code groovy.indy.callsite.initial.capacity}
     * system property for larger applications.
     */
    private static final int INDY_CALLSITE_INITIAL_CAPACITY = SystemUtil.getIntegerSafe("groovy.indy.callsite.initial.capacity", 1024);

    /**
     * flags for method and property calls
     */
    public static final int
            SAFE_NAVIGATION = 1, THIS_CALL = 2,
            GROOVY_OBJECT = 4, IMPLICIT_THIS = 8,
            SPREAD_CALL = 16, UNCACHED_CALL = 32;
    private static final MethodHandleWrapper NULL_METHOD_HANDLE_WRAPPER = MethodHandleWrapper.getNullMethodHandleWrapper();

    /**
     * Enum for easy differentiation between call types
     */
    public enum CallType {
        /**
         * Method invocation type
         */
        METHOD("invoke"),
        /**
         * Constructor invocation type
         */
        INIT("init"),
        /**
         * Get property invocation type
         */
        GET("getProperty"),
        /**
         * Set property invocation type
         */
        SET("setProperty"),
        /**
         * Cast invocation type
         */
        CAST("cast");

        private static final Map<String, CallType> NAME_CALLTYPE_MAP =
                Stream.of(CallType.values()).collect(Collectors.toMap(CallType::getCallSiteName, Function.identity()));

        /**
         * The name of the call site type
         */
        private final String name;

        CallType(String callSiteName) {
            this.name = callSiteName;
        }

        /**
         * Returns the name of the call site type
         */
        public String getCallSiteName() {
            return name;
        }

        public static CallType fromCallSiteName(String callSiteName) {
            return NAME_CALLTYPE_MAP.get(callSiteName);
        }
    }

    /**
     * Logger
     */
    protected static final Logger LOG;
    /**
     * boolean to indicate if logging for indy is enabled
     */
    protected static final boolean LOG_ENABLED;

    static {
        boolean enableLogger = false;

        LOG = Logger.getLogger(IndyInterface.class.getName());

        try {
            if (Boolean.getBoolean("groovy.indy.logging")) {
                LOG.setLevel(Level.ALL);
                enableLogger = true;
            }
        } catch (SecurityException e) {
            // Allow security managers to prevent system property access
        }

        LOG_ENABLED = enableLogger;
    }

    /**
     * LOOKUP constant used for example in unreflect calls
     */
    public static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /**
     * handle for the fromCache method
     */
    private static final MethodHandle FROM_CACHE_METHOD;

    /**
     * handle for the selectMethod method
     */
    private static final MethodHandle SELECT_METHOD;

    static {

        try {
            MethodType mt = MethodType.methodType(Object.class, MutableCallSite.class, Class.class, String.class, int.class, Boolean.class, Boolean.class, Boolean.class, Object.class, Object[].class);
            FROM_CACHE_METHOD = LOOKUP.findStatic(IndyInterface.class, "fromCache", mt);
        } catch (Exception e) {
            throw new GroovyBugError(e);
        }

        try {
            MethodType mt = MethodType.methodType(Object.class, MutableCallSite.class, Class.class, String.class, int.class, Boolean.class, Boolean.class, Boolean.class, Object.class, Object[].class);
            SELECT_METHOD = LOOKUP.findStatic(IndyInterface.class, "selectMethod", mt);
        } catch (Exception e) {
            throw new GroovyBugError(e);
        }
    }

    protected static SwitchPoint switchPoint = new SwitchPoint();
    
    /**
     * Weak set of all CacheableCallSites. Used to invalidate caches when metaclass changes.
     * Uses WeakReferences so call sites can be garbage collected when no longer referenced.
     */
    private static final Set<WeakReference<CacheableCallSite>> ALL_CALL_SITES = ConcurrentHashMap.newKeySet(INDY_CALLSITE_INITIAL_CAPACITY);

    static {
        GroovySystem.getMetaClassRegistry().addMetaClassRegistryChangeEventListener(IndyInterface::invalidateSwitchPoints);
    }
    
    /**
     * Register a call site for cache invalidation when metaclass changes.
     */
    static void registerCallSite(CacheableCallSite callSite) {
        ALL_CALL_SITES.add(new WeakReference<>(callSite));
    }

    /**
     * Invalidate all call site caches. Called when no specific class context is available.
     */
    protected static void invalidateSwitchPoints() {
        invalidateSwitchPoints(null);
    }
    
    /**
     * Callback for constant metaclass update change.
     * Selectively invalidates call site caches based on receiver class for precise invalidation.
     * Only clears cache entries for the changed class (receiver-based clearing).
     */
    protected static void invalidateSwitchPoints(MetaClassRegistryChangeEvent event) {
        final Class<?> changedClass = event != null ? event.getClassToUpdate() : null;
        final String changedClassName = changedClass != null ? changedClass.getName() : null;
        
        if (LOG_ENABLED) {
            LOG.info("invalidating switch point and call site caches for: " + changedClassName);
        }

        synchronized (IndyInterface.class) {
            SwitchPoint old = switchPoint;
            switchPoint = new SwitchPoint();
            SwitchPoint.invalidateAll(new SwitchPoint[]{old});
        }
        
        // Reset all call site targets to default (forces cache lookup on next call)
        // but only clear cache entries for the changed class
        ALL_CALL_SITES.removeIf(ref -> {
            CacheableCallSite cs = ref.get();
            if (cs == null) {
                return true; // Remove garbage collected references
            }
            // Reset target to default (fromCache) so next call goes through cache lookup
            MethodHandle defaultTarget = cs.getDefaultTarget();
            if (defaultTarget != null && cs.getTarget() != defaultTarget) {
                cs.setTarget(defaultTarget);
            }
            // Only clear cache entries for the changed class (selective invalidation by RECEIVER class)
            // This preserves cached handles for unrelated classes
            if (changedClassName != null) {
                cs.clearCacheForClass(changedClassName);
            } else {
                // If no class specified, fall back to clearing everything
                cs.clearCache();
            }
            return false;
        });
    }

    /**
     * bootstrap method for method calls from Groovy compiled code with indy
     * enabled. This method gets a flags parameter which uses the following
     * encoding:<ul>
     * <li>{@value #SAFE_NAVIGATION} is the flag value for safe navigation see {@link #SAFE_NAVIGATION}</li>
     * <li>{@value #THIS_CALL} is the flag value for a call on this see {@link #THIS_CALL}</li>
     * </ul>
     *
     * @param caller   - the caller
     * @param callType - the type of the call
     * @param type     - the call site type
     * @param name     - the real method name
     * @param flags    - call flags
     * @return the produced CallSite
     * @since Groovy 2.1.0
     */
    public static CallSite bootstrap(Lookup caller, String callType, MethodType type, String name, int flags) {
        CallType ct = CallType.fromCallSiteName(callType);
        if (null == ct) throw new GroovyBugError("Unknown call type: " + callType);

        int callID = ct.ordinal();
        boolean safe = (flags & SAFE_NAVIGATION) != 0;
        boolean thisCall = (flags & THIS_CALL) != 0;
        boolean spreadCall = (flags & SPREAD_CALL) != 0;

        return realBootstrap(caller, name, callID, type, safe, thisCall, spreadCall);
    }

    /**
     * backing bootstrap method with all parameters
     */
    private static CallSite realBootstrap(Lookup caller, String name, int callID, MethodType type, boolean safe, boolean thisCall, boolean spreadCall) {
        // since indy does not give us the runtime types
        // we produce first a dummy call site, which then changes the target to one when INDY_OPTIMIZE_THRESHOLD is reached,
        // that does the method selection including the direct call to the
        // real method.
        CacheableCallSite mc = new CacheableCallSite(type, caller);
        final Class<?> sender = caller.lookupClass();
        MethodHandle mh = makeAdapter(mc, sender, name, callID, type, safe, thisCall, spreadCall);
        mc.setTarget(mh);
        mc.setDefaultTarget(mh);
        mc.setFallbackTarget(makeFallBack(mc, sender, name, callID, type, safe, thisCall, spreadCall));
        
        // Register for cache invalidation on metaclass changes
        registerCallSite(mc);

        return mc;
    }

    /**
     * Makes a fallback method for an invalidated method selection
     */
    protected static MethodHandle makeFallBack(MutableCallSite mc, Class<?> sender, String name, int callID, MethodType type, boolean safeNavigation, boolean thisCall, boolean spreadCall) {
        return make(mc, sender, name, callID, type, safeNavigation, thisCall, spreadCall, SELECT_METHOD);
    }

    /**
     * Makes an adapter method for method selection, i.e. get the cached methodhandle(fast path) or fallback
     */
    private static MethodHandle makeAdapter(MutableCallSite mc, Class<?> sender, String name, int callID, MethodType type, boolean safeNavigation, boolean thisCall, boolean spreadCall) {
        return make(mc, sender, name, callID, type, safeNavigation, thisCall, spreadCall, FROM_CACHE_METHOD);
    }

    private static MethodHandle make(MutableCallSite mc, Class<?> sender, String name, int callID, MethodType type, boolean safeNavigation, boolean thisCall, boolean spreadCall, MethodHandle originalMH) {
        MethodHandle mh = MethodHandles.insertArguments(originalMH, 0, mc, sender, name, callID, safeNavigation, thisCall, spreadCall, /*dummy receiver:*/ 1);
        return mh.asCollector(Object[].class, type.parameterCount()).asType(type);
    }

    private static class FallbackSupplier {
        private final CacheableCallSite callSite;
        private final Class<?> sender;
        private final String methodName;
        private final int callID;
        private final Boolean safeNavigation;
        private final Boolean thisCall;
        private final Boolean spreadCall;
        private final Object dummyReceiver;
        private final Object[] arguments;
        private MethodHandleWrapper result;

        FallbackSupplier(CacheableCallSite callSite, Class<?> sender, String methodName, int callID, Boolean safeNavigation, Boolean thisCall, Boolean spreadCall, Object dummyReceiver, Object[] arguments) {
            this.callSite = callSite;
            this.sender = sender;
            this.methodName = methodName;
            this.callID = callID;
            this.safeNavigation = safeNavigation;
            this.thisCall = thisCall;
            this.spreadCall = spreadCall;
            this.dummyReceiver = dummyReceiver;
            this.arguments = arguments;
        }

        MethodHandleWrapper get() {
            if (null == result) {
                result = fallback(callSite, sender, methodName, callID, safeNavigation, thisCall, spreadCall, dummyReceiver, arguments);
            }

            return result;
        }
    }

    /**
     * Get the cached methodhandle. if the related methodhandle is not found in the inline cache, cache and return it.
     */
    public static Object fromCache(MutableCallSite callSite, Class<?> sender, String methodName, int callID, Boolean safeNavigation, Boolean thisCall, Boolean spreadCall, Object dummyReceiver, Object[] arguments) throws Throwable {
        // Cast is safe because bootstrap always creates CacheableCallSite
        CacheableCallSite ccs = (CacheableCallSite) callSite;
        FallbackSupplier fallbackSupplier = new FallbackSupplier(ccs, sender, methodName, callID, safeNavigation, thisCall, spreadCall, dummyReceiver, arguments);

        MethodHandleWrapper mhw =
                bypassCache(spreadCall, arguments)
                    ? NULL_METHOD_HANDLE_WRAPPER
                    : doWithCallSite(
                            ccs, arguments, callID,
                            (cs, cacheKey) ->
                                    cs.getAndPut(
                                            cacheKey,
                                            c -> {
                                                MethodHandleWrapper fbMhw = fallbackSupplier.get();
                                                return fbMhw.isCanSetTarget() ? fbMhw : NULL_METHOD_HANDLE_WRAPPER;
                                            }
                                    )
                    );

        if (NULL_METHOD_HANDLE_WRAPPER == mhw) {
            mhw = fallbackSupplier.get();
        }

        if (mhw.isCanSetTarget() && (ccs.getTarget() != mhw.getTargetMethodHandle()) && (mhw.getLatestHitCount() > INDY_OPTIMIZE_THRESHOLD)) {
            ccs.setTarget(mhw.getTargetMethodHandle());
            if (LOG_ENABLED) LOG.info("call site target set, preparing outside invocation");

            mhw.resetLatestHitCount();
        }

        return mhw.getCachedMethodHandle().invokeExact(arguments);
    }

    private static boolean bypassCache(Boolean spreadCall, Object[] arguments) {
        if (spreadCall) return true;
        final Object receiver = arguments[0];
        return null != receiver && ClassInfo.getClassInfo(receiver.getClass()).hasPerInstanceMetaClasses();
    }

    /**
     * Core method for indy method selection using runtime types.
     */
    public static Object selectMethod(MutableCallSite callSite, Class<?> sender, String methodName, int callID, Boolean safeNavigation, Boolean thisCall, Boolean spreadCall, Object dummyReceiver, Object[] arguments) throws Throwable {
        // Cast is safe because bootstrap always creates CacheableCallSite
        CacheableCallSite ccs = (CacheableCallSite) callSite;
        final MethodHandleWrapper mhw = fallback(ccs, sender, methodName, callID, safeNavigation, thisCall, spreadCall, dummyReceiver, arguments);

        final MethodHandle defaultTarget = ccs.getDefaultTarget();
        final long fallbackCount = ccs.incrementFallbackCount();
        if ((fallbackCount > INDY_FALLBACK_THRESHOLD) && (ccs.getTarget() != defaultTarget)) {
            ccs.setTarget(defaultTarget);
            if (LOG_ENABLED) LOG.info("call site target reset to default, preparing outside invocation");

            ccs.resetFallbackCount();
        }

        if (defaultTarget == ccs.getTarget()) {
            // correct the stale methodhandle in the inline cache of callsite
            // it is important but impacts the performance somehow when cache misses frequently
            doWithCallSite(ccs, arguments, callID, (cs, cacheKey) -> cs.put(cacheKey, mhw));
        }

        return mhw.getCachedMethodHandle().invokeExact(arguments);
    }

    private static MethodHandleWrapper fallback(CacheableCallSite callSite, Class<?> sender, String methodName, int callID, Boolean safeNavigation, Boolean thisCall, Boolean spreadCall, Object dummyReceiver, Object[] arguments) {
        Selector selector = Selector.getSelector(callSite, sender, methodName, callID, safeNavigation, thisCall, spreadCall, arguments);
        selector.setCallSiteTarget();

        return new MethodHandleWrapper(
                selector.handle.asSpreader(Object[].class, arguments.length).asType(MethodType.methodType(Object.class, Object[].class)),
                selector.handle,
                selector.cache
        );
    }

    /**
     * Helper method to execute a function with a call site and compute the appropriate cache key.
     * 
     * <p>When the receiver is a Class object (which happens for constructor calls, static method calls,
     * and static property access), we use that Class's name as the cache key. This ensures that when
     * a class's metaclass changes, the cache entries for that class are properly invalidated.</p>
     * 
     * <p>For example, both {@code new Foo()} and {@code Foo.staticMethod()} have a Class object as
     * receiver, and both should have cache key "Foo" so that changing Foo's metaclass invalidates
     * both cached method handles.</p>
     * 
     * @param callSite the call site
     * @param arguments the call arguments (receiver is at index 0)
     * @param callID the call type ordinal (unused but kept for API consistency)
     * @param f the function to execute, receiving (callSite, cacheKey)
     * @return the result of the function
     */
    private static <T> T doWithCallSite(CacheableCallSite callSite, Object[] arguments, int callID, BiFunction<? super CacheableCallSite, ? super String, ? extends T> f) {
        Object receiver = arguments[0];

        if (null == receiver) receiver = NullObject.getNullObject();

        // Compute cache key:
        // - For Class receivers (static calls, constructors), use the Class's name
        // - For instance receivers, use the instance's class name
        // This ensures metaclass changes invalidate the right cache entries
        final String cacheKey;
        if (receiver instanceof Class) {
            cacheKey = ((Class<?>) receiver).getName();
        } else {
            cacheKey = receiver.getClass().getName();
        }

        return f.apply(callSite, cacheKey);
    }

    /**
     * @since 2.5.0
     */
    public static CallSite staticArrayAccess(MethodHandles.Lookup lookup, String name, MethodType type) {
        if (type.parameterCount() == 2) {
            return new ConstantCallSite(IndyArrayAccess.arrayGet(type));
        } else {
            return new ConstantCallSite(IndyArrayAccess.arraySet(type));
        }
    }
}
