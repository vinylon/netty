package io.netty.example.my;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * @author mawei
 * @date 2020/10/10
 */
public class Test {
    public static void main(String[] args) {
        ByteBuf buf = Unpooled.buffer(100,1024);
        buf.writeInt(100);
        buf.writeInt(200);
        byte b = buf.readByte();
        System.out.println(b);
    }
}
