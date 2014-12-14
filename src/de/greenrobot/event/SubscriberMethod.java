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

import java.lang.reflect.Method;

/**
 * 订阅方法，与Subscription同样，此类为包访问权限，且不可继承。
 */
final class SubscriberMethod {
    // 对应的方法
    final Method method;
    // 对应的线程模式
    final ThreadMode threadMode;
    // Event的类型
    final Class<?> eventType;
    /** Used for efficient comparison */
    /** 方法名称，注释显示用来高效匹配 */
    String methodString;

    SubscriberMethod(Method method, ThreadMode threadMode, Class<?> eventType) {
        this.method = method;
        this.threadMode = threadMode;
        this.eventType = eventType;
    }

    @Override
    public boolean equals(Object other) {
        // 首先判断object是否为SubcriberMethod的实例
        if (other instanceof SubscriberMethod) {
            // 生成本SubscriberMethod的方法名称
            checkMethodString();
            SubscriberMethod otherSubscriberMethod = (SubscriberMethod)other;
            // 生成待匹配的SubscriberMethod的方法名称
            otherSubscriberMethod.checkMethodString();
            // Don't use method.equals because of http://code.google.com/p/android/issues/detail?id=7811#c6
            // 避免使用method.equals，bug连接如上，应该是使用method.equals会消耗比较长的时间
            return methodString.equals(otherSubscriberMethod.methodString);
        } else {
            // 不是SubcriberMethod实例，返回false
            return false;
        }
    }

    // 该函数名称起的不是特别合适，此处应该是获取方法的名称
    private synchronized void checkMethodString() {
        if (methodString == null) {
            // Method.toString has more overhead, just take relevant parts of the method
            // 构造方法名称
            StringBuilder builder = new StringBuilder(64);
            // 1st:获取类的名称
            builder.append(method.getDeclaringClass().getName());
            // 2ed:添加方法的名称
            builder.append('#').append(method.getName());
            // 3rd:添加对应的Event的类型
            builder.append('(').append(eventType.getName());
            methodString = builder.toString();
        }
    }

    @Override
    public int hashCode() {
        return method.hashCode();
    }
}