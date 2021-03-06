package io.netty.example.my;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.example.my.handler.MyNettyServerHandler;

/**
 * @author mawei
 * @date 2020/10/10
 */
public class MyNettyServer {
    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        // 通道的初始化
                        @Override
                        protected void initChannel(SocketChannel ch) throws Exception {
                            ch.pipeline().addLast(new MyNettyServerHandler());
                        }
                    });
            // sync()阻塞
            ChannelFuture cf = bootstrap.bind(8888).sync();
            // 注册一个监听绑定完成事件的回调
            cf.addListener(future -> {
                if (cf.isSuccess()) {
                    System.out.println("监听端口 8888 成功");
                } else {
                    System.out.println("监听端口 8888 失败");
                }
            });
            // 阻塞关闭事件
            cf.channel().closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
