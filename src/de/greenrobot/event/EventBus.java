/*
 * Copyright (C) 2012 Markus Junginger, greenrobot (http://greenrobot.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.greenrobot.event;

import android.os.Looper;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

/**
 * EventBus is a central publish/subscribe event system for Android. Events are posted ({@link #post(Object)}) to the
 * bus, which delivers it to subscribers that have a matching handler method for the event type. To receive events,
 * subscribers must register themselves to the bus using {@link #register(Object)}. Once registered,
 * subscribers receive events until {@link #unregister(Object)} is called. By convention, event handling methods must
 * be named "onEvent", be public, return nothing (void), and have exactly one parameter (the event).
 *
 * @author Markus Junginger, greenrobot
 */
public class EventBus {

    /** Log tag, apps may override it. */
    public static String TAG = "Event";

    // 通过getDefault()方法可以获得的默认实例
    static volatile EventBus defaultInstance;

    private static final EventBusBuilder DEFAULT_BUILDER = new EventBusBuilder();
    private static final Map<Class<?>, List<Class<?>>> eventTypesCache = new HashMap<Class<?>, List<Class<?>>>();

    private final Map<Class<?>, CopyOnWriteArrayList<Subscription>> subscriptionsByEventType;
    private final Map<Object, List<Class<?>>> typesBySubscriber;
    private final Map<Class<?>, Object> stickyEvents;

    // ThreadLocal保存的PostingThreadState，每一个线程中都有其一份实例
    private final ThreadLocal<PostingThreadState> currentPostingThreadState = new ThreadLocal<PostingThreadState>() {
        @Override
        protected PostingThreadState initialValue() {
            return new PostingThreadState();
        }
    };


    private final HandlerPoster mainThreadPoster;
    private final BackgroundPoster backgroundPoster;
    private final AsyncPoster asyncPoster;
    private final SubscriberMethodFinder subscriberMethodFinder;
    private final ExecutorService executorService;

    private final boolean throwSubscriberException;
    private final boolean logSubscriberExceptions;
    private final boolean logNoSubscriberMessages;
    private final boolean sendSubscriberExceptionEvent;
    private final boolean sendNoSubscriberEvent;
    private final boolean eventInheritance;

    /** Convenience singleton for apps using a process-wide EventBus instance. */
    // 单例模式，尝试获取EventBus的实例，如果没有，则初始化一个，采用先检查，后同步，再检查的办法进行单例的初始化。
    public static EventBus getDefault() {
        if (defaultInstance == null) {
            synchronized (EventBus.class) {
                if (defaultInstance == null) {
                    defaultInstance = new EventBus();
                }
            }
        }
        return defaultInstance;
    }

    // EventBus构造者
    public static EventBusBuilder builder() {
        return new EventBusBuilder();
    }

    /** For unit test primarily. */
    public static void clearCaches() {
        SubscriberMethodFinder.clearCaches();
        eventTypesCache.clear();
    }

    /**
     * Creates a new EventBus instance; each instance is a separate scope in which events are delivered. To use a
     * central bus, consider {@link #getDefault()}.
     * 穿件默认的EventBus实例；每一个实例的events并不共享，events之后在其对应的EventBus中分发。为了使用统一的bus，应该考虑使用{@link #getDefault()}
     */
    public EventBus() {
        this(DEFAULT_BUILDER);
    }

