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

/**
 * 订阅者，需要注意：此类不可以被继承，且只有包访问权限
 * */
final class Subscription {
  
    // 订阅者
    final Object subscriber;
    // 订阅的方法
    final SubscriberMethod subscriberMethod;
    // 优先级
    final int priority;
    /**
     * Becomes false as soon as {@link EventBus#unregister(Object)} is called, which is checked by queued event delivery
     * {@link EventBus#invokeSubscriber(PendingPost)} to prevent race conditions.
     * 当{@link EventBus#unregister(Object)}，此属性会变为false。该属性会被queued event delivery{@link EventBus#invokeSubscriber(PendingPost)}
     * 检查，以避免竞争出现。
     */
    volatile boolean active;

    Subscription(Object subscriber, SubscriberMethod subscriberMethod, int priority) {
        this.subscriber = subscriber;
        this.subscriberMethod = subscriberMethod;
        this.priority = priority;
        active = true;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof Subscription) {
            Subscription otherSubscription = (Subscription) other;
            return subscriber == otherSubscription.subscriber
                    && subscriberMethod.equals(otherSubscription.subscriberMethod);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return subscriber.hashCode() + subscriberMethod.methodString.hashCode();
    }
}