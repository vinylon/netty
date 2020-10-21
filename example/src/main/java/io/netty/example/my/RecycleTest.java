package io.netty.example.my;

import io.netty.util.Recycler;

/**
 * @author mawei
 * @date 2020/10/16
 */
public class RecycleTest {
    //创建回收器
    private static final Recycler<MyBuff> RECYCLER = new Recycler<MyBuff>() {
        @Override
        protected MyBuff newObject(Handle<MyBuff> handle) {
            return new MyBuff(handle);
        }
    };

    private static class MyBuff {
        private final Recycler.Handle<MyBuff> handle;

        public MyBuff(Recycler.Handle<MyBuff> handle) {
            this.handle = handle;
        }

        public void recycle() {
            handle.recycle(this);
        }
    }

    public static void main(String[] args)  {
        MyBuff myBuff = RECYCLER.get();
        myBuff.recycle();
    }


}
