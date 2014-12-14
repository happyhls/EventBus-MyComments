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

/**
 * Posts events in background.
 * 在后台线程当中处理events
 * @author Markus
 */
final class BackgroundPoster implements Runnable {

    // 一个保存有PendingPost的队列
    private final PendingPostQueue queue;
    // 保持对EventBus的引用
    private final EventBus eventBus;

    // 看现在的BackgroundPoster是否正在处理event
    private volatile boolean executorRunning;

    BackgroundPoster(EventBus eventBus) {
        this.eventBus = eventBus;
        queue = new PendingPostQueue();
    }

    public void enqueue(Subscription subscription, Object event) {
        // 根据subscription和event构建PendingPost
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
            // 将构建好的PendingPost加入到队列。
            queue.enqueue(pendingPost);
            if (!executorRunning) {
                // 如果当前队列空闲，则设置其为忙，并通过EventBus的线程池执行该线程
                executorRunning = true;
                eventBus.getExecutorService().execute(this);
            }
        }
    }

    @Override
    public void run() {
        try {
            try {
                while (true) {
                    // 阻塞方法，从PendignPostQueue中获取一个PendingPost
                    PendingPost pendingPost = queue.poll(1000);
                    if (pendingPost == null) {
                        synchronized (this) {
                            // Check again, this time in synchronized
                            // 原理同我们之前分析的mainPoster一样的，都是防止在加入的时候尝试取PendignPost而取不到，
                            // 代码到这里的时候，则保证如果要加入队列，工作已经完成的。
                            pendingPost = queue.poll();
                            if (pendingPost == null) {
                                executorRunning = false;
                                return;
                            }
                        }
                    }
                    // 调用对应的订阅者方法
                    eventBus.invokeSubscriber(pendingPost);
                }
            } catch (InterruptedException e) {
                Log.w("Event", Thread.currentThread().getName() + " was interruppted", e);
            }
        } finally {
            // 此时说明没有工作可做，因此释放该线程完成工作，设置标记为false。
            executorRunning = false;
        }
    }

}
