package com.alibaba.dubbo.remoting.exchange.support.completable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.TimeoutException;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.ResponseCallback;
import com.alibaba.dubbo.remoting.exchange.completable.CompletableResponseFuture;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;

public class CompletableDefaultFuture implements CompletableResponseFuture {
	private static final Logger LOGGER = LoggerFactory
			.getLogger(CompletableDefaultFuture.class);

	private final Channel channel;
	private final Request request;
	private final int timeout;
	private final CompletableFuture<Object> future;
	private final long id;
	private final Lock lock;
	private final Condition condition;
	private final long start;
	private volatile long sent;
	private volatile Response response;
	private volatile ResponseCallback callback;

	public CompletableDefaultFuture(final Channel channel, Request request,
			final int timeout) {
		this.channel = channel;
		this.request = request;
		this.timeout = timeout > 0 ? timeout : channel.getUrl()
				.getPositiveParameter(Constants.TIMEOUT_KEY,
						Constants.DEFAULT_TIMEOUT);
		this.lock = new ReentrantLock();
		this.condition = lock.newCondition();
		this.id = request.getId();
		this.start = System.currentTimeMillis();
		FutureAndChannelHolder.put(id, this, channel);
		future = CompletableFuture.supplyAsync(this::waitAsync);
		future.applyToEither(FutureAndChannelHolder.failAfter(this),
				Function.identity())
				.thenAccept(result -> {
					if (callback != null) {
						invokeCallback(callback);
					}
				})
				.exceptionally(this::handleException);
	}

	private Object waitAsync() {
		if (!isDone()) {
			lock.lock();
			try {
				condition.await();
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			} finally {
				lock.unlock();
			}
		}
		Object object = response.getResult();
		if (object == null)
			return null;
		try {
			return ((Result) object).recreate();
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}
	
	private Void handleException(Throwable throwable) {
		Response exceptionResponse = new Response(getId());
		exceptionResponse
				.setStatus(Response.SERVICE_ERROR);
		exceptionResponse
				.setErrorMessage(throwable.getMessage());
		FutureAndChannelHolder.received(channel,
				exceptionResponse);
		if (callback != null) {
			invokeCallback(callback);
		}
		return null;
	}

	@Override
	public Object get() throws RemotingException {
		try {
			return future.get();
		} catch (InterruptedException e) {
			throw new RemotingException(channel, e.getMessage());
		} catch (ExecutionException e) {
			throw new RemotingException(channel, e.getMessage());
		}
	}

	@Override
	public Object get(int timeoutInMillis) throws RemotingException {
		if (timeoutInMillis <= 0) {
			timeoutInMillis = Constants.DEFAULT_TIMEOUT;
		}

		try {
			return future.get(timeoutInMillis, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			throw new RemotingException(channel, e.getMessage());
		} catch (ExecutionException e) {
			throw new RemotingException(channel, e.getMessage());
		} catch (java.util.concurrent.TimeoutException e) {
			throw new TimeoutException(sent > 0, channel,
					getTimeoutMessage(false));
		}
	}

	String getTimeoutMessage(boolean scan) {
		long nowTimestamp = System.currentTimeMillis();
		return (sent > 0 ? "Waiting server-side response timeout"
				: "Sending request timeout in client-side")
				+ (scan ? " by scan timer" : "")
				+ ". start time: "
				+ (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
						.format(new Date(start)))
				+ ", end time: "
				+ (new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
						.format(new Date()))
				+ ","
				+ (sent > 0 ? " client elapsed: " + (sent - start)
						+ " ms, server elapsed: " + (nowTimestamp - sent)
						: " elapsed: " + (nowTimestamp - start))
				+ " ms, timeout: "
				+ timeout
				+ " ms, request: "
				+ request
				+ ", channel: "
				+ channel.getLocalAddress()
				+ " -> "
				+ channel.getRemoteAddress();
	}

	@Override
	public void setCallback(ResponseCallback callback) {
		if (isDone()) {
			invokeCallback(callback);
		} else {
			boolean isdone = false;
			lock.lock();
			try {
				if (!isDone()) {
					this.callback = callback;
				} else {
					isdone = true;
				}
			} finally {
				lock.unlock();
			}
			if (isdone) {
				invokeCallback(callback);
			}
		}
	}

	private void invokeCallback(ResponseCallback c) {
		ResponseCallback callbackCopy = c;
		if (callbackCopy == null) {
			throw new NullPointerException("callback cannot be null.");
		}
		c = null;
		Response res = response;
		if (res == null) {
			throw new IllegalStateException("response cannot be null. url:"
					+ channel.getUrl());
		}

		if (res.getStatus() == Response.OK) {
			try {
				callbackCopy.done(res.getResult());
			} catch (Exception e) {
				LOGGER.error(
						"callback invoke error .reasult:" + res.getResult()
								+ ",url:" + channel.getUrl(), e);
			}
		} else if (res.getStatus() == Response.CLIENT_TIMEOUT
				|| res.getStatus() == Response.SERVER_TIMEOUT) {
			try {
				TimeoutException te = new TimeoutException(
						res.getStatus() == Response.SERVER_TIMEOUT, channel,
						res.getErrorMessage());
				callbackCopy.caught(te);
			} catch (Exception e) {
				LOGGER.error("callback invoke error ,url:" + channel.getUrl(),
						e);
			}
		} else {
			try {
				RuntimeException re = new RuntimeException(
						res.getErrorMessage());
				callbackCopy.caught(re);
			} catch (Exception e) {
				LOGGER.error("callback invoke error ,url:" + channel.getUrl(),
						e);
			}
		}
	}

	@Override
	public boolean isDone() {
		return response != null;
	}

	@Override
	public CompletableFuture<?> getFuture() {
		return future;
	}

	@Override
	public boolean cancel() {
		Response errorResult = new Response(id);
		errorResult.setErrorMessage("request future has been canceled.");
		response = errorResult;
		FutureAndChannelHolder.remove(id);
		return future.cancel(true);
	}

	long getId() {
		return id;
	}

	Channel getChannel() {
		return channel;
	}

	int getTimeout() {
		return timeout;
	}

	void doSent() {
		sent = System.currentTimeMillis();
	}

	boolean isSent() {
		return sent > 0;
	}

	long getStartTimestamp() {
		return start;
	}
	
	public Request getRequest() {
        return request;
    }

	void doReceived(Response response) {
		lock.lock();
		try {
			this.response = response;
			condition.signal();
		} finally {
			lock.unlock();
		}
	}

}
