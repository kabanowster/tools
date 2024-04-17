package krystal;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.val;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import java.util.stream.Stream;

/**
 * Each pipeline execution is a single VirtualThread. The execution starts at first pipeline step declared.
 */
@AllArgsConstructor
public class VirtualPromise<T> {
	
	private static ExecutorService virtualExecutor;
	
	private final LinkedBlockingQueue<Thread> threads;
	private final AtomicReference<T> objectState;
	private final Phaser phaser;
	private final AtomicReference<Thread> activeWorker;
	private final AtomicReference<Throwable> exception;
	/**
	 * By changing this atomic variable (non-blocking), you can put the further execution of the pipeline into hold. The {@link #activeWorker} thread will finish its tasks but won't trigger the next in line.
	 *
	 * @see #holdAndGet(Consumer)
	 */
	private final @Getter AtomicBoolean holdState;
	/**
	 * The pipeline name used for debugging.
	 *
	 * @see #getActiveVirtualName()
	 */
	private final @Getter AtomicReference<String> pipelineName;
	private final AtomicReference<Thread> timeout;
	
	private VirtualPromise() {
		threads = new LinkedBlockingQueue<>();
		objectState = new AtomicReference<>();
		phaser = new Phaser(1);
		activeWorker = new AtomicReference<>();
		exception = new AtomicReference<>();
		holdState = new AtomicBoolean(false);
		pipelineName = new AtomicReference<>("Unnamed VirtualPromise");
		timeout = null;
	}
	
