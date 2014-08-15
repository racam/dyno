/*******************************************************************************
 * Copyright 2011 Netflix
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.netflix.dyno.connectionpool.impl;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Assert;
import org.junit.Test;

import com.netflix.dyno.connectionpool.Host;
import com.netflix.dyno.connectionpool.OperationMonitor;
import com.netflix.dyno.connectionpool.OperationResult;

/**
 * Impl for Future<OperationResult<R>> that encapsulates an inner future. 
 * The class provides a functionality to record the time when the caller calls get() on the future. 
 * This helps record end-end timing for async operations. 
 * Not that there is a caveat here that if the future is called at a later point in time, then yes the timing stats
 * will appear to be bloated unnecessarily. What we really need here is a listenable future, where we should log the 
 * timing stats on the callback. 
 * 
 * @author poberai
 *
 * @param <R>
 */
public class FutureOperationalResultImpl<R> implements Future<OperationResult<R>> {
	
	private final Future<R> future; 
	private final OperationResultImpl<R> opResult; 
	private final long startTime;
	private final AtomicBoolean timeRecorded = new AtomicBoolean(false);
	
	public FutureOperationalResultImpl(String opName, Future<R> rFuture, long start, OperationMonitor opMonitor) {
		this.future = rFuture;
		this.opResult = new OperationResultImpl<R>(opName, rFuture, opMonitor).attempts(1);
		this.startTime = start;
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		return future.cancel(mayInterruptIfRunning);
	}

	@Override
	public boolean isCancelled() {
		return future.isCancelled();
	}

	@Override
	public boolean isDone() {
		return future.isDone();
	}

	@Override
	public OperationResult<R> get() throws InterruptedException, ExecutionException {
		try {
			future.get();
			return opResult;
		} finally {
			recordTimeIfNeeded();
		}
	}

	private void recordTimeIfNeeded() {
		if (timeRecorded.get()) {
			return;
		}
		if (timeRecorded.compareAndSet(false, true)) {
			opResult.setLatency(System.currentTimeMillis()-startTime, TimeUnit.MILLISECONDS);
		}
	}

	@Override
	public OperationResult<R> get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		try {
			future.get(timeout, unit);
			return opResult;
		} finally {
			recordTimeIfNeeded();
		}
	}
	
	public FutureOperationalResultImpl<R> node(Host node) {
		opResult.setNode(node);
		return this;
	}
	
	public OperationResultImpl<R> getOpResult() {
		return opResult;
	}
	
	public static class UnitTest {
		
		@Test
		public void testFutureResult() throws Exception {
			
			final FutureTask<Integer> futureTask = new FutureTask<Integer>(new Callable<Integer>() {
				@Override
				public Integer call() throws Exception {
					return 11;
				}
			});
			
			LastOperationMonitor opMonitor = new LastOperationMonitor();
			FutureOperationalResultImpl<Integer> futureResult = 
					new FutureOperationalResultImpl<Integer>("test", futureTask, System.currentTimeMillis(), opMonitor);
			
			ExecutorService threadPool = Executors.newSingleThreadExecutor();
			
			threadPool.submit(new Callable<Void>() {

				@Override
				public Void call() throws Exception {
					Thread.sleep(400);
					futureTask.run();
					return null;
				}
			});
			
			OperationResult<Integer> opResult = futureResult.get();
			int integerResult = opResult.getResult();
			long latency = opResult.getLatency();
			
			Assert.assertEquals(11, integerResult);
			Assert.assertTrue(latency >= 400);
			
			threadPool.shutdownNow();
		}
		
	}
}
