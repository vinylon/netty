/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.ObjectPool;
import io.netty.util.internal.SystemPropertyUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.MathUtil.safeFindNextPositivePowerOfTwo;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Light-weight object pool based on a thread-local stack.
 * 回收器
 * @param <T> the type of the pooled object
 */
public abstract class Recycler<T> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(Recycler.class);

    @SuppressWarnings("rawtypes")
    private static final Handle NOOP_HANDLE = new Handle() {
        @Override
        public void recycle(Object object) {
            // NOOP
        }
    };
    //id生成器
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(Integer.MIN_VALUE);
    //获取所属线程的id
    private static final int OWN_THREAD_ID = ID_GENERATOR.getAndIncrement();
    private static final int DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD = 4 * 1024; // Use 4k instances as default.
    //每个线程本地变量Stack最大容量,默认4096
    private static final int DEFAULT_MAX_CAPACITY_PER_THREAD;
    //Stack初始化容量，默认256
    private static final int INITIAL_CAPACITY;
    //最大共享容量因子，影响WeakOrderQueue的容量，默认2
    private static final int MAX_SHARED_CAPACITY_FACTOR;
    //每个线程本地变量WeakHashMap的最大键值对个数，默认CPU核心数x2
    private static final int MAX_DELAYED_QUEUES_PER_THREAD;
    //链接中的数组容量，默认16
    private static final int LINK_CAPACITY;
    //回收间隔，默认8
    private static final int RATIO;
    private static final int DELAYED_QUEUE_RATIO;

    static {
        // In the future, we might have different maxCapacity for different object types.
        // e.g. io.netty.recycler.maxCapacity.writeTask
        //      io.netty.recycler.maxCapacity.outboundBuffer
        int maxCapacityPerThread = SystemPropertyUtil.getInt("io.netty.recycler.maxCapacityPerThread",
                SystemPropertyUtil.getInt("io.netty.recycler.maxCapacity", DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD));
        if (maxCapacityPerThread < 0) {
            maxCapacityPerThread = DEFAULT_INITIAL_MAX_CAPACITY_PER_THREAD;
        }

        DEFAULT_MAX_CAPACITY_PER_THREAD = maxCapacityPerThread;

        MAX_SHARED_CAPACITY_FACTOR = max(2,
                SystemPropertyUtil.getInt("io.netty.recycler.maxSharedCapacityFactor",
                        2));

        MAX_DELAYED_QUEUES_PER_THREAD = max(0,
                SystemPropertyUtil.getInt("io.netty.recycler.maxDelayedQueuesPerThread",
                        // We use the same value as default EventLoop number
                        NettyRuntime.availableProcessors() * 2));

        LINK_CAPACITY = safeFindNextPositivePowerOfTwo(
                max(SystemPropertyUtil.getInt("io.netty.recycler.linkCapacity", 16), 16));

        // By default we allow one push to a Recycler for each 8th try on handles that were never recycled before.
        // This should help to slowly increase the capacity of the recycler while not be too sensitive to allocation
        // bursts.
        RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.ratio", 8));
        DELAYED_QUEUE_RATIO = max(0, SystemPropertyUtil.getInt("io.netty.recycler.delayedQueue.ratio", RATIO));

        if (logger.isDebugEnabled()) {
            if (DEFAULT_MAX_CAPACITY_PER_THREAD == 0) {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: disabled");
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: disabled");
                logger.debug("-Dio.netty.recycler.linkCapacity: disabled");
                logger.debug("-Dio.netty.recycler.ratio: disabled");
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: disabled");
            } else {
                logger.debug("-Dio.netty.recycler.maxCapacityPerThread: {}", DEFAULT_MAX_CAPACITY_PER_THREAD);
                logger.debug("-Dio.netty.recycler.maxSharedCapacityFactor: {}", MAX_SHARED_CAPACITY_FACTOR);
                logger.debug("-Dio.netty.recycler.linkCapacity: {}", LINK_CAPACITY);
                logger.debug("-Dio.netty.recycler.ratio: {}", RATIO);
                logger.debug("-Dio.netty.recycler.delayedQueue.ratio: {}", DELAYED_QUEUE_RATIO);
            }
        }

        INITIAL_CAPACITY = min(DEFAULT_MAX_CAPACITY_PER_THREAD, 256);
    }

    private final int maxCapacityPerThread;
    private final int maxSharedCapacityFactor;
    private final int interval;
    private final int maxDelayedQueuesPerThread;
    private final int delayedQueueInterval;

    private final FastThreadLocal<Stack<T>> threadLocal = new FastThreadLocal<Stack<T>>() {
        @Override
        protected Stack<T> initialValue() {
            return new Stack<T>(Recycler.this, Thread.currentThread(), maxCapacityPerThread, maxSharedCapacityFactor,
                    interval, maxDelayedQueuesPerThread, delayedQueueInterval);
        }

        //安全删除Stack键值对
        @Override
        protected void onRemoval(Stack<T> value) {
            // Let us remove the WeakOrderQueue from the WeakHashMap directly if its safe to remove some overhead
            if (value.threadRef.get() == Thread.currentThread()) {
               if (DELAYED_RECYCLED.isSet()) {
                   DELAYED_RECYCLED.get().remove(value);
               }
            }
        }
    };

    protected Recycler() {
        this(DEFAULT_MAX_CAPACITY_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread) {
        this(maxCapacityPerThread, MAX_SHARED_CAPACITY_FACTOR);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, RATIO, MAX_DELAYED_QUEUES_PER_THREAD);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread) {
        this(maxCapacityPerThread, maxSharedCapacityFactor, ratio, maxDelayedQueuesPerThread,
                DELAYED_QUEUE_RATIO);
    }

    protected Recycler(int maxCapacityPerThread, int maxSharedCapacityFactor,
                       int ratio, int maxDelayedQueuesPerThread, int delayedQueueRatio) {
        interval = max(0, ratio);
        delayedQueueInterval = max(0, delayedQueueRatio);
        if (maxCapacityPerThread <= 0) {
            this.maxCapacityPerThread = 0;
            this.maxSharedCapacityFactor = 1;
            this.maxDelayedQueuesPerThread = 0;
        } else {
            this.maxCapacityPerThread = maxCapacityPerThread;
            this.maxSharedCapacityFactor = max(1, maxSharedCapacityFactor);
            this.maxDelayedQueuesPerThread = max(0, maxDelayedQueuesPerThread);
        }
    }

    @SuppressWarnings("unchecked")
    public final T get() {
        //如果不启用，就给一个空实现处理器
        if (maxCapacityPerThread == 0) {
            return newObject((Handle<T>) NOOP_HANDLE);
        }
        //获取栈，不存在就初始化一个
        Stack<T> stack = threadLocal.get();
        //弹出一个处理器
        DefaultHandle<T> handle = stack.pop();
        // 如果stack中没有
        if (handle == null) {
            //处理器不存在就创建一个，再创建一个值
            handle = stack.newHandle();
            handle.value = newObject(handle);
        }
        //返回值
        return (T) handle.value;
    }

    /**
     * @deprecated use {@link Handle#recycle(Object)}.
     */
    @Deprecated
    public final boolean recycle(T o, Handle<T> handle) {
        if (handle == NOOP_HANDLE) {
            return false;
        }

        DefaultHandle<T> h = (DefaultHandle<T>) handle;
        if (h.stack.parent != this) {
            return false;
        }

        h.recycle(o);
        return true;
    }

    final int threadLocalCapacity() {
        return threadLocal.get().elements.length;
    }

    final int threadLocalSize() {
        return threadLocal.get().size;
    }

    /**
     * 创建对象，也就是说在对象池没有对象的时候得能创建对象
     */
    protected abstract T newObject(Handle<T> handle);

    public interface Handle<T> extends ObjectPool.Handle<T>  { }

    private static final class DefaultHandle<T> implements Handle<T> {
        int lastRecycledId;
        int recycleId;

        boolean hasBeenRecycled;

        Stack<?> stack;
        Object value;

        DefaultHandle(Stack<?> stack) {
            this.stack = stack;
        }

        @Override
        public void recycle(Object object) {
            if (object != value) {
                throw new IllegalArgumentException("object does not belong to handle");
            }

            Stack<?> stack = this.stack;
            if (lastRecycledId != recycleId || stack == null) {
                throw new IllegalStateException("recycled already");
            }

            stack.push(this);//入栈
        }
    }

    // 将Stack和WeakOrderQueue对应起来
    // WeakOrderQueue是一个队列
    // 为了不出现多线程同时去存取操作Stack而产生的竞争情况，
    // 就区分了当前线程是不是Stack的拥有线程，如果是，就直接放回到Stack中，
    // 如果不是，就放入对应的WeakOrderQueue中，前面说了Stack和WeakOrderQueue有对应关系。
    // 而且多个线程中都可能有同一个Stack的不同WeakOrderQueue。
    // 他们之间是用单链表连起来的
    private static final FastThreadLocal<Map<Stack<?>, WeakOrderQueue>> DELAYED_RECYCLED =
            new FastThreadLocal<Map<Stack<?>, WeakOrderQueue>>() {
        // WeakHashMap弱键的HashMap 以键的引用地址来判断是不是同一个键
        @Override
        protected Map<Stack<?>, WeakOrderQueue> initialValue() {
            //弱键回收，键如果只有弱引用，可以被GC回收，然后将整个键值对回收
            return new WeakHashMap<Stack<?>, WeakOrderQueue>();
        }
    };

    // a queue that makes only moderate guarantees about visibility: items are seen in the correct order,
    // but we aren't absolutely guaranteed to ever see anything at all, thereby keeping the queue cheap to maintain
    private static final class WeakOrderQueue extends WeakReference<Thread> {

        // 空的
        static final WeakOrderQueue DUMMY = new WeakOrderQueue();

        // Let Link extend AtomicInteger for intrinsics. The Link itself will be used as writerIndex.
        @SuppressWarnings("serial")
        //Link具体存对象
        //本身就是原子对象，可以计数，这个在后面放入对象的时候会用到。这个里面其实就是一个数组，用来存对象，默认容量是16，
        // 还有一个next指向下一个，至于readIndex就是获取对象的时候用，
        // 这个跟netty自定义的ByteBuf的读索引类似，表示下一个可获取对象的索引
        static final class Link extends AtomicInteger {
            // 存对象
            final DefaultHandle<?>[] elements = new DefaultHandle[LINK_CAPACITY];

            // 读索引，表示下一个可获取对象的索引
            int readIndex;
            // 下一个
            Link next;
        }

        // Its important this does not hold any reference to either Stack or WeakOrderQueue.
        //管理着里面所有的链接Link的创建和回收
        //内部还有一个Link的连接，其实是单链表的表头，所有的Link都会被串起来，
        // 还有一个容量availableSharedCapacity，后续的分配和回收都会用到
        private static final class Head {
            private final AtomicInteger availableSharedCapacity;

            Link link;

            Head(AtomicInteger availableSharedCapacity) {
                this.availableSharedCapacity = availableSharedCapacity;
            }

            /**
             * Reclaim all used space and also unlink the nodes to prevent GC nepotism.
             * 就是从head的Link开始，删除到最后，把空间回收了
             */
            void reclaimAllSpaceAndUnlink() {
                Link head = link;
                link = null;
                int reclaimSpace = 0;
                while (head != null) {
                    reclaimSpace += LINK_CAPACITY;//回收空间
                    Link next = head.next;//指向下一个
                    // Unlink to help GC and guard against GC nepotism.
                    head.next = null;
                    head = next;
                }
                if (reclaimSpace > 0) {
                    reclaimSpace(reclaimSpace);//回收
                }
            }

            //回收空间
            private void reclaimSpace(int space) {
                availableSharedCapacity.addAndGet(space);
            }

            void relink(Link link) {
                //回收
                reclaimSpace(LINK_CAPACITY);
                //链接指向link
                this.link = link;
            }

            /**
             * Creates a new {@link} and returns it if we can reserve enough space for it, otherwise it
             * returns {@code null}.
             */
            Link newLink() {
                //如果成功就创建一个链接返回，否则就返回null
                return reserveSpaceForLink(availableSharedCapacity) ? new Link() : null;
            }

            //为Link申请空间 传进来的参数是stack.availableSharedCapacity也就是2048
            // 说明可以申请的容量是跟这个参数相关的，最多2048个
            //也就是说**每个Stack在其他线程中的回收对象最多是2048个。
            // **每次分配16个，如果容量小于16个了，就不分配了，因此可能导致WeakOrderQueue创建失败，丢弃对象
            static boolean reserveSpaceForLink(AtomicInteger availableSharedCapacity) {
                for (;;) {
                    int available = availableSharedCapacity.get();
                    if (available < LINK_CAPACITY) {//可分配容量小于16 分配失败
                        return false;
                    }
                    if (availableSharedCapacity.compareAndSet(available, available - LINK_CAPACITY)) {
                        return true;//分配成功
                    }
                }
            }
        }

        // chain of data items
        private final Head head;
        private Link tail;
        // pointer to another queue of delayed items for the same stack
        private WeakOrderQueue next;
        private final int id = ID_GENERATOR.getAndIncrement();
        private final int interval;
        private int handleRecycleCount;

        private WeakOrderQueue() {
            super(null);
            head = new Head(null);
            interval = 0;
        }

        /**
         * 创建一个链接Link，然后给创建一个Head，并传入availableSharedCapacity引用，
         * 根据这个availableSharedCapacity来进行后续Link的分配和回收的。
         * 然后还有个队尾的引用，同时也存在回收间隔，跟Stack一样，默认是8
         */
        private WeakOrderQueue(Stack<?> stack, Thread thread) {
            super(thread);
            //创建链接，分配LINK_CAPACITY个DefaultHandle类型的数组
            tail = new Link();

            // Its important that we not store the Stack itself in the WeakOrderQueue as the Stack also is used in
            // the WeakHashMap as key. So just store the enclosed AtomicInteger which should allow to have the
            // Stack itself GCed.
            head = new Head(stack.availableSharedCapacity);
            head.link = tail;
            interval = stack.delayedQueueInterval;
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.
        }

        static WeakOrderQueue newQueue(Stack<?> stack, Thread thread) {
            // We allocated a Link so reserve the space
            //  是否可分配链接
            if (!Head.reserveSpaceForLink(stack.availableSharedCapacity)) {
                //分配失败
                return null;
            }
            final WeakOrderQueue queue = new WeakOrderQueue(stack, thread);
            // Done outside of the constructor to ensure WeakOrderQueue.this does not escape the constructor and so
            // may be accessed while its still constructed.
            //头插法，新的队列插到头部
            stack.setHead(queue);

            return queue;
        }

        WeakOrderQueue getNext() {
            return next;
        }

        void setNext(WeakOrderQueue next) {
            assert next != this;
            this.next = next;
        }

        //释放所有空间，并从链表中删除
        void reclaimAllSpaceAndUnlink() {
            //这个操作就是当所在的线程被回收了，所有的对象也释放了，
            // 但是因为有Stack的单链表还引用着，还不能释放，所以要释放剩余的Link，并从单链表中删除
            head.reclaimAllSpaceAndUnlink();
            this.next = null;
        }

        // 这个也是间隔回收的，从队尾的Link 开始，
        // 看是否满了，如果满了就重新创建一个Link加入链表，
        // 然后在elements对应索引位置放入对象，
        // Link本身就是AtomicInteger，可以进行索引的改变
        void add(DefaultHandle<?> handle) {
            // 记录上次回收的Id
            handle.lastRecycledId = id;

            // While we also enforce the recycling ratio when we transfer objects from the WeakOrderQueue to the Stack
            // we better should enforce it as well early. Missing to do so may let the WeakOrderQueue grow very fast
            // without control
            // 回收次数小于间隔
            //回收次数小于间隔，就丢弃对象，为了不让队列增长过快
            if (handleRecycleCount < interval) {
                handleRecycleCount++;
                // Drop the item to prevent recycling to aggressive.
                return;
            }
            // 清零
            handleRecycleCount = 0;

            Link tail = this.tail;
            int writeIndex;
            //如果超过链接容量限制了
            if ((writeIndex = tail.get()) == LINK_CAPACITY) {
                //创建新的链接，如果创建不成功，就返回null，丢弃对象
                Link link = head.newLink();
                if (link == null) {
                    // Drop it.
                    return;
                }
                // We allocate a Link so reserve the space
                this.tail = tail = tail.next = link;//加入链表

                writeIndex = tail.get();
            }
            tail.elements[writeIndex] = handle;//放入对象
            handle.stack = null;//放进queue里就没有栈了
            // we lazy set to ensure that setting stack to null appears before we unnull it in the owning thread;
            // this also means we guarantee visibility of an element in the queue if we see the index updated
            tail.lazySet(writeIndex + 1);//不需要立即可见，这里都是单线程操作
        }

        //即最后一个Link是否还有可以转移的
        boolean hasFinalData() {
            return tail.readIndex != tail.get();
        }

        // transfer as many items as we can from this queue to the stack, returning true if any were transferred
        @SuppressWarnings("rawtypes")
        /**
         * 简单的来说就是从WeakOrderQueue的head中的链接link开始遍历，把link中的element数组的所有对象转移给Stack的element数组。
         * 其中readIndex表示下一个能转移的数组索引，如果readIndex=LINK_CAPACITY即表示转移完了
         *
         * 如果发现link已经转移完，又是最后一个link，就直接返回false，否则就把他的空间释放了，head的link指向下一个
         *
         * 之后还会判断一次，新获取的下一个Link是否有可以转移的对象，如果没有就直接返回false了
         *
         * 如果还能转移，就计算转换后的Stack中预期有多少对象，如果elements不够放的话就进行扩容。
         * 如果扩容了还不行的话，说明满了，就返回false了。
         *
         * 如果可以放的话，就开始转移，从Link的elements转移到Stack的elements，也不是每一个都会转过去，
         * 这里也有个回收间隔，也是间隔8个，也即所有16个对象只能转2个过去，其实就是回收的比较少，大部分都是丢弃的。
         * 如果这个Link所有对象都转移完了，且他的下一个不为null，就将head的link指向下一个。
         *
         * 最后判断是否有对象转移，如果有就给Stack设置新size并返回true，否则就false，
         * 因为转移有间隔，不一定能有对象转移过去的
         */
        boolean transfer(Stack<?> dst) {
            //获取头链接
            Link head = this.head.link;
            //没有了
            if (head == null) {
                return false;
            }

            //链接中的对象全部转移了
            if (head.readIndex == LINK_CAPACITY) {
                if (head.next == null) {
                    //又是最后一个了,返回失败
                    return false;
                }
                //到下一个
                head = head.next;
                //头结点指向新的链接
                this.head.relink(head);
            }

            //可获取的开始索引
            final int srcStart = head.readIndex;
            //可以获取的最后索引
            int srcEnd = head.get();
            //还有多少个可获取的
            final int srcSize = srcEnd - srcStart;
            //没有能获取的了
            if (srcSize == 0) {
                return false;
            }

            //栈中有多少个对象
            final int dstSize = dst.size;
            //期望容量是栈里的个数+队列里的一个链接中的可获取个数
            final int expectedCapacity = dstSize + srcSize;

            //如果大于栈可容纳的个数
            if (expectedCapacity > dst.elements.length) {
                //扩容
                final int actualCapacity = dst.increaseCapacity(expectedCapacity);
                //再次获取最后索引
                srcEnd = min(srcStart + actualCapacity - dstSize, srcEnd);
            }

            //还可以转移
            if (srcStart != srcEnd) {
                //链接中的对象数组
                final DefaultHandle[] srcElems = head.elements;
                //栈中的对象数组
                final DefaultHandle[] dstElems = dst.elements;
                int newDstSize = dstSize;
                //从头到尾遍历
                for (int i = srcStart; i < srcEnd; i++) {
                    DefaultHandle<?> element = srcElems[i];
                    //检查有没被回收过，没有就是0
                    if (element.recycleId == 0) {
                        element.recycleId = element.lastRecycledId;
                    } else if (element.recycleId != element.lastRecycledId) {
                        throw new IllegalStateException("recycled already");
                    }
                    //清空引用，便于GC
                    srcElems[i] = null;

                    //如果不符合条件丢弃对象，并继续
                    if (dst.dropHandle(element)) {
                        // Drop the object.
                        continue;
                    }
                    //把栈设置回来，下次会从栈里里获取
                    element.stack = dst;
                    //放入栈的数组里
                    dstElems[newDstSize ++] = element;
                }

                //如果这个链接是满的，而且下一个不为空，那就把这个链接给回收了，单链表删除
                if (srcEnd == LINK_CAPACITY && head.next != null) {
                    // Add capacity back as the Link is GCed.
                    this.head.relink(head.next);
                }

                //设置获取完毕
                head.readIndex = srcEnd;
                //如果没有对象获取，就返回失败
                if (dst.size == newDstSize) {
                    return false;
                }
                //有就设置个数,返回成功
                dst.size = newDstSize;
                return true;
            } else {
                // The destination stack is full already.
                //栈满了
                return false;
            }
        }
    }

    private static final class Stack<T> {

        // we keep a queue of per-thread queues, which is appended to once only, each time a new thread other
        // than the stack owner recycles: when we run out of items in our stack we iterate this collection
        // to scavenge those that can be reused. this permits us to incur minimal thread synchronisation whilst
        // still recycling all items.
        final Recycler<T> parent;

        // We store the Thread in a WeakReference as otherwise we may be the only ones that still hold a strong
        // Reference to the Thread itself after it died because DefaultHandle will hold a reference to the Stack.
        //
        // The biggest issue is if we do not use a WeakReference the Thread may not be able to be collected at all if
        // the user will store a reference to the DefaultHandle somewhere and never clear this reference (or not clear
        // it in a timely manner).
        final WeakReference<Thread> threadRef;
        final AtomicInteger availableSharedCapacity;
        private final int maxDelayedQueues;

        private final int maxCapacity;
        private final int interval;
        private final int delayedQueueInterval;
        DefaultHandle<?>[] elements;
        int size;
        private int handleRecycleCount;
        private WeakOrderQueue cursor, prev;
        private volatile WeakOrderQueue head;

        Stack(Recycler<T> parent, Thread thread, int maxCapacity, int maxSharedCapacityFactor,
              int interval, int maxDelayedQueues, int delayedQueueInterval) {
            //回收器
            this.parent = parent;
            //所属线程的弱引用
            threadRef = new WeakReference<Thread>(thread);
            //最大容量，默认4096
            this.maxCapacity = maxCapacity;
            //共享容量，也就是其他线程中的WeakOrderQueue中的最大容量的总和 2048
            availableSharedCapacity = new AtomicInteger(max(maxCapacity / maxSharedCapacityFactor, LINK_CAPACITY));
            //存放对象的数组，默认256大小
            elements = new DefaultHandle[min(INITIAL_CAPACITY, maxCapacity)];
            //回收间隔
            this.interval = interval;
            this.delayedQueueInterval = delayedQueueInterval;
            // 间隔计数器，第一个会被回收
            handleRecycleCount = interval; // Start at interval so the first one will be recycled.

            //关联的WeakOrderQueue最大个数，默认16
            this.maxDelayedQueues = maxDelayedQueues;
        }

        // Marked as synchronized to ensure this is serialized.
        synchronized void setHead(WeakOrderQueue queue) {
            // 设置queue为stack的head
            queue.setNext(head);
            // 更新头结点
            head = queue;
        }

        //扩容两倍，不超过最大限制，把数组元素都复制过去
        int increaseCapacity(int expectedCapacity) {
            int newCapacity = elements.length;
            int maxCapacity = this.maxCapacity;
            do {
                newCapacity <<= 1;
            } while (newCapacity < expectedCapacity && newCapacity < maxCapacity);

            newCapacity = min(newCapacity, maxCapacity);
            if (newCapacity != elements.length) {
                elements = Arrays.copyOf(elements, newCapacity);
            }

            return newCapacity;
        }

        @SuppressWarnings({ "unchecked", "rawtypes" })
        DefaultHandle<T> pop() {
            int size = this.size;
            //没有了就清理尝试从队列中转过来
            if (size == 0) {
                // 进行WeakOrderQueue的清理，也就是将WeakOrderQueue里的对象转移到Stack中
                if (!scavenge()) {//清理队列失败
                    return null;
                }
                size = this.size;//设置个数
                if (size <= 0) {//如果还是0的话就返回null
                    // double check, avoid races
                    return null;
                }
            }
            size --;//个数-1
            DefaultHandle ret = elements[size];//获取对象
            elements[size] = null;//清空对象
            // As we already set the element[size] to null we also need to store the updated size before we do
            // any validation. Otherwise we may see a null value when later try to pop again without a new element
            // added before.
            this.size = size;//更新数量

            if (ret.lastRecycledId != ret.recycleId) {
                throw new IllegalStateException("recycled multiple times");
            }
            ret.recycleId = 0;
            ret.lastRecycledId = 0;
            return ret;
        }

        private boolean scavenge() {
            // continue an existing scavenge, if any
            //有清理一些链接了
            if (scavengeSome()) {
                return true;
            }

            // reset our scavenge cursor
            // 没清理的就重新设置
            prev = null;
            cursor = head;
            return false;
        }

        /**
         * 如果以前没有清理过或者没有要清理的了，cursor 为null，
         * 然后尝试开始从head清理。如果head也为null，说明没有WeakOrderQueue，直接返回false清理失败，
         * 否则cursor就是head，即可以从head开始清理。
         * 如果以前有清理过，获取到prev，即上一个WeakOrderQueue，便于后面删除结点保持链表不断链。
         * 然后开始尝试将cursor中的对象转移到Stack中。
         *
         * 如果转移成功直接返回true。
         * 如果发现cursor的引用线程不存在了，如果cursor还有有对象的话，全部转移到Stack中，并设置转移成功标志true。
         * 如果prev存在的话，就把cursor空间释放，并且从链表中删除。
         * 如果cursor的引用线程还存在，就把prev指向cursor。
         * 最后cursor指向下一个WeakOrderQueue。
         *
         * 如果发现cursor不为空，且没有转移成功过，就再进行转移，直到cursor为空，或者转移成功为止。
         * 最后设置prev和cursor。
         * @return
         */
        private boolean scavengeSome() {
            WeakOrderQueue prev;
            WeakOrderQueue cursor = this.cursor;
            if (cursor == null) {//游标为null
                prev = null;
                cursor = head;//游标指向头结点
                if (cursor == null) { //head也是null
                    return false;
                }
            } else {
                prev = this.prev;
            }

            boolean success = false;
            do {
                //每次转移一个链接的量，由于有间隔，一般就只有2个转移
                if (cursor.transfer(this)) {
                    //转移成功
                    success = true;
                    break;
                }
                //只有上一个转移完了，才会获取下一个队列
                WeakOrderQueue next = cursor.getNext();
                //关联线程被回收为null了
                if (cursor.get() == null) {
                    // If the thread associated with the queue is gone, unlink it, after
                    // performing a volatile read to confirm there is no data left to collect.
                    // We never unlink the first queue, as we don't want to synchronize on updating the head.
                    //还有对象
                    if (cursor.hasFinalData()) {
                        for (;;) {
                            //把队列中的所有链接全部转移完为止
                            if (cursor.transfer(this)) {
                                success = true;
                            } else {
                                break;
                            }
                        }
                    }

                    //如果cursor的前一个队列prev存在
                    if (prev != null) {
                        // Ensure we reclaim all space before dropping the WeakOrderQueue to be GC'ed.
                        //释放cursor结点空间
                        cursor.reclaimAllSpaceAndUnlink();
                        //从单链表中删除cursor结点，prev的next指向cursor的下一个，第一个head是不释放的
                        prev.setNext(next);
                    }
                } else {
                    //prev保存前一个，用来链接删除结点的时候链接下一个结点，保持不断链
                    prev = cursor;
                }

                //游标指向下一个队列
                cursor = next;

            } while (cursor != null && !success);//下一个队列不为空，且没有成功转移过

            this.prev = prev;
            //设置游标
            this.cursor = cursor;
            return success;
        }

        void push(DefaultHandle<?> item) {
            Thread currentThread = Thread.currentThread();
            if (threadRef.get() == currentThread) { // 当前线程是否是stack所属的线程
                // The current Thread is the thread that belongs to the Stack, we can try to push the object now.
                // 放到elements中
                pushNow(item);
            } else {
                // The current Thread is not the one that belongs to the Stack
                // (or the Thread that belonged to the Stack was collected already), we need to signal that the push
                // happens later.
                // 放入另一个线程的本地变量WeakHashMap中，以创建<Stack<?>, WeakOrderQueue>键值对，
                // 主要还是创建WeakOrderQueue，然后将WeakOrderQueue加入Stack的单链表中，
                // 这样使得多个其他线程的WeakOrderQueue和所属线程的Stack有关联
                // WeakOrderQueue里面创建Link链接对象，将回收对象放入Link对象的elements数组中。
                // 而且数组的操作全部使用额外的索引，只需要移动索引进行操作，不需要因为增加删除元素而移动数组元素，所以性能非常高，
                // 而且无论Stack还是WeakOrderQueue内部还有间隔回收的限制，不是说放进来就要的，有一定个数间隔的，默认间隔是8，
                // 也就是除了第一次直接回收，后面每来9个，回收1个，比如来了10个，只有第1个和第10个被回收，中间8个全部不管，等着GC去回收了
                pushLater(item, currentThread);
            }
        }


        //首先判断是否回收过，然后记录回收信息，判断回收的数量有没超过限制，或者是不是丢弃，根据回收间隔。
        // 然后看elements数组是否需要扩容，每次扩容到两倍，但是不超过最大容量默认4096。最后把对象放入指定索引的位置
        private void pushNow(DefaultHandle<?> item) {
            //尝试过回收
            if ((item.recycleId | item.lastRecycledId) != 0) {
                throw new IllegalStateException("recycled already");
            }
            //记录定义的线程ID
            item.recycleId = item.lastRecycledId = OWN_THREAD_ID;

            //已有对象数量
            int size = this.size;
            if (size >= maxCapacity || dropHandle(item)) {
                // Hit the maximum capacity or should drop - drop the possibly youngest object.
                return;
            }
            if (size == elements.length) {
                //要扩容了 每次x2 直到maxCapacity
                // 扩容
                elements = Arrays.copyOf(elements, min(size << 1, maxCapacity));
            }

            //放入数组中
            elements[size] = item;
            //个数+1
            this.size = size + 1;
        }

        /**
         * 首先我们会获取线程本地变量WeakHashMap<Stack<?>, WeakOrderQueue>，然后根据Stack获取WeakOrderQueue 。
         *
         * 如果获取不到，说明还没有这个Stack关联的WeakOrderQueue被创建。
         * 尝试创建，但是如果WeakHashMap键值对数量超过限制了，就放一个假的WeakOrderQueue，其实就是一个空的队列，DUMMY。
         * 否则的话就尝试创建一个，如果还有分配的容量的话，就创建，并和Stack一起放入WeakHashMap中，不行的话就丢弃对象。
         * 如果获取的是DUMMY 的话，说明WeakHashMap放满了，就丢弃。
         * 如果获取到了且不是DUMMY就尝试放队列里。
         * @param item
         * @param thread
         */
        private void pushLater(DefaultHandle<?> item, Thread thread) {
            if (maxDelayedQueues == 0) {
                // We don't support recycling across threads and should just drop the item on the floor.
                return;
            }

            // we don't want to have a ref to the queue as the value in our weak map
            // so we null it out; to ensure there are no races with restoring it later
            // we impose a memory ordering here (no-op on x86)
            //每个线程都会有自己的map
            Map<Stack<?>, WeakOrderQueue> delayedRecycled = DELAYED_RECYCLED.get();
            //获取对应的WeakOrderQueue
            WeakOrderQueue queue = delayedRecycled.get(this);
            //不存在尝试创建一个放入map
            if (queue == null) {
                //数量大于阈值 放一个假WeakOrderQueue，丢弃对象
                if (delayedRecycled.size() >= maxDelayedQueues) {
                    // Add a dummy queue so we know we should drop the object
                    delayedRecycled.put(this, WeakOrderQueue.DUMMY);
                    return;
                }
                // Check if we already reached the maximum number of delayed queues and if we can allocate at all.
                //创建一个队列，如果要分配的容量(16)不够的话就丢弃对象
                if ((queue = newWeakOrderQueue(thread)) == null) {
                    // drop object
                    return;
                }
                //放入map里
                delayedRecycled.put(this, queue);
            } else if (queue == WeakOrderQueue.DUMMY) {
                //如果是假的，就丢弃
                // drop object
                return;
            }

            //放入WeakOrderQueue
            queue.add(item);
        }

        /**
         * Allocate a new {@link WeakOrderQueue} or return {@code null} if not possible.
         */
        private WeakOrderQueue newWeakOrderQueue(Thread thread) {
            return WeakOrderQueue.newQueue(this, thread);
        }

        boolean dropHandle(DefaultHandle<?> handle) {
            // 没被回收过的
            if (!handle.hasBeenRecycled) {
                // 回收次数小于阈值（默认8）
                if (handleRecycleCount < interval) {
                    // 回收次数+1
                    handleRecycleCount++;
                    // Drop the object.
                    // 丢弃
                    return true;
                }
                // 回收次数清零
                handleRecycleCount = 0;
                // 被回收了
                handle.hasBeenRecycled = true;
            }
            return false;
        }

        DefaultHandle<T> newHandle() {
            return new DefaultHandle<T>(this);
        }
    }
}