    EventBus(EventBusBuilder builder) {
        // 订阅者们，根据EventType分类：Key是订阅者的类，Value为对应的订阅者
        subscriptionsByEventType = new HashMap<Class<?>, CopyOnWriteArrayList<Subscription>>();
        // 不知道干嘛用的。
        typesBySubscriber = new HashMap<Object, List<Class<?>>>();
        // 保存Sticky Events，注意，这个地方用的是一个线程安全的HashMap
        stickyEvents = new ConcurrentHashMap<Class<?>, Object>();
        // 主线程的Poster
        mainThreadPoster = new HandlerPoster(this, Looper.getMainLooper(), 10);
        // 后台线程的Poster
        backgroundPoster = new BackgroundPoster(this);
        // 异步的Poster
        asyncPoster = new AsyncPoster(this);
        // 订阅者方法寻找器
        subscriberMethodFinder = new SubscriberMethodFinder(builder.skipMethodVerificationForClasses);
        logSubscriberExceptions = builder.logSubscriberExceptions;
        logNoSubscriberMessages = builder.logNoSubscriberMessages;
        sendSubscriberExceptionEvent = builder.sendSubscriberExceptionEvent;
        sendNoSubscriberEvent = builder.sendNoSubscriberEvent;
        throwSubscriberException = builder.throwSubscriberException;
        eventInheritance = builder.eventInheritance;
        executorService = builder.executorService;
    }


    /**
     * Registers the given subscriber to receive events. Subscribers must call {@link #unregister(Object)} once they
     * are no longer interested in receiving events.
     * 注册指定的订阅者以便接收消息。订阅者必须要在不需要接收消息的时候调用{@link #unregister(Object)}方法。
     * <p/>
     * Subscribers have event handling methods that are identified by their name, typically called "onEvent". Event
     * handling methods must have exactly one parameter, the event. If the event handling method is to be called in a
     * specific thread, a modifier is appended to the method name. Valid modifiers match one of the {@link ThreadMode}
     * enums. For example, if a method is to be called in the UI/main thread by EventBus, it would be called
     * "onEventMainThread".
     * 订阅者包含的处理方法由它们的名字区分，典型的是"onEvent"。Event处理方法必须要有个明确的参数，event。如果一个event处理方法在指定的
     * 线程中被调用，需要在方法的名称之后添加一个修饰符。允许的修饰符由{@link ThreadMode}指定，比如，如果一个方法需要被EventBus在
     * main/UI线程中调用，那么方法名应该是"onEventMainThread"
     */
    public void register(Object subscriber) {
        register(subscriber, false, 0);
    }

    /**
     * Like {@link #register(Object)} with an additional subscriber priority to influence the order of event delivery.
     * Within the same delivery thread ({@link ThreadMode}), higher priority subscribers will receive events before
     * others with a lower priority. The default priority is 0. Note: the priority does *NOT* affect the order of
     * delivery among subscribers with different {@link ThreadMode}s!
     * 与{@link #register(Object)}类似，但额外指定了Event分发的优先级。在同样的一个线程之内 ({@link ThreadMode})，更高的优先级的
     * 调阅这会比其他的低优先级的订阅者更早的接收到消息。默认的优先级是0.注意，优先级不会影响不同线程之内的消息分发。
     */
    public void register(Object subscriber, int priority) {
        register(subscriber, false, priority);
    }

    /**
     * Like {@link #register(Object)}, but also triggers delivery of the most recent sticky event (posted with
     * {@link #postSticky(Object)}) to the given subscriber.
     * 与{@link #register(Object)}类似，但同时打开功能：向指定的订阅者分发最近stickyevent(通过{@link #postSticky(Object)})分发的event)。
     */
    public void registerSticky(Object subscriber) {
        register(subscriber, true, 0);
    }

    /**
     * Like {@link #register(Object, int)}, but also triggers delivery of the most recent sticky event (posted with
     * {@link #postSticky(Object)}) to the given subscriber.
     * 与{@link #register(Object, int)}类似，但同时打开功能：向指定的订阅者分发最近stickyevent(通过{@link #postSticky(Object)})分发的event)。
     */
    public void registerSticky(Object subscriber, int priority) {
        register(subscriber, true, priority);
    }

    private synchronized void register(Object subscriber, boolean sticky, int priority) {
        // 查找订阅类当中的处理方法（包括其父类的方法）
        List<SubscriberMethod> subscriberMethods = subscriberMethodFinder.findSubscriberMethods(subscriber.getClass());
        // 根据获取的订阅者方法，将其依次订阅
        for (SubscriberMethod subscriberMethod : subscriberMethods) {
            subscribe(subscriber, subscriberMethod, sticky, priority);
        }
    }

