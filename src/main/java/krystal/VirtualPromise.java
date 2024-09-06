package krystal;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import lombok.val;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Each pipeline execution is a single VirtualThread. The execution starts at first pipeline step declared, unless it is created with {@link #as(String)} method.
 *
 * @apiNote Inactive VP can be started with {@link #start()}.
 */
@Log4j2
@AllArgsConstructor
public class VirtualPromise<T> {
	
	private static @Setter int threadSleepDuration = 10;
	
	private final LinkedBlockingQueue<Thread> threads;
	private final AtomicReference<T> objectState;
	private final AtomicInteger stepsCount;
	private final AtomicReference<Thread> activeWorker;
	private final AtomicReference<Thread> queueWatcher;
	private final AtomicReference<Throwable> exception;
	private final AtomicReference<ExceptionsHandler> exceptionsHandler;
	/**
	 * You can put the further execution of the pipeline into hold. The {@link #activeWorker} thread will finish its tasks but won't trigger the next step in line.
	 *
	 * @see #setOnHold()
	 * @see #holdAndGet(Consumer)
	 * @see #start()
	 * @see #resume()
	 * @see #resumeNext()
	 * @see #cancel()
	 */
	private final AtomicBoolean holdState;
	/**
	 * The pipeline name used for debugging.
	 *
	 * @see #getActiveWorkerName()
	 */
	private final AtomicReference<String> pipelineName;
	private final AtomicReference<Thread> timeout;
	
	/*
	 * Constructors
	 */
	
	private VirtualPromise() {
		threads = new LinkedBlockingQueue<>();
		objectState = new AtomicReference<>();
		stepsCount = new AtomicInteger();
		activeWorker = new AtomicReference<>();
		queueWatcher = new AtomicReference<>();
		exception = new AtomicReference<>();
		exceptionsHandler = new AtomicReference<>();
		holdState = new AtomicBoolean(false);
		pipelineName = new AtomicReference<>("Unnamed VirtualPromise");
		timeout = null;
	}
	
	private VirtualPromise(String pipelineName) {
		this();
		this.pipelineName.set(pipelineName);
	}
	
	private VirtualPromise(Runnable runnable, @Nullable String threadName) {
		this();
		stateThread(Optional.ofNullable(threadName).orElse("run"), runnable).start();
	}
	
	private VirtualPromise(Supplier<T> supplier, @Nullable String threadName) {
		this();
		stateThread(Optional.ofNullable(threadName).orElse("supply"), () -> objectState.set(supplier.get())).start();
	}
	
	private VirtualPromise(@Nullable String threadName, VirtualPromise<?>... promises) {
		this();
		stateThread(
				Optional.ofNullable(threadName).orElse("fork"),
				() -> Stream.of(promises)
				            .map(p -> {
					            p.join();
					            return p.getException();
				            })
				            .filter(Objects::nonNull)
				            .findAny()
				            .ifPresent(exception::set)
		).start();
	}
	
	/*
	 * Factory
	 */
	
	/**
	 * Return blank, inactive VirtualPromise with a given name.
	 */
	public static VirtualPromise<Void> as(String pipelineName) {
		return new VirtualPromise<>(pipelineName);
	}
	
	/**
	 * Return blank run VirtualPromise.
	 */
	public static VirtualPromise<Void> plain() {
		return VirtualPromise.run(() -> {
		});
	}
	
	public static VirtualPromise<Void> run(Runnable runnable, @Nullable String threadName) {
		return new VirtualPromise<>(runnable, threadName);
	}
	
	public static VirtualPromise<Void> run(Runnable runnable) {
		return run(runnable, null);
	}
	
	public static <T> VirtualPromise<T> supply(Supplier<T> supplier, @Nullable String threadName) {
		return new VirtualPromise<>(supplier, threadName);
	}
	
	public static <T> VirtualPromise<T> supply(Supplier<T> supplier) {
		return supply(supplier, null);
	}
	
	public static VirtualPromise<Void> fork(@Nullable String threadName, VirtualPromise<?>... promises) {
		return new VirtualPromise<>(threadName, promises);
	}
	
	public static VirtualPromise<Void> fork(VirtualPromise<?>... promises) {
		return fork(null, promises);
	}
	
	/*
	 * Primitive actions
	 */
	
	private void takeNextThread() {
		val thread = Thread.ofVirtual().name("%s queue watcher".formatted(pipelineName.get())).unstarted(() -> {
			activeWorker.set(null);
			if (!holdState.get()) {
				try {
					while (threads.isEmpty()) {
						// take() method pins the thread
						Thread.sleep(threadSleepDuration);
					}
					val next = threads.poll();
					activeWorker.set(next);
					next.start();
				} catch (InterruptedException e) {
					holdState.set(true);
				}
			}
			queueWatcher.set(null);
		});
		queueWatcher.set(thread);
		thread.start();
	}
	
	private void arriveAndStartNextThread() {
		stepsCount.getAndDecrement();
		takeNextThread();
	}
	
	private String constructThreadName(String threadName) {
		return "%s: [%s]".formatted(pipelineName.get(), threadName);
	}
	
	private Thread stateThread(String threadName, Runnable runnable, boolean onError) {
		stepsCount.getAndIncrement();
		return Thread.ofVirtual().name(constructThreadName(threadName)).unstarted(() -> {
			activeWorker.set(Thread.currentThread());
			try {
				if (onError == (exception.get() != null)) runnable.run();
			} catch (Exception e) {
				setException(e);
			}
			arriveAndStartNextThread();
		});
	}
	
	private Thread stateThread(String threadName, Runnable runnable) {
		return stateThread(threadName, runnable, false);
	}
	
	private VirtualPromise<T> stateError(String threadName, Runnable catchAction) {
		threads.offer(
				stateThread(
						threadName,
						() -> {
							catchAction.run();
							exception.set(null);
						}, true
				));
		return this;
	}
	
	private VirtualPromise<Void> stateVoid(String threadName, Runnable stateAction) {
		threads.offer(stateThread(threadName, stateAction));
		return toVoid();
	}
	
	private VirtualPromise<T> stateKeep(String threadName, Runnable stateAction) {
		threads.offer(stateThread(threadName, stateAction));
		return this;
	}
	
	private <R> VirtualPromise<R> stateChange(String threadName, Supplier<R> stateAction) {
		val newState = new AtomicReference<R>();
		threads.offer(stateThread(threadName, () -> newState.set(stateAction.get())));
		return new VirtualPromise<>(threads, newState, stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	public VirtualPromise<Void> toVoid() {
		return new VirtualPromise<>(threads, new AtomicReference<>(), stepsCount, activeWorker, queueWatcher, exception, exceptionsHandler, holdState, pipelineName, timeout);
	}
	
	/*
	 * Pipeline steps
	 */
	
	public VirtualPromise<Void> thenRun(Runnable runnable, @Nullable String threadName) {
		return stateVoid(Optional.ofNullable(threadName).orElse("thenRun"), runnable);
	}
	
	public VirtualPromise<Void> thenRun(Runnable runnable) {
		return thenRun(runnable, null);
	}
	
	public <R> VirtualPromise<R> thenSupply(Supplier<R> supplier, @Nullable String threadName) {
		return stateChange(Optional.ofNullable(threadName).orElse("thenSupply"), supplier);
	}
	
	public <R> VirtualPromise<R> thenSupply(Supplier<R> supplier) {
		return thenSupply(supplier, null);
	}
	
	public <R> VirtualPromise<R> map(Function<T, R> function, @Nullable String threadName) {
		return stateChange(Optional.ofNullable(threadName).orElse("map"), () -> function.apply(objectState.get()));
	}
	
	public <R> VirtualPromise<R> map(Function<T, R> function) {
		return map(function, null);
	}
	
	public VirtualPromise<T> apply(Consumer<T> updater, @Nullable String threadName) {
		return stateKeep(Optional.ofNullable(threadName).orElse("apply"), () -> objectState.getAndUpdate(o -> {
			updater.accept(o);
			return o;
		}));
	}
	
	public VirtualPromise<T> apply(Consumer<T> updater) {
		return apply(updater, null);
	}
	
	public VirtualPromise<Void> accept(Consumer<T> consumer, @Nullable String threadName) {
		return stateVoid(Optional.ofNullable(threadName).orElse("accept"), () -> consumer.accept(objectState.get()));
	}
	
	public VirtualPromise<Void> accept(Consumer<T> consumer) {
		return accept(consumer, null);
	}
	
	public <R> VirtualPromise<R> compose(Function<T, @NonNull VirtualPromise<R>> function, @Nullable String threadName) {
		return stateChange(Optional.ofNullable(threadName).orElse("compose"), () -> function.apply(objectState.get())
		                                                                                    .catchRun(this::setException)
		                                                                                    .join()
		                                                                                    .orElse(null));
	}
	
	public <R> VirtualPromise<R> compose(Function<T, @NonNull VirtualPromise<R>> function) {
		return compose(function, null);
	}
	
	public <R> VirtualPromise<R> compose(Supplier<@NonNull VirtualPromise<R>> joiner, @Nullable String threadName) {
		return stateChange(Optional.ofNullable(threadName).orElse("compose"), () -> joiner.get().catchRun(this::setException).join().orElse(null));
	}
	
	public <R> VirtualPromise<R> compose(Supplier<@NonNull VirtualPromise<R>> joiner) {
		return compose(joiner, null);
	}
	
	/**
	 * The other promise exception affects the current pipeline. Effectively joins provided promise.
	 */
	public <R, O> VirtualPromise<R> compose(VirtualPromise<O> otherPromise, BiFunction<O, T, R> combiner, @Nullable String threadName) {
		return stateChange(
				Optional.ofNullable(threadName).orElse("compose"),
				() -> combiner.apply(otherPromise.catchRun(this::setException).join().orElse(null), objectState.get())
		);
	}
	
	public <R, O> VirtualPromise<R> compose(VirtualPromise<O> otherPromise, BiFunction<O, T, R> combiner) {
		return compose(otherPromise, combiner, null);
	}
	
	public <O, R> VirtualPromise<R> composeFlat(VirtualPromise<O> otherPromise, BiFunction<O, T, VirtualPromise<R>> returnedPromise, @Nullable String threadName) {
		return stateChange(
				Optional.ofNullable(threadName).orElse("compose"),
				() -> returnedPromise.apply(otherPromise.catchRun(this::setException).join().orElse(null), objectState.get())
				                     .catchRun(this::setException)
				                     .join()
				                     .orElse(null)
		);
	}
	
	public <O, R> VirtualPromise<R> composeFlat(VirtualPromise<O> otherPromise, BiFunction<O, T, VirtualPromise<R>> returnedPromise) {
		return composeFlat(otherPromise, returnedPromise, null);
	}
	
	/**
	 * The provided promises will execute parallel to ongoing pipeline, and begin as soon as it states this step. To fork promises <b><i>after</i></b> finishing the previous step use {@link #thenFork(Supplier, String)}.
	 */
	public VirtualPromise<Void> thenFork(@Nullable String threadName, VirtualPromise<?>... promises) {
		return stateVoid(
				Optional.ofNullable(threadName).orElse("join"),
				() -> Stream.of(promises)
				            .map(p -> {
					            p.join();
					            return p.getException();
				            })
				            .filter(Objects::nonNull)
				            .findAny()
				            .ifPresent(exception::set)
		);
	}
	
	/**
	 * @see #thenFork(String, VirtualPromise[])
	 */
	public VirtualPromise<Void> thenFork(VirtualPromise<?>... promises) {
		return thenFork(null, promises);
	}
	
	/**
	 * Joins provided fork of promises, which will begin executions <b><i>after</i></b> finishing the previous step.
	 */
	public VirtualPromise<Void> thenFork(Supplier<Stream<VirtualPromise<?>>> promises, @Nullable String threadName) {
		return stateVoid(
				Optional.ofNullable(threadName).orElse("join"),
				() -> promises.get()
				              .map(p -> {
					              p.join();
					              return p.getException();
				              })
				              .filter(Objects::nonNull)
				              .findAny()
				              .ifPresent(exception::set)
		);
	}
	
	/**
	 * @see #thenFork(Supplier, String)
	 */
	public VirtualPromise<Void> thenFork(Supplier<Stream<VirtualPromise<?>>> promises) {
		return thenFork(promises, null);
	}
	
	/**
	 * @param elementMapper
	 * 		Throws {@link NullPointerException} if the return of this {@link Function} is {@code null} or of {@link Void} type.
	 */
	public <E, R> VirtualPromise<Stream<R>> mapFork(Function<T, Stream<E>> streamSupplier, Function<E, R> elementMapper, @Nullable String threadName) {
		return stateChange(
				Optional.ofNullable(threadName).orElse("mapFork"),
				() -> {
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
										// elementMapper can not return null or will throw
										if (exception.get() == null) result.put(i, elementMapper.apply(o));
									} catch (Exception e) {
										setException(e);
									}
								});
							});
					
					// monitor the fork done
					try {
						while (result.size() != elementsCount && exception.get() == null) {
							Thread.sleep(threadSleepDuration);
						}
					} catch (InterruptedException e) {
						setException(e);
					}
					
					return result.entrySet()
					             .stream()
					             .sorted(Comparator.comparingInt(Entry::getKey))
					             .map(Entry::getValue);
				});
	}
	
	public <E, R> VirtualPromise<Stream<R>> mapFork(Function<T, Stream<E>> streamSupplier, Function<E, R> elementMapper) {
		return mapFork(streamSupplier, elementMapper, null);
	}
	
	public <R> VirtualPromise<Void> acceptFork(Function<T, Stream<R>> streamSupplier, Consumer<R> elementConsumer, @Nullable String threadName) {
		return stateVoid(
				Optional.ofNullable(threadName).orElse("acceptFork"),
				() -> {
					val object = objectState.get();
					val elementsCount = streamSupplier.apply(object).mapToInt(_ -> 1).sum();
					val counter = new AtomicInteger();
					
					// split to the fork
					streamSupplier.apply(object).forEach(
							o -> Thread.ofVirtual().start(() -> {
								try {
									if (exception.get() == null) {
										elementConsumer.accept(o);
										counter.getAndIncrement();
									}
								} catch (Exception e) {
									setException(e);
									activeWorker.get().interrupt();
								}
							}));
					
					// monitor the fork done
					try {
						while (counter.get() != elementsCount) {
							Thread.sleep(threadSleepDuration);
						}
					} catch (InterruptedException e) {
						setException(e);
					}
				});
	}
	
	public <R> VirtualPromise<Void> acceptFork(Function<T, Stream<R>> streamSupplier, Consumer<R> elementConsumer) {
		return acceptFork(streamSupplier, elementConsumer, null);
	}
	
	/*
	 * Error handling
	 */
	
	/**
	 * Resolve any exception, registered up to the current step in the pipeline. Occurrence of the exception prevents the executions down the pipeline, until it is resolved. This step in the pipeline can throw exception, thus overwriting the one being the
	 * subject.
	 *
	 * @see #catchSupply(Function, String)
	 * @see #catchThrow(String)
	 * @see #catchExceptions(ExceptionsHandler)
	 */
	public VirtualPromise<T> catchRun(Consumer<Throwable> consumer, @Nullable String threadName) {
		return stateError(Optional.ofNullable(threadName).orElse("catchRun"), () -> consumer.accept(exception.get()));
	}
	
	/**
	 * @see #catchRun(Consumer, String)
	 */
	public VirtualPromise<T> catchRun(Consumer<Throwable> consumer) {
		return catchRun(consumer, null);
	}
	
	/**
	 * Resolve like {@link #catchRun(Consumer)}, but instead consuming the exception, supply the pipeline.
	 */
	public VirtualPromise<T> catchSupply(Function<Throwable, T> consumeAndSupplyFunction, @Nullable String threadName) {
		return stateError(Optional.ofNullable(threadName).orElse("catchSupply"), () -> objectState.set(consumeAndSupplyFunction.apply(exception.get())));
	}
	
	/**
	 * @see #catchSupply(Function, String)
	 */
	public VirtualPromise<T> catchSupply(Function<Throwable, T> consumeAndSupplyFunction) {
		return catchSupply(consumeAndSupplyFunction, null);
	}
	
	/**
	 * Resolve held exception (if any) by throwing {@link RuntimeException}.
	 */
	public VirtualPromise<T> catchThrow(@Nullable String threadName) {
		return stateError(Optional.ofNullable(threadName).orElse("catchThrow"), () -> {
			throw new RuntimeException(exception.get());
		});
	}
	
	/**
	 * @see #catchThrow(String)
	 */
	public VirtualPromise<T> catchThrow() {
		return catchThrow(null);
	}
	
	/**
	 * Set a default exceptions handler as a step in pipeline, to be applied to the next steps.
	 * This will resolve exceptions at their occurrence rather than at the catching step like {@link #catchRun(Consumer, String)}.
	 *
	 * @param exceptionsHandler
	 * 		Set to {@code null} to trigger off.
	 * @see ExceptionsHandler
	 * @see #setExceptionsHandler(ExceptionsHandler)
	 */
	public VirtualPromise<T> catchExceptions(@Nullable ExceptionsHandler exceptionsHandler) {
		return monitor(_ -> this.exceptionsHandler.set(exceptionsHandler));
	}
	
	/**
	 * Create a default catchExceptions for the whole pipeline.
	 *
	 * @param overridePipelineHandlers
	 * 		If set to {@code true}, exceptions won't be stored within pipeline which won't trigger any further catching steps like {@link #catchRun(Consumer, String)}.
	 */
	public record ExceptionsHandler(boolean overridePipelineHandlers, Consumer<Throwable> exceptionsHandler) {
		
		public ExceptionsHandler(Consumer<Throwable> exceptionsHandler) {
			this(true, exceptionsHandler);
		}
		
	}
	
	/*
	 * Monitoring
	 */
	
	/**
	 * Take {@link Consumer} action on current .
	 * I.e. this step can be used to create dependencies on other , at the time of evaluation.
	 *
	 * @see #mirror(Supplier[])
	 */
	public VirtualPromise<T> monitor(Consumer<VirtualPromise<T>> actionOnSelf, @Nullable String threadName) {
		return stateKeep(Optional.ofNullable(threadName).orElse("monitor"), () -> actionOnSelf.accept(this));
	}
	
	/**
	 * @see #monitor(Consumer, String)
	 */
	public VirtualPromise<T> monitor(Consumer<VirtualPromise<T>> actionOnSelf) {
		return monitor(actionOnSelf, null);
	}
	
	/**
	 * Monitor and {@link #monitor(Consumer, String) mirror} other promises state, creating dependency to resume execution.
	 * If the other promises have exceptions or are {@link #isIdle() idle} - {@link #cancelAndDrop()}.
	 * If they are on {@link #holdState hold} - {@link Thread#sleep(long) wait} until they resume.
	 */
	@SafeVarargs
	public final VirtualPromise<T> mirror(Supplier<VirtualPromise<?>>... others) {
		return monitor(_ -> Arrays.stream(others).map(Supplier::get).forEach(promise -> {
			if (this.isIdle() || hasException()) return;
			
			if (promise.hasException() || promise.isIdle()) {
				cancelAndDrop();
				return;
			}
			try {
				while (promise.isOnHold()) {
					if (promise.hasException() || promise.isIdle()) {
						cancelAndDrop();
						return;
					}
					Thread.sleep(threadSleepDuration);
				}
			} catch (InterruptedException e) {
				setException(e);
			}
		}));
	}
	
	/*
	 * Information
	 */
	
	/**
	 * Sets the pipeline name for the <b><i>next</i></b> steps. To make as step, use with {@link #monitor(Consumer, String)}.
	 *
	 * @see #as(String)
	 */
	public VirtualPromise<T> name(String pipelineName) {
		this.pipelineName.set(pipelineName);
		return this;
	}
	
	/**
	 * Uses current {@link #objectState} as the source for the name.
	 *
	 * @apiNote The action is performed at the step declaration, so the object state can be {@code null}.
	 * @see #name(String)
	 */
	public VirtualPromise<T> name(Function<T, String> withCurrentObject) {
		this.pipelineName.set(withCurrentObject.apply(objectState.get()));
		return this;
	}
	
	/**
	 * @return Current name set for pipeline.
	 */
	public String getName() {
		return pipelineName.get();
	}
	
	/**
	 * Active thread name has a format of {@code "pipelineName: [threadName]"}, where, by default, it gets a name from corresponding pipeline task (i.e. "thenRun").
	 */
	public String getActiveWorkerName() {
		return activeWorker.get().getName();
	}
	
	/**
	 * Setting exception can infer the pipeline execution.
	 *
	 * @see #catchRun(Consumer, String)
	 * @see #catchExceptions(ExceptionsHandler)
	 */
	public void setException(Throwable exc) {
		if (!Optional.ofNullable(exceptionsHandler.get())
		             .map(h -> {
			             h.exceptionsHandler.accept(exc);
			             return h.overridePipelineHandlers;
		             })
		             .orElse(false)
		) exception.compareAndSet(null, exc);
	}
	
	public @Nullable Throwable getException() {
		return exception.get();
	}
	
	public boolean hasException() {
		return exception.get() != null;
	}
	
	/**
	 * Set to {@code null} to disable.
	 *
	 * @see ExceptionsHandler
	 * @see #catchExceptions(ExceptionsHandler)
	 */
	public VirtualPromise<T> setExceptionsHandler(@Nullable ExceptionsHandler exceptionsHandler) {
		this.exceptionsHandler.set(exceptionsHandler);
		return this;
	}
	
	/**
	 * True if all threads in pipeline completed their tasks, and it is ready to return result.
	 */
	public boolean isComplete() {
		return stepsCount.get() == 0;
	}
	
	/**
	 * True if there is any thread working at the moment.
	 */
	public boolean isActive() {
		return activeWorker.get() != null;
	}
	
	/**
	 * True if there is any thread working at the moment or any thread watching the queue.
	 */
	public boolean isAlive() {
		return activeWorker.get() != null || queueWatcher.get() != null;
	}
	
	/**
	 * Is on hold and has no further steps to follow. I.e. as a result of {@link #cancelAndDrop()}.
	 */
	public boolean isIdle() {
		return isOnHold() && threads.isEmpty();
	}
	
	/**
	 * @see #holdState
	 */
	public boolean isOnHold() {
		return holdState.get();
	}
	
	public boolean isDropped() {
		return isIdle() && stepsCount.get() > 0;
	}
	
	/*
	 * Results handling and control
	 */
	
	/**
	 * Get current state of the object.
	 */
	public T peek() {
		return objectState.get();
	}
	
	/**
	 * Wait for the pipeline to complete and return the {@link Optional} of the result. If the promise is in the hold state, just returns the Optional with current state of object. The VP becomes {@link #cancel() cancelled} after returning the result.
	 *
	 * @see #holdState
	 */
	public Optional<T> join() {
		// phaser.arriveAndAwaitAdvance(); AWAIT ADVANCE IS PINNING VIRTUAL THREADS :(
		if (!holdState.get()) {
			try {
				while (!isComplete() || isActive()) {
					if (isIdle() || hasException()) break;
					Thread.sleep(threadSleepDuration);
				}
			} catch (InterruptedException e) {
				throw new RuntimeException(e);
			}
		}
		cancel();
		return Optional.ofNullable(objectState.get());
	}
	
	/**
	 * Effectively {@link #catchThrow()} and {@link #join()}, wrapped together.
	 *
	 * @see #holdState
	 */
	public Optional<T> joinThrow() {
		return this.catchThrow().join();
	}
	
	/**
	 * @see #holdState
	 */
	public VirtualPromise<T> setOnHold() {
		holdState.set(true);
		return this;
	}
	
	/**
	 * Join the active worker, hold further pipeline executions, and get the current result.
	 *
	 * @see #holdState
	 * @see #join()
	 * @see #resume()
	 */
	public Optional<T> holdAndGet(@Nullable Consumer<InterruptedException> handler) {
		holdState.set(true);
		try {
			val thread = activeWorker.get();
			if (thread != null) thread.join();
		} catch (InterruptedException e) {
			if (handler != null)
				handler.accept(e);
		}
		return Optional.ofNullable(objectState.get());
	}
	
	/**
	 * Interrupt and cancel the current execution and set the pipeline on hold state.
	 *
	 * @see #holdState
	 * @see #cancelAndDrop()
	 * @see #cancelAndGet()
	 */
	public void cancel() {
		holdState.set(true);
		Optional.ofNullable(activeWorker.getAndSet(null)).ifPresent(t -> {
			t.interrupt();
			activeWorker.set(null);
		});
		Optional.ofNullable(queueWatcher.getAndSet(null)).ifPresent(t -> {
			t.interrupt();
			queueWatcher.set(null);
		});
	}
	
	/**
	 * {@link #cancel()} and drop any further steps.
	 */
	public void cancelAndDrop() {
		cancel();
		threads.clear();
	}
	
	/**
	 * {@link #cancel()} and get the current result (at this specific moment).
	 *
	 * @see #join()
	 */
	public Optional<T> cancelAndGet() {
		cancel();
		return Optional.ofNullable(objectState.get());
	}
	
	/**
	 * If the pipeline is not {@link #isAlive() alive}, begin the executions by invoking the next thread in line. The pipeline can still be on {@link #holdState}, then this method won't trigger further execution.
	 *
	 * @see #holdState
	 * @see #resumeNext()
	 */
	public VirtualPromise<T> start() {
		if (!isAlive()) takeNextThread();
		return this;
	}
	
	/**
	 * Switch the {@link #holdState} flag to false. Does not invoke execution of queued threads. Can be used as {@link #mirror(Supplier[])} for other VPs.
	 *
	 * @see #holdState
	 * @see #start()
	 */
	public VirtualPromise<T> resume() {
		holdState.set(false);
		return this;
	}
	
	/**
	 * {@link #start()} but switch the {@link #holdState} flag to false first.
	 */
	public VirtualPromise<T> resumeNext() {
		return resume().start();
	}
	
	/**
	 * Start a watcher thread, that after given timeout, will {@link #cancel()} the promise if not {@link #isComplete()}. The exception while waiting, does not affect the pipeline and can be handled independently.
	 */
	public VirtualPromise<T> setTimeout(Duration duration, @Nullable Consumer<InterruptedException> handler) {
		timeout.set(Thread.startVirtualThread(() -> {
			try {
				Thread.sleep(duration);
				if (!isComplete()) cancel();
			} catch (InterruptedException e) {
				if (handler != null)
					handler.accept(e);
			}
		}));
		return this;
	}
	
	/*
	 * CompletableFuture Mutation
	 */
	
	public static <T> VirtualPromise<T> fromFuture(CompletableFuture<T> future) {
		return VirtualPromise.supply(future::join);
	}
	
	public CompletableFuture<T> toFuture() {
		return CompletableFuture.supplyAsync(() -> joinThrow().orElse(null));
	}
	
}