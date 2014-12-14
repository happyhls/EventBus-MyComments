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
 * Each event handler method has a thread mode, which determines in which thread the method is to be called by EventBus.
 * EventBus takes care of threading independently from the posting thread.
 * 线程模式，每一个Event handler都有一个对应的线程模式，决定了对应的方法会在哪个线程上被EventBus调用。
 * EventBus会独立于发送线程之外处理线程的对立性。
 * @see EventBus#register(Object)
 * @see 参考EventBus#register(Object)
 * @author Markus
 */
public enum ThreadMode {
    /**
     * Subscriber will be called in the same thread, which is posting the event. This is the default. Event delivery
     * implies the least overhead because it avoids thread switching completely. Thus this is the recommended mode for
     * simple tasks that are known to complete is a very short time without requiring the main thread. Event handlers
     * using this mode must return quickly to avoid blocking the posting thread, which may be the main thread.
     * 订阅者会在月发送消息的线程上得到调用。这是默认的情况，这样可以完全避免线程切换的开销。因此，当我们对应的任务比较小，而且又不需要
     * 要求必须在主线程上调用的时候，这是最为推荐的模式。注意，对应的Event的handler必须要很快的返回以避免阻塞发送线程(发送线程可能是主线程)。
     */
    PostThread,

    /**
     * Subscriber will be called in Android's main thread (sometimes referred to as UI thread). If the posting thread is
     * the main thread, event handler methods will be called directly. Event handlers using this mode must return
     * quickly to avoid blocking the main thread.
     * 订阅者会在Android的主线程(也被称作为UI线程)上调用。如果发送线程也是主线程的话，那么对应的handler方法会被直接调用。Event的handler必须要
     * 很快的完成任务，否则会阻塞主线程引发ANR错误。
     */
    MainThread,

    /**
     * Subscriber will be called in a background thread. If posting thread is not the main thread, event handler methods
     * will be called directly in the posting thread. If the posting thread is the main thread, EventBus uses a single
     * background thread, that will deliver all its events sequentially. Event handlers using this mode should try to
     * return quickly to avoid blocking the background thread.
     * 订阅者会在背景线程中被调用。如果发送线程不是主线程，那么event handler对应的方法会直接在发送线程中被调用。如果发送线程是主线程的话，
     * EventBus会使用一个单独的背景线程，该北京线程会顺序的线性依次分发这些时间。当使用该模式的时候，同样的对应的handler应该快速的完成任务，
     * 以避免阻塞背景线程。
     */
    BackgroundThread,

    /**
     * Event handler methods are called in a separate thread. This is always independent from the posting thread and the
     * main thread. Posting events never wait for event handler methods using this mode. Event handler methods should
     * use this mode if their execution might take some time, e.g. for network access. Avoid triggering a large number
     * of long running asynchronous handler methods at the same time to limit the number of concurrent threads. EventBus
     * uses a thread pool to efficiently reuse threads from completed asynchronous event handler notifications.
     * Event对应的handler方法会在一个独立的线程中调用。该线程总是独立与UI线程和发送线程。使用该模式发送events的时候，不要等待handler方法执行完成。
     * Event对应的handler方法可能会消耗比较长的时间，比如网络访问请求。需要注意的是，要避免同时发起大量的长时间的异步处理handler，以免达到
     * 并发线程的最大值。EventBushi使用了一个线程池来更加高效的复用线程。
     */
    Async
}