    // Must be called in synchronized block
    // 必须要在同步快中执行
    private void subscribe(Object subscriber, SubscriberMethod subscriberMethod, boolean sticky, int priority) {
        
        // 获取订阅者处理方法的参数类型，即Event事件的类型
        Class<?> eventType = subscriberMethod.eventType;
        // 根据类型尝试从subscriptionsByEventType中获取参数类型，即Event事件类型所对应的订阅者列表。
        CopyOnWriteArrayList<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        // 生成新的订阅者对象
        Subscription newSubscription = new Subscription(subscriber, subscriberMethod, priority);
        // 如果订阅者列表为null，则说明是头一次添加该Event类型。
        if (subscriptions == null) {
            subscriptions = new CopyOnWriteArrayList<Subscription>();
            subscriptionsByEventType.put(eventType, subscriptions);
        } else {
            if (subscriptions.contains(newSubscription)) {
                throw new EventBusException("Subscriber " + subscriber.getClass() + " already registered to event "
                        + eventType);
            }
        }

        // Starting with EventBus 2.2 we enforced methods to be public (might change with annotations again)
        // subscriberMethod.method.setAccessible(true);
        // 从EventBus2.2之后，设置方法必须为public
        // subscriberMethod.method.setAccessible(true);        
        int size = subscriptions.size();
        // 根据优先级，将包含订阅者处理方法的订阅者对象添加到队列的合适位置上
        for (int i = 0; i <= size; i++) {
            if (i == size || newSubscription.priority > subscriptions.get(i).priority) {
                subscriptions.add(i, newSubscription);
                break;
            }
        }

        // 保存订阅者类所能够处理的EventType
        List<Class<?>> subscribedEvents = typesBySubscriber.get(subscriber);
        if (subscribedEvents == null) {
            subscribedEvents = new ArrayList<Class<?>>();
            typesBySubscriber.put(subscriber, subscribedEvents);
        }
        subscribedEvents.add(eventType);

        // 如果Event设置为sticky
        if (sticky) {
            Object stickyEvent;
            synchronized (stickyEvents) {
                stickyEvent = stickyEvents.get(eventType);
            }
            if (stickyEvent != null) {
                // If the subscriber is trying to abort the event, it will fail (event is not tracked in posting state)
                // --> Strange corner case, which we don't take care of here.
                postToSubscription(newSubscription, stickyEvent, Looper.getMainLooper() == Looper.myLooper());
            }
        }
    }

    public synchronized boolean isRegistered(Object subscriber) {
        return typesBySubscriber.containsKey(subscriber);
    }

    /** Only updates subscriptionsByEventType, not typesBySubscriber! Caller must update typesBySubscriber. */
    /** 下面代码仅仅更新subscriptionsByEventType，不是typesBySubscriber！调用者必须更新typesBySubscriber */
    private void unubscribeByEventType(Object subscriber, Class<?> eventType) {
        // 获取Event类型所对应的所有的订阅者
        List<Subscription> subscriptions = subscriptionsByEventType.get(eventType);
        if (subscriptions != null) {
            int size = subscriptions.size();
            // 依次遍历Event类型所对应的所有的订阅者，如果对应的subscriber就是窑门要删除的subscriber，那么则将其删除。
            for (int i = 0; i < size; i++) {
                Subscription subscription = subscriptions.get(i);
                if (subscription.subscriber == subscriber) {
                    subscription.active = false;
                    subscriptions.remove(i);
                    i--;
                    size--;
                }
            }
        }
    }

    /** Unregisters the given subscriber from all event classes. */
    public synchronized void unregister(Object subscriber) {
        List<Class<?>> subscribedTypes = typesBySubscriber.get(subscriber);
        // 根据类型删除
        if (subscribedTypes != null) {
            for (Class<?> eventType : subscribedTypes) {
                unubscribeByEventType(subscriber, eventType);
            }
            // 根据subscriber删除其所对应的所有的types
            typesBySubscriber.remove(subscriber);
        } else {
            Log.w(TAG, "Subscriber to unregister was not registered before: " + subscriber.getClass());
        }
    }

