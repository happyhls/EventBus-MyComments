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

import java.util.ArrayList;
import java.util.List;

/**
 * 等待发送的消息 
 *
 */
final class PendingPost {
    // 这个地方是一个缓冲池，缓存之前生成的PendignPost对象，这样就可以节省大量的构造PendingPost对象的空间消耗和时间消耗。
    private final static List<PendingPost> pendingPostPool = new ArrayList<PendingPost>();

    // 待发送的事件
    Object event;
    // 订阅者
    Subscription subscription;
    // 要放在链表中，所以需要指向下一个PendingPost的指针。
    PendingPost next;

    private PendingPost(Object event, Subscription subscription) {
        this.event = event;
        this.subscription = subscription;
    }

    // 根据订阅者和消息，构建PendingPost
    static PendingPost obtainPendingPost(Subscription subscription, Object event) {
        // 该代码块作用：先尝试从pendingPostPool中获取一个对象，如果有的话，则直接取一个利用，如果没有，则重新构建一个
        // 同步pendingPostPoll
        synchronized (pendingPostPool) {
            // 获取pendingPostPool的size
            int size = pendingPostPool.size();
            if (size > 0) {
                PendingPost pendingPost = pendingPostPool.remove(size - 1);
                pendingPost.event = event;
                pendingPost.subscription = subscription;
                pendingPost.next = null;
                return pendingPost;
            }
        }
        // 代码运行到这里，说明没有可以使用的PendingPost，需要重新构建
        return new PendingPost(event, subscription);
    }

    // 释放用完的PendingPost，将其放回到缓冲池当中
    static void releasePendingPost(PendingPost pendingPost) {
        // 将其具体内容设置为null
        pendingPost.event = null;
        pendingPost.subscription = null;
        pendingPost.next = null;
        // 同步保护，并放入到缓冲池当中
        synchronized (pendingPostPool) {
            // Don't let the pool grow indefinitely
            if (pendingPostPool.size() < 10000) {
                pendingPostPool.add(pendingPost);
            }
        }
    }

}