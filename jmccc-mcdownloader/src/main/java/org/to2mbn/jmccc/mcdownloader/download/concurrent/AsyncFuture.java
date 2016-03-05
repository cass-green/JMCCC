package org.to2mbn.jmccc.mcdownloader.download.concurrent;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class AsyncFuture<V> implements Future<V>, Callback<V>, Cancellable {

	private static final int RUNNING = 0;
	private static final int COMPLETING = 1;
	private static final int DONE = 2;
	private static final int FAILED = 3;
	private static final int CANCELLED = 4;

	private final Cancellable cancellable;
	private volatile Callback<V> callback;

	private final AtomicInteger state = new AtomicInteger(RUNNING);
	private volatile Throwable e;
	private volatile V result;

	private final CountDownLatch latch = new CountDownLatch(1);

	public AsyncFuture() {
		this(null);
	}

	public AsyncFuture(Cancellable cancellable) {
		this.cancellable = cancellable;
	}

	public Callback<V> getCallback() {
		return callback;
	}

	public void setCallback(Callback<V> callback) {
		this.callback = callback;
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		cancelled();
		return true;
	}

	@Override
	public boolean isCancelled() {
		return state.get() == CANCELLED;
	}

	@Override
	public boolean isDone() {
		return state.get() == DONE;
	}

	@Override
	public V get() throws InterruptedException, ExecutionException {
		if (isRunning()) {
			latch.await();
		}
		return getResult();
	}

	@Override
	public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		if (isRunning()) {
			if (!latch.await(timeout, unit)) {
				throw new TimeoutException();
			}
		}
		return getResult();
	}

	@Override
	public void done(V result) {
		if (state.compareAndSet(RUNNING, COMPLETING)) {
			this.result = result;
			state.set(DONE);

			terminated();
			Callback<V> c = callback;
			if (c != null)
				c.done(result);
		}
	}

	@Override
	public void failed(Throwable e) {
		if (state.compareAndSet(RUNNING, COMPLETING)) {
			this.e = e;
			state.set(FAILED);

			terminated();
			cancelUnderlying();
			Callback<V> c = callback;
			if (c != null)
				c.failed(e);
		}
	}

	@Override
	public void cancelled() {
		if (state.compareAndSet(RUNNING, CANCELLED)) {
			terminated();
			cancelUnderlying();
			Callback<V> c = callback;
			if (c != null)
				c.cancelled();
		}
	}

	private void terminated() {
		latch.countDown();
	}

	private V getResult() throws ExecutionException {
		switch (state.get()) {
			case DONE:
				return result;

			case FAILED:
				throw new ExecutionException(e);

			case CANCELLED:
				throw new CancellationException();

			default:
				throw new IllegalStateException("not in a completed state");
		}
	}

	private void cancelUnderlying() {
		if (cancellable != null)
			cancellable.cancel(true);
	}

	private boolean isRunning() {
		int s = state.get();
		return s == RUNNING || s == COMPLETING;
	}

}