    /** Posts the given event to the event bus. */
    /** 将指定的event发送给event bus */
    public void post(Object event) {
      
        // 获取当前线程(调用post方法的线程)中的一个PostingThreadState实例
        PostingThreadState postingState = currentPostingThreadState.get();
        // 获取当前线程(调用post方法的线程)中的EventQueue
        List<Object> eventQueue = postingState.eventQueue;
        // 将Event添加到队列当中
        eventQueue.add(event);

        // 如果当前线程(调用post方法的线程)没有在发布Event
        if (!postingState.isPosting) {
            // 判断调用者是否工作在主线程上
            postingState.isMainThread = Looper.getMainLooper() == Looper.myLooper();
            // 设置标志，正在发布Event
            postingState.isPosting = true;
            if (postingState.canceled) {
                throw new EventBusException("Internal error. Abort state was not reset");
            }
            try {
                // 依次发送，直至eventQueue为空为止
                while (!eventQueue.isEmpty()) {
                    postSingleEvent(eventQueue.remove(0), postingState);
                }
            } finally {
                postingState.isPosting = false;
                postingState.isMainThread = false;
            }
        }
    }

    /**
     * Called from a subscriber's event handling method, further event delivery will be canceled. Subsequent
     * subscribers
     * won't receive the event. Events are usually canceled by higher priority subscribers (see
     * {@link #register(Object, int)}). Canceling is restricted to event handling methods running in posting thread
     * {@link ThreadMode#PostThread}.
     */
    public void cancelEventDelivery(Object event) {
        PostingThreadState postingState = currentPostingThreadState.get();
        if (!postingState.isPosting) {
            throw new EventBusException(
                    "This method may only be called from inside event handling methods on the posting thread");
        } else if (event == null) {
            throw new EventBusException("Event may not be null");
        } else if (postingState.event != event) {
            throw new EventBusException("Only the currently handled event may be aborted");
        } else if (postingState.subscription.subscriberMethod.threadMode != ThreadMode.PostThread) {
            throw new EventBusException(" event handlers may only abort the incoming event");
        }

        postingState.canceled = true;
    }

    /**
     * Posts the given event to the event bus and holds on to the event (because it is sticky). The most recent sticky
     * event of an event's type is kept in memory for future access. This can be {@link #registerSticky(Object)} or
     * {@link #getStickyEvent(Class)}.
     */
    public void postSticky(Object event) {
        synchronized (stickyEvents) {
            stickyEvents.put(event.getClass(), event);
        }
        // Should be posted after it is putted, in case the subscriber wants to remove immediately
        post(event);
    }

