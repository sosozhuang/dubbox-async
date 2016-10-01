package com.alibaba.dubbo.remoting.exchange.support.completable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;

public final class FutureAndChannelHolder {
	private static final Logger LOGGER = LoggerFactory
			.getLogger(FutureAndChannelHolder.class);
	private static final Map<Long, CompletableDefaultFuture> FUTURES = new ConcurrentHashMap<Long, CompletableDefaultFuture>();
	private static final Map<Long, Channel> CHANNELS = new ConcurrentHashMap<Long, Channel>();

	private FutureAndChannelHolder() {
	}

	public static boolean hasFuture(Channel channel) {
		return CHANNELS.containsValue(channel);
	}

	static void put(Long id, CompletableDefaultFuture future, Channel channel) {
		FUTURES.put(id, future);
		CHANNELS.put(id, channel);
	}

	static void remove(Long id) {
		FUTURES.remove(id);
		CHANNELS.remove(id);
	}
	
	public static CompletableDefaultFuture getFuture(Long id) {
		return FUTURES.get(id);
	}

	public static void sent(Channel channel, Request request) {
		CompletableDefaultFuture future = FUTURES.get(request.getId());
		if (future != null) {
			future.doSent();
		}
	}

	public static void received(Channel channel, Response response) {
		try {
			CompletableDefaultFuture future = FUTURES.remove(response.getId());
			if (future != null) {
				future.doReceived(response);
			} else {
				LOGGER.warn("The timeout response finally returned at "
						+ (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
								.format(new Date()))
						+ ", response "
						+ response
						+ (channel == null ? "" : ", channel: "
								+ channel.getLocalAddress() + " -> "
								+ channel.getRemoteAddress()));
			}
		} finally {
			CHANNELS.remove(response.getId());
		}
	}
}
