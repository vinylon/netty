/*
 * Copyright 2012 The Netty Project
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
package io.netty.channel;

import java.util.ArrayList;
import java.util.List;

import static io.netty.util.internal.ObjectUtil.checkPositive;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * The {@link RecvByteBufAllocator} that automatically increases and
 * decreases the predicted buffer size on feed back.
 * <p>
 * It gradually increases the expected number of readable bytes if the previous
 * read fully filled the allocated buffer.  It gradually decreases the expected
 * number of readable bytes if the read operation was not able to fill a certain
 * amount of the allocated buffer two times consecutively.  Otherwise, it keeps
 * returning the same prediction.
 * 可调节的缓冲区分配器
 * 这个是有限制的，默认最小尺寸64，最大是65536，初始是1024，
 * 初始化时候会创建一个数组，里面有所有可分配的尺寸，从16到2^30。
 * 16-496是间隔16分的，512以上是2倍分的。
 * 然后会将最小最大初始尺寸都转化为索引，方便操作
 */
public class AdaptiveRecvByteBufAllocator extends DefaultMaxMessagesRecvByteBufAllocator {

    static final int DEFAULT_MINIMUM = 64;//最小
    // Use an initial value that is bigger than the common MTU of 1500
    static final int DEFAULT_INITIAL = 2048;//初始
    static final int DEFAULT_MAXIMUM = 65536;//最大

    //增加索引+4 减少索引-1
    private static final int INDEX_INCREMENT = 4;
    private static final int INDEX_DECREMENT = 1;

    //尺寸数组
    private static final int[] SIZE_TABLE;

    //16-496间隔16 512到2的31次-1 间隔2倍
    static {
        List<Integer> sizeTable = new ArrayList<Integer>();
        for (int i = 16; i < 512; i += 16) {
            sizeTable.add(i);
        }

        for (int i = 512; i > 0; i <<= 1) {
            sizeTable.add(i);
        }

        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i ++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    /**
     * @deprecated There is state for {@link #maxMessagesPerRead()} which is typically based upon channel type.
     */
    @Deprecated
    public static final AdaptiveRecvByteBufAllocator DEFAULT = new AdaptiveRecvByteBufAllocator();

    //二分查找
    private static int getSizeTableIndex(final int size) {
        for (int low = 0, high = SIZE_TABLE.length - 1;;) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;//返回最近大于size的尺寸
            }
        }
    }

    //真正进行尺寸伸缩的处理器
    //每次设置上一次读取的字节数时，
    // 会判断真实读取的字节数是不是把分配的缓冲区给填满了，
    // 如果满了就要进行缓冲区尺寸的伸缩。
    // 伸缩算法就是如果真实读取的字节数小于等于当前尺寸的前一个尺寸大小，且要连续两次，
    // 那就会把空间缩小成前一个尺寸大小。
    // 如果真实读取字节数大于等于预测的接收缓冲区大小，
    // 那就扩容，每次扩容是当前尺寸的后4个尺寸大小，但是不超过最大尺寸。

    //举个例子16,32,48,64,80,96.6个尺寸，
    // 开始是在32，如果两次发现真实的读取数据都小于等于16，
    // 那就设置成16，如果发现数据大于等于32，就跳4个位置，就是96。
    // 为什么要扩容的时候跳那么多呢，我想可能是因为扩容太小的话会可能会有多次扩容，
    // 多次申请直接缓冲区，直接缓冲区的创建和释放是有性能消耗的
    private final class HandleImpl extends MaxMessageHandle {
        private final int minIndex;
        private final int maxIndex;
        //当前在尺寸表的索引
        private int index;
        //下一次接受缓冲区大小
        private int nextReceiveBufferSize;
        private boolean decreaseNow;

        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            index = getSizeTableIndex(initial);
            nextReceiveBufferSize = SIZE_TABLE[index];
        }

        //设置上次读取的字节数
        @Override
        public void lastBytesRead(int bytes) {
            // If we read as much as we asked for we should check if we need to ramp up the size of our next guess.
            // This helps adjust more quickly when large amounts of data is pending and can avoid going back to
            // the selector to check for more data. Going back to the selector can add significant latency for large
            // data transfers.
            //如果真实读取的字节数等于读取尝试的字节数，也就是将接受缓冲区的可写位置全部填满了
            if (bytes == attemptedBytesRead()) {
                //要进行尺寸伸缩了
                record(bytes);
            }
            super.lastBytesRead(bytes);
        }

        //猜测下一个接受缓冲区的大小
        @Override
        public int guess() {
            return nextReceiveBufferSize;
        }

        //记录，根据这次真实接受的尺寸，进行下一次接受缓冲区的大小伸缩
        private void record(int actualReadBytes) {
            //连续两次小，才会进行缩减
            if (actualReadBytes <= SIZE_TABLE[max(0, index - INDEX_DECREMENT)]) {
                if (decreaseNow) {//减少，索引-1，不小于最小索引
                    index = max(index - INDEX_DECREMENT, minIndex);
                    nextReceiveBufferSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualReadBytes >= nextReceiveBufferSize) {//扩大，索引+4，不超过最大索引
                index = min(index + INDEX_INCREMENT, maxIndex);
                nextReceiveBufferSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }

        //读取完成
        @Override
        public void readComplete() {
            record(totalBytesRead());
        }
    }

    //记录最小尺寸索引
    private final int minIndex;
    //记录最大尺寸索引
    private final int maxIndex;
    //记录初始尺寸索引
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 1024}, does not
     * go down below {@code 64}, and does not go up above {@code 65536}.
     */
    public AdaptiveRecvByteBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum  the inclusive lower bound of the expected buffer size
     * @param initial  the initial buffer size when no feed back was received
     * @param maximum  the inclusive upper bound of the expected buffer size
     */
    public AdaptiveRecvByteBufAllocator(int minimum, int initial, int maximum) {
        checkPositive(minimum, "minimum");
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        //设置最小索引
        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        //设置最大索引
        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        // 初始索引
        this.initial = initial;
    }

    @SuppressWarnings("deprecation")
    @Override
    public Handle newHandle() {
        return new HandleImpl(minIndex, maxIndex, initial);
    }

    @Override
    public AdaptiveRecvByteBufAllocator respectMaybeMoreData(boolean respectMaybeMoreData) {
        super.respectMaybeMoreData(respectMaybeMoreData);
        return this;
    }
}