    /**
     * Gets the most recent sticky event for the given type.
     *
     * @see #postSticky(Object)
     */
    public <T> T getStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.get(eventType));
        }
    }

    /**
     * Remove and gets the recent sticky event for the given event type.
     *
     * @see #postSticky(Object)
     */
    public <T> T removeStickyEvent(Class<T> eventType) {
        synchronized (stickyEvents) {
            return eventType.cast(stickyEvents.remove(eventType));
        }
    }

    /**
     * Removes the sticky event if it equals to the given event.
     *
     * @return true if the events matched and the sticky event was removed.
     */
    public boolean removeStickyEvent(Object event) {
        synchronized (stickyEvents) {
            Class<?> eventType = event.getClass();
            Object existingEvent = stickyEvents.get(eventType);
            if (event.equals(existingEvent)) {
                stickyEvents.remove(eventType);
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Removes all sticky events.
     */
    public void removeAllStickyEvents() {
        synchronized (stickyEvents) {
            stickyEvents.clear();
        }
    }

    public boolean hasSubscriberForEvent(Class<?> eventClass) {
        List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
        if (eventTypes != null) {
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                Class<?> clazz = eventTypes.get(h);
                CopyOnWriteArrayList<Subscription> subscriptions;
                synchronized (this) {
                    subscriptions = subscriptionsByEventType.get(clazz);
                }
                if (subscriptions != null && !subscriptions.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    // 发送单个的Event
    private void postSingleEvent(Object event, PostingThreadState postingState) throws Error {
        // 获取event的类
        Class<?> eventClass = event.getClass();
        // 标记，用来表示是否已经找到event对应的订阅者
        boolean subscriptionFound = false;
        // 判断event是否开启继承？
        if (eventInheritance) {
            // 查找Event对应的所有的event类型（包括父类和接口）。
            List<Class<?>> eventTypes = lookupAllEventTypes(eventClass);
            int countTypes = eventTypes.size();
            for (int h = 0; h < countTypes; h++) {
                // 获取其中的一个Event类型(Event对应的类或者其父类或者其实现的接口的一种)
                Class<?> clazz = eventTypes.get(h);
                subscriptionFound |= postSingleEventForEventType(event, postingState, clazz);
            }
        } else {
            // 如果没有开启Event继承，则直接根据Event的类型，在指定的线程中发送Event即可。
            subscriptionFound = postSingleEventForEventType(event, postingState, eventClass);
        }
        if (!subscriptionFound) {
            if (logNoSubscriberMessages) {
                Log.d(TAG, "No subscribers registered for event " + eventClass);
            }
            if (sendNoSubscriberEvent && eventClass != NoSubscriberEvent.class &&
                    eventClass != SubscriberExceptionEvent.class) {
                post(new NoSubscriberEvent(this, event));
            }
        }
    }

    // 为指定的EventType发送单个Event
    private boolean postSingleEventForEventType(Object event, PostingThreadState postingState, Class<?> eventClass) {
        CopyOnWriteArrayList<Subscription> subscriptions;
        synchronized (this) {
            // 根据Event类型获取其对应的订阅者。
            subscriptions = subscriptionsByEventType.get(eventClass);
        }
        // 如果存在订阅者
        if (subscriptions != null && !subscriptions.isEmpty()) {
            // 依次发送给对应的订阅者
            for (Subscription subscription : subscriptions) {
                // 设置post()方法调用线程中对应的postingState
                postingState.event = event;
                postingState.subscription = subscription;
                boolean aborted = false;
                try {
                    // 将event发送给对应的订阅者。
                    postToSubscription(subscription, event, postingState.isMainThread);
                    aborted = postingState.canceled;
                } finally {
                    postingState.event = null;
                    postingState.subscription = null;
                    postingState.canceled = false;
                }
                if (aborted) {
                    break;
                }
            }
            return true;
        }
        return false;
    }

    // 将event发送给对应的调用者
    private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        switch (subscription.subscriberMethod.threadMode) {
            case PostThread:
                // 直接调用
                invokeSubscriber(subscription, event);
                break;
            case MainThread:
                if (isMainThread) {
                    // 如果post的发送线程是UI线程，那么则直接调用对应的方法即可
                    invokeSubscriber(subscription, event);
                } else {
                    // 否则则发送到main线程中对应的Handler中
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            case BackgroundThread:
                // 背景线程
                if (isMainThread) {
                    // 如果当前工作在主线程，则直接压入背景Poster的队列
                    backgroundPoster.enqueue(subscription, event);
                } else {
                    // 反之则直接调用
                    invokeSubscriber(subscription, event);
                }
                break;
            case Async:
                //直接压入异步Poster的队列
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }

    /** Looks up all Class objects including super classes and interfaces. Should also work for interfaces. */
    /** 查找所有的类对象，包括父类和接口。 */
    private List<Class<?>> lookupAllEventTypes(Class<?> eventClass) {
        synchronized (eventTypesCache) {
            List<Class<?>> eventTypes = eventTypesCache.get(eventClass);
            if (eventTypes == null) {
                eventTypes = new ArrayList<Class<?>>();
                Class<?> clazz = eventClass;
                while (clazz != null) {
                    eventTypes.add(clazz);
                    addInterfaces(eventTypes, clazz.getInterfaces());
                    clazz = clazz.getSuperclass();
                }
                eventTypesCache.put(eventClass, eventTypes);
            }
            return eventTypes;
        }
    }

    /** Recurses through super interfaces. */
    static void addInterfaces(List<Class<?>> eventTypes, Class<?>[] interfaces) {
        for (Class<?> interfaceClass : interfaces) {
            if (!eventTypes.contains(interfaceClass)) {
                eventTypes.add(interfaceClass);
                addInterfaces(eventTypes, interfaceClass.getInterfaces());
            }
        }
    }

    /**
     * Invokes the subscriber if the subscriptions is still active. Skipping subscriptions prevents race conditions
     * between {@link #unregister(Object)} and event delivery. Otherwise the event might be delivered after the
     * subscriber unregistered. This is particularly important for main thread delivery and registrations bound to the
     * live cycle of an Activity or Fragment.
     * 如果订阅者依然活跃的话，就调用订阅者对应的方法。需要注意的是，要避免在 {@link #unregister(Object)}和event分发之间调用，以避免竞争现象的发生。
     * 特别是在Activity和Fragment与声明周期绑定的时候，要特别注意。
     */
    void invokeSubscriber(PendingPost pendingPost) {
        // 获取event对象
        Object event = pendingPost.event;
        // 获取订阅者
        Subscription subscription = pendingPost.subscription;
        // 此时PendingPost已经用完，释放，并将其放回到缓冲池里面
        PendingPost.releasePendingPost(pendingPost);
        // 根据订阅者是否活跃，调用订阅者对应的事件处理函数。(如果active为false，即用户已经unregister对应的订阅者)
        if (subscription.active) {
            invokeSubscriber(subscription, event);
        }
    }

    void invokeSubscriber(Subscription subscription, Object event) {
        try {
            // 使用反射机制，调用对应的事件处理函数。
            subscription.subscriberMethod.method.invoke(subscription.subscriber, event);
        } catch (InvocationTargetException e) {
            handleSubscriberException(subscription, event, e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unexpected exception", e);
        }
    }

    private void handleSubscriberException(Subscription subscription, Object event, Throwable cause) {
        if (event instanceof SubscriberExceptionEvent) {
            if (logSubscriberExceptions) {
                // Don't send another SubscriberExceptionEvent to avoid infinite event recursion, just log
                Log.e(TAG, "SubscriberExceptionEvent subscriber " + subscription.subscriber.getClass()
                        + " threw an exception", cause);
                SubscriberExceptionEvent exEvent = (SubscriberExceptionEvent) event;
                Log.e(TAG, "Initial event " + exEvent.causingEvent + " caused exception in "
                        + exEvent.causingSubscriber, exEvent.throwable);
            }
        } else {
            if (throwSubscriberException) {
                throw new EventBusException("Invoking subscriber failed", cause);
            }
            if (logSubscriberExceptions) {
                Log.e(TAG, "Could not dispatch event: " + event.getClass() + " to subscribing class "
                        + subscription.subscriber.getClass(), cause);
            }
            if (sendSubscriberExceptionEvent) {
                SubscriberExceptionEvent exEvent = new SubscriberExceptionEvent(this, cause, event,
                        subscription.subscriber);
                post(exEvent);
            }
        }
    }

    /** For ThreadLocal, much faster to set (and get multiple values). */
    /** 内部静态类，用于ThreadLocal，可以更快的设置和获取多个值*/
    final static class PostingThreadState {
        // eventQueue
        final List<Object> eventQueue = new ArrayList<Object>();
        // 标志：正在发布？
        boolean isPosting;
        // 标志：是主线程？
        boolean isMainThread;
        // 订阅者
        Subscription subscription;
        // Event
        Object event;
        // 已经取消
        boolean canceled;
    }

    ExecutorService getExecutorService() {
        return executorService;
    }

    // Just an idea: we could provide a callback to post() to be notified, an alternative would be events, of course...
    /* public */interface PostCallback {
        void onPostCompleted(List<SubscriberExceptionEvent> exceptionEvents);
    }

}
