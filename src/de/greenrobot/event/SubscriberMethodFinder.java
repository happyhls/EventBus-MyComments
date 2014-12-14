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

import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 该类
 */
class SubscriberMethodFinder {
    private static final String ON_EVENT_METHOD_NAME = "onEvent";

    /*
     * In newer class files, compilers may add methods. Those are called bridge or synthetic methods.
     * EventBus must ignore both. There modifiers are not public but defined in the Java class file format:
     * http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.6-200-A.1
     * 在比较新的class文件中，编译器可能会添加一些方法，这些方法被称作为bridge或者synthetic方法。
     * EventBus必须忽略这些，它们的修饰符并不是pubic，但仍然是以Java类文件的格式存在。
     */
    private static final int BRIDGE = 0x40;
    private static final int SYNTHETIC = 0x1000;

    // 忽视的Java方法的修饰符
    private static final int MODIFIERS_IGNORE = Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC;
    // Method的缓存
    private static final Map<String, List<SubscriberMethod>> methodCache = new HashMap<String, List<SubscriberMethod>>();
    // 跳过对应的class的修饰符检查-->还不太明白其作用是什么
    private final Map<Class<?>, Class<?>> skipMethodVerificationForClasses;

    SubscriberMethodFinder(List<Class<?>> skipMethodVerificationForClassesList) {
        skipMethodVerificationForClasses = new ConcurrentHashMap<Class<?>, Class<?>>();
        if (skipMethodVerificationForClassesList != null) {
            for (Class<?> clazz : skipMethodVerificationForClassesList) {
                skipMethodVerificationForClasses.put(clazz, clazz);
            }
        }
    }

    // 寻找订阅者方法
    List<SubscriberMethod> findSubscriberMethods(Class<?> subscriberClass) {
        // 获取类的全名作为key
        String key = subscriberClass.getName();
        // 订阅者方法列表
        List<SubscriberMethod> subscriberMethods;
        // 尝试从methodCache中获取订阅者方法
        synchronized (methodCache) {
            subscriberMethods = methodCache.get(key);
        }
        // 如果订阅者方法列表已经存在，则直接返回
        if (subscriberMethods != null) {
            return subscriberMethods;
        }
        
        subscriberMethods = new ArrayList<SubscriberMethod>();
        Class<?> clazz = subscriberClass;
        HashSet<String> eventTypesFound = new HashSet<String>();
        StringBuilder methodKeyBuilder = new StringBuilder();
        while (clazz != null) {
            String name = clazz.getName();
            // 如果这些类是java，javax或者android的话，则直接跳过。
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")) {
                // Skip system classes, this just degrades performance
                break;
            }

            // Starting with EventBus 2.2 we enforced methods to be public (might change with annotations again)
            // 从EventBus2.2开始，强制必须将修饰符设置为public
            Method[] methods = clazz.getDeclaredMethods();
            // 依次遍历类的全部方法
            for (Method method : methods) {
                // 获取方法名
                String methodName = method.getName();
                // 判断方法名是否以ON_EVENT_METHOD_NAME，即是否以"onEvent"开头
                if (methodName.startsWith(ON_EVENT_METHOD_NAME)) {
                    // 获取方法的修饰符
                    int modifiers = method.getModifiers();
                    // 要求方法匹配public修饰符，并且不是(Modifier.ABSTRACT | Modifier.STATIC | BRIDGE | SYNTHETIC)中任一种
                    if ((modifiers & Modifier.PUBLIC) != 0 && (modifiers & MODIFIERS_IGNORE) == 0) {
                        // 获取参数的类型
                        Class<?>[] parameterTypes = method.getParameterTypes();
                        // 如果参数的类型只有一种，也就是说，参数只有一个
                        if (parameterTypes.length == 1) {
                            // 获取方法名当中去掉onEvent剩下的部分，并根据这一部分判断其工作在哪个县城之上
                            String modifierString = methodName.substring(ON_EVENT_METHOD_NAME.length());
                            ThreadMode threadMode;
                            if (modifierString.length() == 0) {
                                // PostThread
                                threadMode = ThreadMode.PostThread;
                            } else if (modifierString.equals("MainThread")) {
                                // MainThread
                                threadMode = ThreadMode.MainThread;
                            } else if (modifierString.equals("BackgroundThread")) {
                                // BackgroundThread
                                threadMode = ThreadMode.BackgroundThread;
                            } else if (modifierString.equals("Async")) {
                                // AsyncThread
                                threadMode = ThreadMode.Async;
                            } else {
                                if (skipMethodVerificationForClasses.containsKey(clazz)) {
                                    continue;
                                } else {
                                    throw new EventBusException("Illegal onEvent method, check for typos: " + method);
                                }
                            }
                            // 获取订阅者处理方法onEvent方法的参数类型
                            Class<?> eventType = parameterTypes[0];
                            methodKeyBuilder.setLength(0);
                            // 添加方法名
                            methodKeyBuilder.append(methodName);
                            // 添加方法类型
                            methodKeyBuilder.append('>').append(eventType.getName());
                            String methodKey = methodKeyBuilder.toString();
                            // 将methodKey添加到eventTypesFound当中 true:说明该方法没有被添加过，false:说明该方法已经被添加过
                            if (eventTypesFound.add(methodKey)) {
                                // Only add if not already found in a sub class
                                // 如果methodKey在子类当中没有被添加过，则构造SubscriberMethod，并添加到subscriberMethods当中
                                subscriberMethods.add(new SubscriberMethod(method, threadMode, eventType));
                            }
                        }
                    } else if (!skipMethodVerificationForClasses.containsKey(clazz)) {
                        Log.d(EventBus.TAG, "Skipping method (not public, static or abstract): " + clazz + "."
                                + methodName);
                    }
                }
            }
            // 该类处理完成，继续处理其父类！！！
            clazz = clazz.getSuperclass();
        }
        // 如果该类或者其父类当中不包含订阅者方法，那么则抛出异常
        if (subscriberMethods.isEmpty()) {
            throw new EventBusException("Subscriber " + subscriberClass + " has no public methods called "
                    + ON_EVENT_METHOD_NAME);
        } else {
            // 该类或者其父类当中，包含订阅者方法，则将其添加到methodCache当中
            synchronized (methodCache) {
                methodCache.put(key, subscriberMethods);
            }
            return subscriberMethods;
        }
    }

    // 清除cache
    static void clearCaches() {
        synchronized (methodCache) {
            methodCache.clear();
        }
    }

}
