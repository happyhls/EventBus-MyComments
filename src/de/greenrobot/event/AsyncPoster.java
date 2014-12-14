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
 * Posts events in background.
 * 后台event处理
 * @author Markus
 */
class AsyncPoster implements Runnable {

    private final PendingPostQueue queue;
    private final EventBus eventBus;

    AsyncPoster(EventBus eventBus) {
        this.eventBus = eventBus;
        queue = new PendingPostQueue();
    }

    public void enqueue(Subscription subscription, Object event) {
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        queue.enqueue(pendingPost);
        // 与mainPoster和backgroundPoster相比，直接将PendingPost执行
        eventBus.getExecutorService().execute(this);
    }

    @Override
    public void run() {
        // 获取需要处理的PendingPost
        PendingPost pendingPost = queue.poll();
        if(pendingPost == null) {
            throw new IllegalStateException("No pending post available");
        }
        // 调用相应的订阅者方法
        eventBus.invokeSubscriber(pendingPost);
    }

}
