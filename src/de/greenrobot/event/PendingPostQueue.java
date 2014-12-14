package de.greenrobot.event;

// PendingPost队列
final class PendingPostQueue {
    // 队列头
    private PendingPost head;
    // 队列尾
    private PendingPost tail;

    // 入队列
    synchronized void enqueue(PendingPost pendingPost) {
        // 首先输入合法性检查
        if (pendingPost == null) {
            throw new NullPointerException("null cannot be enqueued");
        }
        // 判断tail是否为空，在此处的意义即，看队列中是否已经有数据
        if (tail != null) {
            // 队列中已经有元素，则将pendingPost放入到队列当中
            tail.next = pendingPost;
            tail = pendingPost;
        } else if (head == null) {
            // 此时队列为空，需要设置head和tail
            head = tail = pendingPost;
        } else {
            throw new IllegalStateException("Head present, but no tail");
        }
        // 通知相关的线程
        notifyAll();
    }

    // 从队列中获取PendingPost，如果队列为空，则返回null，非阻塞
    synchronized PendingPost poll() {
        // 取出head对象
        PendingPost pendingPost = head;
        // 取出成功，则修正head指针
        if (head != null) {
            head = head.next;
            if (head == null) {
                tail = null;
            }
        }
        return pendingPost;
    }

    // 与上面相对，阻塞方法，如果没有，则等待maxMillisToWait
    synchronized PendingPost poll(int maxMillisToWait) throws InterruptedException {
        if (head == null) {
            wait(maxMillisToWait);
        }
        return poll();
    }

}
