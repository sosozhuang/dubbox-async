package com.alibaba.dubbo.remoting.exchange.support.completable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

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

	// private static class RemotingInvocationTimeoutScan implements Runnable {
	//
	// public void run() {
	// for (CompletableDefaultFuture future : FUTURES.values()) {
	// if (future == null || future.isDone()) {
	// continue;
	// }
	// if (System.currentTimeMillis() - future.getStartTimestamp() > future
	// .getTimeout()) {
	// // create exception response.
	// Response timeoutResponse = new Response(future.getId());
	// // set timeout status.
	// timeoutResponse
	// .setStatus(future.isSent() ? Response.SERVER_TIMEOUT
	// : Response.CLIENT_TIMEOUT);
	// timeoutResponse.setErrorMessage(future
	// .getTimeoutMessage(true));
	// // handle response.
	// received(future.getChannel(), timeoutResponse);
	// }
	// }
	// }
	// }

	private static ScheduledExecutorService service = Executors
			.newScheduledThreadPool(1, new ThreadFactory() {
				@Override
				public Thread newThread(Runnable r) {
					Thread t = new Thread(r, "DubboResponseTimeout");
					t.setDaemon(true);
					return t;
				}
			});

	// static {
	// service.scheduleAtFixedRate(new Runnable() {
	//
	// @Override
	// public void run() {
	// for (CompletableDefaultFuture future : FUTURES.values()) {
	// if (future == null || future.isDone()) {
	// continue;
	// }
	// if (System.currentTimeMillis() - future.getStartTimestamp() > future
	// .getTimeout()) {
	// // create exception response.
	// Response timeoutResponse = new Response(future.getId());
	// // set timeout status.
	// timeoutResponse
	// .setStatus(future.isSent() ? Response.SERVER_TIMEOUT
	// : Response.CLIENT_TIMEOUT);
	// timeoutResponse.setErrorMessage(future
	// .getTimeoutMessage(true));
	// // handle response.
	// received(future.getChannel(), timeoutResponse);
	// }
	// }
	// }
	// }, 0, 50, TimeUnit.MILLISECONDS);
	// }

	static CompletableFuture<Object> failAfter(CompletableDefaultFuture future) {
		final CompletableFuture<Object> promise = new CompletableFuture<>();
		service.schedule(() -> {
			if (future != null && !future.isDone()) {
				// create exception response.
				Response timeoutResponse = new Response(future.getId());
				// set timeout status.
				timeoutResponse.setStatus(future.isSent() ? Response.SERVER_TIMEOUT
						: Response.CLIENT_TIMEOUT);
				timeoutResponse.setErrorMessage(future.getTimeoutMessage(true));
				// handle response.
				received(future.getChannel(), timeoutResponse);
			}
			return promise.complete(null);
			}, future.getTimeout(), TimeUnit.MILLISECONDS);
		return promise;
	}
}