	private VirtualPromise(Runnable runnable, String threadName) {
		this();
		phaser.register();
		Thread.startVirtualThread(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				runnable.run();
			} catch (Exception e) {
				exception.set(e);
			}
			try {
				arriveAndStartNextThread(threads.poll(3, TimeUnit.SECONDS));
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}).setName(constructName(threadName, "run"));
	}
	
	private VirtualPromise(Supplier<T> supplier, String threadName) {
		this();
		phaser.register();
		Thread.startVirtualThread(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				objectState.set(supplier.get());
			} catch (Exception e) {
				exception.set(e);
			}
			try {
				arriveAndStartNextThread(threads.poll(3, TimeUnit.SECONDS));
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}).setName(constructName(threadName, "supply"));
	}
	
	private VirtualPromise(String threadName, VirtualPromise<?>... promises) {
		this();
		phaser.register();
		Thread.startVirtualThread(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				Stream.of(promises)
				      .map(p -> {
					      p.join();
					      return p.getException();
				      })
				      .filter(Objects::nonNull)
				      .findAny()
				      .ifPresent(exception::set);
			} catch (Exception e) {
				exception.set(e);
			}
			try {
				arriveAndStartNextThread(threads.poll(3, TimeUnit.SECONDS));
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}).setName(constructName(threadName, "fork"));
	}
	
	/*
	 * Factory
	 */
	
	public static VirtualPromise<Void> run(Runnable runnable) {
		return run(runnable, null);
	}
	
	public static VirtualPromise<Void> run(Runnable runnable, @Nullable String threadName) {
		return new VirtualPromise<>(runnable, threadName);
	}
	
	public static <T> VirtualPromise<T> supply(Supplier<T> supplier) {
		return supply(supplier, null);
	}
	
	public static <T> VirtualPromise<T> supply(Supplier<T> supplier, @Nullable String threadName) {
		return new VirtualPromise<>(supplier, threadName);
	}
	
	public static VirtualPromise<Void> forkJoin(VirtualPromise<?>... promises) {
		return forkJoin(null, promises);
	}
	
	public static VirtualPromise<Void> forkJoin(@Nullable String threadName, VirtualPromise<?>... promises) {
		return new VirtualPromise<>(threadName, promises);
	}
	
	/*
	 * Intermediate methods
	 */
	
	public VirtualPromise<T> thenRun(Runnable runnable) {
		return thenRun(runnable, null);
	}
	
	public VirtualPromise<T> thenRun(Runnable runnable, @Nullable String threadName) {
		phaser.register();
		val thread = Thread.ofVirtual().name(constructName(threadName, "thenRun")).unstarted(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				if (exception.get() == null) runnable.run();
			} catch (Exception e) {
				exception.set(e);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return new VirtualPromise<>(threads, new AtomicReference<>(), phaser, activeWorker, exception, holdState, pipelineName, timeout);
	}
	
	public <R> VirtualPromise<R> thenSupply(Supplier<R> supplier) {
		return thenSupply(supplier, null);
	}
	
	public <R> VirtualPromise<R> thenSupply(Supplier<R> supplier, @Nullable String threadName) {
		phaser.register();
		val newState = new AtomicReference<R>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "thenSupply")).unstarted(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				if (exception.get() == null) newState.set(supplier.get());
			} catch (Exception e) {
				exception.set(e);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return new VirtualPromise<>(threads, newState, phaser, activeWorker, exception, holdState, pipelineName, timeout);
	}
	
	public <R> VirtualPromise<R> map(Function<T, R> function) {
		return map(function, null);
	}
	
	public <R> VirtualPromise<R> map(Function<T, R> function, @Nullable String threadName) {
		phaser.register();
		val newState = new AtomicReference<R>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "map")).unstarted(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				if (exception.get() == null) newState.set(function.apply(objectState.get()));
			} catch (Exception e) {
				exception.set(e);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return new VirtualPromise<>(threads, newState, phaser, activeWorker, exception, holdState, pipelineName, timeout);
	}
	
	public VirtualPromise<T> apply(UnaryOperator<T> updater) {
		return apply(updater, null);
	}
	
	public VirtualPromise<T> apply(UnaryOperator<T> updater, @Nullable String threadName) {
		phaser.register();
		val thread = Thread.ofVirtual().name(constructName(threadName, "apply")).unstarted(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				if (exception.get() == null) objectState.getAndUpdate(updater);
			} catch (Exception e) {
				exception.set(e);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return this;
	}
	
	public VirtualPromise<Void> accept(Consumer<T> consumer) {
		return accept(consumer, null);
	}
	
	public VirtualPromise<Void> accept(Consumer<T> consumer, @Nullable String threadName) {
		phaser.register();
		val thread = Thread.ofVirtual().name(constructName(threadName, "accept")).unstarted(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				if (exception.get() == null) consumer.accept(objectState.get());
			} catch (Exception e) {
				exception.set(e);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return new VirtualPromise<>(threads, new AtomicReference<>(), phaser, activeWorker, exception, holdState, pipelineName, timeout);
	}
	
	public <R> VirtualPromise<R> compose(Function<T, @NonNull VirtualPromise<R>> function) {
		return compose(function, null);
	}
	
	public <R> VirtualPromise<R> compose(Function<T, @NonNull VirtualPromise<R>> function, @Nullable String threadName) {
		phaser.register();
		val newState = new AtomicReference<R>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "compose")).unstarted(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				if (exception.get() == null) newState.set(function.apply(objectState.get()).joinExceptionally().orElse(null));
			} catch (Exception e) {
				exception.set(e);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return new VirtualPromise<>(threads, newState, phaser, activeWorker, exception, holdState, pipelineName, timeout);
	}
	
	public <R, O> VirtualPromise<R> combine(VirtualPromise<O> otherPromise, BiFunction<O, T, R> combiner) {
		return combine(otherPromise, combiner, null);
	}
	
	/**
	 * The other promise exception affects the current pipeline. Effectively joins provided promise.
	 */
	public <R, O> VirtualPromise<R> combine(VirtualPromise<O> otherPromise, BiFunction<O, T, R> combiner, @Nullable String threadName) {
		phaser.register();
		val newState = new AtomicReference<R>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "combine")).unstarted(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				val otherState = otherPromise.joinExceptionally();
				if (exception.get() == null) newState.set(combiner.apply(otherState.orElse(null), objectState.get()));
			} catch (Exception e) {
				exception.set(e);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return new VirtualPromise<>(threads, newState, phaser, activeWorker, exception, holdState, pipelineName, timeout);
	}
	
	public <O, R> VirtualPromise<R> combineFlat(VirtualPromise<O> otherPromise, BiFunction<O, T, VirtualPromise<R>> returnedPromise) {
		return combineFlat(otherPromise, returnedPromise, null);
	}
	
	public <O, R> VirtualPromise<R> combineFlat(VirtualPromise<O> otherPromise, BiFunction<O, T, VirtualPromise<R>> returnedPromise, @Nullable String threadName) {
		phaser.register();
		val newState = new AtomicReference<R>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "combineFlat")).unstarted(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				if (exception.get() == null) {
					newState.set(returnedPromise.apply(otherPromise.joinExceptionally().orElse(null), objectState.get()).joinExceptionally().orElse(null));
				}
			} catch (Exception e) {
				exception.set(e);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return new VirtualPromise<>(threads, newState, phaser, activeWorker, exception, holdState, pipelineName, timeout);
	}
	
	public <E, R> VirtualPromise<Stream<R>> mapFork(Function<T, Stream<E>> streamSupplier, Function<E, R> elementMapper) {
		return mapFork(streamSupplier, elementMapper, null);
	}
	
	public <E, R> VirtualPromise<Stream<R>> mapFork(Function<T, Stream<E>> streamSupplier, Function<E, R> elementMapper, @Nullable String threadName) {
		phaser.register();
		val newState = new AtomicReference<Stream<R>>();
		val thread = Thread.ofVirtual().name(constructName(threadName, "mapFork")).unstarted(() -> {
			try {
				activeWorker.set(Thread.currentThread());
				
				if (exception.get() == null) {
					val object = objectState.get();
					val elementsCount = streamSupplier.apply(object).mapToInt(e -> 1).sum();
					val result = new ConcurrentHashMap<Integer, R>(elementsCount);
					val counter = new AtomicInteger();
					
					// split to the fork
					streamSupplier.apply(object).forEach(
							o -> {
								val i = counter.getAndIncrement();
								Thread.ofVirtual().start(() -> {
									try {
										if (exception.get() == null) result.put(i, elementMapper.apply(o));
									} catch (Exception e) {
										exception.set(e);
										activeWorker.get().interrupt();
									}
								});
							});
					
					// monitor the fork done
					while (result.size() != elementsCount) {
						Thread.sleep(100);
					}
					
					newState.set(result.entrySet()
					                   .stream()
					                   .sorted(Comparator.comparingInt(Entry::getKey))
					                   .map(Entry::getValue));
				}
			} catch (Exception e) {
				exception.compareAndSet(null, e);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return new VirtualPromise<>(threads, newState, phaser, activeWorker, exception, holdState, pipelineName, timeout);
	}
	
	/*
	 * Error handling
	 */
	
	/**
	 * @see #catchRun(Consumer, String)
	 */
	public VirtualPromise<T> catchRun(Consumer<Throwable> consumer) {
		return catchRun(consumer, null);
	}
	
	/**
	 * Resolve any exception, registered up to the current step in the pipeline. Occurrence of the exception prevents the executions down the pipeline, until it is resolved. This step in the pipeline can throw exception, thus overwriting the one being the
	 * subject.
	 */
	public VirtualPromise<T> catchRun(Consumer<Throwable> consumer, @Nullable String threadName) {
		phaser.register();
		val thread = Thread.ofVirtual().name(constructName(threadName, "catchRun")).unstarted(() -> {
			try {
				val ex = exception.get();
				if (ex != null) {
					consumer.accept(ex);
					exception.set(null);
				}
			} catch (Exception e) {
				exception.set(e);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return this;
		// return new VirtualPromise<>(threads, new AtomicReference<>(), phaser, activeWorker, exception, holdState, pipelineName, timeout);
	}
	
	/**
	 * @see #catchSupply(Function, String)
	 */
	public VirtualPromise<T> catchSupply(Function<Throwable, T> consumeAndSupplyFunction) {
		return catchSupply(consumeAndSupplyFunction, null);
	}
	
	/**
	 * Resolve like {@link #catchRun(Consumer)}, but instead consuming the exceptions, supply the pipeline.
	 */
	public VirtualPromise<T> catchSupply(Function<Throwable, T> consumeAndSupplyFunction, @Nullable String threadName) {
		phaser.register();
		val thread = Thread.ofVirtual().name(constructName(threadName, "catchSupply")).unstarted(() -> {
			try {
				val ex = exception.get();
				if (ex != null) {
					objectState.set(consumeAndSupplyFunction.apply(ex));
					exception.set(null);
				}
			} catch (Exception e) {
				exception.set(e);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return this;
	}
	
	/**
	 * @see #catchThrow(String)
	 */
	public VirtualPromise<T> catchThrow() {
		return catchThrow(null);
	}
	
	/**
	 * Resolve held exception by throwing {@link RuntimeException}.
	 */
	public VirtualPromise<T> catchThrow(@Nullable String threadName) {
		phaser.register();
		val thread = Thread.ofVirtual().name(constructName(threadName, "catchthrow")).unstarted(() -> {
			val ex = exception.get();
			if (ex != null) {
				throw new RuntimeException(ex);
			}
			arriveAndStartNextThread(threads.poll());
		});
		startOrQueue(thread);
		return this;
	}
	
	/*
	 * Private tools
	 */
	
	private void arriveAndStartNextThread(@Nullable Thread next) {
		phaser.arriveAndDeregister();
		if (next == null || holdState.get()) return;
		next.start();
	}
	
	private void startOrQueue(Thread thread) {
		if (isComplete() && !holdState.get()) {
			thread.start();
		} else {
			threads.offer(thread);
		}
	}
	
	private String constructName(String threadName, String defaultName) {
		return "%s: [%s]".formatted(pipelineName.get(), threadName == null ? defaultName : threadName);
	}
	
	/*
	 * Handling
	 */
	
	/**
	 * Wait for the pipeline to complete and return the {@link Optional} of the result. If the promise is in the hold state, just returns the Optional.
	 *
	 * @see #getHoldState()
	 */
	public Optional<T> join() {
		if (!holdState.get()) phaser.arriveAndAwaitAdvance();
		return Optional.ofNullable(objectState.get());
	}
	
	/**
	 * {@link #join()} and any uncaught exceptions will throw here.
	 */
	public Optional<T> joinExceptionally() throws ExecutionException {
		if (!holdState.get()) phaser.arriveAndAwaitAdvance();
		val ex = exception.get();
		if (ex != null) {
			throw new ExecutionException(ex);
		}
		return Optional.ofNullable(objectState.get());
	}
	
	public @Nullable Throwable getException() {
		return exception.get();
	}
	
	/**
	 * True if all threads in pipeline completed their tasks, and it is ready to return result.
	 */
	public boolean isComplete() {
		return phaser.getUnarrivedParties() == 1;
	}
	
	/**
	 * Interrupt the active worker, put the pipeline on hold, and get the current result.
	 *
	 * @see #join()
	 * @see #getHoldState()
	 */
	public Optional<T> dropAndGet() {
		holdState.set(false);
		activeWorker.get().interrupt();
		return Optional.ofNullable(objectState.get());
	}
	
	/**
	 * Join the active worker, hold further pipeline executions, and get the result.
	 *
	 * @see #join()
	 * @see #getHoldState()
	 * @see #resume()
	 */
	public Optional<T> holdAndGet(@Nullable Consumer<InterruptedException> handler) {
		holdState.set(true);
		try {
			activeWorker.get().join();
		} catch (InterruptedException e) {
			if (handler != null)
				handler.accept(e);
		}
		return Optional.ofNullable(objectState.get());
	}
	
	/**
	 * Drop (interrupt) and cancel further queued pipeline executions.
	 */
	public void cancel() {
		holdState.set(true);
		activeWorker.get().interrupt();
		threads.clear();
	}
	
	/**
	 * If the pipeline is on hold, resume the executions.
	 *
	 * @see #getHoldState()
	 */
	public void resume() {
		if (holdState.getAndSet(false)) {
			val next = threads.poll();
			if (next == null) return;
			next.start();
		}
	}
	
	/**
	 * Start a watcher thread, that after given timeout, will {@link #cancel()} the promise. The exception while waiting, does not affect the pipeline and can be handled independently.
	 */
	public void setTimeout(Duration duration, @Nullable Consumer<InterruptedException> handler) {
		timeout.set(Thread.ofVirtual().start(() -> {
			try {
				Thread.sleep(duration);
				cancel();
			} catch (InterruptedException e) {
				if (handler != null)
					handler.accept(e);
			}
		}));
	}
	
	/**
	 * Active thread name has a format of {@code "pipelineName: [threadName]"}, where, by default, it gets a name from corresponding pipeline task (i.e. "thenRun").
	 */
	public String getActiveVirtualName() {
		return activeWorker.get().getName();
	}
	
	/*
	 * Virtual CompletableFuture Factory
	 */
	
	private static ExecutorService getVirtualExecutor() {
		return Optional.ofNullable(virtualExecutor).orElseGet(() -> {
			virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
			return virtualExecutor;
		});
	}
	
	public static CompletableFuture<Void> futureRun(Runnable runnable) {
		return CompletableFuture.runAsync(runnable, getVirtualExecutor());
	}
	
	public static <F> CompletableFuture<F> futureSupply(Supplier<F> supplier) {
		return CompletableFuture.supplyAsync(supplier, getVirtualExecutor());
	}
	
	public static <T> VirtualPromise<T> fromFuture(CompletableFuture<T> future) {
		return VirtualPromise.supply(future::join);
	}
	
	public CompletableFuture<T> toFuture() {
		return futureSupply(() -> {
			try {
				return joinExceptionally().orElse(null);
			} catch (ExecutionException e) {
				throw new RuntimeException(e);
			}
		});
	}
	
}