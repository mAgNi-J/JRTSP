package com.rtsp.module.netty.module;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.rtsp.RtspDecoder;
import io.netty.handler.codec.rtsp.RtspEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.rtsp.module.Streamer;
import com.rtsp.module.netty.base.NettyChannelType;
import com.rtsp.module.netty.handler.RtcpChannelHandler;
import com.rtsp.module.netty.handler.RtspChannelHandler;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @class public class NettyChannel
 * @brief NettyChannel class
 */
public class NettyChannel {

    private static final Logger logger = LoggerFactory.getLogger(NettyChannel.class);

    private EventLoopGroup bossGroup = new NioEventLoopGroup();
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();

    private ServerBootstrap b;

    /*메시지 수신용 채널 */
    private Channel serverChannel;

    private final String listenIp;
    private final int listenPort;

    /* Streamer Map */
    /* Key: To MDN, value: Streamer */
    private final HashMap<String, Streamer> messageSenderMap = new HashMap<>();
    private final ReentrantLock messageSenderLock = new ReentrantLock();

    ////////////////////////////////////////////////////////////////////////////////

    public NettyChannel(String ip, int port) {
        this.listenIp = ip;
        this.listenPort = port;
    }

    ////////////////////////////////////////////////////////////////////////////////

    public void run (String ip, int port, int channelType) {
        bossGroup = new NioEventLoopGroup();
        b = new ServerBootstrap();
        b.group(bossGroup, workerGroup);
        b.channel(NioServerSocketChannel.class)
                /*.option(ChannelOption.SO_BROADCAST, false)
                .option(ChannelOption.SO_SNDBUF, 33554432)*/
                .option(ChannelOption.SO_RCVBUF, 16777216)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        final ChannelPipeline pipeline = socketChannel.pipeline();

                        switch (channelType) {
                            case NettyChannelType.RTSP:
                                pipeline.addLast(new RtspDecoder(), new RtspEncoder());
                                pipeline.addLast(
                                        //new DefaultEventExecutorGroup(1),
                                        new RtspChannelHandler(
                                                ip,
                                                port
                                        )
                                );
                                break;
                            case NettyChannelType.RTCP:
                                pipeline.addLast(
                                        //new DefaultEventExecutorGroup(1),
                                        new RtcpChannelHandler(
                                                ip,
                                                port
                                        )
                                );
                                break;
                            default:
                                break;
                        }
                    }
                });
    }

    /**
     * @fn public void stop()
     * @brief Netty Channel 을 종료하는 함수
     */
    public void stop () {
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }

    ////////////////////////////////////////////////////////////////////////////////

    /**
     * @param ip   바인딩할 ip
     * @param port 바인당할 port
     * @return 성공 시 생성된 Channel, 실패 시 null 반환
     * @fn public Channel openChannel(String ip, int port)
     * @brief Netty Server Channel 을 생성하는 함수
     */
    public Channel openChannel (String ip, int port) {
        if (serverChannel != null) {
            logger.warn("Channel is already opened.");
            return null;
        }

        InetAddress address;
        ChannelFuture channelFuture;

        try {
            address = InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            logger.warn("UnknownHostException is occurred. (ip={})", ip, e);
            return null;
        }

        try {
            channelFuture = b.bind(address, port).sync();
            serverChannel = channelFuture.channel();
            logger.debug("Channel is opened. (ip={}, port={})", address, port);

            return channelFuture.channel();
        } catch (Exception e) {
            logger.warn("Channel is interrupted. (address={}:{})", ip, port, e);
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * @fn public void closeChannel()
     * @brief Netty Server Channel 을 닫는 함수
     */
    public void closeChannel ( ) {
        if (serverChannel == null) {
            logger.warn("Channel is already closed.");
            return;
        }

        serverChannel.close();
        logger.debug("Channel is closed.");
    }

    public String getListenIp() {
        return listenIp;
    }

    public int getListenPort() {
        return listenPort;
    }


    ////////////////////////////////////////////////////////////////////////////////

    public Streamer addStreamer (String key) {
        try {
            messageSenderLock.lock();

            if (messageSenderMap.get(key) != null) {
                logger.warn("Streamer is already connected. (key={})", key);
                return null;
            }

            Streamer streamer = new Streamer(
                    key,
                    listenIp,
                    listenPort
            ).init();

            if (streamer == null) {
                logger.warn("Fail to create Streamer. (key={})", key);
                return null;
            }

            messageSenderMap.putIfAbsent(
                    key,
                    streamer
            );

            return messageSenderMap.get(key);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            messageSenderLock.unlock();
        }
    }

    public void deleteStreamer (String key) {
        try {
            messageSenderLock.lock();

            Streamer streamer = messageSenderMap.get(key);
            if (streamer == null) {
                logger.warn("Streamer is null. Fail to delete the Streamer. (key={})", key);
                return;
            }

            streamer.finish();
            messageSenderMap.remove(key);
            logger.debug("Streamer is deleted. (key={})", key);
        } catch (Exception e) {
            logger.warn("Fail to delete the Streamer. (key={})", key, e);
        } finally {
            messageSenderLock.unlock();
        }
    }

    public void deleteAllStreamers () {
        try {
            messageSenderLock.lock();

            for (Map.Entry<String, Streamer> entry : getCloneStreamerMap().entrySet()) {
                String key = entry.getKey();
                if (key == null) {
                    continue;
                }

                Streamer streamer = entry.getValue();
                if (streamer == null) {
                    continue;
                }

                streamer.finish();
                messageSenderMap.remove(key);
            }
        } catch (Exception e) {
            logger.warn("Fail to delete all the Streamers.", e);
        } finally {
            messageSenderLock.unlock();
        }
    }

    public Map<String, Streamer> getCloneStreamerMap() {
        HashMap<String, Streamer> cloneMap;

        try {
            messageSenderLock.lock();

            cloneMap = (HashMap<String, Streamer>) messageSenderMap.clone();
        } catch (Exception e) {
            logger.warn("Fail to clone the message sender map.");
            cloneMap = messageSenderMap;
        } finally {
            messageSenderLock.unlock();
        }

        return cloneMap;
    }

    /**
     * @fn public Streamer getStreamer (String key)
     * @brief 지정한 key 에 해당하는 Streamer 를 반환하는 함수
     * @param key Streamer key
     * @return 성공 시 Streamer 객체, 실패 시 null 반환
     */
    public Streamer getStreamer (String key) {
        try {
            messageSenderLock.lock();

            return messageSenderMap.get(key);
        } catch (Exception e) {
            logger.warn("Fail to get the messageSender. (key={})", key, e);
            return null;
        } finally {
            messageSenderLock.unlock();
        }
    }

    public void startStreaming(String key) {
        Streamer streamer = getStreamer(key);
        if (streamer == null) {
            return;
        }

        streamer.start();
    }

    public void stopStreaming(String key) {
        Streamer streamer = getStreamer(key);
        if (streamer == null) {
            return;
        }

        streamer.stop();
    }

}
