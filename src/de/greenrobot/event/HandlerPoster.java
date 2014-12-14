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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

/**
 * 主线程Poster，本质上为一个Handler 
 *
 */
final class HandlerPoster extends Handler {

    // 维护一个PendingPostQueue的队列
    private final PendingPostQueue queue;
    // 不太懂
    private final int maxMillisInsideHandleMessage;
    // EventBus对象
    private final EventBus eventBus;
    // 标记本Handler是否空闲：true：忙，false：空闲
    private boolean handlerActive;

    HandlerPoster(EventBus eventBus, Looper looper, int maxMillisInsideHandleMessage) {
        super(looper);
        this.eventBus = eventBus;
        this.maxMillisInsideHandleMessage = maxMillisInsideHandleMessage;
        queue = new PendingPostQueue();
    }

    // 入队列
    void enqueue(Subscription subscription, Object event) {
        // 获取一个PendingPost
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
            // 将pendingPost放入queue队列当中
            queue.enqueue(pendingPost);
            // 发送消息
            if (!handlerActive) {
                handlerActive = true;
                // 通过Handler中的MessageQueue，将通知工作在某个线程(可能是main Thread，post thread,background thread,asnyc thread)处理消息
                if (!sendMessage(obtainMessage())) {
                    throw new EventBusException("Could not send handler message");
                }
            }
        }
    }

    // 消息的处理，需要注意的是，该函数段是工作在Looper对应的线程之上的。
    // 有个问题，如果event很快处理完成，那么这个时候是不需要rescheduled的，那么如果在该event处理过程当中，已经放入其他的消息，那么这个消息会在什么时候得到处理呢？
    @Override
    public void handleMessage(Message msg) {
        boolean rescheduled = false;
        try {
            // 记录开始时间
            long started = SystemClock.uptimeMillis();
            while (true) {
                // 从等待处理的队列当中获取一个PendingPost
                PendingPost pendingPost = queue.poll();
                // 判断获取到的pendingPost是否为null，如果null则是没有需要处理的event
                if (pendingPost == null) {
                    synchronized (this) {
                        // Check again, this time in synchronized
                        // 再次处理，需要注意的是，该方法是同步的，跟谁同步的呢？是跟enqueue方法中的代码块同步，做什么用呢？
                        // 我理解的是，此处的代码主要是用于避免一种现象的发生，就是上面已经给Handler发送消息，但并未处理的时候。---> 但貌似又不是
                        // 这次是对的：就是等待前面的enqueue函数执行完成，以便于从中获取event进行处理，如果此时仍然为空，说明队列是空的，标记handlerActive为空，
                        // 这样的话，下次enqueue的时候，就可以直接通过sendMessage通知Handler立刻进行处理。
                        pendingPost = queue.poll();
                        // 如果再次从中获取数据，但为空，则说明handler不是Activie的了。
                        if (pendingPost == null) {
                            // 标记handler已经空闲
                            handlerActive = false;
                            return;
                        }
                    }
                }
                // eventBus调用订阅者的对应的方法
                eventBus.invokeSubscriber(pendingPost);
                // 工作做完，统计消耗时间
                long timeInMethod = SystemClock.uptimeMillis() - started;
                // 超时
                if (timeInMethod >= maxMillisInsideHandleMessage) {
                    // 立刻尝试处理下一个消息
                    if (!sendMessage(obtainMessage())) {
                        throw new EventBusException("Could not send handler message");
                    }
                    // 设置标记
                    rescheduled = true;
                    return;
                }
            }
        } finally {
            // 如果已经rescheduled，那么说明此时该handler已经在忙，否则则说明handler已经空闲。
            handlerActive = rescheduled;
        }
    }
}